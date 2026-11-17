#!/usr/bin/env python3
"""Static contract checks for the Android Bluetooth bridge."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = (ROOT / "app/src/main/java/com/trotabares/argentascomandas/MainActivity.kt").read_text()
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
HTML = (ROOT / "app/src/main/assets/index.html").read_text()

checks = {
    "WebView registers the Bluetooth bridge": 'addJavascriptInterface(NativeBridge(), "ArgentasNativeBluetooth")' in KOTLIN,
    "JS server call has a native implementation": "fun startServer()" in KOTLIN and "function waitBT()" in HTML,
    "JS connect call has a native implementation": "fun connect(address: String)" in KOTLIN and "function connectBT()" in HTML,
    "JS send call has a native implementation": "fun send(message: String)" in KOTLIN and "function sync()" in HTML,
    "JS refresh call has a native implementation": "fun refresh()" in KOTLIN and "function refreshBT()" in HTML,
    "native bridge emits Bluetooth state": "window.onBluetoothState" in KOTLIN and "window.onBluetoothState=" in HTML,
    "native bridge emits received messages": "window.onBluetoothMessage" in KOTLIN and "window.onBluetoothMessage=" in HTML,
    "both sides use the same RFCOMM service UUID": "7a1d6c20-4e7b-4c8d-9f12-6d4e7a9b2031" in KOTLIN,
    "Android 12+ connect permission declared": "android.permission.BLUETOOTH_CONNECT" in MANIFEST,
    "legacy Bluetooth permission is capped at Android 11": 'android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"' in MANIFEST,
    "incoming messages have a size limit": "MAX_MESSAGE_CHARS" in KOTLIN,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
print(f"{len(checks)-len(failed)}/{len(checks)} Bluetooth bridge checks passed")
if failed:
    raise SystemExit(1)
