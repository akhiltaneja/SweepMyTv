#!/usr/bin/env python3
"""Find the configured Sweep TV test device and report its current ADB state."""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time


HERE = Path(__file__).resolve().parent
CONFIG = HERE / ".tv-device.json"
HOME_ACTIVITY = "com.sweepmytv.launcher/.HomeActivity"
BOOT_HELPER = "com.google.android.tvlauncher/com.google.android.tvlauncher.BootHelperEmptyActivity"


def adb_output(adb, *args, timeout=4):
    try:
        return subprocess.run(
            [adb, *args], capture_output=True, text=True, timeout=timeout, check=True
        ).stdout.strip()
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return ""


def advertised_targets(listing, serial):
    for line in listing.splitlines():
        columns = line.split()
        if len(columns) != 3:
            continue
        name, service, endpoint = columns
        if service.rstrip(".") not in {"_adb._tcp", "_adb-tls-connect._tcp"}:
            continue
        if name == f"adb-{serial}" or name.startswith(f"adb-{serial}-"):
            yield endpoint


def find_tv(adb, serial, last_address):
    for attempt in range(3):
        devices = dict(
            (fields[0], fields[1])
            for line in adb_output(adb, "devices").splitlines()[1:]
            if len(fields := line.split()) >= 2
        )
        discovered = advertised_targets(adb_output(adb, "mdns", "services"), serial)
        candidates = dict.fromkeys([*discovered, last_address, serial])
        for target in candidates:
            if not target:
                continue
            if target != serial and devices.get(target) != "device":
                adb_output(adb, "connect", target, timeout=3)
            identity = adb_output(adb, "-s", target, "shell", "getprop", "ro.serialno")
            if identity.splitlines()[-1:] == [serial]:
                return target
        if attempt < 2:
            time.sleep(1.5)
    return None


def remember_address(config, target):
    if ":" not in target or target == config.get("last_address"):
        return
    config["last_address"] = target
    with tempfile.NamedTemporaryFile(mode="w", dir=HERE, prefix=".tv-device-", suffix=".tmp", delete=False) as output:
        temporary = Path(output.name)
        json.dump(config, output, indent=2)
        output.write("\n")
    temporary.replace(CONFIG)


def tv_state(adb, target):
    output = adb_output(adb, "-s", target, "shell",
                        "dumpsys window | grep mCurrentFocus; dumpsys power | grep mWakefulness=")
    focus = re.search(r"mCurrentFocus=.*? u\d+ ([^\s}]+)\}", output)
    foreground = focus.group(1) if focus else None
    wakefulness = re.search(r"mWakefulness=(\w+)", output)
    power = wakefulness.group(1) if wakefulness else None
    if foreground == "com.sweepmytv.launcher/com.sweepmytv.launcher.HomeActivity":
        foreground = HOME_ACTIVITY
    return foreground, power


def playback_idle(adb, target):
    media = adb_output(adb, "-s", target, "shell", "dumpsys", "media_session")
    if not media:
        return False
    states = re.findall(r"PlaybackState \{state=(\d+)", media)
    return all(state in {"0", "1", "2", "7"} for state in states)


def wake_idle_tv(adb, target, foreground, power):
    if power != "Dreaming" or not foreground or not foreground.endswith(":dream"):
        return foreground, power
    if not playback_idle(adb, target) or tv_state(adb, target) != (foreground, power):
        return foreground, power
    adb_output(adb, "-s", target, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    for _ in range(5):
        time.sleep(.4)
        foreground, power = tv_state(adb, target)
        if power == "Awake" and foreground:
            break
    if power == "Awake" and foreground == BOOT_HELPER and playback_idle(adb, target):
        if tv_state(adb, target) == (BOOT_HELPER, "Awake"):
            adb_output(adb, "-s", target, "shell", "am", "start", "-W",
                       "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
                       "-n", HOME_ACTIVITY, timeout=8)
            foreground, power = tv_state(adb, target)
    return foreground, power


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wake-idle", action="store_true",
                        help="Dismiss an idle screen saver when preparing an authorized TV update.")
    args = parser.parse_args()
    try:
        config = json.loads(CONFIG.read_text())
        serial = config["serial"]
        if not isinstance(serial, str) or not serial.strip():
            raise ValueError("Missing TV serial")
        last_address = config.get("last_address")
        if last_address is not None and not isinstance(last_address, str):
            raise ValueError("Invalid last ADB address")
        adb = shutil.which(str(HERE.parent / "tools/platform-tools/adb")) or shutil.which("adb")
        if adb is None:
            raise ValueError("ADB is unavailable on this Mac")
    except (OSError, KeyError, TypeError, ValueError) as error:
        print(json.dumps({"reachable": False, "status": "configuration_error", "error": str(error)}))
        return 2

    target = find_tv(adb, serial, last_address)
    if target is None:
        print(json.dumps({"reachable": False, "status": "unreachable"}))
        return 1

    remember_address(config, target)
    foreground, power = tv_state(adb, target)
    if args.wake_idle:
        foreground, power = wake_idle_tv(adb, target, foreground, power)
    print(json.dumps({
        "reachable": True,
        "address": target,
        "foreground": foreground,
        "wakefulness": power,
        "ready_for_update": foreground == HOME_ACTIVITY and power == "Awake",
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
