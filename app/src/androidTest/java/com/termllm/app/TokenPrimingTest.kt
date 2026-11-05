package com.termllm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.ui.ChatDocumentHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The document-start seed for the web UI's bearer lives in localStorage, so the
 * literal it builds must survive quotes, backslashes, newlines and `<` without
 * letting injected text break out of the literal.
 */
@RunWith(AndroidJUnit4::class)
class TokenPrimingTest {

    private val holder = ChatDocumentHolder(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun plainTokensBecomePlainLiterals() {
        assertEquals("\"tok-123\"", holder.jsLiteral("tok-123"))
    }

    @Test
    fun quotesAndBackslashesAreEscaped() {
        assertEquals("\"a\\\"b\"", holder.jsLiteral("a\"b"))
        assertEquals("\"a\\\\b\"", holder.jsLiteral("a\\b"))
    }

    @Test
    fun newlinesAndScriptTagsCannotBreakOut() {
        val literal = holder.jsLiteral("a\nb</script>")
        assertFalse("newline must be escaped: $literal", literal.contains("\n"))
        assertFalse("angle bracket must be escaped: $literal", literal.contains("</script>"))
    }
}
