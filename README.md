# TachiyomiSY Renamer

When a TachiyomiSY source extension updates its URL structure, path format, or directory hashing schemes, previously downloaded manga chapters under the old scheme become unrecognized by the reader app (showing them as not downloaded). 

This standalone, modern Jetpack Compose Android application solves this problem. It scans your local storage, decodes your Tachiyomi `.tachibk` / `.proto.gz` backup file, and matches legacy chapter folders against official backup metadata. It then renames those old chapter folders to match the new naming and hashing conventions expected by the updated extension, letting Tachiyomi recognize them again without redowning.

## Key Features

* **Direct Backup Loading**: Decodes `.tachibk` and `.proto.gz` backup files on-device using a pure Kotlin implementation of the Protobuf backup schema.
* **Smart Matching & Filtering**: Uses intelligent boundary matching to identify legacy folder names (e.g., `Chapter 182_`, `Ch. 12`) without falling victim to regex word boundary limitations (which fail on underscores or incorrectly match integer chapters like `168` to decimal chapters like `168.5`).
* **Interactive Tree Selection**: Displays discovered sources and mangas in an interactive hierarchy. You can select exactly which mangas to scan.
* **Scanlator Resolving**: Resolves multiple scanlators for a single chapter number, presenting them as options inside an expandable tree view.
* **All Files Access**: Uses Android's storage permission framework to rename folders safely under public directories.
* **Premium Dark Theme**: Sleek dark mode UI with interactive logs console, progress reporting, and clear horizontal scrollability for long titles.

---

## How to Use

1. **Create a Backup**: Open your Tachiyomi/TachiyomiSY application and navigate to:
   * **More** ➔ **Settings** ➔ **Backup and restore** ➔ **Create backup**
2. **Select Backup File**: In this helper app, tap **Select Backup File** and choose the backup file you just created.
3. **Configure Downloads Path**: Type or tap **Browse** to point to your Tachiyomi downloads directory (e.g. `TachiyomiSY/downloads`).
4. **Select Mangas**: Tap **Load Directory Structure**. Check/uncheck the sources or individual mangas you want to scan, then tap **Scan Selected Mangas**.
5. **Select Renames**: Review the match options. If a chapter has multiple scanlator options, pick the correct one, then tap **Apply Renames**.
6. **Reindex Downloads**: Finally, open Tachiyomi/TachiyomiSY and run:
   * **More** ➔ **Settings** ➔ **Advanced** ➔ **Reindex downloads**

---

## 🛠️ How it Works under the Hood

1. **Schema Decoder**: Re-implements Tachiyomi's Protobuf backup structure locally to extract manga metadata, titles, chapter URLs, chapter names, and scanlator information.
2. **Scan & Compare**: Matches local chapter folders in the target directory (e.g., `Chapter 194`) against chapter entries in the backup using custom token boundaries:
   - Ensures the chapter number is not preceded by a digit or dot (preventing suffix matches like `94` in `194` or decimal fractions like `.5` in `168.5`).
   - Ensures the chapter number is not succeeded by a digit or a dot followed by a digit (preventing prefix matches like `19` in `194` or matching integer chapters like `168` to decimal chapters like `168.5`).
   - Handles underscores (`_`), spaces, hyphens, and parenthesis boundaries correctly.
3. **Rename & Reindex**: Renames selected directory folders. After finishing, it prompts the user to perform a **Reindex downloads** operation in TachiyomiSY:
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
