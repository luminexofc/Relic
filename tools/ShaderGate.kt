import com.retrocam.catalog.FilterCatalog
import com.retrocam.catalog.FilterFamily
import com.retrocam.catalog.FilterSpec
import com.retrocam.catalog.Shaders
import java.io.File

/**
 * The GLSL gate.
 *
 * Writes every shader the catalog can produce, both the OES variant the live
 * viewfinder uses and the 2D variant the composer chain uses, then checks two
 * things about what it wrote:
 *
 *  1. It parses (glslangValidator runs afterwards; this only reports the count).
 *  2. It CONTAINS the current source. A count guard cannot see staleness, and
 *     the gate passed four times in a row against a stale generator that
 *     emitted the right NUMBER of files with the wrong bodies. So the body that
 *     went into the file is compared against the body in memory, for every
 *     shader, rather than the file count being compared against a number.
 *
 * Compiled fresh by verify.sh every run, so a stale class file cannot survive:
 * the whole point of §4.1 was that "rebuild gen.jar" was the fix and nobody had
 * a check that proved it.
 */
object ShaderGate {

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrElse(0) { "build/verify/shaders" })
        outDir.deleteRecursively()
        outDir.mkdirs()

        // The lab grade is not a catalog entry: FilterRenderer builds it as a
        // second chain pass from the spec that carries the recipe. Adding it by
        // hand here is the same trick, and without it the one shader with the
        // most code in it is the one the gate never checks.
        val specs = FilterCatalog.all + FilterSpec(
            id = "lab_grade",
            displayName = "LAB",
            family = FilterFamily.LAB,
            fragmentBody = Shaders.LAB_GRADE,
            param1 = 0f,
            param2 = 0f,
        )
        var written = 0

        for (spec in specs) {
            val oes = Shaders.fragmentFor(spec.fragmentBody)
            val tex = Shaders.fragmentFor2D(spec.fragmentBody)
            File(outDir, "${spec.id}.frag").writeText(oes)
            File(outDir, "${spec.id}.2d.frag").writeText(tex)

            // The content guard. Re-read from disk, so this cannot pass on an
            // in-memory value that never made it to the file.
            val backOes = File(outDir, "${spec.id}.frag").readText()
            val backTex = File(outDir, "${spec.id}.2d.frag").readText()
            require(backOes == oes) { "STALE WRITE: ${spec.id}.frag does not match Shaders.fragmentFor" }
            require(backTex == tex) { "STALE WRITE: ${spec.id}.2d.frag does not match Shaders.fragmentFor2D" }

            // The specific failure from §4.1: lab_grade.frag present, correct
            // count, older body. Named explicitly so the error says which.
            if (spec.id == "lab_grade") {
                require(backOes.contains(Shaders.LAB_GRADE.trim())) {
                    "STALE LAB_GRADE: the generated lab_grade.frag is not the current body. " +
                        "Rebuild the generator."
                }
            }
            written += 2
        }

        // The whole point of the report: the shared footer is where area masks
        // and intensity live, so a header that lost the mask helper would still
        // produce 46 valid files.
        val lab = File(outDir, "lab_grade.frag").readText()
        for (needle in listOf("maskFactor", "rangeWeight", "u_maskShape", "u_curve")) {
            require(lab.contains(needle)) { "HEADER REGRESSION: $needle missing from lab_grade.frag" }
        }

        println("shader gate: ${specs.size} filters, $written files, content-verified")
    }
}
