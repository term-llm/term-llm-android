package com.termllm.app
import com.termllm.app.data.ServerAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
class MountBoundaryTest {
 @Test fun originPortMountAndEncodedPathsAreExact() {
  val base="https://example.org/ui/".toHttpUrl()
  assertTrue(ServerAddress.isWithinMount("https://example.org/ui/chat/1".toHttpUrl(),base))
  for (url in listOf("https://example.org/ui-other/", "https://example.org:444/ui/", "https://other.example/ui/", "https://example.org/ui/%2f../secret", "https://example.org/ui/%252e%252e/secret", "https://example.org/ui/%255csecret", "https://user@example.org/ui/")) assertFalse(url,ServerAddress.isWithinMount(url.toHttpUrl(),base))
 }
}
