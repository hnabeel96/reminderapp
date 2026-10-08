package com.eko.reminders

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            resumeTick.intValue++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        Scheduler.rescheduleAll(this, includeNag = false)
        if (!Notifier.canPost(this) && Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppTheme { App(resumeTick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class Fix(val text: String, val action: (Context) -> Unit)

private fun openSafely(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

private fun healthIssues(ctx: Context): List<Fix> {
    val out = mutableListOf<Fix>()
    val pkg = ctx.packageName
    if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
        out += Fix("Notifications are off, so reminders can't show.") {
            openSafely(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
        }
    }
    if (!Scheduler.canExact(ctx) && Build.VERSION.SDK_INT >= 31) {
        out += Fix("Exact alarms aren't allowed, so reminders may be late.") {
            openSafely(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
        }
    }
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(pkg)) {
        out += Fix("Battery optimization may kill reminders. Allow this app to run unrestricted.") {
            openSafely(it, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
        }
    }
    if (!Notifier.canFullScreen(ctx) && Build.VERSION.SDK_INT >= 34) {
        out += Fix("Full-screen alarms aren't allowed (needed for alarm-style reminders).") {
            openSafely(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(Store.all(ctx)) }
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var isNew by remember { mutableStateOf(false) }

    // Refresh when a notification button (Done/Snooze) changes data in the background.
    DisposableEffect(Unit) {
        val prefs = Store.prefs(ctx)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            items = Store.all(ctx)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val issues = remember(resumeTick, items) { healthIssues(ctx) }

    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        Editor(current, isNew) { editing = null }
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Reminders") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val start = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                isNew = true
                editing = Reminder(
                    id = -1,
                    title = "",
                    timeMillis = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
            }) { Icon(Icons.Filled.Add, contentDescription = "Add reminder") }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(issues) { fix ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fix.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { fix.action(ctx) }) { Text("Fix") }
                    }
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        "No reminders yet. Tap + to add one.",
                        modifier = Modifier.padding(top = 32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(items, key = { it.id }) { r ->
                ReminderRow(r, onClick = { isNew = false; editing = r })
            }
        }
    }
}

@Composable
private fun ReminderRow(r: Reminder, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (r.enabled || r.awaitingAck) r.summary() else "Off · ${r.summary()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.awaitingAck) {
                    TextButton(
                        onClick = { runCatching { Scheduler.doneIntent(ctx, r.id).send() } },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("Due · mark done") }
                }
            }
            Switch(
                checked = r.enabled,
                onCheckedChange = { on ->
                    val updated = r.copy(enabled = on, awaitingAck = false)
                    if (on && updated.nextAfter(System.currentTimeMillis()) == null) {
                        Toast.makeText(ctx, "That time has passed. Tap to edit it.", Toast.LENGTH_SHORT).show()
                        return@Switch
                    }
                    Notifier.cancel(ctx, r.id)
                    Scheduler.cancelAll(ctx, r.id)
                    Store.upsert(ctx, updated)
                    Scheduler.schedule(ctx, updated)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Editor(initial: Reminder, isNew: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(initial.title) }
    var dt by remember {
        mutableStateOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(initial.timeMillis), zone))
    }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var nText by remember { mutableStateOf(initial.everyNDays.toString()) }
    var alarmStyle by remember { mutableStateOf(initial.alarmStyle) }
    var nag by remember { mutableIntStateOf(initial.nagMinutes) }

    Scaffold(topBar = { TopAppBar(title = { Text(if (isNew) "New reminder" else "Edit reminder") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What to remember") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    DatePickerDialog(ctx, { _, y, m, d ->
                        dt = dt.withYear(y).withMonth(m + 1).withDayOfMonth(d)
                    }, dt.year, dt.monthValue - 1, dt.dayOfMonth).show()
                }) { Text(dt.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))) }
                OutlinedButton(onClick = {
                    TimePickerDialog(ctx, { _, h, min ->
                        dt = dt.withHour(h).withMinute(min).withSecond(0).withNano(0)
                    }, dt.hour, dt.minute, DateFormat.is24HourFormat(ctx)).show()
                }) { Text(dt.format(DateTimeFormatter.ofPattern("h:mm a"))) }
            }

            Text("Repeat", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Repeat.entries.forEach { opt ->
                    FilterChip(
                        selected = repeat == opt,
                        onClick = { repeat = opt },
                        label = { Text(opt.label) },
                    )
                }
            }
            if (repeat == Repeat.EVERY_N_DAYS) {
                OutlinedTextField(
                    value = nText,
                    onValueChange = { nText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Every how many days?") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ring like an alarm", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Loud, loops, opens full screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = alarmStyle, onCheckedChange = { alarmStyle = it })
            }

            Text("Nag until done", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 15, 30, 60).forEach { m ->
                    FilterChip(
                        selected = nag == m,
                        onClick = { nag = m },
                        label = { Text(if (m == 0) "Off" else "Every ${m}m") },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val millis = dt.atZone(zone).toInstant().toEpochMilli()
                    val n = nText.toIntOrNull()?.coerceIn(1, 365) ?: 2
                    when {
                        title.isBlank() ->
                            Toast.makeText(ctx, "Give it a name", Toast.LENGTH_SHORT).show()
                        repeat == Repeat.ONCE && millis <= System.currentTimeMillis() ->
                            Toast.makeText(ctx, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                        else -> {
                            val id = if (isNew) Store.newId(ctx) else initial.id
                            val r = Reminder(
                                id = id,
                                title = title.trim(),
                                timeMillis = millis,
                                repeat = repeat,
                                everyNDays = n,
                                alarmStyle = alarmStyle,
                                nagMinutes = nag,
                                enabled = true,
                                awaitingAck = false,
                            )
                            Notifier.cancel(ctx, id)
                            Scheduler.cancelAll(ctx, id)
                            Store.upsert(ctx, r)
                            Scheduler.schedule(ctx, r)
                            Store.get(ctx, id)?.let {
                                Toast.makeText(ctx, "Next: ${Reminder.formatTime(it.timeMillis)}", Toast.LENGTH_SHORT).show()
                            }
                            close()
                        }
                    }
                }) { Text("Save") }
                OutlinedButton(onClick = close) { Text("Cancel") }
                Spacer(Modifier.width(1.dp).weight(1f))
                if (!isNew) {
                    TextButton(onClick = {
                        Notifier.cancel(ctx, initial.id)
                        Scheduler.cancelAll(ctx, initial.id)
                        Store.delete(ctx, initial.id)
                        close()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
