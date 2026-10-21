<!--
  Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
  Author: Yassine Moukil ELECTRO-GAMMA
-->
# NOTICE

This project is otherwise original work, but includes protocol logic
ported from two GPL-licensed C projects, some BSD-licensed code and
data, and two bundled third-party font files. This file documents what
was ported from where, per those licenses' attribution expectations.
It's a factual provenance note, not a legal opinion — if you plan to
redistribute this app, check the actual license terms yourself.

## Ported code

**`app/src/main/java/com/androprogx/app/ch341/Ch341Spi.kt`**
Protocol logic (`config_stream()`, `enable_pins()`,
`ch341a_spi_send_command()`, `swap_byte()`, and the packet-framing
constants) ported from **flashrom**'s `ch341a_spi.c`
(https://github.com/flashrom/flashrom), licensed GPL-2.0-or-later.

**`app/src/main/java/com/androprogx/app/ch341/Ch341I2c.kt`** and
**`app/src/main/java/com/androprogx/app/eeprom/I2cEeprom.kt`**
I2C stream command-byte protocol and the 24Cxx device-address encoding
scheme (the `algorithm` byte, and how extra address bits fold into the
device-address byte for chips like 24C04/08/16, plus the 24LC1025/24LC515
variants that fold their extra bit into device-address bit 2 instead of
bit 0) ported from **IMSProg**'s `ch34x_i2c.c`
(`ch34xi2cBlockRead`/`ch34xi2cBlockWrite`)
(https://github.com/inexbee/IMSProg — this project was built from
IMSProg 1.8.6), licensed GPL-3.0-or-later.

**`app/src/main/java/com/androprogx/app/eeprom/SpiFlash.kt`** (partial)
Most of this file is original (see below), but `unprotect()` (clearing
the status register's block-protect bits before erase/write) and the
4-byte-addressing enter/exit commands in `configureAddressing()` are
ported from IMSProg's `spi_nor_flash.c` (`snorUnprotect()` /
`snor_4byte_mode()`'s generic 0xB7/0xE9 path — the Winbond/Spansion
vendor-specific quirks in that function were not ported), licensed
GPL-3.0-or-later.

**`app/src/main/java/com/androprogx/app/eeprom/SpiFlashDatabase.kt`**
The manufacturer-ID table and the exact part names for the EON/XMC
"25QH" family, Winbond W25Q, and GigaDevice GD25Q chips are extracted
from IMSProg's chip database (`IMSProg.Dat`), licensed GPL-3.0-or-later.
The JEDEC size-code-to-bytes formula itself is a public JEDEC convention,
not IMSProg-specific.

## Ported code (BSD-licensed)

**`app/src/main/java/com/androprogx/app/display/Ssd1306.kt`**
The SSD1306 init sequence itself is standard across the chip's datasheet
and every SSD1306 driver library, and the word-wrap/framebuffer/flush
logic is original. But the per-panel-size (width *and* height, not just
height) COM-pin/contrast table in `panelConfig()`, the 64-wide-panel
column-address offset in `flush()`, and the hardware scroll commands
(`startScrollRight`/`Left`/`DiagRight`/`DiagLeft`/`stopScroll`) were
pulled from **Adafruit's official SSD1306 Arduino library**
(`Adafruit_SSD1306.cpp`, https://github.com/adafruit/Adafruit_SSD1306),
and `FONT_5X7` is Adafruit_GFX's classic `glcdfont.c` "Standard ASCII 5x7
font" (https://github.com/adafruit/Adafruit-GFX-Library) - both
BSD-licensed, Copyright (c) 2012 Adafruit Industries, replacing this
file's earlier hand-rolled uppercase/digits-only font and height-only
contrast bucketing (which had a real bug: it silently gave 96x16 panels
128x32's contrast value instead of their own).

## Original code

Everything else in the app is original to this project,
written from public/standard specifications rather than any GPL source:

- **`app/src/main/java/com/androprogx/app/ch341/Ch341UsbDevice.kt`** -
  the raw Android `UsbDeviceConnection` transport (bulk transfers,
  permission flow). IMSProg/flashrom talk to the same chip via `libusb`
  on desktop OSes; this uses Android's own USB host API instead, so
  there's no meaningful code to port at that layer.
- **`app/src/main/java/com/androprogx/app/eeprom/SpiFlash.kt`** (the
  rest of it) - the SPI NOR flash command set (JEDEC ID, page program,
  sector/chip erase, status polling) is the industry-standard
  instruction set used identically across virtually every 25xx-series
  flash chip regardless of manufacturer, not something specific to
  either GPL source.
- **`app/src/main/java/com/androprogx/app/eeprom/I2cEeprom.kt`**'s
  `erase()` and `detectSize()` are this project's own additions, not
  ported from IMSProg (IMSProg has no equivalent capacity-detection
  feature for I2C EEPROMs - it relies on the user picking the chip from
  its database). "Write Random" (for both the I2C and SPI panels) isn't
  a separate ported/original function at all - `MainActivity` just
  generates a random buffer and reuses the same write path as a normal
  write, which is also what makes the hex editor able to show exactly
  what got written afterward.
- **`app/src/main/java/com/androprogx/app/ui/HexEditorAdapter.kt`** -
  a plain offset/hex/ASCII grid (`RecyclerView`-backed, one row per 16
  bytes, tap a byte to edit it) built for this app's own UI toolkit, not
  a port of IMSProg's Qt-based `QHexEdit` widget - it's the same general
  idea (and the same layout convention: offset | hex | ASCII) but none
  of the widget code itself.
- **`app/src/main/java/com/androprogx/app/display/OledCompositor.kt`**
  and the clock/stats-dashboard/animation additions to
  `ProgrammerController.kt` - original code. The clock/stats screens are
  conceptually similar to the classic Python `clock.py`/`stats.py`
  reference scripts this project's SSD1306 support was originally
  cross-checked against (same idea: an analog clock face, a battery/RAM/
  storage dashboard), but nothing was ported from them - the graphics
  are drawn with Android's `Canvas`, the phone-stats readings come from
  Android's own `BatteryManager`/`ActivityManager`/`StatFs` APIs (a
  phone has no CPU-temperature sensor API the way the Pi scripts assumed),
  and the animation loop is this project's own take on the same idea as
  `pbm_gif_all.py` (loop over a folder of frames) adapted to
  Android-picked image files instead of a folder of `.pbm`s.
- **`app/src/main/java/com/androprogx/app/programmer/ProgrammerController.kt`**
  and the rest of the app's UI code in `MainActivity.kt` / `panel_programmer.xml`
  are original glue code specific to this app.

## Bundled font assets

**`app/src/main/assets/fonts/alagard.ttf`** and
**`app/src/main/assets/fonts/PixelOperator.ttf`** are third-party font
files bundled as-is (not modified), offered as one-tap "Custom Font"
options alongside picking any font from the phone. They're commonly
distributed as free-for-personal-and-commercial-use pixel fonts -
Alagard by Brian Kent (Vault42) and PixelOperator by Jayvee Enaguas
(HarvettFox96) - but this project didn't originate them and isn't the
place to take a license's exact terms on faith: if you plan to
redistribute this app, confirm the license of the specific copies of
these two files yourself.
