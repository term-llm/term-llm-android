package com.termllm.app.chat

/**
 * Port of iOS `VoiceLaunchScript`: the voice shortcut opens the agent's page and
 * presses the page's own "Start live voice" button. Only main-frame UI
 * affordances are inspected; nothing reaches into frontend stores.
 */
object VoiceLaunch {

    private fun fn(body: String) = "(() => { " + body + " })()"

    /** 'active' | 'failed' | 'waiting' */
    val inspect: String = fn(
        """
        const visible = b => b.getClientRects().length > 0;
        const buttons = [...document.querySelectorAll('button')].filter(visible);
        if (buttons.some(b => b.getAttribute('aria-label') === 'Stop live voice' ||
            (b.closest('section[aria-label="Live voice"]') && b.textContent.trim() === 'Stop'))) return 'active';
        if (buttons.some(b => b.getAttribute('aria-label') === 'Retry live voice')) return 'failed';
        return 'waiting';
        """.trimIndent()
    )

    /** 'active' | 'failed' | 'waiting' from the status section's phase classes. */
    val confirm: String = fn(
        """
        const status = document.querySelector('section[aria-label="Live voice"]');
        if (status && status.classList.contains('live-status-failed')) return 'failed';
        if (['listening', 'speaking', 'working'].some(phase => status && status.classList.contains('live-status-' + phase))) return 'active';
        if ([...document.querySelectorAll('button')].some(b => b.getAttribute('aria-label') === 'Retry live voice')) return 'failed';
        return 'waiting';
        """.trimIndent()
    )

    /** 'active' | 'failed' | 'waiting' | 'clicked' */
    val start: String = fn(
        """
        const buttons = [...document.querySelectorAll('button')].filter(b => b.getClientRects().length > 0);
        if (buttons.some(b => b.getAttribute('aria-label') === 'Stop live voice' ||
            (b.closest('section[aria-label="Live voice"]') && b.textContent.trim() === 'Stop'))) return 'active';
        const button = buttons.find(b => b.getAttribute('aria-label') === 'Start live voice' && !b.disabled);
        if (buttons.some(b => b.getAttribute('aria-label') === 'Retry live voice')) return 'failed';
        if (!button || document.visibilityState !== 'visible') return 'waiting';
        button.click();
        return 'clicked';
        """.trimIndent()
    )

    /** How long to keep polling for the Start button after the document loaded. */
    const val START_TIMEOUT_MS = 20_000L
    const val CONFIRM_TIMEOUT_MS = 15_000L
    const val POLL_MS = 500L

    /** Unquote a `evaluateJavascript` result ("\"clicked\"" → clicked). */
    fun unquote(result: String?): String = result?.trim()?.trim('"') ?: "null"
}
