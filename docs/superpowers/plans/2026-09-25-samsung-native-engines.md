# Samsung authoritative palette engines implementation plan

> Execute inline using superpowers:executing-plans. User explicitly requests direct implementation of the native → fabricated → error architecture; no additional approval gate.

**Goal:** Replace the settings-only Samsung apply route with verified engine calls.
**Architecture:** A JVM coordinator serializes native/fabricated transactions and durable rollback. A Shizuku UserService resolves OEM Binder methods from the boot class loader, clears incoming Binder identity to its own shell identity, probes permissions and performs engine operations. MAIN stays the internal generator output. GG is independently generated using Samsung's ColorScheme/ColorPalette semantics, with identical app tuning and explicit overrides. Resource verification is backend-specific and bounded to ten seconds.
**Tech stack:** Kotlin, existing AIDL Shizuku UserService, reflection on firmware classes, SharedPreferences journal, JUnit/Robolectric, GitHub Actions.
**Spec:** User's current request, including no root, no daemons, no MAIN→GG copy, no hardcoded Binder transaction, no modifications to non-Samsung/root flow; forced reinstall authorized without backup.

## Work
- [ ] Prove transport contracts with fake native/fabricated engines: allowed/denied/no-op, stale resources, fallback only after verified rollback, reset, concurrency and pending recovery.
- [ ] Implement engine coordinator and separate durable journal; leave existing diagnostic regression suite intact.
- [ ] Add append-only AIDL calls for capability, OEM GG generation, snapshot, native apply/restore and fabricated apply/cleanup. Resolve firmware interfaces reflectively; no AOSP transaction indices.
- [ ] Generate full SystemUI mapping from firmware MetaDataManager/TemplateManager and runtime ThemePalette; no two-resource approximation. Generate framework 65 from internal generator for fabricated backend.
- [ ] Capture native pair/state/overlay enablement and resolved probes before mutation. Native rollback uses applyWallpaperColor, never settings writes. Fabricated ownership limited to ColorBlendr IDs; persist its prior payload for replacement/reset.
- [ ] Integrate native-first selection in Samsung bridge; verification checks state, both native arrays, all five families and QS resources. Exclude old settings-only apply.
- [ ] Test GG tuning/overrides independently with injected OEM generator. Preserve all existing tests. Test malformed input, cleanup refusal, no-op native, missing OEM API/style, stale replacement and cross-instance concurrency.
- [ ] Review, run core tests, commit/push, run CI tests+assembleDebug and download/hash artifact.
- [ ] Deliver one reinstall script using adb uninstall → adb install -g → pm path. Execute only if physical device is connected.

## Review focus
- Shell denial must not turn into success or unauthorized identity escalation.
- Native void return is not evidence of mutation; verify canonical pair and resolved resources.
- OEM-specific styles missing at runtime must not silently map to another style.
- Failed restoration must preserve journal and prevent another backend from obscuring the failure.
- A stale old overlay owned by this app must be replaced/removed without touching SemWT ownership.
