package com.termllm.app
import com.termllm.app.push.PushRouting
import org.junit.Assert.*
import org.junit.Test
class PushReadIdentityTest {
 @Test fun onlyExactNonemptyStoreSessionAndSequenceMayAcknowledge() {
  fun matches(session:String="shared",store:String="store-a",seq:Long=7)=PushRouting.matchesReadIdentity(session,store,seq,"shared","store-a",7)
  assertTrue(matches())
  assertFalse(matches(store="store-b")) // restarted store, colliding ids
  assertFalse(matches(session="shared-prefix"))
  assertFalse(matches(session=""));assertFalse(matches(store=""))
  assertFalse(matches(seq=0));assertFalse(matches(seq=6));assertFalse(matches(seq=8))
 }
}
