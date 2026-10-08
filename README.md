# Next Move

A personal, fully on-device Android to-do list + reminder app with a chess theme.
No servers, no accounts. One move at a time.

## Get the APK
Every push to `main` builds a signed APK with GitHub Actions and publishes it under
**Releases** (`build-N`). Open the latest release on your phone, download the `.apk`,
allow "Install unknown apps" for your browser, and install. New builds install as updates.

## Features
- To-do board: Your move (overdue), Today, Anytime, Upcoming, Done today
- Priorities as pieces: Pawn +5, Rook +10, King +20 rating
- Rating and daily streak for completed tasks
- Quick add from the board; tap the piece to change priority

### Reminders
- One-time, daily, weekdays, weekly, or every-N-days reminders
- Done / Snooze 10m buttons on the notification
- Alarm style: loud, looping, full screen over the lock screen
- Nag mode: re-notify every 5–60 min until you tap Done
- Survives reboot, app updates, and time-zone changes
- Setup checks on the home screen (notifications, exact alarms, battery, full screen)

## How it works
`Store` (JSON in SharedPreferences) → `Scheduler` (AlarmManager alarm clock) →
`AlarmReceiver` (fires notification, advances recurring reminders) → `Notifier`.
`BootReceiver` re-arms everything after a restart.

Note: the signing key is committed on purpose (personal app) so every build
updates over the previous install.
