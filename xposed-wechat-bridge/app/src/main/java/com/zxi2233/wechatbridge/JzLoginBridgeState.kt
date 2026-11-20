package com.zxi2233.wechatbridge

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Volatile UI state. No session, state token or result is persisted here. */
object JzLoginBridgeState {
    @Volatile
    private var requestState: String = ""
    @Volatile
    private var requestReturnUrl: String = ""
    @Volatile
    private var requestReturnUri: String = ""
    @Volatile
    private var requestReturnPackage: String = ""
    var hostHandoff by mutableStateOf(false)
        private set
    var hostUnavailable by mutableStateOf(false)
        private set
    var returnRequested by mutableStateOf(false)
        private set
    var returnUnavailable by mutableStateOf(false)
        private set
    var result by mutableStateOf<BridgeResultUi?>(null)
        private set

    fun markRequest(
        state: String,
        returnUrl: String = "",
        returnUri: String = "",
        returnPackage: String = "",
    ) {
        requestState = state
        requestReturnUrl = returnUrl
        requestReturnUri = returnUri
        requestReturnPackage = returnPackage
        result = null
        hostHandoff = false
        hostUnavailable = false
        returnRequested = false
        returnUnavailable = false
    }

    fun pendingRequest(): String = requestState

    fun pendingReturnUrl(): String = requestReturnUrl

    fun pendingReturnUri(): String = requestReturnUri

    fun pendingReturnPackage(): String = requestReturnPackage

    fun hasReturnTarget(): Boolean =
        requestReturnUrl.isNotBlank() ||
            requestReturnUri.isNotBlank() ||
            requestReturnPackage.isNotBlank()

    fun markHostOpening() {
        hostHandoff = true
        hostUnavailable = false
    }

    fun reportHostUnavailable() {
        hostUnavailable = true
    }

    fun returnRequested() {
        returnRequested = true
        returnUnavailable = false
    }

    fun reportReturnUnavailable() {
        returnUnavailable = true
    }

    fun clear() {
        requestState = ""
        requestReturnUrl = ""
        requestReturnUri = ""
        requestReturnPackage = ""
        hostHandoff = false
        hostUnavailable = false
        returnRequested = false
        returnUnavailable = false
        result = null
    }

    fun applyResult(
        action: String?,
        state: String,
        hasSession: Boolean,
        origin: String,
        wechatError: String,
        wechatErrorCode: Int,
        capturedAt: Long,
        hostVersion: String,
    ) {
        result = bridgeResultFromLaunch(
            action = action,
            actionShowCredential = MainActivity.ACTION_SHOW_CREDENTIAL,
            state = state,
            hasSession = hasSession,
            origin = origin,
            wechatError = wechatError,
            wechatErrorCode = wechatErrorCode,
            capturedAt = capturedAt,
            hostVersion = hostVersion,
        )
    }
}

data class BridgeResultUi(
    val state: String = "",
    val hasSession: Boolean = false,
    val origin: String = "",
    val wechatError: String = "",
    val wechatErrorCode: Int = 0,
    val capturedAt: Long = 0L,
    val hostVersion: String = "",
) {
    val isTerminal: Boolean
        get() = hasSession || wechatErrorCode != 0
}

internal fun isLoginBridgeDeepLink(action: String?, scheme: String?, host: String?): Boolean =
    Intent.ACTION_VIEW == action &&
        MainActivity.DEEP_LINK_SCHEME == scheme &&
        MainActivity.DEEP_LINK_HOST == host

fun bridgeResultFromLaunch(
    action: String?,
    actionShowCredential: String,
    state: String,
    hasSession: Boolean,
    origin: String,
    wechatError: String,
    wechatErrorCode: Int,
    capturedAt: Long,
    hostVersion: String,
): BridgeResultUi? {
    if (action != actionShowCredential && state.isBlank() && !hasSession) return null
    return BridgeResultUi(
        state = state,
        hasSession = hasSession,
        origin = origin,
        wechatError = wechatError,
        wechatErrorCode = wechatErrorCode,
        capturedAt = capturedAt,
        hostVersion = hostVersion,
    )
}
