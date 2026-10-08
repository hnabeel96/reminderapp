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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

private enum class Tab { TASKS, REMINDERS }

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
    var tab by remember { mutableStateOf(Tab.TASKS) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var isNew by remember { mutableStateOf(false) }

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

    Box(Modifier.fillMaxSize()) {
        Starfield()

        val current = editing
        if (current != null) {
            BackHandler { editing = null }
            Editor(current, isNew) { editing = null }
        } else {
            val onEdit: (Item) -> Unit = { isNew = false; editing = it }
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(containerColor = Cosmos.Deep.copy(alpha = 0.85f)) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cosmos.Deep,
                            indicatorColor = Cosmos.Cyan,
                            selectedTextColor = Cosmos.Cyan,
                            unselectedIconColor = Cosmos.Muted,
                            unselectedTextColor = Cosmos.Muted,
                        )
                        NavigationBarItem(
                            selected = tab == Tab.TASKS,
                            onClick = { tab = Tab.TASKS },
                            icon = { Icon(Icons.Filled.List, contentDescription = null) },
                            label = { Text("Tasks") },
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
                        containerColor = Cosmos.Cyan,
                        contentColor = Cosmos.Deep,
                        shape = RoundedCornerShape(50),
                    ) { Icon(Icons.Filled.Add, contentDescription = "Add") }
                },
            ) { pad ->
                when (tab) {
                    Tab.TASKS -> TasksScreen(items, stats, issues, pad, onEdit)
                    Tab.REMINDERS -> RemindersScreen(items, issues, pad, onEdit)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tasks (to-do)

@Composable
private fun TasksScreen(
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
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { Header(stats, doneToday.size, load) }
        items(issues, key = { "fix_" + it.text }) { IssueCard(it) }
        item(key = "quickadd") { QuickAdd() }
        section("Overdue", overdue, Cosmos.Pink, onEdit)
        section("Today", todayList, Cosmos.Cyan, onEdit)
        section("Anytime", anytime, Cosmos.Violet, onEdit)
        section("Upcoming", upcoming, Cosmos.Muted, onEdit)
        section("Done today", doneToday, Cosmos.Mint, onEdit)
        if (overdue.isEmpty() && todayList.isEmpty() && anytime.isEmpty() && upcoming.isEmpty() && doneToday.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing here yet. Type a task above, or tap + for one with a reminder.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = Cosmos.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun LazyListScope.section(title: String, list: List<Item>, accent: Color, onEdit: (Item) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h_$title") {
        Row(Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                color = accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text("${list.size}", color = Cosmos.Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
    items(list, key = { "${title}_${it.id}" }) { TaskRow(it, onEdit) }
}

@Composable
private fun Header(stats: Stats, done: Int, load: Int) {
    Glass(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE")),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Cosmos.Text,
                )
                Text(
                    LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cosmos.Muted,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    when {
                        load == 0 -> "A clear sky today."
                        done == load -> "Everything done. Nice orbit."
                        else -> "${load - done} left for today"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cosmos.Text,
                )
                if (stats.streak > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${stats.streak}-day streak",
                        style = MaterialTheme.typography.labelMedium,
                        color = Cosmos.Violet,
                    )
                }
            }
            OrbitRing(fraction = if (load == 0) 0f else done.toFloat() / load, ringSize = 104.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$done/$load",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Light,
                        color = Cosmos.Text,
                    )
                    Text("done", style = MaterialTheme.typography.labelSmall, color = Cosmos.Muted)
                }
            }
        }
    }
}

@Composable
private fun IssueCard(fix: Fix) {
    val ctx = LocalContext.current
    Glass(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                fix.text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Cosmos.Pink,
            )
            TextButton(onClick = { fix.action(ctx) }) { Text("Fix", color = Cosmos.Cyan) }
        }
    }
}

@Composable
private fun QuickAdd() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.LOW) }

    fun add() {
        val t = text.trim()
        if (t.isEmpty()) return
        Store.upsert(
            ctx,
            Item(
                id = Store.newId(ctx),
                title = t,
                priority = priority,
                hasTime = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
        text = ""
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Add a task…", color = Cosmos.Muted) },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        leadingIcon = {
            IconButton(onClick = { priority = Priority.entries[(priority.ordinal + 1) % Priority.entries.size] }) {
                PriorityStar(priority, starSize = 20.dp)
            }
        },
        trailingIcon = {
            IconButton(onClick = { add() }) {
                Icon(Icons.Filled.Add, contentDescription = "Add task", tint = Cosmos.Cyan)
            }
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.Sentences,
        ),
        keyboardActions = KeyboardActions(onDone = { add() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cosmos.Cyan,
            unfocusedBorderColor = Cosmos.GlassEdge,
            cursorColor = Cosmos.Cyan,
            focusedContainerColor = Cosmos.Glass,
            unfocusedContainerColor = Cosmos.Glass,
        ),
    )
}

@Composable
private fun TaskRow(item: Item, onEdit: (Item) -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDone = item.doneNow
    Glass(Modifier.fillMaxWidth(), onClick = { onEdit(item) }) {
        Row(
            Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isDone,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (checked) Actions.complete(ctx, item.id) else Actions.uncomplete(ctx, item.id)
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = Cosmos.Mint,
                    uncheckedColor = Cosmos.Muted,
                    checkmarkColor = Cosmos.Deep,
                ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isDone) Cosmos.Muted else Cosmos.Text,
                    textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                )
                if (item.hasTime) {
                    Text(
                        item.detail(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.awaitingAck && !isDone) Cosmos.Pink else Cosmos.Muted,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            PriorityStar(item.priority, dimmed = isDone)
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
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                Text("Reminders", style = MaterialTheme.typography.headlineMedium, color = Cosmos.Text)
                Text(
                    "Everything with a time. Switch one off to mute it without deleting it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.Muted,
                )
            }
        }
        items(issues, key = { "fix_" + it.text }) { IssueCard(it) }
        if (timed.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No reminders yet. Tap + to set one.",
                    modifier = Modifier.padding(top = 24.dp, start = 4.dp),
                    color = Cosmos.Muted,
                )
            }
        }
        items(timed, key = { "r_${it.id}" }) { ReminderRow(it, onEdit) }
    }
}

@Composable
private fun ReminderRow(r: Item, onEdit: (Item) -> Unit) {
    val ctx = LocalContext.current
    Glass(Modifier.fillMaxWidth(), onClick = { onEdit(r) }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    Dates.time(r.timeMillis),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (r.enabled) Cosmos.Cyan else Cosmos.Muted,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityStar(r.priority, starSize = 12.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(r.title, style = MaterialTheme.typography.bodyLarge, color = Cosmos.Text)
                }
                Text(r.detail(), style = MaterialTheme.typography.bodySmall, color = Cosmos.Muted)
                if (r.awaitingAck) {
                    TextButton(
                        onClick = { Actions.complete(ctx, r.id) },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("Due · mark done", color = Cosmos.Pink) }
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
                colors = cosmicSwitch(),
            )
        }
    }
}

@Composable
private fun cosmicSwitch() = SwitchDefaults.colors(
    checkedThumbColor = Cosmos.Deep,
    checkedTrackColor = Cosmos.Cyan,
    uncheckedThumbColor = Cosmos.Muted,
    uncheckedTrackColor = Cosmos.Panel,
    uncheckedBorderColor = Cosmos.Outline,
)

@Composable
private fun cosmicChip() = FilterChipDefaults.filterChipColors(
    containerColor = Cosmos.Glass,
    labelColor = Cosmos.Muted,
    selectedContainerColor = Cosmos.Violet.copy(alpha = 0.25f),
    selectedLabelColor = Cosmos.Text,
)

// ---------------------------------------------------------------- editor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Editor(initial: Item, isNew: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(initial.title) }
    var priority by remember { mutableStateOf(initial.priority) }
    var hasTime by remember { mutableStateOf(initial.hasTime) }
    var dt by remember {
        val base = if (initial.timeMillis > 0) initial.timeMillis else nextHourMillis()
        mutableStateOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(base), zone))
    }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var nText by remember { mutableStateOf(initial.everyNDays.toString()) }
    var alarmStyle by remember { mutableStateOf(initial.alarmStyle) }
    var nag by remember { mutableIntStateOf(initial.nagMinutes) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New task" else "Edit task", fontWeight = FontWeight.Light) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Cosmos.Text,
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
                label = { Text("What needs doing?") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            Text("Priority", style = MaterialTheme.typography.labelLarge, color = Cosmos.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p ->
                    FilterChip(
                        selected = priority == p,
                        onClick = { priority = p },
                        leadingIcon = { PriorityStar(p, starSize = 14.dp) },
                        label = { Text(p.label) },
                        colors = cosmicChip(),
                    )
                }
            }

            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remind me", style = MaterialTheme.typography.bodyLarge, color = Cosmos.Text)
                            Text(
                                "Off = a plain to-do",
                                style = MaterialTheme.typography.bodySmall,
                                color = Cosmos.Muted,
                            )
                        }
                        Switch(checked = hasTime, onCheckedChange = { hasTime = it }, colors = cosmicSwitch())
                    }

                    if (hasTime) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = {
                                DatePickerDialog(ctx, { _, y, m, d ->
                                    dt = dt.withYear(y).withMonth(m + 1).withDayOfMonth(d)
                                }, dt.year, dt.monthValue - 1, dt.dayOfMonth).show()
                            }) { Text(dt.format(DateTimeFormatter.ofPattern("EEE d MMM")), color = Cosmos.Text) }
                            OutlinedButton(onClick = {
                                TimePickerDialog(ctx, { _, h, min ->
                                    dt = dt.withHour(h).withMinute(min).withSecond(0).withNano(0)
                                }, dt.hour, dt.minute, DateFormat.is24HourFormat(ctx)).show()
                            }) { Text(dt.format(DateTimeFormatter.ofPattern("h:mm a")), color = Cosmos.Cyan) }
                        }

                        Text("Repeat", style = MaterialTheme.typography.labelLarge, color = Cosmos.Muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Repeat.entries.forEach { opt ->
                                FilterChip(
                                    selected = repeat == opt,
                                    onClick = { repeat = opt },
                                    label = { Text(opt.label) },
                                    colors = cosmicChip(),
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
                                Text("Ring like an alarm", style = MaterialTheme.typography.bodyLarge, color = Cosmos.Text)
                                Text(
                                    "Loud, loops, opens full screen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Cosmos.Muted,
                                )
                            }
                            Switch(checked = alarmStyle, onCheckedChange = { alarmStyle = it }, colors = cosmicSwitch())
                        }

                        Text("Nag until done", style = MaterialTheme.typography.labelLarge, color = Cosmos.Muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 5, 10, 15, 30, 60).forEach { m ->
                                FilterChip(
                                    selected = nag == m,
                                    onClick = { nag = m },
                                    label = { Text(if (m == 0) "Off" else "Every ${m}m") },
                                    colors = cosmicChip(),
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val millis = dt.atZone(zone).toInstant().toEpochMilli()
                        val n = nText.toIntOrNull()?.coerceIn(1, 365) ?: 2
                        when {
                            title.isBlank() ->
                                Toast.makeText(ctx, "Give it a name", Toast.LENGTH_SHORT).show()
                            hasTime && repeat == Repeat.ONCE && millis <= now && !initial.done ->
                                Toast.makeText(ctx, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                            else -> {
                                val id = if (isNew) Store.newId(ctx) else initial.id
                                val saved = initial.copy(
                                    id = id,
                                    title = title.trim(),
                                    priority = priority,
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
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cosmos.Cyan, contentColor = Cosmos.Deep),
                ) { Text("Save") }
                OutlinedButton(onClick = close) { Text("Cancel", color = Cosmos.Text) }
                Spacer(Modifier.weight(1f))
                if (!isNew) {
                    TextButton(onClick = {
                        Notifier.cancel(ctx, initial.id)
                        Scheduler.cancelAll(ctx, initial.id)
                        Store.delete(ctx, initial.id)
                        close()
                    }) { Text("Delete", color = Cosmos.Pink) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
