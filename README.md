# Orbit

A personal, fully on-device Android to-do list + reminder app with a cosmic look.
No servers, no accounts.

## Get the APK
Every push to `main` builds a signed APK with GitHub Actions and publishes it under
**Releases** (`build-N`). Open the latest release on your phone, download the `.apk`,
allow "Install unknown apps" for your browser, and install. New builds install as updates.

## Features
- Tasks: Overdue, Today, Anytime, Upcoming, Done today
- Priority as stars: Low, Medium, High
- Orbit ring shows today's progress; daily streak
- Quick add; tap the star to change priority

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
