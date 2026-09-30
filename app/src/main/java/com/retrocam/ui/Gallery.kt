package com.retrocam.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** One captured photo or video. */
data class MediaItem(val id: Long, val uri: Uri, val isVideo: Boolean, val mime: String)

/** Queries RetroCam's own MediaStore folder (photos + videos, newest first). */
object Gallery {

    private const val SELECTION = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"

    /** Full-dir search pattern for [loadItems]. */
    private fun argsFor(dir: String) = arrayOf("%$dir%")

    fun latestThumbnail(context: Context, sizePx: Int, dir: String): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        val item = loadItems(context, dir = dir).firstOrNull() ?: return null
        return runCatching {
            context.contentResolver.loadThumbnail(item.uri, android.util.Size(sizePx, sizePx), null)
        }.getOrNull()
    }

    fun loadItems(context: Context, dir: String, limit: Int = 200): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        collect(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
            isVideo = false,
            dir = dir,
            limit = limit,
            out = out,
        )
        collect(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.MIME_TYPE,
            isVideo = true,
            dir = dir,
            limit = limit,
            out = out,
        )
        return out.sortedByDescending { it.id }
    }

    private fun collect(
        context: Context,
        collection: Uri,
        idCol: String,
        mimeCol: String,
        isVideo: Boolean,
        dir: String,
        limit: Int,
        out: MutableList<MediaItem>,
    ) {
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(idCol, mimeCol),
                SELECTION,
                argsFor(dir),
                "$idCol DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndex(idCol)
                val mimeIdx = c.getColumnIndex(mimeCol)
                while (c.moveToNext() && out.size < limit) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                    out.add(
                        MediaItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            isVideo = isVideo,
                            mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else "",
                        ),
                    )
                }
            }
        }
    }

    /** Result of a delete attempt: gone, needs the user's tap, or failed. */
    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data class NeedConsent(val sender: android.content.IntentSender) : DeleteResult
        data object Failed : DeleteResult
    }

    /**
     * Deletes one item. On Android 10+ deleting someone else's file - which a
     * gallery file is, once MediaStore owns it - throws
     * RecoverableSecurityException carrying the consent screen, so a delete is
     * up to two steps and the caller finishes it.
     */
    fun delete(context: Context, item: MediaItem): DeleteResult {
        return try {
            val n = context.contentResolver.delete(item.uri, null, null)
            if (n > 0) DeleteResult.Deleted else DeleteResult.Failed
        } catch (e: android.app.RecoverableSecurityException) {
            // The consent screen rides on the exception: its action intent's
            // sender is what the caller launches, and the delete lands if the
            // user confirms. createDeleteRequest is the same screen by another
            // door on API 30+, kept as the fallback.
            val sender = runCatching { e.userAction.actionIntent.intentSender }.getOrNull()
                ?: runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        android.provider.MediaStore.createDeleteRequest(
                            context.contentResolver, listOf(item.uri),
                        ).intentSender
                    } else null
                }.getOrNull()
            if (sender != null) DeleteResult.NeedConsent(sender) else DeleteResult.Failed
        } catch (t: Throwable) {
            DeleteResult.Failed
        }
    }

    /** Full bitmap of a photo, downsampled to fit [maxEdge], for viewing. */
    fun loadBitmap(context: Context, item: MediaItem, maxEdge: Int = 2048): Bitmap? {
        return runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(item.uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            var sample = 1
            val longest = maxOf(opts.outWidth, opts.outHeight)
            while (longest / sample > maxEdge) sample *= 2
            val full = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(item.uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, full)
            }
        }.getOrNull()
    }

    /** Rotated/mirrored copy; the caller owns recycling the source. */
    fun transform(src: Bitmap, rotateCw: Boolean): Bitmap {
        val m = android.graphics.Matrix()
        if (rotateCw) m.postRotate(90f) else m.postScale(-1f, 1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Saves [bmp] as a new JPEG next to the original, so an edit never
     * destroys the shot it came from. Returns the new item, if MediaStore
     * accepted it.
     */
    fun saveCopy(context: Context, dir: String, item: MediaItem, bmp: Bitmap): MediaItem? {
        return runCatching {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val base = displayName(context, item).substringBeforeLast('.').takeIf { it.isNotBlank() } ?: "RetroCam"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${base}_EDIT_$stamp.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, dir)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            ) ?: return null
            val ok = resolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            } == true
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            MediaItem(
                id = android.content.ContentUris.parseId(uri),
                uri = uri, isVideo = false, mime = "image/jpeg",
            )
        }.getOrNull()
    }

    private fun displayName(context: Context, item: MediaItem): String {
        return runCatching {
            context.contentResolver.query(item.uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: ""
    }

    /** Opens an item in whatever viewer the device has for it. */
    fun open(context: Context, item: MediaItem) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, item.mime.ifEmpty { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
    }

    /** Share sheet for one item. */
    fun share(context: Context, item: MediaItem) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    type = item.mime.ifEmpty { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
    }
}
