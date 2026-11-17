# Install SweepMyTV

[Android phone](#install-from-an-android-phone) · [Laptop](#install-from-a-laptop) · [Claude Code](#install-with-claude-code) · [First launch](#first-launch)

SweepMyTV requires an **Android TV or Google TV device running Android 8.0 or newer** that allows APK installation. Check **Settings → About** on the device receiving the app. An Android TV streaming box connected to a Samsung or LG television can qualify even when the television itself runs a different OS.

Start with the [signed preview release](https://github.com/akhiltaneja/SweepMyTv/releases/tag/v1.3.0-sweep.25). Download `sweep-tv.apk` and `SHA256SUMS` from its Assets section. The release page describes the exact build available to install.

## Install from an Android phone

1. Download `sweep-tv.apk` to your phone. Install [Send Files to TV](https://play.google.com/store/apps/details?id=com.yablio.sendfilestotv) on your phone and TV.
2. Connect both devices to the same Wi-Fi. Open Send Files to TV on the TV and choose **Receive**. On the phone, choose **Send**, select the APK, and pick your TV.
3. Open the received APK with the TV’s file manager or directly from the transfer app if it offers that option. If Android asks, allow installation from the app opening the file. Choose **Install**, then open Sweep TV from the TV’s Apps screen.

If your TV supports USB drives, you can put the APK on a drive and open it with the TV’s file manager. Use that route or the laptop method if you have an iPhone.

## Install from a laptop

1. Download and extract [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) for macOS, Windows, or Linux. Open a terminal in the extracted directory. The examples use `adb`. Use `./adb` on macOS or Linux, or `.\adb.exe` in PowerShell if it is outside your PATH.
2. Verify the APK’s SHA-256 against the matching line in `SHA256SUMS` before installing. Run `shasum -a 256 sweep-tv.apk` on macOS, `sha256sum sweep-tv.apk` on Linux, or `Get-FileHash .\sweep-tv.apk -Algorithm SHA256` in PowerShell. Run the check from the APK’s download folder or use its full path.
3. Put the laptop and TV on the same Wi-Fi. On the TV, open **Settings → About** and select **Build** seven times, or until Developer options appear. Enable **Wireless debugging** or the **Network/ADB debugging** option your TV provides.

### Connect to the TV

Use the address and port shown on your TV. The values below are examples.

**If the TV shows a pairing code**, select **Pair device with pairing code** and use the address from that dialog. When pairing finishes, use the connection address from the main Wireless debugging screen, which may have a different port.

```sh
adb pair 192.168.1.100:37123
adb connect 192.168.1.100:42189
```

**If the TV offers network debugging without a pairing code**, use its debugging address and accept its authorization prompt when shown.

```sh
adb connect 192.168.1.100:5555
```

Check that your TV appears as `device` under `adb devices -l`. See [Android’s wireless debugging instructions](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi) if your menu differs.

### Install the APK

Replace the device address with the entry from `adb devices -l`. Use the full path to the APK if it is in a different folder.

```sh
adb -s 192.168.1.100:5555 install -r sweep-tv.apk
```

Wait for `Success` and open Sweep TV from Apps. The `-r` flag preserves data when updating an existing copy signed with the same key. If Android reports a signing conflict, check how the existing copy was installed before removing it. Disconnect the session and turn off TV debugging when you are finished with it.

## Install with Claude Code

Use [Claude Code](https://code.claude.com/docs/en/quickstart) or another coding assistant with a local terminal on the laptop connected to the TV’s Wi-Fi.

1. Download the APK and checksum to a folder on your laptop. Enable TV debugging using the steps [above](#install-from-a-laptop).
2. Open Claude Code in that folder. On first launch, follow its sign-in instructions.
3. Copy the prompt below. Replace the TV model and address, then paste it into the local Claude Code session. If your TV uses a pairing code, keep the dialog open until the assistant requests it.

```text
Help me install the official SweepMyTV preview on my own TV.

TV brand and model: <your TV model>
TV debugging address: <IP address and port shown on the TV>
APK: sweep-tv.apk in this folder
Checksum file: SHA256SUMS from the same SweepMyTV GitHub release
Expected package: com.sweepmytv.launcher

1. Confirm this device runs Android TV or Google TV on Android 8.0 or newer. Use the Android Platform Tools already on this computer. If they are missing, ask before installing them.
2. Verify the APK's SHA-256 against the release checksum. Stop if the checksum is missing or differs.
3. Connect only to the TV address I give you. If wireless pairing is required, ask me for its pairing address and code. Confirm the connected model before installing.
4. Install the APK, preserving settings when updating a copy signed with the same certificate. If there is a signing conflict, explain it before removing anything. Keep the stock launcher and my streaming apps installed.
5. Help me open Sweep TV and test the dock, physical Home button, and a streaming app. If the TV needs extra access for a feature, explain it and ask before changing any permission, Home setting, or remote mapping.
6. Leave optional cleanup, firmware, and networking settings alone. Tell me what worked and disconnect from ADB when we're done unless I ask to keep it connected.
```

## First launch

- **Open Sweep TV** from Apps. If your TV offers a **Home app** picker, use it to select Sweep TV. If the physical Home button still goes to stock Home, review **Finish setup → Home button** and enable the optional helper only if needed.
- **Set up Continue Watching** by reviewing **Notification access** and **Read TV listings** in Finish setup if they are available on your TV. What appears depends on the apps that share playback information.
- **Make it yours.** Choose your dock apps and theme in Sweep TV settings. You can install and use the launcher without a catalog key. The available discovery options depend on your build.
- **Check your TV.** Try the physical remote, streaming playback, and any inputs you use. [Report your TV](https://github.com/akhiltaneja/SweepMyTv/issues/new?template=tv-test.yml) with its model and firmware if you can share your results.

See the [troubleshooting notes](../README.md#updates-and-troubleshooting) for installation or remote problems, and the [recovery guide](../README.md#optional-cleanup-and-recovery) to return to stock Home.
