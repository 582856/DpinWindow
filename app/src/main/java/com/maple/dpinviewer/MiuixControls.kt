package com.maple.dpinviewer

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.function.IntConsumer

class MiuixControls(context: Context, onToggle: Runnable, onReconnect: Runnable,
    onBackgroundColor: IntConsumer) : FrameLayout(context) {
    private var statusText by mutableStateOf("正在连接米联…")
    private var signalText by mutableStateOf("输入参数：等待 DP 信号")
    private var powerText by mutableStateOf("正在采样")
    private var statusError by mutableStateOf(false)
    private var previewDesired by mutableStateOf(false)
    private var previewEnabled by mutableStateOf(false)

    init {
        val compose = ComposeView(context)
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val background = MiuixTheme.colorScheme.background
                SideEffect { onBackgroundColor.accept(background.toArgb()) }
                Column(
                    modifier = Modifier.fillMaxWidth().background(background)
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("DP-in窗口显示", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Card(modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(statusText, fontSize = 15.sp,
                                    color = if (statusError) Color(0xFFD34848) else MiuixTheme.colorScheme.onSurfaceContainer)
                                Text("$signalText | 功率：$powerText", fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.65f))
                            }
                            Button(onClick = { onToggle.run() }, modifier = Modifier.widthIn(min = 120.dp),
                                enabled = previewEnabled, colors = ButtonDefaults.buttonColorsPrimary()) {
                                Text(if (previewDesired) "停止预览" else "开始预览")
                            }
                            Button(onClick = { onReconnect.run() }, modifier = Modifier.widthIn(min = 88.dp)) {
                                Text("重连")
                            }
                        }
                    }
                }
            }
        }
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setStatus(text: String?, error: Boolean) {
        statusText = text.orEmpty()
        statusError = error
    }

    fun setSignal(text: String) { signalText = text }

    fun setPower(text: String) { powerText = text }

    fun setPreview(desired: Boolean, enabled: Boolean) {
        previewDesired = desired
        previewEnabled = enabled
    }
}
