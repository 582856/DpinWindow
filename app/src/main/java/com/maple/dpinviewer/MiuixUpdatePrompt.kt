package com.maple.dpinviewer

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MiuixUpdatePrompt(context: Context) : FrameLayout(context) {
    private var shown by mutableStateOf(false)
    private var version by mutableStateOf("")
    private var notes by mutableStateOf("")
    private var update: Runnable? = null

    init {
        val compose = ComposeView(context)
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                if (shown) {
                    Dialog(onDismissRequest = { dismiss() },
                        properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Card(modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(0.9f),
                            insideMargin = PaddingValues(24.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("发现新版本 $version", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text(notes, modifier = Modifier.heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()), fontSize = 15.sp)
                                Row(modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { dismiss() }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(color = Color.White,
                                            contentColor = Color(0xFF202020))) { Text("取消", color = Color(0xFF202020)) }
                                    Button(onClick = {
                                        val action = update
                                        dismiss()
                                        action?.run()
                                    }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColorsPrimary(color = Color(0xFF3482FF),
                                            contentColor = Color.White)) { Text("更新", color = Color.White) }
                                }
                            }
                        }
                    }
                }
            }
        }
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun showUpdate(version: String, notes: String, onUpdate: Runnable) {
        this.version = version
        this.notes = notes
        update = onUpdate
        shown = true
    }

    fun dismiss() { shown = false; update = null }
}
