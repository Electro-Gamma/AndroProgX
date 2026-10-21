<!--
  Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
  Author: Yassine Moukil ELECTRO-GAMMA
  Third-party / ported code attributions: see NOTICE.md
-->

<div align="center">

# AndroProgX

### Android I2C / SPI EEPROM &amp; Flash Programmer (CH341A)

**Read, write, verify and recover chips straight from your phone &mdash; unbrick modems, routers, IP cameras, DVRs, TV boards and more. No PC needed.**

[![Download APK](https://img.shields.io/badge/Download-APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/YOUR_GITHUB_USERNAME/AndroProgX/releases/latest)
[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/YOUR_GITHUB_USERNAME/AndroProgX/blob/main/colab/Build_AndroProgX.ipynb)

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Programmer](https://img.shields.io/badge/programmer-CH341A-orange)
![Chips](https://img.shields.io/badge/chips-24Cxx%20I2C%20%7C%2025xx%20SPI-blue)
![Version](https://img.shields.io/badge/version-1.0-informational)

**Author: ELECTRO-GAMMA**

</div>

---

## Why AndroProgX?

A bricked modem or a dead camera is very often just a **corrupted firmware stored in a small 8-pin SPI flash chip**. A PC-based programmer fixes it, but you need a computer, drivers and software. AndroProgX turns a **~$3 CH341A board + an OTG cable + your phone** into a complete field programmer:

- **SPI NOR flash (25xx)** &mdash; where routers, modems, ONTs, IP cameras, DVRs, set-top boxes, TV mainboards and PC BIOS keep their firmware.
- **I2C EEPROM (24Cxx)** &mdash; where devices keep settings, calibration, serial numbers, EDID and configuration.
- **Safe workflow** &mdash; detect &rarr; **backup** &rarr; erase &rarr; write &rarr; **verify**, with a built-in hex editor and hex compare.
- **Fully on the phone** &mdash; load firmware `.bin` files from storage, save dumps back, work in the workshop or on site.

## What you can recover or clone

| Device | Typical chip | What AndroProgX is used for |
|---|---|---|
| **Modems / routers / ONTs** (bricked, boot loop, bad flash) | SPI NOR `25xx` (Winbond W25Q, GigaDevice GD25Q, Macronix MX25L, EON/XMC 25QH&hellip;) | Back up, then re-flash the vendor / OpenWrt firmware image |
| **IP cameras, DVR / NVR, doorbells** | SPI NOR `25xx` | Restore a corrupted bootloader/firmware, clone a working dump |
| **Set-top boxes, smart TV &amp; monitor boards** | SPI NOR `25xx`, I2C `24Cxx` (EDID/config) | Fix boot failures, restore EDID/settings |
| **PC / laptop BIOS &amp; UEFI** | SPI NOR `25xx` (3.3 V parts) | Recover a failed BIOS update |
| **Network switches, access points, printers, appliances** | SPI NOR / `24Cxx` | Firmware recovery, settings restore |
| **DIY &amp; embedded projects** (Arduino, ESP32, STM32) | `24Cxx`, `25xx` | Read, fill, erase and inspect external memory chips |
| **Any `24Cxx` / `25xx` chip on your bench** | &mdash; | Read, edit in hex, write, verify, compare against a file |

> Use AndroProgX only on devices you own or are authorised to repair, and respect the law and the firmware licences that apply to you.

## Supported chips

| Family | Details |
|---|---|
| **I2C EEPROM `24Cxx`** | 24C01 (128 B) &rarr; 24C1024 (128 KB), AT24Cxx variants, special-addressing **24LC515 / 24LC1025**, or a **custom size**. Auto **size detect**. |
| **SPI NOR flash `25xx`** | Standard JEDEC command set (works with most W25Qxx, GD25Qxx, MX25Lxx, 25QHxxx-style parts). **JEDEC ID detection** with a built-in chip database, automatic **4-byte addressing above 16 MB**, or manual size / page size. |

Not supported: 1.8 V chips without a level-shifter adapter, SPI **NAND**, Microwire `93Cxx`, and parallel flash.

## Quick start

1. **Install** the APK from the [latest release](https://github.com/YOUR_GITHUB_USERNAME/AndroProgX/releases/latest) (allow *Install unknown apps* if asked).
2. Plug the **CH341A** (USB `1A86:5512`, programmer mode) into the phone through an **OTG adapter**. AndroProgX opens automatically.
3. Choose **SPI Flash (25xx)** or **I2C EEPROM (24Cxx)** in the **Mode** picker and tap **Connect**.
4. Tap **Detect Chip (JEDEC ID)** (SPI) or pick the chip / use **Detect** (I2C).
5. **Read** &rarr; **Dump** a backup &rarr; **Erase** &rarr; **Load/Write** your file &rarr; **Verify**.

**Requirements:** Android 8.0+ (API 26) and a phone with **USB host (OTG)** support.

## Recovering a bricked modem, router or camera (step by step)

The same recipe works for most devices whose firmware lives in an SPI flash chip.

### 1. Find the chip
Open the device and look for an **8-pin chip** (SOIC-8 / WSON-8) marked `W25Q…`, `GD25Q…`, `MX25L…`, `EN25Q…`, `XM25Q…`, `BY25Q…`, etc. Note the marking and, if you can, read its datasheet (capacity and **voltage: 3.3 V vs 1.8 V**).

### 2. Prepare safely
- **Unplug the device completely** (power adapter, battery, PoE). Never program a powered board.
- Use a **SOIC-8 test clip** to program **in-circuit**. If readings are unstable because the rest of the board is waking up and driving the bus, **desolder the chip** or hold the main SoC in reset.
- Check your CH341A board's **logic level** (see *Hardware safety* below).

### 3. Wire it (SOIC-8 SPI flash)

```
        ┌──────U──────┐
   CS  1│             │8  VCC (3.3 V)
   DO  2│  25xx SPI   │7  HOLD  (tie to VCC)
   WP  3│    FLASH    │6  CLK
  GND  4│             │5  DI
        └─────────────┘
```

| Chip pin | Signal | CH341A (25xx socket / clip) |
|---|---|---|
| 1 | /CS | CS |
| 2 | DO (MISO) | MISO / DO |
| 3 | /WP | VCC (or as on the adapter) |
| 4 | GND | GND |
| 5 | DI (MOSI) | MOSI / DI |
| 6 | CLK | CLK |
| 7 | /HOLD | VCC (or as on the adapter) |
| 8 | VCC | **3.3 V** |

Using the CH341A's built-in 25xx socket or the clip adapter that ships with it already gives you this mapping &mdash; just match **pin 1** of the chip to **pin 1** of the clip (the red wire).

### 4. Detect
In AndroProgX: **Mode &rarr; SPI Flash (25xx) &rarr; Connect &rarr; Detect Chip (JEDEC ID)**. You should see the manufacturer, part name and size.
`FF FF FF` or `00 00 00` means bad contact, wrong voltage or the board is interfering &mdash; re-seat the clip before going further.

### 5. Back up first (always)
**Dump** the chip and save the file. Read it a **second time** and use **Verify** / **Hex Compare** to confirm both reads are identical. Keep this file safe: it contains the device's **MAC address, serial number and calibration/radio data** that no generic firmware has.

### 6. Erase &rarr; write &rarr; verify
1. **Erase Chip** &mdash; Write sends the buffer straight to the chip (page program) without erasing, so the target area must already be blank.
2. **Load File** the correct firmware image (a **full-flash image** matching the chip size, from the vendor or a working identical unit &mdash; check the size in the hex editor).
3. **Write**, then **Verify**. PASS means the chip matches the buffer byte for byte.
4. If the device had unique data (MAC, calibration/ART, serial), restore those bytes from your backup in the hex editor before writing &mdash; the built-in **Find / Replace** and byte editing help here.

### 7. Reassemble and boot
Remove the clip, reconnect the device and power it. A recovered modem/router usually starts its normal boot or its recovery web page / TFTP mode.

### Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| ID reads `FF FF FF` / `00 00 00` | Poor clip contact, pin 1 reversed, chip not powered, or board powering up and fighting the bus &mdash; desolder or hold SoC in reset |
| Detects, but **Verify fails** | Unstable contact, or the board is partly powered. Re-seat, shorten wires, try again |
| Write runs but device still won't boot | Wrong image (not a full-flash dump), wrong size, or unique data (MAC / calibration) missing &mdash; restore from backup |
| Chip not recognised | It may be a different family (NAND, 93Cxx, 1.8 V). Set size/page manually for unknown 25xx parts |
| Phone doesn't open the app on plug-in | Check the OTG cable/adapter and that the board is in CH341A **programmer** mode (not UART) |

## Hardware safety (read this)

- **Logic level:** many cheap black CH341A boards drive data lines at **5 V** even when VCC is set to 3.3 V. That can **damage 3.3 V chips and the SoC beside them**. Measure with a multimeter, or use a board / adapter that is truly 3.3 V (and a 1.8 V adapter for 1.8 V chips such as many modern BIOS chips).
- **Never** program a chip while the device is powered or the battery is connected.
- **Always back up** before erasing or writing &mdash; it is the only way to get unique per-device data back.
- Dumps can contain **Wi-Fi passwords, keys and personal data** &mdash; do not upload them publicly.
- The phone's USB port supplies power: use a good OTG cable and keep the phone charged.

## I2C EEPROM (24Cxx) workflow

1. **Mode &rarr; I2C EEPROM (24Cxx)**, select the chip (or **Detect** to probe the real capacity) and **Connect**.
2. **Read** into the hex editor, or **Dump** to read and save to a file in one tap.
3. Edit bytes in place, **Load File**, **Write** (page by page), then **Verify**.
4. **Erase** fills the chip with `0xFF`; **Write Random** and **Write Txt** help with test patterns and quick text writes.

Typical wiring (DIP-8 / SOIC-8): `1-3 = A0-A2 (GND)`, `4 = GND`, `5 = SDA`, `6 = SCL`, `7 = WP (GND to allow writes)`, `8 = VCC`. Use **in-circuit** programming only when the host board is unpowered.

## Hex tools

- 16 bytes per row: **offset | hex | ASCII**; tap any byte to edit it.
- **Hex Compare**: chip vs file side by side with diff highlighting and **CRC32**.
- **Find / Replace** in UTF-8, Hex, ASCII or Decimal, forwards or backwards.
- **Load File / Save File** independent of the chip; **Verify** after every write.

## Advanced: I2C / SPI over TCP (Termux, scripts)

Start the **TCP Server** page (default ports **3334** I2C, **3335** SPI) and any device on the network &mdash; or **Termux on the same phone** &mdash; can talk to the chip. Handy for scripting and for probing I2C devices that aren't plain EEPROMs:

```bash
python tools/ch341-bridge-termux i2c write-read 0x50 0000 16     # read 16 bytes of a 24Cxx
python tools/ch341-bridge-termux spi write-read 9F 3             # read the JEDEC ID of a 25xx
python tools/ch341-bridge-termux status i2c                      # is the port listening?
```

The protocol is documented at the top of [`tools/ch341-bridge-termux`](tools/ch341-bridge-termux).

> AndroProgX also includes a small SSD1306 I2C OLED mode (text, images, clock) for bench experiments, but the app is built around EEPROM and flash work.

## Build it yourself

### Option 1 &mdash; Google Colab (no PC needed)
Click the badge, then **Runtime &rarr; Run all**. The notebook clones this repo, installs JDK 17, the Android SDK and Gradle 8.7, generates a signing key, builds **debug + release** APKs and downloads them (plus the keystore). About 5&ndash;10 minutes.

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/YOUR_GITHUB_USERNAME/AndroProgX/blob/main/colab/Build_AndroProgX.ipynb)

> Keep the downloaded keystore and password so later builds can upgrade over the one you installed.

### Option 2 &mdash; Android Studio
Open the folder, let Gradle sync, run on a phone with USB host. `minSdk 26` &middot; `compileSdk/targetSdk 35` &middot; Kotlin &middot; JDK 17 &middot; Gradle 8.7 &middot; AGP 8.5.2.

### Signed release
`app/build.gradle` signs the release build when `ANDROPROGX_KEYSTORE`, `ANDROPROGX_KEYSTORE_PASSWORD`, `ANDROPROGX_KEY_ALIAS` and `ANDROPROGX_KEY_PASSWORD` are set (the notebook sets them). Without them `assembleRelease` yields an unsigned APK that Android refuses to install.

## Publish to GitHub from Termux

[`tools/github_sync.py`](tools/github_sync.py) pushes, pulls and publishes releases (APK attached) with only Python + git:

```bash
pkg install git python
python github_sync.py init                 # create repo + first push
python github_sync.py push -m "message"    # commit and push
python github_sync.py pull                 # get latest changes
python github_sync.py release --tag v1.0   # GitHub Release with the APK
```

## Project structure

```
AndroProgX/
├── app/src/main/java/com/androprogx/app/
│   ├── MainActivity.kt        UI, hex editor, mode switching
│   ├── ProgrammerService.kt   Keeps USB + TCP alive in the background
│   ├── ch341/                 CH341A USB transport + SPI / I2C stream protocols
│   ├── eeprom/                24Cxx + 25xx logic and chip databases
│   ├── display/               SSD1306 driver
│   ├── programmer/            Controller, I2C-over-TCP and SPI-over-TCP servers
│   ├── ui/                    Hex editor grid
│   └── util/                  Helpers
├── colab/Build_AndroProgX.ipynb   Colab build notebook
├── tools/ch341-bridge-termux      Termux client for the TCP bridge
├── tools/github_sync.py           Termux push / pull / release helper
└── NOTICE.md                  Third-party attributions
```

## Permissions

| Permission | Why |
|---|---|
| USB host | Talk to the CH341A |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | TCP servers and showing the phone's IP |
| `FOREGROUND_SERVICE` (+ connected device), `POST_NOTIFICATIONS` | Keep a long read/write alive in the background |

Files are opened and saved through Android's file picker, so no storage permission is needed.

## Project status &amp; disclaimer

The protocol logic follows known-working reference implementations (flashrom and IMSProg) and the code has been through real Gradle build/install cycles. It was written without a physical CH341A attached, so treat chip-level behaviour as carefully reviewed rather than hardware-verified, and **keep a backup before every write**. The software is provided as is, without warranty; you are responsible for any device you program.

## License &amp; credits

Copyright &copy; 2026 **Yassine Moukil &mdash; ELECTRO-GAMMA**. All rights reserved.

Parts of the app are ported from third-party projects &mdash; protocol logic from the GPL-licensed **flashrom** and **IMSProg**, BSD-licensed code from **Adafruit's SSD1306 / GFX** libraries, and two bundled pixel fonts. They are documented in [`NOTICE.md`](NOTICE.md); those licences apply to those parts, so check them before redistributing.