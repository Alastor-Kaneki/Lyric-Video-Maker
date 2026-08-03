package com.alastorkaneki.lyricvideomaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.alastorkaneki.lyricvideomaker.ui.LyricVideoMakerScreen
import com.alastorkaneki.lyricvideomaker.ui.theme.LyricVideoMakerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var amoled by rememberSaveable { mutableStateOf(true) }
            LyricVideoMakerTheme(amoled = amoled) {
                LyricVideoMakerScreen(amoled = amoled, onAmoledChanged = { amoled = it })
            }
        }
    }
}
