package com.termllm.app.listen
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
/** Observable preference; all persistent access is on IO, including worker reads. */
object DownloadSettings {
 val allowMetered=MutableStateFlow(true)
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 suspend fun load(context:Context) = withContext(Dispatchers.IO) { allowMetered.value=read(context).allowMetered }
 fun read(context:Context):DownloadPreferences=DownloadPreferences(allowMetered=context.getSharedPreferences("download-settings",Context.MODE_PRIVATE).getBoolean("allow_metered",true))
 fun set(context:Context,allow:Boolean) { allowMetered.value=allow;scope.launch {context.getSharedPreferences("download-settings",Context.MODE_PRIVATE).edit().putBoolean("allow_metered",allow).commit()} }
}
