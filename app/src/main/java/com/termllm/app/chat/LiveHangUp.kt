package com.termllm.app.chat

/**
 * Port of iOS `LiveHangUp`: ends the page's live voice call when this app starts
 * music or a podcast, because an open call keeps listening and talks over the
 * audio. It only presses the page's own Stop button and never reaches into
 * frontend stores.
 *
 * When the agent started the audio through a device tool, the call must stay
 * up until that tool's result reaches the server (or the voice turn records the
 * tool as failed); the installed script watches resource timing for the live
 * tool-result upload and stops afterwards. [TOOL_RESULT_GRACE_MS] bounds the wait.
 */
object LiveHangUp {

    const val TOOL_RESULT_GRACE_MS = 4_000L

    /** Installed at document start, main frame only. */
    val userScript: String = """
        (() => {
          'use strict';
          if (window.top !== window) return;
          if (window.__termLLMLiveHangUp) return;
          const RESULT = /\/v1\/live\/sessions\/[^/]+\/tool_calls\/[^/]+\/result${'$'}/;
          let pending = false;
          const stopButton = () => {
            const buttons = [...document.querySelectorAll('button')].filter((b) => !b.disabled);
            return buttons.find((b) => b.closest('section[aria-label="Live voice"]') && b.textContent.trim() === 'Stop') ||
              buttons.find((b) => b.getAttribute('aria-label') === 'Stop live voice');
          };
          const stop = () => {
            pending = false;
            const button = stopButton();
            if (!button) return 'idle';
            button.click();
            return 'stopped';
          };
          try {
            new PerformanceObserver((list) => {
              if (!pending) return;
              for (const entry of list.getEntries()) {
                let path = '';
                try { path = new URL(entry.name).pathname; } catch { continue; }
                if (RESULT.test(path)) { stop(); return; }
              }
            }).observe({ type: 'resource' });
          } catch {}
          Object.defineProperty(window, '__termLLMLiveHangUp', {
            value: Object.freeze({
              // afterToolResult: wait for the next live tool result upload to finish.
              request(afterToolResult) {
                if (!stopButton()) { pending = false; return 'idle'; }
                if (!afterToolResult) return stop();
                pending = true;
                return 'pending';
              },
              // Retiring a document/bridge must not stop a later voice call.
              cancel() { pending = false; return 'idle'; },
              // The grace period ran out: end a still-pending call regardless.
              flush() { return pending ? stop() : 'idle'; },
            }),
            configurable: true,
          });
        })();
    """.trimIndent()

    fun requestScript(afterToolResult: Boolean): String =
        "window.__termLLMLiveHangUp && window.__termLLMLiveHangUp.request(" + afterToolResult + "); void 0;"

    const val FLUSH_SCRIPT = "window.__termLLMLiveHangUp && window.__termLLMLiveHangUp.flush(); void 0;"
    const val CANCEL_SCRIPT = "window.__termLLMLiveHangUp && window.__termLLMLiveHangUp.cancel(); void 0;"

    /**
     * Decides what to send to the page when playback starts (iOS Coordinator
     * `appWillStartPlayback` / `deviceToolCallReplied`). Pure so it is testable:
     *
     *  - playback started by the user (not inside a device-tool call) → hang up now;
     *  - playback started by a tool → nothing yet; when that tool's reply has been
     *    handed to the page → `request(true)` and a grace flush after
     *    [TOOL_RESULT_GRACE_MS]; a newer tool start cancels an armed grace.
     */
    class Coordinator(
        private val run: (script: String) -> Unit,
        private val schedule: (delayMs: Long, action: () -> Unit) -> Cancellable,
    ) {
        interface Cancellable { fun cancel() }

        private var toolPlaybackPending = false
        private var grace: Cancellable? = null

        fun onPlaybackStarting(fromTool: Boolean) {
            if (fromTool) {
                if (grace != null) cancelGrace(cancelPageRequest = true)
                toolPlaybackPending = true
            } else if (!toolPlaybackPending) {
                cancelGrace(cancelPageRequest = true)
                run(requestScript(afterToolResult = false))
            }
        }

        /** The tool reply has been delivered to the page. */
        fun onToolReplied() {
            if (!toolPlaybackPending) return
            toolPlaybackPending = false
            run(requestScript(afterToolResult = true))
            grace = schedule(TOOL_RESULT_GRACE_MS) {
                grace = null
                run(FLUSH_SCRIPT)
            }
        }

        /** The document is going away: an armed request must not stop a later call. */
        fun reset() {
            toolPlaybackPending = false
            cancelGrace(cancelPageRequest = true)
        }

        private fun cancelGrace(cancelPageRequest: Boolean) {
            if (grace != null && cancelPageRequest) run(CANCEL_SCRIPT)
            grace?.cancel()
            grace = null
        }
    }
}
