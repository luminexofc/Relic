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
