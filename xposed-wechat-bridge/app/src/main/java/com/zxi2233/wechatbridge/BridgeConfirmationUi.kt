package com.zxi2233.wechatbridge

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicBoolean

/** MD3 confirmation dialog hosted from the Xposed-injected XTC process. */
object BridgeConfirmationUi {
    private val colors = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF9ECBFF),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF003258),
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF204A6D),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD0E4FF),
        secondary = androidx.compose.ui.graphics.Color(0xFF79D79B),
        onSecondary = androidx.compose.ui.graphics.Color(0xFF00391B),
        surface = androidx.compose.ui.graphics.Color(0xFF1A1E25),
        onSurface = androidx.compose.ui.graphics.Color(0xFFE7E8EC),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2A303A),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB9BEC9),
        outline = androidx.compose.ui.graphics.Color(0xFF737985),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF424852),
    )

    @JvmStatic
    fun show(
        activity: Activity,
        origin: String,
        onAllow: Runnable,
        onReject: Runnable,
        onDismiss: Runnable = Runnable {},
    ): Dialog {
        val dialog = Dialog(activity)
        val settled = AtomicBoolean(false)
        val allow = Runnable {
            if (settled.compareAndSet(false, true)) {
                if (dialog.isShowing) dialog.dismiss()
                onAllow.run()
            }
        }
        val reject = Runnable {
            if (settled.compareAndSet(false, true)) {
                if (dialog.isShowing) dialog.dismiss()
                onReject.run()
            }
        }
        val content = ComposeView(activity).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                ConfirmationDialog(
                    origin = origin,
                    onAllow = allow::run,
                    onReject = reject::run,
                )
            }
        }

        dialog.setOnCancelListener { reject.run() }
        dialog.setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.setOnDismissListener { onDismiss.run() }
        return dialog
    }

    @Composable
    private fun ConfirmationDialog(
        origin: String,
        onAllow: () -> Unit,
        onReject: () -> Unit,
    ) {
        var accepted by remember { mutableStateOf(false) }
        MaterialTheme(colorScheme = colors, typography = bridgeTypography()) {
            AlertDialog(
                onDismissRequest = {
                    if (!accepted) onReject()
                },
                modifier = Modifier.widthIn(max = 420.dp),
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                icon = {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                },
                title = {
                    Text(
                        "允许这次登录？",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "小天才已准备好，确认后将打开官方微信完成授权。",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PermissionLine(
                                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    label = "请求来源",
                                    value = origin,
                                )
                                PermissionLine(
                                    icon = Icons.Rounded.Lock,
                                    label = "授权范围",
                                    value = "仅为这次网页登录读取一次账号会话",
                                )
                            }
                        }
                        Text(
                            "只有点击“允许并登录”后才会调用微信登录。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (!accepted) {
                                accepted = true
                                onAllow()
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("允许并登录")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onReject,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("取消")
                    }
                },
            )
        }
    }

    @Composable
    private fun PermissionLine(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        value: String,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun bridgeTypography(): Typography {
        val family = FontFamily(Font(R.font.cat_book))
        return Typography(
            headlineSmall = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 25.sp,
                lineHeight = 32.sp,
            ),
            bodyLarge = TextStyle(
                fontFamily = family,
                fontSize = 17.sp,
                lineHeight = 26.sp,
            ),
            bodyMedium = TextStyle(
                fontFamily = family,
                fontSize = 15.sp,
                lineHeight = 23.sp,
            ),
            labelLarge = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            labelMedium = TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
        )
    }
}
