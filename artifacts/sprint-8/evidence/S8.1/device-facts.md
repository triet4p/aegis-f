# S8.1 read-only handset facts

**Observed:** 2026-10-25, after confirming one connected authorized device. No serial/ID, bank data, app data, notifications, account data or log buffers were recorded.

## Commands run

```powershell
adb -d get-state
adb -d shell getprop ro.build.version.release
adb -d shell getprop ro.build.version.sdk
adb -d shell getprop ro.product.cpu.abilist
adb -d shell getconf PAGESIZE
adb -d shell cat /proc/meminfo
adb -d shell getprop ro.product.model
adb -d shell getprop ro.product.manufacturer
adb -d shell getprop ro.hardware
adb -d shell getprop ro.soc.model
adb -d shell getprop ro.config.low_ram
adb -d shell getprop ro.hardware.vulkan
adb -d shell ls /vendor/lib64/hw/vulkan.*
```

## Results

- `get-state`: `device` (authorized connection).
- Android release/API: `14` / `34`.
- ABI list: `arm64-v8a,armeabi-v7a,armeabi`.
- Page size: `4096` bytes.
- Model/manufacturer: `ALT-LX2` / `HONOR`.
- Hardware/SoC property: `qcom` / `SM6225`.
- `ro.config.low_ram`: empty; this is unknown/not declared, not a `false` assertion.
- `ro.hardware.vulkan`: `adreno`; `/vendor/lib64/hw/vulkan.adreno.so` exists.
- `MemTotal`: `7837732 kB`; `MemAvailable` at this observation: `2659192 kB`; `MemFree`: `485216 kB`.

These are capability facts at a single observation, not a memory budget, continuous headroom result, model inference result, proof of OpenCL availability, proof of Vulkan feature-level support, or proof of any LiteRT/NNAPI/vendor-NPU delegate. No `vulkaninfo`, GPU benchmark, NPU probe or new benchmark was run. Existing developer-setup facts match the observations (Android/API, ABI, page size, total RAM, empty low-RAM property, device state); the added facts identify reported SoC and driver library only.

## Safety limits and actions not performed

ADB was used only for shell `getprop`, `getconf`, `/proc/meminfo`, and listing an existing vendor driver library. No installs/uninstalls, app opens, model download/transfer, filesystem mutation, settings/permissions change, or `dumpsys`/logcat access was performed. The zero new trials restriction was preserved.
