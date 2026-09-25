# Samsung Shizuku bridge implementation plan

Goal: submit ColorBlendr's internally generated palette to Samsung's wallpaper
theme engine using the existing Shizuku connection, with bounded verification,
recovery, and no external process or fabricated overlays.

Spec: the user's Galaxy S25 / Android 16 / One UI 8.5 requirements.
Execution: inline, in the freshly cloned checkout and dedicated fix branch.
The user explicitly requests implementation without approval gates.

1. Audit completed: ColorUtil returns six rows of thirteen, matching
   systemPaletteNames; row 5 is error and is not part of Samsung's 65 colors.
   ColorModifiers consumes per-shade prefs, secondary/tertiary overrides are
   upstream. Custom/community themes are preference bundles, not another engine.
2. Add JVM tests for mapping, eligibility, shell quoting, transactional failure,
   overlay recovery, concurrency, reset and wallpaper callback deduplication.
   Keep the core Android-independent so tests can run without an Android SDK.
3. Implement SamsungPaletteTransaction and its injected settings/overlay gateway,
   backup store, delay and logger. Validate before writes; preserve an original
   backup; restore on error; never touch the independently managed Google array.
4. Add checked shell execution to BOTH Shizuku implementations and AIDL.
   Integrate SamsungShizukuPaletteBridge before the legacy secure JSON branch.
   Serialize apply/remove with a coroutine mutex and move them to IO.
5. Deduplicate unchanged wallpaper callbacks with an atomic fingerprint, retaining
   real wallpaper changes and night-mode updates. Add timestamped diagnostics to
   PreviewController, BroadcastListener and OverlayManager.
6. Run JVM tests, Android test/lint/build tasks; resolve environment failures where
   possible. Add CI for the current branch (existing workflow builds fdroid).
7. Review the final diff, document actual results and device-only uncertainties in
   SAMSUNG_ONEUI_SHIZUKU_BRIDGE_REPORT.md, commit, and deliver build artifacts if built.

Review focus: cancellation after state=0; dead Shizuku binder during recovery;
missing SystemUI overlay; delayed identical callbacks; real wallpaper change
during an apply; reset after process restart; system user versus work profile.

Decisions: supported Samsung route does not write secure theme JSON, does not
disable any overlay, and verifies/enables required Samsung overlays. It uses the
same generated tonal palette as the live preview, not framework lookups. The 65
tones cannot encode every root-only Material role override; document this limit.
