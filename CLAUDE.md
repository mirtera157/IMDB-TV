# IMDB-TV: instructions for AI agents

This repo installs **IMDB-TV** (package `br.tvvibe.imdbbadge`, label "Notas IMDb") on an Android TV: an
accessibility service that shows an IMDb rating badge for the title selected in the **Apple TV** app.
The person asking is often not technical. **Reply in their language**, in short steps, one at a time, and do
everything you can from the computer yourself.

When asked to "install IMDB-TV / the IMDb thing on my TV", follow **Install** below.

## What the person must do on the TV (you can't)

1. Turn on Developer options: *Settings → Device Preferences → About* (Google TV: *Settings → System → About*), click **Build** 7 times.
2. In Developer options, turn on **USB debugging** (sometimes "ADB debugging").
3. Read the TV's IP: *Settings → Network & Internet →* the connected network (e.g. `192.168.0.50`). PC and TV must be on the same network.
4. When the installer connects, the TV shows "Allow USB debugging?": tick "Always allow" and press OK.

Ask for the IP and confirm steps 1–2 before running anything.

## Install

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -TvIp <TV IP>
```

macOS / Linux (or Git Bash):

```bash
./install.sh <TV IP>
```

The script creates a private Python venv in `tools/venv` (first run, needs internet; if Python 3 is missing on
Windows: `winget install Python.Python.3.12`, then a new terminal). It downloads `apk/IMDB-TV.apk` from the latest
GitHub release unless one is already there, and connects to the TV over network ADB with `tools/tv.py` (pure Python,
no adb binary). Then it sends the APK with `tools/b64push.py` (prints `chunk N/M`, ends with `md5 ... MATCH`, takes a
few minutes), installs it, grants `WRITE_SECURE_SETTINGS` and enables the accessibility service.

Afterwards, ask the person to open the Apple TV app and browse: the first badge takes ~10 s. Finish by reminding
them they can switch **USB debugging off**.

If the person already has `adb`, the README's "Already have adb?" commands do the same thing.

## Troubleshooting

- **Timeout / "Could not reach the TV"**: wrong IP, different network, or USB debugging off. A rebooted TV may have a new IP.
- **No "Allow USB debugging?" prompt**: toggle USB debugging off and on, run again.
- **`INSTALL_FAILED_OLDER_SDK`**: the TV runs Android older than 8.0, which is not supported.
- **`pm grant` fails**: some devices need "USB debugging (Security settings)" in Developer options. Without the
  permission the badge only works if accessibility happens to be switched on while the Apple TV app is open.
- **No badge**: only the Apple TV app is supported (Netflix and HBO Max expose no titles; tested). See what the
  service reads: `TV_HOST=<ip> <venv python> -I tools/tv.py 'logcat -d -s ImdbBadge'`. The Apple TV app only
  reports titles if accessibility is switched on while it is open; `Rearm` does that (up to 4 tries).
- **Other accessibility services got switched off**: the installer replaces `enabled_accessibility_services`.
  Re-add the other one, colon-separated.

## Uninstall

`install.ps1 -TvIp <ip> -Uninstall` / `install.sh <ip> --uninstall`, or on the TV: *Settings → Accessibility → Notas IMDb*.

## Layout

- `android/`: the Android project (Java, no dependencies). `BadgeService` listens to the Apple TV app,
  `TitleParser`/`Resolver` extract the title from the tile description, `ImdbDb` binary-searches the memory-mapped
  `app/src/main/assets/imdb.tsv`, `Rearm` toggles accessibility, `Norm` must match `normalize()` in `scripts/build_db.py`.
  Unit tests: `android/app/src/test/` (`./gradlew testReleaseUnitTest`).
- `scripts/build_db.py`: builds `imdb.tsv` from the IMDb non-commercial datasets (personal use only).
- `tools/`: minimal network ADB client from cleanyourtv (MIT).
- `docs/img/`: README images (illustrations; the Apple TV app's screen can't be captured).

Building needs JDK 17 + Android SDK (`android/local.properties`, not committed). Bump `versionCode` whenever
`imdb.tsv` changes, because the app only re-copies the database on a new version.
