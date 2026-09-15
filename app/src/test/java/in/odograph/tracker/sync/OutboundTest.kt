package `in`.odograph.tracker.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class OutboundTest {

    @Test
    fun spreadsheetLinkIsNeverMistakenForScriptEndpoint() {
        assertThat(Outbound.isSpreadsheetLink(
            "https://docs.google.com/spreadsheets/d/1N-R5vy5lMhHZ3kyAPJMt3OwbFAj010IYc1vLOJ6YhxE/edit?usp=sharing"
        )).isTrue()
        assertThat(Outbound.isSpreadsheetLink("https://docs.google.com/spreadsheets/d/abc/edit")).isTrue()
        assertThat(Outbound.isSpreadsheetLink("https://script.google.com/macros/s/AKfyc/exec")).isFalse()
        assertThat(Outbound.isSpreadsheetLink("")).isFalse()
        assertThat(Outbound.isSpreadsheetLink("https://docs.google.com/forms")).isFalse()
    }

    @Test
    fun docsSheetIdExtractsIdFromShareableLink() {
        assertThat(Outbound.docsSheetId(
            "https://docs.google.com/spreadsheets/d/1N-R5vy5lMhHZ3kyAPJMt3OwbFAj010IYc1vLOJ6YhxE/edit?usp=sharing"
        )).isEqualTo("1N-R5vy5lMhHZ3kyAPJMt3OwbFAj010IYc1vLOJ6YhxE")
        assertThat(Outbound.docsSheetId("https://script.google.com/macros/s/xyz/exec")).isNull()
        assertThat(Outbound.docsSheetId("")).isNull()
    }

    @Test
    fun linkThatBehavesLikeBothIsTreatedAsReadOnly() {
        val tricky = "https://docs.google.com/spreadsheets/d/abc/edit?usp=sharing"
        assertThat(Outbound.isSpreadsheetLink(tricky)).isTrue()
        assertThat(Outbound.docsSheetId(tricky)).isEqualTo("abc")
    }
}