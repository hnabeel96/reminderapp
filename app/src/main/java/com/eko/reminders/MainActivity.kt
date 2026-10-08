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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
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

// ---------------------------------------------------------------- setup health

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

// ---------------------------------------------------------------- app shell

private enum class Tab { BOARD, REMINDERS }

private fun nextHourMillis(): Long =
    LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun blankItem(withTime: Boolean) = Item(
    id = -1,
    title = "",
    hasTime = withTime,
    timeMillis = nextHourMillis(),
    createdAt = System.currentTimeMillis(),
)

@Composable
fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(Store.all(ctx)) }
    var stats by remember { mutableStateOf(Store.stats(ctx)) }
    var tab by remember { mutableStateOf(Tab.BOARD) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var isNew by remember { mutableStateOf(false) }

    // Refresh whenever storage changes (including from notification buttons) or the app resumes.
    DisposableEffect(Unit) {
        val prefs = Store.prefs(ctx)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            items = Store.all(ctx)
            stats = Store.stats(ctx)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(resumeTick) {
        items = Store.all(ctx)
        stats = Store.stats(ctx)
    }
    val issues = remember(resumeTick, items) { healthIssues(ctx) }

    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        Editor(current, isNew) { editing = null }
        return
    }

    val onEdit: (Item) -> Unit = { isNew = false; editing = it }

    Scaffold(
        containerColor = Palette.Bg,
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.Bg,
                    indicatorColor = Palette.Gold,
                    selectedTextColor = Palette.Gold,
                    unselectedIconColor = Palette.Muted,
                    unselectedTextColor = Palette.Muted,
                )
                NavigationBarItem(
                    selected = tab == Tab.BOARD,
                    onClick = { tab = Tab.BOARD },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("Board") },
                    colors = colors,
                )
                NavigationBarItem(
                    selected = tab == Tab.REMINDERS,
                    onClick = { tab = Tab.REMINDERS },
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    label = { Text("Reminders") },
                    colors = colors,
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { isNew = true; editing = blankItem(withTime = tab == Tab.REMINDERS) },
                containerColor = Palette.Gold,
                contentColor = Palette.Bg,
            ) { Icon(Icons.Filled.Add, contentDescription = "Add") }
        },
    ) { pad ->
        when (tab) {
            Tab.BOARD -> BoardScreen(items, stats, issues, pad, onEdit)
            Tab.REMINDERS -> RemindersScreen(items, issues, pad, onEdit)
        }
    }
}

// ---------------------------------------------------------------- board (to-do)

@Composable
private fun BoardScreen(
    items: List<Item>,
    stats: Stats,
    issues: List<Fix>,
    pad: PaddingValues,
    onEdit: (Item) -> Unit,
) {
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val overdue = mutableListOf<Item>()
    val todayList = mutableListOf<Item>()
    val anytime = mutableListOf<Item>()
    val upcoming = mutableListOf<Item>()
    val doneToday = mutableListOf<Item>()

    for (it in items) {
        when {
            it.recurring -> when {
                it.awaitingAck -> overdue += it
                Dates.isToday(it.doneAt) -> doneToday += it
                it.enabled && Dates.date(it.timeMillis) == today -> todayList += it
                // Other recurring occurrences live on the Reminders tab.
            }
            it.done -> if (Dates.isToday(it.doneAt)) doneToday += it
            !it.hasTime -> anytime += it
            it.awaitingAck || it.timeMillis <= now -> overdue += it
            Dates.date(it.timeMillis) == today -> todayList += it
            else -> upcoming += it
        }
    }
    anytime.sortWith(compareByDescending<Item> { it.priority.ordinal }.thenBy { it.createdAt })
    overdue.sortWith(compareByDescending<Item> { it.priority.ordinal }.thenBy { it.timeMillis })
    todayList.sortBy { it.timeMillis }
    upcoming.sortBy { it.timeMillis }

    val load = overdue.size + todayList.size + doneToday.size

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { Header(stats, doneToday.size, load) }
        items(issues, key = { "fix_" + it.text }) { IssueCard(it) }
        item(key = "quickadd") { QuickAdd() }
        section("Your move", overdue, Palette.Red, onEdit)
        section("Today", todayList, Palette.Gold, onEdit)
        section("Anytime", anytime, Palette.Ivory, onEdit)
        section("Upcoming", upcoming, Palette.Muted, onEdit)
        section("Done today", doneToday, Palette.Green, onEdit)
        if (overdue.isEmpty() && todayList.isEmpty() && anytime.isEmpty() && upcoming.isEmpty() && doneToday.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Empty board. Type a task above, or tap + for one with a reminder.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = Palette.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun LazyListScope.section(title: String, list: List<Item>, accent: Color, onEdit: (Item) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h_$title") {
        Row(Modifier.padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                color = accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text("${list.size}", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
    items(list, key = { "${title}_${it.id}" }) { TaskRow(it, onEdit) }
}

private fun motto(done: Int, load: Int, streak: Int): String = when {
    load == 0 && streak > 0 -> "Clear board. Protect the $streak-day streak."
    load == 0 -> "Clear board. Plan your next move."
    done == load -> "All moves made today. Well played."
    done == 0 -> "One move at a time."
    else -> "${load - done} to go. Keep the initiative."
}

@Composable
private fun Header(stats: Stats, done: Int, load: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
    ) {
        Column {
            CheckerStrip()
            Column(Modifier.padding(16.dp)) {
                Text("Today's board", style = MaterialTheme.typography.headlineSmall, color = Palette.Ivory)
                Text(
                    LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Rating", stats.rating.toString())
                    Stat("Streak", "${stats.streak}d")
                    Stat("Moves", "$done/$load")
                }
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { if (load == 0) 0f else done.toFloat() / load },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Palette.Gold,
                    trackColor = Palette.Surface2,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    motto(done, load, stats.streak),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = Palette.Muted,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Palette.Gold,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.2.sp,
            color = Palette.Muted,
        )
    }
}

@Composable
private fun IssueCard(fix: Fix) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                fix.text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = { fix.action(ctx) }) { Text("Fix") }
        }
    }
}

@Composable
private fun QuickAdd() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var piece by remember { mutableStateOf(Piece.PAWN) }

    fun add() {
        val t = text.trim()
        if (t.isEmpty()) return
        Store.upsert(
            ctx,
            Item(
                id = Store.newId(ctx),
                title = t,
                priority = piece,
                hasTime = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
        text = ""
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Add a task…", color = Palette.Muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        leadingIcon = {
            TextButton(onClick = { piece = Piece.entries[(piece.ordinal + 1) % Piece.entries.size] }) {
                Text(piece.glyph, fontSize = 22.sp, color = pieceColor(piece))
            }
        },
        trailingIcon = {
            IconButton(onClick = { add() }) {
                Icon(Icons.Filled.Add, contentDescription = "Add task", tint = Palette.Gold)
            }
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.Sentences,
        ),
        keyboardActions = KeyboardActions(onDone = { add() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Gold,
            unfocusedBorderColor = Palette.Outline,
            cursorColor = Palette.Gold,
            focusedContainerColor = Palette.Surface,
            unfocusedContainerColor = Palette.Surface,
        ),
    )
}

@Composable
private fun TaskRow(item: Item, onEdit: (Item) -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDone = item.doneNow
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onEdit(item) },
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isDone,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (checked) {
                        val pts = Actions.complete(ctx, item.id)
                        if (pts > 0) Toast.makeText(ctx, "Move made · +$pts rating", Toast.LENGTH_SHORT).show()
                    } else {
                        Actions.uncomplete(ctx, item.id)
                    }
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = Palette.Gold,
                    uncheckedColor = Palette.Muted,
                    checkmarkColor = Palette.Bg,
                ),
            )
            Text(
                item.priority.glyph,
                fontSize = 22.sp,
                color = if (isDone) Palette.Outline else pieceColor(item.priority),
                modifier = Modifier.width(30.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isDone) Palette.Muted else Palette.Ivory,
                    textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                )
                if (item.hasTime) {
                    Text(
                        item.detail(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Palette.Muted,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- reminders tab

@Composable
private fun RemindersScreen(
    items: List<Item>,
    issues: List<Fix>,
    pad: PaddingValues,
    onEdit: (Item) -> Unit,
) {
    val timed = items.filter { it.hasTime && !it.done }.sortedBy { it.timeMillis }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            Column {
                CheckerStrip(height = 8.dp, squares = 32)
                Spacer(Modifier.height(12.dp))
                Text("Scheduled", style = MaterialTheme.typography.headlineSmall, color = Palette.Ivory)
                Text(
                    "Everything with a time. Switch one off to mute it without deleting it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        items(issues, key = { "fix_" + it.text }) { IssueCard(it) }
        if (timed.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No reminders yet. Tap + to set one.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = Palette.Muted,
                )
            }
        }
        items(timed, key = { "r_${it.id}" }) { ReminderRow(it, onEdit) }
    }
}

@Composable
private fun ReminderRow(r: Item, onEdit: (Item) -> Unit) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onEdit(r) },
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.priority.glyph,
                fontSize = 22.sp,
                color = pieceColor(r.priority),
                modifier = Modifier.width(32.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    Dates.time(r.timeMillis),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    color = if (r.enabled) Palette.Gold else Palette.Muted,
                )
                Text(r.title, style = MaterialTheme.typography.titleMedium, color = Palette.Ivory)
                Text(
                    r.detail(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                if (r.awaitingAck) {
                    TextButton(
                        onClick = { Actions.complete(ctx, r.id) },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("Due · mark done", color = Palette.Red) }
                }
            }
            Switch(
                checked = r.enabled,
                onCheckedChange = { on ->
                    val updated = r.copy(enabled = on, awaitingAck = false)
                    if (on && !updated.recurring && updated.nextAfter(System.currentTimeMillis()) == null) {
                        Toast.makeText(ctx, "That time has passed. Tap to edit it.", Toast.LENGTH_SHORT).show()
                        return@Switch
                    }
                    Notifier.cancel(ctx, r.id)
                    Scheduler.cancelAll(ctx, r.id)
                    Store.upsert(ctx, updated)
                    Scheduler.schedule(ctx, updated)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.Bg,
                    checkedTrackColor = Palette.Gold,
                    uncheckedThumbColor = Palette.Muted,
                    uncheckedTrackColor = Palette.Surface2,
                    uncheckedBorderColor = Palette.Outline,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- editor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Editor(initial: Item, isNew: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(initial.title) }
    var piece by remember { mutableStateOf(initial.priority) }
    var hasTime by remember { mutableStateOf(initial.hasTime) }
    var dt by remember {
        val base = if (initial.timeMillis > 0) initial.timeMillis else nextHourMillis()
        mutableStateOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(base), zone))
    }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var nText by remember { mutableStateOf(initial.everyNDays.toString()) }
    var alarmStyle by remember { mutableStateOf(initial.alarmStyle) }
    var nag by remember { mutableIntStateOf(initial.nagMinutes) }

    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = Palette.Bg,
        checkedTrackColor = Palette.Gold,
        uncheckedThumbColor = Palette.Muted,
        uncheckedTrackColor = Palette.Surface2,
        uncheckedBorderColor = Palette.Outline,
    )

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New move" else "Edit move") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Palette.Bg,
                    titleContentColor = Palette.Ivory,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What's the move?") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            Text("Priority", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Piece.entries.forEach { p ->
                    FilterChip(
                        selected = piece == p,
                        onClick = { piece = p },
                        label = { Text("${p.glyph}  ${p.label}  +${p.points}") },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remind me", style = MaterialTheme.typography.bodyLarge, color = Palette.Ivory)
                    Text(
                        "Off = a plain to-do on the board",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }
                Switch(checked = hasTime, onCheckedChange = { hasTime = it }, colors = switchColors)
            }

            if (hasTime) {
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
                    }) { Text(dt.format(DateTimeFormatter.ofPattern("h:mm a")), fontFamily = FontFamily.Monospace) }
                }

                Text("Repeat", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Repeat.entries.forEach { opt ->
                        FilterChip(selected = repeat == opt, onClick = { repeat = opt }, label = { Text(opt.label) })
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
                        Text("Ring like an alarm", style = MaterialTheme.typography.bodyLarge, color = Palette.Ivory)
                        Text(
                            "Loud, loops, opens full screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                    }
                    Switch(checked = alarmStyle, onCheckedChange = { alarmStyle = it }, colors = switchColors)
                }

                Text("Nag until done", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 10, 15, 30, 60).forEach { m ->
                        FilterChip(
                            selected = nag == m,
                            onClick = { nag = m },
                            label = { Text(if (m == 0) "Off" else "Every ${m}m") },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val now = System.currentTimeMillis()
                    val millis = dt.atZone(zone).toInstant().toEpochMilli()
                    val n = nText.toIntOrNull()?.coerceIn(1, 365) ?: 2
                    when {
                        title.isBlank() ->
                            Toast.makeText(ctx, "Name the move first", Toast.LENGTH_SHORT).show()
                        hasTime && repeat == Repeat.ONCE && millis <= now && !initial.done ->
                            Toast.makeText(ctx, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                        else -> {
                            val id = if (isNew) Store.newId(ctx) else initial.id
                            val saved = initial.copy(
                                id = id,
                                title = title.trim(),
                                priority = piece,
                                hasTime = hasTime,
                                timeMillis = if (hasTime) millis else 0L,
                                repeat = if (hasTime) repeat else Repeat.ONCE,
                                everyNDays = n,
                                alarmStyle = hasTime && alarmStyle,
                                nagMinutes = if (hasTime) nag else 0,
                                enabled = true,
                                awaitingAck = false,
                                createdAt = if (initial.createdAt > 0) initial.createdAt else now,
                            )
                            Notifier.cancel(ctx, id)
                            Scheduler.cancelAll(ctx, id)
                            Store.upsert(ctx, saved)
                            Scheduler.schedule(ctx, saved)
                            if (saved.armed) {
                                Store.get(ctx, id)?.let {
                                    Toast.makeText(ctx, "Reminder: ${Dates.formatWhen(it.timeMillis)}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            close()
                        }
                    }
                }) { Text("Save") }
                OutlinedButton(onClick = close) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                if (!isNew) {
                    TextButton(onClick = {
                        Notifier.cancel(ctx, initial.id)
                        Scheduler.cancelAll(ctx, initial.id)
                        Store.delete(ctx, initial.id)
                        close()
                    }) { Text("Delete", color = Palette.Red) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
