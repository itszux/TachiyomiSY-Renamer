# TachiyomiSY Renamer

When a TachiyomiSY source extension updates its URL structure, path format, or directory hashing schemes, previously downloaded manga chapters under the old scheme become unrecognized by the reader app (showing them as not downloaded). 

This standalone, modern Jetpack Compose Android application solves this problem. It scans your local storage, decodes your Tachiyomi `.tachibk` / `.proto.gz` backup file, and matches legacy chapter folders against official backup metadata. It then renames those old chapter folders to match the new naming and hashing conventions expected by the updated extension, letting Tachiyomi recognize them again without redowning.

## Key Features

* **Direct Backup Loading**: Decodes `.tachibk` and `.proto.gz` backup files on-device using a pure Kotlin implementation of the Protobuf backup schema.
* **Official Tachiyomi `ChapterRecognition` Engine**: Ported Tachiyomi's official regex-based chapter recognition engine to extract chapter numbers reliably, stripping volume tags (`vol`, `version`, `season`), manga title prefixes, and subchapter tags (`extra`, `omake`, `special`).
* **Precision Floating-Point Matching**: Solves `Float` vs `Double` precision mismatches from Tachiyomi's backup database (e.g. `11.2f` converting to `11.199999809265137`), ensuring decimal subchapters (`.1`, `.2`) are detected accurately.
* **Interactive Tree Selection & Rename Counters**: Displays discovered sources and mangas in an interactive hierarchy, pre-scanning and showing the number of pending renames next to each manga/source with a "Select Changes" shortcut.
* **Bulk Scanlator Selection**: Automatically detects all scanlators across chapter groups and provides a dropdown to bulk-select your preferred scanlator, logging warnings for any chapters requiring manual selection.
* **Recognized Folder Filtering**: Toggle option to show or hide local download folders that are already correctly recognized by Tachiyomi (lacking only the URL hash).
* **All Files Access**: Uses Android's storage permission framework to rename folders safely under public directories.
* **Premium Dark Theme**: Sleek dark mode UI with interactive logs console, progress reporting, and clear horizontal scrollability for long titles.

---

## How to Use

1. **Create a Backup**: Open your Tachiyomi/TachiyomiSY application and navigate to:
   * **More** ➔ **Settings** ➔ **Backup and restore** ➔ **Create backup**
2. **Select Backup File**: In this helper app, tap **Select Backup File** and choose the backup file you just created.
3. **Configure Downloads Path**: Type or tap **Browse** to point to your Tachiyomi downloads directory (e.g. `TachiyomiSY/downloads`).
4. **Select Mangas**: Tap **Load & Scan Directory**. View the pending rename counters next to each manga, tap **Select Changes** (or select manually), then tap **Scan Selected Mangas**.
5. **Select Renames**: Review the match options. Use the **Bulk Scanlator** dropdown or pick individual scanlators, then tap **Apply Renames**.
6. **Reindex Downloads**: Finally, open Tachiyomi/TachiyomiSY and run:
   * **More** ➔ **Settings** ➔ **Advanced** ➔ **Reindex downloads**

---

## 🛠️ How it Works under the Hood

1. **Schema Decoder**: Re-implements Tachiyomi's Protobuf backup structure locally to extract manga metadata, titles, chapter URLs, chapter names, and scanlator information.
2. **ChapterRecognition Parsing**: Uses Tachiyomi's official regex patterns (`NUMBER_PATTERN`, `basic`, `number`, `unwanted`, `unwantedWhiteSpace`) to clean folder names and isolate true chapter numbers.
3. **Epsilon Matching**: Uses delta comparison (`abs(parsedNumber - chapter.chapterNumber) < 0.001`) to match parsed numbers against database records, preventing double-precision discrepancies from missing decimal subchapters.
4. **Rename & Reindex**: Renames selected directory folders. After finishing, it prompts the user to perform a **Reindex downloads** operation in TachiyomiSY:
   * **More tab** ➔ **Settings** ➔ **Advanced** ➔ **Reindex downloads**

---

## How to Build

### Requirements
* **JDK 17** or newer
* **Android SDK** (API Level 34)

### Build Steps

Clone the repository and run the following commands in your shell:

#### 1. Compile Debug APK
```bash
./gradlew :app:assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/TachiyomiSY Renamer-debug.apk`

#### 2. Compile Signed Release APK
```bash
./gradlew :app:assembleRelease
```
The output APK will be generated at:
`app/build/outputs/apk/release/TachiyomiSY Renamer.apk`

> **Note**: The release build is configured to sign itself using the debug certificate for easy sideloading without needing to set up custom keystore files.
