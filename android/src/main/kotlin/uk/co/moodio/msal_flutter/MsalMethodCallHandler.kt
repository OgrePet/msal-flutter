package uk.co.moodio.msal_flutter

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicReference

class MsalMethodCallHandler(private val msalApplicationRef: AtomicReference<IMultipleAccountPublicClientApplication?>,
                            private val applicationContext: Context,
                            private val scheduler: Scheduler,
                            private val activityProducer: () -> Activity?)
    : MethodChannel.MethodCallHandler {

    private var msalApplication
        get() = msalApplicationRef.get()
        set(value) = msalApplicationRef.set(value)

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val scopes: Array<String>? = call.argument<ArrayList<String>?>("scopes")?.toTypedArray()
        when (call.method) {
            "initialize" -> initialize(call.argument("clientId"), call.argument("authority"), result)
            "acquireToken" -> acquireToken(scopes, result)
            "acquireTokenSilent" -> acquireTokenSilent(scopes, result)
            "logout" -> logout(result)
            else -> result.notImplemented()
        }
    }

    private fun initialize(clientId: String?, authority: String?, result: MethodChannel.Result) {
        val msalApplication = this.msalApplication
        when {
            clientId == null -> result.foregroundError(ERROR_MSAL_NO_CLIENT_ID)
            msalApplication != null ->
                if (msalApplication.configuration.clientId == clientId) result.success(true)
                else result.foregroundError(ERROR_MSAL_MULTIPLE_CLIENT_IDS)
            authority == null -> PublicClientApplication.create(applicationContext, clientId, ApplicationCreationListenerAdapter(result))
            else -> PublicClientApplication.create(applicationContext, clientId, authority, ApplicationCreationListenerAdapter(result))
        }
    }

    private inner class ApplicationCreationListenerAdapter(private val result: MethodChannel.Result) : IPublicClientApplication.ApplicationCreatedListener {
        override fun onCreated(application: IPublicClientApplication) {
            msalApplication = application as MultipleAccountPublicClientApplication
            result.success(true)
        }

        override fun onError(exception: MsalException?) =
                result.foregroundError(ERROR_MSAL_INITIALIZATION, exception?.details())
    }


    private fun acquireToken(scopes: Array<String>?, result: MethodChannel.Result) = scheduler.background {
        val msalApplication = this.msalApplication
        val activity = activityProducer()
        when {
            msalApplication == null -> result.foregroundError(ERROR_COMMON_NO_CLIENT)
            activity == null -> result.foregroundError(ERROR_TOKEN_NO_UI)
            scopes == null -> result.foregroundError(ERROR_COMMON_NO_SCOPE)
            else -> {
                try {
                    msalApplication.accounts.forEach(msalApplication::removeAccount)
                    msalApplication.acquireToken(activity, scopes, AuthenticationCallbackAdapter(result))
                } catch (e: MsalException) {
                    result.foregroundError(ERROR_TOKEN_ACQUIRE, e.details())
                }
            }
        }
    }

    private inner class AuthenticationCallbackAdapter(private val result: MethodChannel.Result)
        : AuthenticationCallback {
        override fun onSuccess(authenticationResult: IAuthenticationResult) =
                scheduler.foreground { result.success(authenticationResult.accessToken) }

        override fun onError(exception: MsalException?) =
                result.foregroundError(ERROR_TOKEN_ACQUIRE, exception?.details())

        override fun onCancel() = result.foregroundError(ERROR_TOKEN_ACQUIRE_CANCELLATION)
    }

    private fun acquireTokenSilent(scopes: Array<String>?, result: MethodChannel.Result) = scheduler.background {
        val msalApplication = this.msalApplication
        when {
            msalApplication == null -> result.foregroundError(ERROR_COMMON_NO_CLIENT)
            scopes == null -> result.foregroundError(ERROR_COMMON_NO_SCOPE)
            msalApplication.accounts.isEmpty() -> result.foregroundError(ERROR_SILENT_TOKEN_NO_ACCOUNT)
            else -> {
                try {
                    val res = msalApplication.acquireTokenSilent(scopes, msalApplication.accounts[0],
                            msalApplication.configuration.defaultAuthority.authorityURL.toString())
                    scheduler.foreground { result.success(res.accessToken) }
                } catch (e: Exception) {
                    result.foregroundError(ERROR_SILENT_TOKEN, e.details())
                }
            }
        }
    }


    private fun logout(result: MethodChannel.Result) = scheduler.background {
        val msalApplication = this.msalApplication
        if (msalApplication == null) {
            result.foregroundError(ERROR_COMMON_NO_CLIENT)
        } else {
            try {
                msalApplication.accounts.forEach(msalApplication::removeAccount)
                scheduler.foreground { result.success(true) }
            } catch (e: MsalException) {
                result.foregroundError(ERROR_LOGOUT, e.details())
            }
        }
    }

    private fun MethodChannel.Result.foregroundError(key: String, details: String? = null) =
            scheduler.foreground { errorOf(key, details).forward(this) }

    private fun Throwable.details() = "${localizedMessage}\n${stackTraceToString()}"

    private companion object {
        private const val ERROR_COMMON_NO_CLIENT = "ERROR_COMMON_NO_CLIENT"
        private const val ERROR_COMMON_NO_SCOPE = "ERROR_COMMON_NO_SCOPE"
        private const val ERROR_MSAL_NO_CLIENT_ID = "ERROR_MSAL_NO_CLIENT_ID"
        private const val ERROR_MSAL_MULTIPLE_CLIENT_IDS = "ERROR_MSAL_MULTIPLE_CLIENT_IDS"
        private const val ERROR_MSAL_INITIALIZATION = "ERROR_INITIALIZATION"
        private const val ERROR_TOKEN_NO_UI = "ERROR_TOKEN_NO_UI"
        private const val ERROR_TOKEN_ACQUIRE = "ERROR_TOKEN_ACQUIRING"
        private const val ERROR_TOKEN_ACQUIRE_CANCELLATION = "ERROR_AUTHENTICATION_CANCELLATION"
        private const val ERROR_SILENT_TOKEN_NO_ACCOUNT = "ERROR_SILENT_TOKEN_NO_ACCOUNT"
        private const val ERROR_SILENT_TOKEN = "ERROR_SILENT_TOKEN"
        private const val ERROR_LOGOUT = "ERROR_LOGOUT"
        private val ERRORS = mapOf(
                ERROR_COMMON_NO_CLIENT to ("NO_CLIENT" to "Client must be initialized before attempting any operation besides \"initialize\"."),
                ERROR_COMMON_NO_SCOPE to ("NO_SCOPE" to "Call must include a scope"),
                ERROR_MSAL_NO_CLIENT_ID to ("NO_CLIENTID" to "Call must include a clientId"),
                ERROR_MSAL_MULTIPLE_CLIENT_IDS to ("NO_CLIENTID" to "Call must include a clientId"),
                ERROR_MSAL_INITIALIZATION to ("INIT_ERROR" to "Error initializing client"),
                ERROR_TOKEN_NO_UI to ("NO_UI" to "Not attached to UI"),
                ERROR_TOKEN_ACQUIRE to ("ACQUIRE_TOKEN" to "Error occurred during token acquiring"),
                ERROR_TOKEN_ACQUIRE_CANCELLATION to ("CANCELLED" to "User cancelled"),
                ERROR_SILENT_TOKEN_NO_ACCOUNT to ("NO_ACCOUNT" to "No account is available to acquire token silently for"),
                ERROR_SILENT_TOKEN to ("ACQUIRE_TOKEN" to "Error occurred during silent token acquiring"),
                ERROR_LOGOUT to ("LOGOUT_ERROR" to "Logging out failed"),
        )

        private fun errorOf(key: String, details: String? = null) =
                PluginError.of(ERRORS[key] ?: error("Key \"$key\" was not found"), details)
    }

    private class PluginError(private val code: String, private val message: String, private val details: String?) {

        fun forward(result: MethodChannel.Result) = result.error(code, message, details)

        companion object {
            fun of(mapping: Pair<String, String>, details: String?) =
                    PluginError(mapping.first, mapping.second, details)
        }
    }
}
