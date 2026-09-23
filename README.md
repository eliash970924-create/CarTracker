# CarTally

An Android app for tracking what a car actually costs to run: fuel, service,
insurance and everything else, per car, with the figures worked out for you.

Built for Swedish use, so running costs are quoted in **kr/mil** (per 10 km)
and consumption per 100 km.

The app was called CarTracker until it was renamed; the repository, the
package (`se.eliash.cartracker`) and the on-device storage names still use
the old spelling. Those are not visible, and renaming any of them would cost
something real - see below.

## What it does

- **Multiple cars**, each with its own photo, colour theme and history, in a
  garage that opens first - or straight into one default car
- **Bifuel and plug-in hybrids** — a second fuel is tracked separately against
  its own odometer trail, so petrol and electric figures stay honest
- **Fill-ups** by odometer reading or trip distance, with a "missed previous
  fill-up" flag so a gap does not silently distort consumption
- **Expenses** by category (Maintenance, Tires, Insurance, Parking, Wash,
  Tolls, Other), with recurring monthly costs filled in automatically
- **Charts** for fuel price and consumption over time
- **Monthly overview** of what the car costs and how far it goes, month by
  month, with the running averages - cost split by fuel and everything else,
  so a hybrid's petrol and electricity show separately
- **Backup** that exports and restores everything: history, the car, and the photo
- **Automatic backup** of the whole garage to one file - on Google Drive or the
  phone - kept up to date daily or weekly
- **Dark mode**, following the phone or set by hand
- **A "Log fill-up" shortcut** on a long press of the app icon, straight to the
  form with the keyboard up
- **Settings** for the theme, the car to open at start, automatic backup, and
  backup and restore

Fuel types: Petrol, Diesel, Electric, Gas, E85.

## Building

Requires JDK 17 or newer (21 recommended) and the Android SDK.

```bash
./gradlew test           # unit tests
./gradlew assembleDebug  # debug APK
./gradlew installDebug   # build and install to a connected device
```

## Getting it onto a phone

```bash
./install.sh              # latest main
./install.sh some-branch  # that branch, for trying a PR before merging
./install.sh --here       # whatever is checked out now, no fetch
```

Updates the checkout, builds, installs and launches. It stops early with
something useful to do if no phone is connected, rather than after a build,
and refuses to move the checkout out from under uncommitted work.

The update step is the point. `git checkout main` on a local branch that
already exists leaves it wherever it was, so you build last week's code and
wonder why your change is missing; `install.sh` forces the branch to match
the remote.

**Do not sideload the APK attached to a CI run** to update an existing
install. It is signed with the runner's throwaway debug key rather than
yours, so Android refuses to install it over the app you already have, and
uninstalling first takes the data with it. That APK is for looking at a
build you have not checked out, on a phone that does not already have one.

CI runs both of the first two on every pull request and on pushes to `main`,
and attaches the built APK to the run — so a change can be installed from the
exact reviewed commit rather than from a local checkout that may be behind.

minSdk 24, targetSdk 36.

## How it is put together

Single-Activity Jetpack Compose UI over a Room database.

| File | Role |
| --- | --- |
| `MainActivity.kt` | assembles the screen and owns the state the pieces share |
| `EntriesTab.kt` | the fill-up form, the dashboard figures and the history list |
| `ExpensesTab.kt` | the expense form, total and history |
| `ChartsTab.kt` | fuel price, consumption and monthly charts |
| `MonthlySection.kt` | the monthly overview: averages, tappable bars and the month list |
| `AddEditCarDialog.kt` | the add/edit car form |
| `EditFuelUpDialog.kt` | editing or deleting one fill-up |
| `EditExpenseDialog.kt` | editing or deleting one expense, and stopping a repeat |
| `Garage.kt` | the garage screen, and which car opens at start |
| `GarageDrawer.kt` | the drawer: cars, views and the way to Settings |
| `Settings.kt` | theme, which car opens at start, automatic backup, import, per-car export |
| `FuelViewModel.kt` | database access, import and the recurring-expense fill-in |
| `Stats.kt` | consumption and cost arithmetic, and the chart series |
| `MonthlyOverview.kt` | cost and distance per month, and the bar scaling |
| `RecurringExpenses.kt` | which months of a monthly expense are missing |
| `CsvBackup.kt` | the backup file format: writing, escaping and parsing |
| `BackupArchive.kt` | the zip backup in both layouts, and telling a zip from a bare CSV |
| `AutoBackup.kt` | automatic backup: the chosen file, the schedule, the worker and its status |
| `CarPhotos.kt` | car photos in app-private storage |
| `Migrations.kt` | schema migrations |
| `AppDatabase.kt`, `*Dao.kt`, `Car/FuelUp/Expense.kt` | Room |

The arithmetic and the file parsing are kept out of the composables as pure
functions, so the things most able to be quietly wrong can be tested. See
`StatsTest`, `RecurringExpensesTest`, `BackupParseTest`,
`MonthlyOverviewTest`, `GarageTest`, `PaletteTest`, `ThemeTest`,
`ImageSamplingTest`, `ExportOrderTest`, `GarageBackupTest`,
`AutoBackupStatusTest` and `ImportMessageTest` under `app/src/test/`; `./gradlew test` runs them.

A form's contents belong to `MainActivity` rather than to the tab or dialog
showing them, as a state holder passed down. A composable is disposed when
its tab or dialog goes away, so holding the state there would silently clear
a half-filled form.

## Decisions worth knowing

A few things look odd until you know why.

**The database refuses to migrate rather than wiping itself.** From version 8
on, bumping the version without writing a `Migration` throws at startup. That
is deliberate: the app previously used `fallbackToDestructiveMigration()`, so
any schema change silently deleted every fill-up and expense ever logged. A
crash during development beats data vanishing on a real device. Versions 1–7
predate schema export and cannot be reconstructed, so only those still fall
back.

Each version's schema is exported to `app/schemas` and committed — that record
is what makes a migration possible, so commit the new JSON alongside one.
Version 8's own schema is not there: export was switched on in the same change
that moved to 9, so the earliest recorded schema is `9.json`.

**The dashboard and the charts count the first fill-up differently.** The
dashboard measures from the car's initial odometer, so the first fill-up
counts. The charts start from the first fill-up, so it plots no point — a
chart point needs a measured interval between two readings. Both are
intentional.

**A recurring expense is grouped by category and description, not by cost.**
Including the cost would make a price change look like a separate expense: the
old amount would keep generating beside the new one, every month, for ever.
Where two rows share the month a price changed, the one entered later wins,
because that is the one carrying the new price.

**Stopping a repeat clears the flag on every row of the series, not just the
one being edited.** The generator seeds from the latest row still marked
monthly, so clearing one leaves the next one down to take over and fill the
months straight back in. For the same reason, deleting a single month of a
live repeat does not stick: turn the repeat off first. The dialog says so
rather than letting it look like the delete failed. Months already recorded
are kept either way — switching a repeat off ends it, it does not erase it.

**Deleting a car asks first, and says what goes with it.** The button sits
directly under a field worth editing, and the car takes every fill-up and
expense with it through the foreign key cascade. The confirmation counts them.

**The rename to CarTally stopped at what you can see.** The name under the
icon and in the top bar changed; three things kept the old spelling on
purpose. The package, because it is the app's identity on the device and a
new one installs as a separate, empty app. The database file and the
preferences file, because they are where the data already is and a new name
opens a new, empty one. Each carries a comment saying so.

**The launcher icon's gauge is a hole, not paint.** Themed icons (Android 13+)
recolour every opaque pixel to a single tint, so a gauge painted petrol on
amber would vanish into a plain drop. Cut through, it shows whatever is
behind it - petrol normally, the theme colour when themed - which is also why
the monochrome layer reuses the foreground unchanged. The legacy raster
icons for Android 7 are rendered from the same path.

**The app opens on the garage unless a car is starred.** The star makes a
car the default, so someone with one car - or one main car - goes straight
to it. It is never set for you, even with only one car. The default lives in
preferences rather than the database, as a choice about this phone rather
than a fact about the car, which means it can outlive the car it names;
`launchCar` treats a missing car as no default. Back from a car goes to the
garage rather than out of the app.

**The selected car is held as an id, looked up in the live list.** Holding
the Car itself meant a stale copy after every edit, refreshed by hand, and it
did not survive rotation: turning the phone jumped back to the first car.
The id is saveable, and the launch decision is made once, so a rotation does
not re-open the default car over wherever you had got to.

**Accent surfaces are solid tints, never see-through.** They were once 20%
opaque copies of the accent colour. On the page that looks like a tint, but
a translucent surface shows whatever is behind it - on the add button, its
own shadow, as a lighter square - and petrol at 20% reads as plain grey. The
tints are now blended over the page colour, which looks the same and hides
what is underneath.

**The garage wears CarTally's colours; a car wears its own.** Petrol bar,
ivory page, white cards and an amber add button in the garage; inside a car,
the colour chosen for it. The pairings are held to WCAG contrast by
`PaletteTest` - the logo's amber is only 2.1:1 on white, so the star and
anything else on a card use a deeper amber or petrol.

**In dark mode a car's colour is lifted until it reads.** Colours are chosen
on a light screen, and petrol on a dark page is 1.3:1 - a chart line in it
would not be there. `liftUntilReadable` blends it towards white until it
clears 3:1; a colour already bright enough is left exactly as chosen, and
light mode is untouched. `ThemeTest` holds a spread of colours, black and
white included, to that and to a readable title on each car's bar.

**Photos are decoded at the size they are shown.** A 12-megapixel photo is
48 MB as a bitmap. Thumbnails are decoded with an `inSampleSize` so the
shorter side just covers the target - 750 KB for the garage card - off the
main thread, and cached so the garage and the drawer share one.

**Exports read the car's history from the database, not from the screen.**
Settings exports any car, not only the open one, so it cannot use what is on
screen. The history is read for the car chosen and put in the order the
history lists use, so an export reads the same wherever it was started;
`ExportOrderTest` holds that order to the fill-up query's own.

**Long press edits, in both lists.** It used to delete an expense outright
while the same gesture on a fill-up opened an editor, so one press meant two
very different things and one of them destroyed data without asking.

**A month's distance is spread across the days a tank covered, not dropped
into the month it was filled in.** An odometer reading only exists at a
fill-up, so a tank spanning a month boundary has to be attributed somehow.
Even driving is an assumption, but a mild one, and it stops the timing of a
fill-up deciding how a month looks. A day belongs to the month it starts in.

The monthly figures also differ from the consumption arithmetic in three
ways, each deliberate: distance follows the car's odometer however it was
fuelled, so a hybrid's kilometres are counted once rather than twice; a
missed fill-up still contributes its distance, because the fuel is unknown
but the driving happened; and the first fill-up contributes none, because
the car's initial odometer carries no date to measure from. Quiet months are
included with zeroes, since insurance is owed in a month nothing was driven,
and the month in progress is left out so a part-finished month cannot drag
the average down.

**Import matches duplicates by calendar day, not by timestamp.** Export writes
`yyyy-MM-dd`, so a row that goes out and comes back lands at midnight while
the row it came from still carries the time of day it was entered. Matching on
the timestamp would never find the original and would duplicate the whole
file.

**Car photos are copied into app storage rather than referenced.** The photo
picker returns a `content://` URI, which is a grant, not a file: it dies on
reinstall and when the original leaves the gallery, and the photo would
silently stop appearing. The bytes are copied verbatim rather than re-encoded,
because writing a `Bitmap` back out would drop the EXIF orientation tag and
leave photos sideways.

**Automatic backup writes to a file the user picks, not through the Drive
API.** The system's "save as" screen already lists Google Drive, the phone's
storage and any other installed provider, and a file chosen there can be kept
writable across restarts. So backing up to Drive needs no sign-in, no API keys
and no Cloud project, and works just as well for someone who would rather keep
it on the phone or a memory card. WorkManager runs it, so it survives the app
being closed and the phone restarting.

**It is a mirror, not a history.** Each run replaces the file with the garage
as it is now. Drive normally keeps earlier versions of a file for about a
month, which may cover the occasional "I want last week's back", but that is
Drive's doing, not the app's. The one exception is a
garage with no cars, which is never written: if everything were deleted by
mistake, the last backup is exactly what is needed, so it is left alone.

**A late backup is shown as a problem, not just a failed one.** Some phones'
battery savers stop scheduled work without a word, and a backup nobody knows
has stopped is worse than none. More than twice the interval since the last
success turns the status red and suggests "Back up now".

**The "Log fill-up" shortcut opens a car only when there is no doubt which.**
The default car, or the only one; with several and no default, the garage
opens and the form takes focus once a car is picked. A fill-up logged against
a guessed car is a quiet error found months later, and costs more than a tap.

**Monthly cost is split by fuel; distance is not.** Each cost bar stacks the
car's fuels and then everything else, and the selected month lists each part
with its amount. A plug-in hybrid's odometer counts every kilometre whichever
fuel drove it, so a per-fuel distance - and with it a "saved by charging"
figure - would be a guess presented as a measurement. The colours are fixed
by position (first fuel blue, second orange, the rest aqua) and were checked
with a colour-blindness validator against both card colours.

**Every screen shares CarTally's neutrals.** A car brings its own accent -
the bar, the buttons, the chart line - but the page, cards, borders and
secondary text are the garage's ivory and white (or night, in dark mode).
Material's defaults lean purple, and the car screens used to look like a
different app from the garage.

## The backup format

Fill-ups first, then optional expense and car sections:

```
Date,Odometer (km),Fuel Type,Amount,Price per Unit (SEK),Total Cost (SEK),Missed Previous
2026-01-15,12000,Petrol,40.0,18.5,740.0,false

[Expenses]
Date,Category,Description,Cost (SEK),Monthly
2026-01-20,Tires,"Winter set, mounted",8000.0,false

[Car]
Name,Primary Fuel,Secondary Fuel,Initial Odometer,Theme Colour,Photo
Volvo V60,Petrol,Electric,12000,4280391411,car_a1b2.img
```

Fields are quoted per RFC 4180, so a comma in a description is safe. Numbers
use a dot regardless of device locale, so a file exported on a Swedish phone
reads anywhere.

Importing **merges**: rows already present are skipped, nothing is deleted. If
the file carries a `[Car]` section, a car of that name is reused or created —
which is what lets a backup be restored onto a fresh install without setting
the car up by hand first.

Only the fuel section is required, so files exported before the later sections
existed still import.

A row that cannot be read is skipped and counted, and the number is reported
alongside what was imported. It is not given a substitute date: an invented
date would sit in the history looking like fact. One unreadable row used to
abort the whole file.

## Two exports

Settings offers both, for every car, because they answer different questions,
and automatic backup on top for the whole garage.

**Export to CSV** writes the file above. It is the one to open in a
spreadsheet.

**Full backup (.zip)** writes that same CSV as `backup.csv` alongside the car
photo under `photos/`. It is the one to keep if the phone is lost, because a
CSV cannot carry an image and a restore from one arrives without the picture.

**Automatic backup** writes the whole garage as one zip, one folder per car,
each folder exactly the single-car layout:

```
cars/1/backup.csv
cars/1/photos/car_a1b2.img
cars/2/backup.csv
```

Restoring it imports every car in turn and reports a total. A photo is only
kept if its folder also has a CSV, so a damaged archive cannot leave orphaned
images behind. A car that already exists takes the photo from the backup only
if it has none of its own.

Import takes either, decided by the file's first four bytes, so every file the
app has ever written still restores. A photo from an archive is saved under a
name generated on import rather than the one in the file: an archive entry
name is text someone else wrote, and `../` in it would otherwise write outside
the photo directory.
