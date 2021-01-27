package uk.co.moodio.msal_flutter

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.microsoft.identity.client.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

class MsalFlutterPlugin : FlutterPlugin, ActivityAware {

    private val _channel = AtomicReference<MethodChannel>()
    private var channel: MethodChannel?
        get() = _channel.get()
        set(value) = _channel.set(value)
    private val _msalApplication = AtomicReference<IMultipleAccountPublicClientApplication?>()
    private val _activity = AtomicReference<Activity>()
    private var activity: Activity?
        get() = _activity.get()
        set(value) = _activity.set(value)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val handler = MsalMethodCallHandler(
                _msalApplication,
                binding.applicationContext,
                Scheduler.fromExecutors(BACKGROUND_EXECUTOR, FOREGROUND_EXECUTOR),
        ) { activity }
        channel = MethodChannel(binding.binaryMessenger, PLUGIN_ID)
                .apply { setMethodCallHandler(handler) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    private companion object {
        private const val PLUGIN_ID = "msal_flutter"
        private val BACKGROUND_EXECUTOR: Executor = Executors.newCachedThreadPool()
        private val FOREGROUND_EXECUTOR: Executor = HandlerExecutor(Handler(Looper.getMainLooper()))
    }

    private class HandlerExecutor(private val handler: Handler) : Executor {
        override fun execute(command: Runnable) {
            if (!handler.post(command)) throw RejectedExecutionException("$handler is shutting down")
        }
    }

}
