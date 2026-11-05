package com.termllm.app

import com.termllm.app.auth.NativeLogin
import com.termllm.app.data.ServerKind
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The PKCE handoff ported from iOS `NativeLogin` against core
 * `serve_native_handoff.go`: S256 challenge, strict callback validation, and a
 * redeem that accepts only the exact session cookie the server scopes to the mount.
 */
class NativeLoginTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun mountUrl(path: String = "/hub/") = (server.url("/").toString().trimEnd('/') + path).toHttpUrl()

    @Test
    fun challengeIsRfc7636S256() {
        // RFC 7636 appendix B vector.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", NativeLogin.challenge(verifier))
        val pending = NativeLogin.begin("c1")
        assertEquals(43, pending.verifier.length)
        assertEquals(43, pending.challenge.length)
        assertEquals(NativeLogin.challenge(pending.verifier), pending.challenge)
    }

    @Test
    fun startUrlStaysInsideTheMount() {
        val url = NativeLogin.startUrl("https://hub.example.com/work/".toHttpUrl(), "abc")
        assertEquals("https://hub.example.com/work/auth/native/abc", url.toString())
        val root = NativeLogin.startUrl("https://hub.example.com/".toHttpUrl(), "abc")
        assertEquals("https://hub.example.com/auth/native/abc", root.toString())
    }

    @Test
    fun callbackIsValidatedStrictly() {
        val challenge = NativeLogin.challenge("v")
        val code = NativeLogin.base64Url(ByteArray(32) { 7 })
        assertEquals(code, NativeLogin.parseCallback("termllm-auth://callback?code=$code&state=$challenge", challenge))
        // wrong state
        assertNull(NativeLogin.parseCallback("termllm-auth://callback?code=$code&state=other", challenge))
        // extra item
        assertNull(NativeLogin.parseCallback("termllm-auth://callback?code=$code&state=$challenge&x=1", challenge))
        // short code
        assertNull(NativeLogin.parseCallback("termllm-auth://callback?code=abc&state=$challenge", challenge))
        // wrong host / scheme / path
        assertNull(NativeLogin.parseCallback("termllm-auth://other?code=$code&state=$challenge", challenge))
        assertNull(NativeLogin.parseCallback("https://callback?code=$code&state=$challenge", challenge))
        assertNull(NativeLogin.parseCallback("termllm-auth://callback/x?code=$code&state=$challenge", challenge))
    }

    @Test
    fun redeemSendsOriginAndKeepsOnlyTheScopedHttpOnlyCookie() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                .addHeader("Set-Cookie", "term_llm_web_session=s3cr3t; Path=/hub/; HttpOnly; SameSite=Strict; Max-Age=600")
                .addHeader("Set-Cookie", "decoy=1; Path=/hub/")
        )
        val cookie = NativeLogin.redeem(mountUrl(), ServerKind.DIRECT_NODE, "code", "verifier")
        assertEquals("term_llm_web_session", cookie.name)
        assertEquals("s3cr3t", cookie.value)
        assertEquals("/hub/", cookie.path)
        assertTrue(cookie.expiresAt != null && cookie.expiresAt!! > System.currentTimeMillis())
        val recorded = server.takeRequest()
        assertEquals("/hub/api/auth/native/redeem", recorded.path)
        assertEquals(server.url("/").toString().trimEnd('/'), recorded.getHeader("Origin"))
        assertNull(recorded.getHeader("Cookie"))
        assertNull(recorded.getHeader("Authorization"))
        assertEquals("""{"code":"code","code_verifier":"verifier"}""", recorded.body.readUtf8())
    }

    @Test
    fun redeemRejectsWrongCookieScopeNameAndRedirects() {
        // Cookie at a sibling path is not the session this mount issued.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                .addHeader("Set-Cookie", "term_llm_web_session=s; Path=/other/; HttpOnly")
        )
        try {
            NativeLogin.redeem(mountUrl(), ServerKind.DIRECT_NODE, "code", "verifier")
            fail("sibling-path cookie accepted")
        } catch (e: NativeLogin.LoginException) {
        }
        // A Hub expects the hub cookie name, not the web one.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                .addHeader("Set-Cookie", "term_llm_web_session=s; Path=/hub/; HttpOnly")
        )
        try {
            NativeLogin.redeem(mountUrl(), ServerKind.HUB, "code", "verifier")
            fail("wrong cookie name accepted")
        } catch (e: NativeLogin.LoginException) {
        }
        // Non-HttpOnly cookies are refused.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                .addHeader("Set-Cookie", "term_llm_web_session=s; Path=/hub/")
        )
        try {
            NativeLogin.redeem(mountUrl(), ServerKind.DIRECT_NODE, "code", "verifier")
            fail("non-HttpOnly cookie accepted")
        } catch (e: NativeLogin.LoginException) {
        }
        // Redirects are never followed.
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://evil.example/"))
        try {
            NativeLogin.redeem(mountUrl(), ServerKind.DIRECT_NODE, "code", "verifier")
            fail("redirect accepted")
        } catch (e: NativeLogin.LoginException) {
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun cookieStringsCarryScopeAndFlags() {
        val cookie = NativeLogin.SessionCookie("term_llm_hub_session", "v", "/work/", System.currentTimeMillis() + 60_000, secure = true)
        val text = NativeLogin.webViewCookie(cookie)
        assertTrue(text.startsWith("term_llm_hub_session=v; Path=/work/; HttpOnly; Secure; Max-Age="))
        val ok = NativeLogin.okHttpCookie("https://hub.example.com/work/".toHttpUrl(), cookie)
        assertEquals("hub.example.com", ok.domain)
        assertEquals("/work/", ok.path)
        assertTrue(ok.httpOnly && ok.secure)
    }
}
