# Third-Party Notices

WayLandIE source, scripts, and docs are published separately from vendor driver
packages, Steam, Proton, games, and Android system components.

## Not Included

Do not commit these into this repository:

- Qualcomm Adreno Linux driver packages or extracted driver rootfs trees.
- Android Vulkan driver blobs loaded through AdrenoTools.
- Steam client files, Proton builds, game files, shader caches, or compatdata.
- DXVK or Turnip binary builds unless their license allows redistribution and
  the build is intentionally added with matching notices.

Private bundles produced by `export-steam-arm64-bundle.sh` are local artifacts.
They are meant to help you reproduce your own working container on your own
devices, not to publish Steam, Proton, games, account data, or proprietary
drivers in this repository. Those private bundles may include locally imported
Qualcomm Adreno Linux driver slots when they already exist in your container.

## User-Supplied Components

The driver and runtime import scripts expect the user to provide legally
obtained packages. The scripts only arrange local paths and profile metadata.

## Android and Linux Dependencies

Android SDK, Android NDK, Termux packages, Debian packages, Mesa, Vulkan tools,
MangoHud, FEX, Proton, DXVK, and Steam remain under their own licenses.
