# CarTracker

An Android app for tracking what a car actually costs to run: fuel, service,
insurance and everything else, per car, with the figures worked out for you.

Built for Swedish use, so running costs are quoted in **kr/mil** (per 10 km)
and consumption per 100 km.

## What it does

- **Multiple cars**, each with its own photo, colour theme and history
- **Bifuel and plug-in hybrids** — a second fuel is tracked separately against
  its own odometer trail, so petrol and electric figures stay honest
- **Fill-ups** by odometer reading or trip distance, with a "missed previous
  fill-up" flag so a gap does not silently distort consumption
- **Expenses** by category (Maintenance, Tires, Insurance, Parking, Wash,
  Tolls, Other), with recurring monthly costs filled in automatically
- **Charts** for fuel price and consumption over time
- **CSV backup** that exports and restores everything, including the car

Fuel types: Petrol, Diesel, Electric, Gas, E85.

## Building

Requires JDK 17 or newer (21 recommended) and the Android SDK.

```bash
./gradlew test           # unit tests
./gradlew assembleDebug  # debug APK
./gradlew installDebug   # build and install to a connected device
```

CI runs both of the first two on every pull request and on pushes to `main`,
and attaches the built APK to the run — so a change can be installed from the
exact reviewed commit rather than from a local checkout that may be behind.

minSdk 24, targetSdk 36.

## How it is put together

Single-Activity Jetpack Compose UI over a Room database.

| File | Role |
| --- | --- |
| `MainActivity.kt` | the whole UI: drawer, dialogs, and the Log & History, Service & Expenses and Charts & Graphs tabs |
| `FuelViewModel.kt` | database access and the import/export operations |
| `Stats.kt` | consumption and cost arithmetic, and the chart series |
| `CsvBackup.kt` | backup file format, escaping and parsing |
| `CarPhotos.kt` | car photos in app-private storage |
| `Migrations.kt` | schema migrations |
| `AppDatabase.kt`, `*Dao.kt`, `Car/FuelUp/Expense.kt` | Room |

`Stats.kt` holds the calculations the app exists to produce, kept as pure
functions so they can be tested — see `app/src/test/.../StatsTest.kt`.

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

## The backup format

Fill-ups first, then optional expense and car sections:

```
Date,Odometer (km),Fuel Type,Amount,Price per Unit (SEK),Total Cost (SEK),Missed Previous
2026-01-15,12000,Petrol,40.0,18.5,740.0,false

[Expenses]
Date,Category,Description,Cost (SEK),Monthly
2026-01-20,Tires,"Winter set, mounted",8000.0,false

[Car]
Name,Primary Fuel,Secondary Fuel,Initial Odometer,Theme Colour
Volvo V60,Petrol,Electric,12000,4280391411
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

**Photos are not in the backup.** They survive a reinstall in place, but a
restore onto a different phone will not carry them.
