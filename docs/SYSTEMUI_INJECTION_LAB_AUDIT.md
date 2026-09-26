# SystemUI Injection Lab — initial device audit

Audit date: 2026-09-26. All checks below were read-only and ran on the connected
Galaxy S25 through ADB shell UID 2000. The probe built into ColorBlendr repeats
these checks through its Shizuku UserService before an apply is attempted.

| Check | Observed result |
| --- | --- |
| Model / Android | SM-S931B, Android 16, API 36 |
| One UI build property | ro.build.version.oneui=80500 |
| Shell identity | uid=2000(shell) |
| Shizuku | Shizuku app and server process present; ColorBlendr UserService ran as shell |
| cmd overlay fabricate --help | Rejected: Error: must be root to fabricate overlays through the shell |
| theme_customization_overlay_packages | Secure setting is currently empty |
| settings get system accent_color | No value returned |
| Android accent lookup | android:color/system_accent1_500 resolved to #ff6476a5 |
| QS active lookup | com.android.systemui:color/qs_tile_round_background_on resolved to #ff79aeeb |
| QS inactive lookup | com.android.systemui:color/qs_tile_round_background_off resolved to #40000000 |
| Volume accent lookup | com.android.systemui:color/volume_seekbar_progress_color resolved to #ff8eaed3 |

Resource lookup proves that these resource names resolve in the current
configuration. It does not prove that shell UID can overlay them. The current
overlay list includes Samsung's SemWT_MonetPalette on android and a dynamic
SystemUI overlay. No palette was written during this audit.

The lab uses ColorBlendr's existing Samsung transaction engine on Samsung. It
tests the firmware-native path and fabricated-overlay fallback only after the
user explicitly confirms Apply; that engine snapshots owned state, verifies
effective colors over time, and rolls back failed applies. On non-Samsung
Android 12+, the lab snapshots theme_customization_overlay_packages before a
write and calls it successful only when the effective accent matches the
generated ColorBlendr palette. If the OEM accepts the setting but the color
does not match, the lab restores the snapshot. A setting changed by another
writer is not overwritten by rollback.

The standalone cmd overlay fabricate path is not treated as available on this
Samsung firmware. Quick Settings direct overlay support remains unproven until
the Samsung engine's confirmed apply path reports verified resources.
