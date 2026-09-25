# MistVD

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-blueviolet?logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-11%2B-green?logo=android)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-blue?logo=android)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/Media3%2FExoPlayer-1.7.1-orange?logo=android)](https://developer.android.com/guide/topics/media/media3)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

**English** · [简体中文](README.zh-CN.md)

MistVD is an open-source **local video player for Android**, built with Kotlin and Jetpack Compose on top of
AndroidX Media3 / ExoPlayer. It browses your storage as real folders, keeps playback fully offline, and adds a
set of player features that the stock experience does not have — including a **real-time beauty filter**, an
**8x playback speed with long-press temporary boost**, and **unlimited shuffle playback**.

This project is a **fork of [NekoVideo](https://github.com/FellipitoPV/NekoVideo)**, extended and rebranded.
See [Attribution & License](#attribution--license).

## Download

Prebuilt APKs are published on the **[Releases page](https://github.com/muhan81/MistVD/releases)**.

> Builds are uploaded manually by the maintainer, so the Releases page may be empty at any given time.
> If you do not see a build there, [build it yourself](#building) — it takes one Gradle command.

⚠️ **Before you upgrade, read this:** release APKs are signed with a self-signed key that is stable across
versions, so installing a newer build over an older one keeps your data. Installing a build signed with a
**different** key (for example someone else's build) requires uninstalling the app first, which **erases the
private vault password** — and with it access to files already moved into the vault. Always back up before
switching between differently signed builds.

## Highlights

- Folder-based local video library — no forced "media library only" view
- Media3 / ExoPlayer player with PiP, mini player, and background playback
- **Real-time beauty filter** — skin smoothing, whitening, rosy tone, sharpening, brightness, contrast, saturation
- **Playback speed 0.25x – 8x**, plus long-press-to-boost temporary speed
- **Unlimited shuffle** — shuffled history is kept, so "next" never dead-ends and never repeats too soon
- Four playback modes: sequential, repeat playlist, repeat one, shuffle
- Nine-grid **toolbox** for batch actions (tags, shuffle, rename, move, delete, share, …)
- Private vault with password / biometric unlock, obfuscated on-disk names, and no decrypted temp files
- Video tags with separate normal / private scopes, automatic backups, and tag-based shuffle
- DLNA / UPnP casting without proprietary cast SDKs
- No ads, no analytics, no cloud dependency

## Beauty Filter

The beauty filter runs **in real time during playback** (no export step), applied inside the Media3 video
pipeline. It exposes 7 adjustable parameters:

| Parameter | Range | Notes |
|---|---|---|
| Skin smoothing | 0 – 100 % | Edge-preserving; keeps hair, eyes and facial detail |
| Whitening | 0 – 100 % | |
| Rosy | 0 – 100 % | |
| Sharpening | 0 – 100 % | |
| Brightness | −100 – +100 % | |
| Contrast | −100 – +100 % | |
| Saturation | −100 – +100 % | |

- **Global settings** apply to every video; a **per-video override** can be set for one specific video.
  Priority: per-video > global > disabled.
- **Presets**: name, save, apply, rename and delete parameter combinations.
- Three entry points: the player's **More actions** drawer, the **toolbox** grid (auto-detects whether a
  single video is selected), and **Settings → Beauty**.
- The master switch is **off by default**. Per-video parameters can be adjusted at any time — they take
  effect as soon as the master switch is on.
- **HDR sources do not enable beauty** (PQ / HLG detected automatically) and show a notice instead, because
  the smoothing shader assumes 8-bit sRGB input.

The skin-smoothing / sharpening shader kernel is ported from
[**GPUPixel**](https://github.com/pixpark/gpupixel) (Apache License 2.0, © 2021 PixPark) — see
[Attribution & License](#attribution--license). The remaining parameters reuse built-in Media3 effects.

## What The App Does

### Library and file management

- Browses storage as real folders; scans and caches folders containing videos
- Common formats: `mp4`, `mkv`, `webm`, `avi`, `mov`, `wmv`, `m4v`, `3gp`, `flv`
- Thumbnails cached in memory and on disk; search inside the current folder
- Sort by name, date, or size; optional duration / size display
- Pin up to 10 folders for quick access
- Create folders, rename, move, delete, share; batch operations from the toolbox
- **Multi-select storage volume support** — browses the volume you choose (including SD cards), with the
  choice persisted by volume UUID
- **Migration wizard** for moving an existing private vault between storage volumes
- Opens videos from other apps via `VIEW` and `SEND_MULTIPLE` intents

### Playback

- Folder playback with playlist navigation
- **Unlimited shuffle**: played items are recorded, so repeated "next" keeps producing fresh videos and
  loops forever; "previous" then "next" still yields a new random item
- Double-tap seek, stacked double-tap skips, vertical swipe for volume / brightness
- Playback speed **0.25x – 8x**; **long-press anywhere to temporarily boost** and release to snap back
  (boost speed configurable in settings)
- Four playback modes: sequential / repeat playlist / repeat one / shuffle
- Sleep timer, keep-screen-on, audio & subtitle track selection, external subtitle files, subtitle size
- Automatic orientation; background playback via `MediaSessionService`
- Media notification and lock-screen controls; persistent mini player while browsing
- Continue Watching: resumes on the home card and inside folders, restoring audio / subtitle / external
  subtitle choices

### Private vault

- Password-protected vault, with biometric unlock after password setup
- **Enter by tapping the app icon in the top-left corner three times**, then entering your password
- On the vault-unlock dialog, a **correct password is submitted automatically** — no need to press Verify.
  A wrong password shows **no** error until you press Verify.
- File and folder names are replaced with UUIDs, a `.nomedia` marker hides the vault from other apps, and
  the first 8 KB of each file is XOR-obfuscated so containers are not recognisable by their magic bytes
- Playback deobfuscates **on the fly** through a custom data source — **no decrypted temporary files** are
  ever written to disk
- Vault content is not rendered at all until unlocked (not merely greyed out), and hides again on cold start

> **Security note (please read):** this is a **disguise-grade protection, not full encryption**. Only the
> first 8 KB of each file is obfuscated; the remainder of the video is stored as-is. The vault key material
> is derived from your password but the salt is stored in plain text next to the vault. Treat it as
> protection against casual snooping — not against a motivated forensic attacker.

### Casting

- Discovers DLNA / UPnP renderers over SSDP, streams local files through an embedded HTTP server
- Playlist casting, next / previous control, playback state polling
- Works without the Google Cast SDK

### Tags

- Create, rename, delete tags; assign to one or many videos
- Separate tag scopes for normal and private content
- Tag-based shuffle with include / exclude / neutral filters
- Tag references survive rename and move; automatic on-device backups with import / merge / export

### Settings

- Playback: background playback, auto-hide controls, seek gestures, volume / brightness gestures,
  long-press boost speed, continue watching, sleep timer
- Interface: theme (light / dark / system, pure-black AMOLED dark), app language
- Display: durations and file sizes
- Storage: thumbnail cache, continue-watching history, storage volume selection
- Security: password, biometric unlock
- **Beauty**: master switch, 7 parameters, presets
- Tag management with backup and restore

## Supported Languages

Portuguese · English · Spanish · French · German · Russian · Hindi · Chinese (Simplified) ·
Chinese (Traditional) · System default

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.1 + Coroutines |
| UI | Jetpack Compose + Material 3 |
| Playback | AndroidX Media3 / ExoPlayer 1.7.1 (+ media3-effect) |
| Background media | MediaSessionService |
| Navigation | Navigation Compose |
| Thumbnails | Coil, Glide, MediaMetadataRetriever |
| Local database | Room |
| Serialization | Gson |
| Casting | DLNA / UPnP via SSDP + SOAP |
| Local streaming | NanoHTTPD |
| Build | Gradle 8.13 (Kotlin DSL) + AGP 8.13 |

## Requirements

- Android 11 (API 30) or higher
- Access to local storage; `All files access` may be required for folder-based browsing
- The packaged APK ships **`arm64-v8a` only**

## Building

```bash
./gradlew assembleRelease
```

Release builds are minified with R8 and signed with the keystore referenced by
`app/release-signing.properties` (not part of this repository). Without it, use `assembleDebug`.

Project info:

- `minSdk = 30`, `targetSdk = 36`
- `versionName = "1.20"`, `versionCode = 46`

## Privacy

MistVD contains no analytics, no ads, and no third-party tracking. Network activity is limited to
local-network casting (DLNA discovery and playback control). Thumbnail generation, tag storage and
private-vault handling all happen on the device.

## Attribution & License

This project is a fork of **[NekoVideo](https://github.com/FellipitoPV/NekoVideo)** and is distributed under
the **GNU General Public License v3.0** — see [LICENSE](LICENSE) for the full text.
Modifications and additions in this fork are copyright © 2025-2026 NKL's.

Third-party components used by this fork:

| Component | License | Used for |
|---|---|---|
| [NekoVideo](https://github.com/FellipitoPV/NekoVideo) | GPL v3 | Upstream project this fork is based on |
| [GPUPixel](https://github.com/pixpark/gpupixel) | Apache License 2.0 | Skin-smoothing / sharpening shader kernel (ported, © 2021 PixPark) |
| AndroidX Media3 | Apache License 2.0 | Playback, media session, video effects |

Because this project is GPL v3, any redistributed build — modified or not — must also be released under
GPL v3 with its complete corresponding source.
