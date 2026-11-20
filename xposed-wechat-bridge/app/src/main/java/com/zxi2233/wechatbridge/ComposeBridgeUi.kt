package com.zxi2233.wechatbridge

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** A Kotlin bridge that lets the Java compatibility activity host Compose. */
object ComposeBridgeUi {
    @JvmStatic
    fun attach(
        activity: ComponentActivity,
        onOpenHost: Runnable,
        onReturnToCaller: Runnable,
        onClearSavedIntent: Runnable,
    ) {
        activity.setContent {
            JzLoginBridgeApp(
                onOpenHost = onOpenHost::run,
                onReturnToCaller = onReturnToCaller::run,
                onClearSavedIntent = onClearSavedIntent::run,
            )
        }
    }
}
