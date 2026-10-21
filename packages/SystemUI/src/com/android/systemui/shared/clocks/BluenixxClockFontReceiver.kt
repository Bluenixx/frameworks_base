package com.android.systemui.shared.clocks

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BluenixxClockFontReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val uri = intent.data ?: return
        val pending = goAsync()

        Thread {
            var temp: File? = null
            var target: File? = null
            var committed = false

            try {
                val id = UUID.randomUUID().toString()
                temp = File(context.filesDir, "bluenixx_clock_font_$id.tmp")
                target = File(context.filesDir, "bluenixx_clock_font_$id.ttf")

                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Fonte indisponível" }

                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L

                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break

                            total += count
                            require(total <= MAX_FONT_SIZE) {
                                "Fonte excede 20 MB"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }

                Typeface.createFromFile(temp)

                val userId = ActivityManager.getCurrentUser()
                val current = Settings.Secure.getStringForUser(
                    context.contentResolver,
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                    userId
                )

                val root = if (current.isNullOrBlank()) {
                    JSONObject().put("clockId", "DEFAULT")
                } else {
                    JSONObject(current)
                }

                val metadata = root.optJSONObject("metadata") ?: JSONObject()
                metadata.put("bluenixx_clock_font", target.absolutePath)
                root.put("metadata", metadata)

                check(temp.renameTo(target)) {
                    "Não foi possível instalar o arquivo da fonte"
                }

                check(
                    Settings.Secure.putStringForUser(
                        context.contentResolver,
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                        root.toString(),
                        userId
                    )
                ) {
                    "Falha ao salvar configuração"
                }

                committed = true
            } catch (e: Exception) {
                Log.e("BluenixxClockFont", "Falha ao importar fonte", e)
            } finally {
                if (!committed) {
                    temp?.delete()
                    target?.delete()
                }
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION =
            "com.android.systemui.action.SET_BLUENIXX_CLOCK_FONT"

        private const val MAX_FONT_SIZE = 20L * 1024 * 1024
    }
}
