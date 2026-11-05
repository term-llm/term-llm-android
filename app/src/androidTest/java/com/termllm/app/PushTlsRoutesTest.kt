package com.termllm.app
import kotlinx.coroutines.flow.first
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.push.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
@RunWith(AndroidJUnit4::class)
class PushTlsRoutesTest {
    @get:org.junit.Rule val foreground = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    private fun enableForegroundNotifications() {
        lateinit var vm:com.termllm.app.ui.AppViewModel
        foreground.scenario.onActivity { vm=androidx.lifecycle.ViewModelProvider(it)[com.termllm.app.ui.AppViewModel::class.java] }
        kotlinx.coroutines.runBlocking { vm.settingsReady.first { it } }
        instrumentation.runOnMainSync { vm.setNotificationsEnabled(true) }
        instrumentation.waitForIdleSync()
    }

 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val context=instrumentation.targetContext
 private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build()
 private val relay="https://10.0.2.2:8443"
 private val manager get()=context.getSystemService(NotificationManager::class.java)
 private val keys=PushCrypto.generateKeyPair()
 private val public=PushCrypto.base64Url(PushCrypto.publicKeyBytes(keys.public))
 private fun waitFor(what:String,condition:()->Boolean) {val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(100)};error(what)}
 private fun vapid():String {
  fun b64(b:ByteArray)=PushCrypto.base64Url(b)
  val input=b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".toByteArray())+"."+b64(("{\"aud\":\"https://localhost:8443\",\"exp\":"+(System.currentTimeMillis()/1000+300)+",\"sub\":\"mailto:fixture@example.invalid\"}").toByteArray())
  val signer=java.security.Signature.getInstance("SHA256withECDSA");signer.initSign(keys.private);signer.update(input.toByteArray());val der=signer.sign();val raw=ByteArray(64);var at=2
  repeat(2){part->at++;val len=der[at++].toInt()and 255;val skip=(len-32).coerceAtLeast(0);System.arraycopy(der,at+skip,raw,part*32+(32-(len-skip)),len-skip);at+=len}
  return "vapid t="+input+"."+b64(raw)
 }
 private fun send(route:String,event:String) {
  val sub=PushCenter.subscription(route)!!;val k=sub.getJSONObject("keys")
  val payload=PushPayload(title="Route "+route,body="TLS QA fixture",url="/"+route+"/chat/shared",sessionId="shared",eventId=event)
  val body=PushCrypto.seal(PushMessageJson.encode(payload).toByteArray(),PushCrypto.base64UrlDecode(k.getString("p256dh")),PushCrypto.base64UrlDecode(k.getString("auth")))
  val nodeEndpoint=sub.getString("endpoint");val deviceEndpoint=nodeEndpoint.replace("https://localhost:8443",relay)
  val request=Request.Builder().url(deviceEndpoint).header("Authorization",vapid()).header("Content-Encoding","aes128gcm").header("TTL","120").post(body.toRequestBody("application/octet-stream".toMediaType())).build()
  client.newCall(request).execute().use {assertEquals(201,it.code)}
 }
 @Test fun httpsEnrollmentTwoCollidingSourcesAndEndpointSwitchStopWorkWithoutRelaunch()= runBlocking {
  enableForegroundNotifications()
  PushCenter.awaitReady(context)
  instrumentation.uiAutomation.executeShellCommand("pm grant "+context.packageName+" android.permission.POST_NOTIFICATIONS").close()
  manager.cancelAll()
  val a="tls-a";val b="tls-b"
  try {
   PushCenter.attachRoute(a,"https://example.invalid","/tls-a/");PushCenter.attachRoute(b,"https://example.invalid","/tls-b/")
   val one=PushCenter.enroll(a,public,relay)!!;val two=PushCenter.enroll(b,public,relay)!!
   assertNotEquals("Same VAPID key must still create route-specific endpoints",one,two)
   PushStreamService.start(context,one,a);PushStreamService.start(context,two,b)
   waitFor("TLS streams never became active"){PushCenter.streamState(a).active&&PushCenter.streamState(b).active}
   val oldSecret=PushCenter.deviceSecret(a)
   val sameEndpoint=PushCenter.reenroll(a,relay)!!
   assertEquals(one,sameEndpoint)
   assertTrue("Renewal must rotate the fixture capability",oldSecret != PushCenter.deviceSecret(a))
   PushStreamService.start(context,sameEndpoint,a)
   send(a,"same-endpoint-"+System.nanoTime())
   waitFor("Rotated capability must deliver, not retain the old live stream"){manager.activeNotifications.any {it.notification.channelId==PushNotifications.ALERTS}}
   manager.cancelAll()
   PushCenter.markNeedsReenroll(a,"fixture expired capability")
   assertNull("An auth-failed subscription must not be advertised as usable",PushCenter.subscription(a))
   PushCenter.enroll(a,public,relay)!!
   assertNotNull(PushCenter.subscription(a))
   PushStreamService.start(context,sameEndpoint,a)
   // Route A ciphertext on B's authenticated stream must never be rerouted to A.
   val ka=PushCenter.subscription(a)!!.getJSONObject("keys")
   val cross=PushCrypto.seal(PushMessageJson.encode(PushPayload(title="Wrong route",body="Must refuse",url="/tls-a/chat/shared",eventId="cross")).toByteArray(),PushCrypto.base64UrlDecode(ka.getString("p256dh")),PushCrypto.base64UrlDecode(ka.getString("auth")))
   assertNull(PushCenter.handleBody("cross-fixture",PushCrypto.base64Url(cross),b))
   assertEquals(0,manager.activeNotifications.count {it.notification.channelId==PushNotifications.ALERTS})
   PushCenter.recordPresence(a,"https://example.invalid","shared","doc-a",true,java.time.Instant.now().plusSeconds(60).toString())
   send(b,"presence-collision-"+System.nanoTime())
   waitFor("A's same-origin presence must not suppress B"){manager.activeNotifications.any {it.notification.channelId==PushNotifications.ALERTS}}
   PushCenter.recordPresence(a,"https://example.invalid","shared","doc-a",false,"")
   manager.cancelAll()
   foreground.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
   PushCenter.recordPresence(a,"https://example.invalid","shared","stale-background-doc",true,java.time.Instant.now().plusSeconds(60).toString())
   send(a,"background-presence-"+System.nanoTime())
   waitFor("A stopped native window must not suppress background alerts"){manager.activeNotifications.any {it.notification.channelId==PushNotifications.ALERTS}}
   manager.cancelAll()
   foreground.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
   val event="collision-"+System.nanoTime();send(a,event);send(b,event)
   waitFor("Both colliding event/session IDs must notify separately"){manager.activeNotifications.count {it.notification.channelId==PushNotifications.ALERTS}==2}
   val expected=setOf(PushRouting.notificationId(a,event),PushRouting.notificationId(b,event))
   assertEquals(expected,manager.activeNotifications.filter {it.notification.channelId==PushNotifications.ALERTS}.map{it.id}.toSet())
   // Route table survives an actual reload; routing does not depend on last page.
   assertEquals("/tls-a/",PushCenter.state.value.routes[a]!!.mountPrefix)
   assertEquals("/tls-b/",PushCenter.state.value.routes[b]!!.mountPrefix)
   val secondKeys=PushCrypto.generateKeyPair();val secondPublic=PushCrypto.base64Url(PushCrypto.publicKeyBytes(secondKeys.public))
   val changed=PushCenter.enroll(a,secondPublic,relay)!!
   assertNotEquals(one,changed);PushStreamService.start(context,changed,a)
   waitFor("Re-enrolled endpoint did not replace the active call"){PushCenter.streamState(a).active&&PushCenter.streamState(a).endpoint==changed}
   PushCenter.disable(a)
   waitFor("Disabling one route must cancel its active socket"){!PushCenter.streamState(a).running}
   assertTrue("Disabling A must leave B active",PushCenter.streamState(b).active)
   foreground.scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[com.termllm.app.ui.AppViewModel::class.java].setNotificationsEnabled(false) }
   PushStreamService.stop(context)
   waitFor("Stop must cancel active sockets promptly"){!PushCenter.streamState(a).running&&!PushCenter.streamState(b).running}
  } finally {PushStreamService.stop(context);PushCenter.disable(a);PushCenter.disable(b);manager.cancelAll()}
 }
}
