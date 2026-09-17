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
     * Which way a screen is allowed to run past its window.
     *
     * A fixed instrument like the driving screen may not overflow at all — anything outside the
     * window is lost, because there is no way to reach it. A scrolling screen is the opposite: its
     * content is *expected* to continue past the fold, and asserting otherwise only flags it for
     * having more than one screenful. What still matters there is the other axis, where nothing
     * scrolls and an overrun is as invisible as it would be on the instrument.
     */
    enum class Scroll { NONE, VERTICAL }

    /**
     * Nothing was placed outside the viewport.
     *
     * Catches a child that escapes its parent — a fixed width that does not fit, a row that pushes
     * past the edge. One pixel of slack absorbs rounding in the layout pass.
     */
    fun assertNothingOverflows(
        root: SemanticsNode,
        where: String,
        scroll: Scroll = Scroll.NONE
    ) {
        val rootW = root.size.width.toFloat()
        val rootH = root.size.height.toFloat()
        val offenders = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            val left = node.positionInRoot.x
            val top = node.positionInRoot.y
            val right = left + node.size.width
            val bottom = top + node.size.height
            // One pixel of slack absorbs rounding in the layout pass.
            val escapesSideways = right > rootW + 1f || left < -1f
            val escapesVertically = bottom > rootH + 1f || top < -1f
            val bad = when (scroll) {
                Scroll.NONE -> escapesSideways || escapesVertically
                Scroll.VERTICAL -> escapesSideways
            }
            if (bad) {
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

    /**
     * No scrolling list was measured out of existence.
     *
     * This closes a hole the other two cannot reach. A lazy list given no height composes none of
     * its items, so there is no text node to come back collapsed and nothing to sit outside the
     * viewport — the rows simply never exist, and every other assertion passes while the screen
     * shows an empty column. It happens whenever a header above the list takes the whole window
     * and the list was never told to claim what is left.
     */
    fun assertNoScrollerIsStarved(root: SemanticsNode, where: String) {
        val starved = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            val scrolls = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            // Width but no height is the starved case: the list was laid out across the screen and
            // then given nothing to be tall with. A container that is 0x0 is simply not on screen
            // — a detail panel with nothing selected — and has no rows to lose.
            if (scrolls && node.size.width > 0 && node.size.height == 0) {
                starved += "a vertical scroller at (${node.positionInRoot.x}," +
                    "${node.positionInRoot.y}) was measured ${node.size.width}x0"
            }
            node.children.forEach { walk(it) }
        }
        walk(root)

        assertThat(starved).`as`("scrollers measured at no height — $where").isEmpty()
    }

    /**
     * The screen is actually showing the data it was given.
     *
     * The layout guards answer "is anything drawn wrongly", which is not the same question as "is
     * anything drawn at all". A list starved of height composes no rows, so there is nothing
     * misplaced, nothing collapsed and nothing outside the window — every geometric assertion
     * passes over a screen that is simply blank. This asserts the rows exist.
     */
    fun assertShowsAnyOf(root: SemanticsNode, expected: List<String>, where: String) {
        // Says what it did see. "Expected true, was false" on a screen full of text is a riddle;
        // the text itself usually names the cause outright.
        assertThat(showsAnyOf(root, expected))
            .`as`("none of %s appeared — %s showed instead: %s", expected, where, allText(root).take(400))
            .isTrue()
    }

    /** Every piece of text the screen is currently showing, flattened. */
    fun allText(root: SemanticsNode): String {
        val seen = StringBuilder()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)
                ?.forEach { seen.append(it.text).append(" | ") }
            node.children.forEach { walk(it) }
        }
        walk(root)
        return seen.toString()
    }

    /** The same question without the assertion, so a wait can poll on it. */
    fun showsAnyOf(root: SemanticsNode, expected: List<String>): Boolean {
        val seen = StringBuilder()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)
                ?.forEach { seen.append(it.text).append(' ') }
            node.children.forEach { walk(it) }
        }
        walk(root)

        val text = seen.toString()
        return expected.any { it in text }
    }

    private fun describe(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
            ?: node.config.toString().take(80)
}
