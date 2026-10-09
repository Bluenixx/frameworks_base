package com.android.systemui.bluenixx.qs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.res.R
import kotlin.math.roundToInt

private const val KEY_MEDIA_COMPACT = "bluenixx_qs_media_size" // 0 = normal, 1 = compacto

@Composable
private fun rememberMediaCompact(): State<Boolean> {
    val cr = LocalContext.current.contentResolver
    val state = remember { mutableStateOf(Settings.Secure.getInt(cr, KEY_MEDIA_COMPACT, 0) == 1) }
    DisposableEffect(cr) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = Settings.Secure.getInt(cr, KEY_MEDIA_COMPACT, 0) == 1
            }
        }
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_MEDIA_COMPACT), false, obs)
        onDispose { cr.unregisterContentObserver(obs) }
    }
    return state
}

@Composable
fun bluenixxMediaStyle(): MediaPresentationStyle =
    if (rememberMediaCompact().value) MediaPresentationStyle.Compressed
    else MediaPresentationStyle.Default

@Composable
fun BluenixxVolumeSlider(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val stream = AudioManager.STREAM_MUSIC
    val max = remember { am.getStreamMaxVolume(stream).coerceAtLeast(1) }
    var vol by remember { mutableStateOf(am.getStreamVolume(stream).toFloat()) }
    val compact by rememberMediaCompact()

    DisposableEffect(context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                vol = am.getStreamVolume(stream).toFloat()
            }
        }
        context.registerReceiver(
            r, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(r) }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(android.R.drawable.ic_lock_silent_mode_off),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
            Slider(
                value = vol,
                onValueChange = {
                    vol = it
                    am.setStreamVolume(stream, it.roundToInt(), 0)
                },
                valueRange = 0f..max.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(
                    if (compact) R.drawable.bluenixx_ic_media_expand
                    else R.drawable.bluenixx_ic_media_compact),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clickable {
                        Settings.Secure.putInt(
                            context.contentResolver, KEY_MEDIA_COMPACT, if (compact) 0 else 1)
                    }
                    .padding(8.dp),
            )
        }
    }
}
