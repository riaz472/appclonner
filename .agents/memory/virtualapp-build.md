---
name: VirtualApp Android build fixes
description: All fixes needed to build the legacy VirtualApp Android project in Replit (NDK r16b, Java 11, dependency and source code patches).
---

## Context
Legacy Android project using AGP 7.4.2 / Gradle 7.6.1 / compileSdk 28 / NDK r16b.

## Environment setup
- **JAVA_HOME**: `/nix/store/r9w6q359r1608viyc13bm7a3h65nhxp0-openjdk-11.0.26+4` (JDK 11 via Nix)
- **ANDROID_HOME**: `/home/runner/android-sdk` (SDK platform 28, build-tools 28.0.3 installed here)
- **NDK r16b**: `/home/runner/workspace/android-ndk/android-ndk-r16b` (MUST be in workspace — /home/runner hits quota with SDK already using ~3.6GB)
- **ncurses5** system dep required: NDK r16b's clang++ links against `libncurses.so.5`

## Why NDK r16b is mandatory
`Application.mk` sets `APP_STL := gnustl_static`. gnustl was removed in NDK r18. Do not upgrade NDK without migrating STL to `c++_static`.

## Disk quota quirk
Replit's /home/runner has a ~4GB effective write quota. After SDK install (~3.6GB), only /home/runner/workspace remains writable. NDK must go there.

## Build fixes applied to source code
1. `app/build.gradle` — removed 3 dead JitPack deps (FloatingActionButton, once, SwitchButton); bumped minSdkVersion 15→16 (Flurry requires 16)
2. Created `app/src/main/java/jonathanfinerty/once/Once.java` — local stub for the `once` library using SharedPreferences
3. `lib/.../ActivityManagerStub.java:93` — added `(List)` cast in ternary (Java 11 stricter generics)
4. `lib/.../MethodProxies.java:450,533,1103,1207` — same `(List)` cast fix (4 locations)
5. `app/.../RippleButton.java` — replaced `com.nineoldandroids.*` with `android.animation.*`; replaced `ViewHelper.setAlpha(v, 1)` with `v.setAlpha(1f)`; replaced `Canvas.CLIP_SAVE_FLAG` with plain `canvas.save()`
6. `app/.../VirtualAppsFragment.java:217` — removed `getActivity()` override (final in newer support lib)

**Why:** `android.animation` is available from API 11+; nineoldandroids was only needed for pre-11.
