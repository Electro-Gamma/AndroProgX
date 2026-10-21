<!--
  Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
  Author: Yassine Moukil ELECTRO-GAMMA
  Third-party / ported code attributions: see NOTICE.md
-->

<div align="center">

# AndroProgX

### CH341A EEPROM &amp; SPI Flash Programmer for Android

Turn your phone into a pocket **USB EEPROM / SPI flash programmer** &mdash; no PC required.

[![Download APK](https://img.shields.io/badge/Download-APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Electro-Gamma/AndroProgX/releases/latest)
[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/Electro-Gamma/AndroProgX/blob/main/colab/Build_AndroProgX.ipynb)

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Chip](https://img.shields.io/badge/programmer-CH341A-orange)
![Version](https://img.shields.io/badge/version-1.0-blue)

**Author: ELECTRO-GAMMA**

</div>

---

## What is AndroProgX?

Plug a common black/green **CH341A programmer board** (USB `1A86:5512`, in its EEPROM/SPI *programmer* mode &mdash; not a plain CH340 UART adapter) into your phone with an OTG cable, pick a mode, tap **Connect**, and you can:

- read, write, erase and verify **I2C EEPROMs (24Cxx)**,
- read, write, erase and verify **SPI NOR flash chips (25xx)**,
- drive a small **SSD1306 OLED** display (text, images, clock, device stats, animations),
- **bridge I2C and SPI over TCP** so another device (or Termux on the same phone) can talk to the chip.

## Download &amp; install

1. Open the **[latest release](https://github.com/Electro-Gamma/AndroProgX/releases/latest)** and download `AndroProgX-vX.Y.apk`.
2. Open the file on your phone and allow **Install unknown apps** if Android asks.
3. Connect the CH341A through an **OTG adapter**. AndroProgX opens automatically when the programmer is plugged in.

**Requirements:** Android 8.0+ (API 26) and a phone with **USB host (OTG)** support.

> If you already have a build signed with a different key, uninstall it first &mdash; Android cannot upgrade an app in place across different signing keys.

## Features

### Four modes

The **Mode** picker (also shown as a small row at the top of every screen) switches between:

| Mode | What it does |
|---|---|
| **I2C EEPROM (24Cxx)** | Read / write / erase 24-series I2C EEPROMs |
| **SPI Flash (25xx)** | Read / write / erase 25-series SPI NOR flash |
| **SSD1306 Display** | Drive a small I2C OLED panel |
| **TCP Server** | Expose I2C and SPI to other devices over the network |

The picker is locked while connected &mdash; disconnect first to switch modes.

### I2C EEPROM (24Cxx)

- Chip presets from **24C01 (128 B)** up to **24C1024 (128 KB)**, including the special-addressing **24LC515 / 24LC1025**, or a custom size.
- **Read**, **Write** (from a `.bin` file), **Erase** (fills with `0xFF`), **Write Random**, **Write Txt**.
- **Detect** probes the real capacity when you don't know the chip.
- **Verify** reads the chip back and compares it with the editor buffer (PASS/FAIL + highlighted differences).
- **Dump** reads the whole chip and immediately offers to save it to a file.

### SPI Flash (25xx)

- **Detect Chip (JEDEC ID)** identifies manufacturer, part and size (Winbond W25Q, GigaDevice GD25Q, EON/XMC 25QH, plus a generic fallback for other makers), with automatic **4-byte addressing** above 16 MB.
- **Read**, **Write**, **Erase Chip**, **Write Random**, **Verify**, **Dump** &mdash; using the standard SPI NOR command set.
- Manual size / page size if you prefer to skip detection.

### Hex editor

- 16 bytes per row: **offset | hex | ASCII**; tap any byte to edit it in place.
- **Hex Compare** tab: chip data vs file data side by side with diff highlighting and **CRC32**.
- **Find / Replace** with UTF-8, Hex, ASCII or Decimal search, forwards or backwards.
- **Load File** / **Save File** independent of the chip.

### SSD1306 OLED

- Address (`3C` / `3D`) and panel size: 128x64, 128x32, 96x16, 64x32.
- **Test Pattern**, **Clear**, **Draw Text**, **Load Image** (auto-scaled and dithered to 1-bit).
- **Custom fonts**: bundled *Alagard* and *PixelOperator*, or any `.ttf` / `.otf` from your phone.
- **Clock** (analog + digital), **Device Stats** (battery, temperature, RAM, storage, IP) and **Animate Images**.

### TCP bridge (I2C-over-TCP / SPI-over-TCP)

Start the server in the app (default ports **3334** for I2C, **3335** for SPI) and talk to the chip from anywhere on the network &mdash; or from **Termux on the same phone** with the included client:

```bash
# in Termux, with the app's TCP server running
python tools/ch341-bridge-termux i2c write-read 0x50 0000 16     # read 16 bytes of a 24Cxx
python tools/ch341-bridge-termux spi write-read 9F 3             # read the JEDEC ID of a 25xx
python tools/ch341-bridge-termux status i2c                      # is the port listening?
```

The protocol is a small length-prefixed binary format, documented at the top of [`tools/ch341-bridge-termux`](tools/ch341-bridge-termux) and in `I2cTcpServer.kt` / `SpiTcpServer.kt`.

## Build it yourself

### Option 1 &mdash; Google Colab (no PC needed)

Click the badge, then **Runtime &rarr; Run all**. The notebook clones this repo, installs JDK 17, the Android SDK and Gradle 8.7, generates a signing key, builds **debug + release** APKs and downloads them (plus the keystore) to your device. It takes about 5&ndash;10 minutes.

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/Electro-Gamma/AndroProgX/blob/main/colab/Build_AndroProgX.ipynb)

> Keep the downloaded keystore and its password if you want later builds to upgrade over the one you installed.

### Option 2 &mdash; Android Studio

Open the folder, let Gradle sync, and run on a phone with USB-host support.
`minSdk 26` &middot; `targetSdk` / `compileSdk 35` &middot; Kotlin &middot; JDK 17 &middot; Gradle 8.7 &middot; AGP 8.5.2.

### Signed release builds

`app/build.gradle` signs the release build when these environment variables are set (the Colab notebook sets them for you):

`ANDROPROGX_KEYSTORE` (path), `ANDROPROGX_KEYSTORE_PASSWORD`, `ANDROPROGX_KEY_ALIAS`, `ANDROPROGX_KEY_PASSWORD`

Without them `assembleRelease` produces an *unsigned* APK, which Android refuses to install.

## Publishing to GitHub from Termux

[`tools/github_sync.py`](tools/github_sync.py) pushes, pulls and publishes releases (APK attached) using only Python and git:

```bash
pkg install git python
python github_sync.py init                 # create repo + first push
python github_sync.py push -m "message"    # commit and push
python github_sync.py pull                 # get latest changes
python github_sync.py release --tag v1.0   # GitHub Release with the APK
```

Fill in your GitHub username, Gmail and token at the top of the script first (instructions are inside).

## Project structure

```
AndroProgX/
├── app/src/main/java/com/androprogx/app/
│   ├── MainActivity.kt        UI wiring: buttons, spinners, hex editor, mode switching
│   ├── ProgrammerService.kt   Foreground service keeping USB + TCP servers alive in background
│   ├── ch341/                 CH341A USB transport + SPI / I2C stream protocols
│   ├── eeprom/                24Cxx + 25xx logic and chip databases
│   ├── display/               SSD1306 driver, clock / stats / text compositor
│   ├── programmer/            ProgrammerController, I2C-over-TCP and SPI-over-TCP servers
│   ├── ui/                    HexEditorAdapter (offset | hex | ASCII grid)
│   └── util/                  Hex helpers
├── app/src/main/res/          Layouts, drawables, themes (light + dark)
├── app/src/main/assets/fonts/ Alagard, PixelOperator
├── colab/Build_AndroProgX.ipynb   Colab build notebook
├── tools/ch341-bridge-termux      Termux client for the TCP bridge
├── tools/github_sync.py           Termux push / pull / release helper
└── NOTICE.md                  Third-party attributions
```

## Permissions

| Permission | Why |
|---|---|
| USB host feature | Talk to the CH341A over USB |
| `INTERNET` | Run the I2C / SPI-over-TCP servers |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Show the phone's IP address |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` | Keep the connection alive while the app is in the background |

File loading/saving and image/font picking use Android's file picker, so no storage permission is needed.

## Project status

The protocol logic follows known-working reference implementations (flashrom and IMSProg) and the code has been through real Gradle build/install cycles. It was written without a physical CH341A attached, so treat chip-level behaviour as carefully reviewed rather than hardware-verified, and **always keep a backup of a chip before writing to it**. Bug reports are welcome via GitHub Issues.

## License &amp; credits

Copyright &copy; 2026 **Yassine Moukil &mdash; ELECTRO-GAMMA**. All rights reserved.

Parts of the app are ported from third-party projects &mdash; protocol logic from the GPL-licensed **flashrom** and **IMSProg**, BSD-licensed code and data from **Adafruit's SSD1306 / GFX** libraries, and two bundled pixel fonts (*Alagard* by Brian Kent, *PixelOperator* by Jayvee Enaguas). They are documented file by file in [`NOTICE.md`](NOTICE.md); those licenses apply to those parts, so check them before redistributing.
