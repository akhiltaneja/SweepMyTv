# Install SweepMyTV

[Claude Code or Codex](#install-with-claude-code-or-codex) · [Android or iPhone](#install-from-your-phone) · [Laptop](#install-from-a-laptop) · [First launch](#first-launch)

SweepMyTV requires an **Android TV or Google TV device running Android 8.0 or newer** that allows APK installation. Check **Settings → About** on the device receiving the app. An Android TV streaming box connected to a Samsung or LG television can qualify even when the television itself runs a different OS.

<a name="install-with-claude-code"></a>

## Install with Claude Code or Codex

**Recommended.** A local coding assistant can download the signed SweepMyTV preview, verify its checksum, and install it over Wi-Fi.

1. Put your computer and TV on the same Wi-Fi. [Enable wireless or network debugging on the TV](https://sweepmytv.com/tutorials/wireless-debugging/).
2. Open Claude Code or Codex on your computer. In the ChatGPT desktop app, select **Codex** and start a local session. You can also use one of the other local tools below.
3. Paste the [SweepMyTV setup prompt](../prompt.md). Share the debugging address shown on your TV, approve the connection if asked, and enter any pairing code directly in your terminal. Once Sweep TV opens, follow the [first-launch steps](#first-launch).

### Which AI assistants can I use?

| Local tool | Account options |
| --- | --- |
| [Claude Code](https://code.claude.com/docs/en/quickstart) | Included with paid Claude plans such as Pro and Max. Claude Console billing also works. |
| [Codex CLI](https://learn.chatgpt.com/docs/codex/cli) or [ChatGPT desktop in Codex mode](https://learn.chatgpt.com/docs/app) | Sign in with **ChatGPT Free** or a paid plan. [Usage limits vary by plan.](https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan) |
| [Gemini CLI](https://geminicli.com/docs/get-started/installation/) | [Free tier](https://geminicli.com/plans/) with a personal Google account, subject to usage limits. |
| [GitHub Copilot CLI](https://docs.github.com/en/copilot/get-started/cli-quickstart) | Available with [Copilot Free](https://docs.github.com/en/copilot/get-started/plans) and paid plans. |

**Using ChatGPT or Gemini in a browser?** Paste the same prompt and add “Give me one step at a time and wait for my result.” Run the commands it gives you in your computer’s terminal.

<a name="install-from-an-android-phone"></a>

## Install from your phone

Follow the [phone install guide](https://sweepmytv.com/tutorials/phone/) for Android and iPhone. Download the signed installer, open [LocalSend](https://localsend.org/download) on both your phone and TV, and send the file over the same Wi-Fi. Open the received file on your TV to install SweepMyTV, then follow the [first-launch steps](#first-launch).

## Install from a laptop

1. Download and extract [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) for macOS, Windows, or Linux. Open a terminal in the extracted directory. The examples use `adb`. Use `./adb` on macOS or Linux, or `.\adb.exe` in PowerShell if it is outside your PATH.
2. Download `sweep-tv.apk` and `SHA256SUMS` from the Assets section of the [signed preview release](https://github.com/akhiltaneja/SweepMyTv/releases/tag/v1.3.0-sweep.151). Verify the APK’s SHA-256 against the matching line in `SHA256SUMS`. Run `shasum -a 256 sweep-tv.apk` on macOS, `sha256sum sweep-tv.apk` on Linux, or `Get-FileHash .\sweep-tv.apk -Algorithm SHA256` in PowerShell. Run the check from the APK’s download folder or use its full path.
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

## First launch

Follow the [first-time setup guide](https://sweepmytv.com/tutorials/first-time-setup/) after installing.

- **Open Sweep TV** from Apps. If your TV offers a **Home app** picker, use it to select Sweep TV. If the physical Home button still goes to stock Home, review **System → Finish setup → Home button helper** and enable the optional helper only if needed.
- **Set up Continue Watching** by reviewing **Notification access** and **Read TV listings** in Finish setup if they are available on your TV. What appears depends on the apps that share playback information.
- **Choose your sports.** In **System → Finish setup**, enable Formula 1, Cricket, or both under **Sports to follow**. Both start off when you have no saved choice.
- **Make it yours.** Choose your dock apps and theme in Sweep TV settings. You can install and use the launcher without a catalog key. The available discovery options depend on your build.
- **Check your TV.** Try the physical remote, streaming playback, and any inputs you use. [Report your TV](https://github.com/akhiltaneja/SweepMyTv/issues/new?template=tv-test.yml) with its model and firmware if you can share your results.

See the [troubleshooting notes](../README.md#updates-and-troubleshooting) for installation or remote problems, and the [recovery guide](../README.md#optional-cleanup-and-recovery) to return to stock Home.
