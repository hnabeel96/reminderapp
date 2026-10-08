package com.eko.reminders

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Full-screen ringing view for alarm-style reminders (shows over the lock screen). */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val title = Store.get(this, id)?.title ?: "Reminder"
        val now = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))

        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(now, style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(48.dp))
                        Button(
                            onClick = {
                                runCatching { Scheduler.doneIntent(this@AlarmActivity, id).send() }
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text("Done") }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching { Scheduler.snoozeIntent(this@AlarmActivity, id).send() }
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text("Snooze ${Scheduler.SNOOZE_MINUTES} min") }
                    }
                }
            }
        }
    }
}
