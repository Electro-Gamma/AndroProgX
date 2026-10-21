# Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
# Author: Yassine Moukil ELECTRO-GAMMA
# Third-party / ported code attributions: see NOTICE.md

# Keep the USB serial library fully intact
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**
