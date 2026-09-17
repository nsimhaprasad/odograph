package `in`.odograph.tracker.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.assertj.core.api.Assertions.assertThat

/**
 * The two mechanical definitions of "the screen did not break", shared by every layout suite.
 *
 * They catch different failures and neither subsumes the other, which is the whole reason both
 * exist. Keeping them in one place means a new suite gets both rather than whichever one its
 * author remembered.
 */
object LayoutAssertions {

    /**
     * Nothing was placed outside the viewport.
     *
     * Catches a child that escapes its parent — a fixed width that does not fit, a row that pushes
     * past the edge. One pixel of slack absorbs rounding in the layout pass.
     */
    fun assertNothingOverflows(root: SemanticsNode, where: String) {
        val rootW = root.size.width.toFloat()
        val rootH = root.size.height.toFloat()
        val offenders = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            val left = node.positionInRoot.x
            val top = node.positionInRoot.y
            val right = left + node.size.width
            val bottom = top + node.size.height
            if (right > rootW + 1f || bottom > rootH + 1f || left < -1f || top < -1f) {
                offenders += "${describe(node)}: [$left,$top,$right,$bottom] outside ${rootW}x$rootH"
            }
            node.children.forEach { walk(it) }
        }
        walk(root)

        assertThat(offenders).`as`("nodes outside the viewport — $where").isEmpty()
    }

    /**
     * Nothing that carries text was squeezed out of existence.
     *
     * The bounds walk above structurally cannot see this. A Row measures each child against the
     * width that is left, so a child that does not fit is constrained down and clipped rather than
     * placed outside its parent: its position stays perfectly legal while its content disappears.
     * That is how the drive screen shipped a stat row whose tail was cut mid-word and whose last
     * entries rendered at no height at all. A collapsed text node is the signature of it, and
     * unlike a screenshot it can be asserted.
     */
    fun assertNoTextIsSqueezedAway(root: SemanticsNode, where: String) {
        val starved = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                ?.takeIf { it.isNotBlank() }
            if (text != null && (node.size.width == 0 || node.size.height == 0)) {
                starved += "\"$text\" rendered at ${node.size.width}x${node.size.height}"
            }
            node.children.forEach { walk(it) }
        }
        walk(root)

        assertThat(starved).`as`("text collapsed to nothing — $where").isEmpty()
    }

    private fun describe(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
            ?: node.config.toString().take(80)
}
