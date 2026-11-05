package com.termllm.app
import android.app.Application
import android.app.Activity
import android.os.Bundle
import com.termllm.app.listen.ListenStore
import com.termllm.app.listen.PlayerHub
/** Application-scoped repository; UI controllers exist only while this app's activity is visible. */
class TermLlmApplication : Application() {
 val listenStore by lazy { ListenStore(this) }
 override fun onCreate() {
  super.onCreate()
  listenStore
  registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks {
   private var visibleActivities = 0
   override fun onActivityStarted(activity:Activity) { if(activity is MainActivity) { visibleActivities++;com.termllm.app.push.PushCenter.noteAppVisibility(true);PlayerHub.storeRef=listenStore;PlayerHub.connect(this@TermLlmApplication,listenStore) } }
   override fun onActivityStopped(activity:Activity) { if(activity is MainActivity) { visibleActivities=(visibleActivities-1).coerceAtLeast(0);if(visibleActivities==0) {com.termllm.app.push.PushCenter.noteAppVisibility(false);PlayerHub.release()} } }
   override fun onActivityCreated(activity:Activity,bundle:Bundle?) {}
   override fun onActivityResumed(activity:Activity) {}
   override fun onActivityPaused(activity:Activity) {}
   override fun onActivitySaveInstanceState(activity:Activity,bundle:Bundle) {}
   override fun onActivityDestroyed(activity:Activity) {}
  })
 }
}
