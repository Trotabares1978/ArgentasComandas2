from pathlib import Path

p = Path("app/src/main/assets/index.html")
if not p.is_file():
    raise SystemExit("Generated Argentas HTML is missing")
html = p.read_text(encoding="utf-8")

checks = {
    "VENDER retry has bounded exponential backoff": "return Math.min(300000,2000*Math.pow(2,exponent))",
    "VENDER attempts persist before transport": "x.attempts=Number(x.attempts||0)+1;x.lastAttemptAt=now;save();",
    "VENDER async transport failures are retained": "result.catch(function(e){x.lastError=",
    "state snapshot ACK waits for resolved transport": "result.then(sent,failed)",
    "state snapshot failures are persisted": "q.lastError=detail;q.lastFailureAt=Date.now();saveSyncQueue()",
    "comanda status ACK matches exact delivery": "String(item.deliveryId||'')===target",
    "comanda status queue persists attempts before transport": "x.attempts=Number(x.attempts||0)+1;x.lastSentAt=Date.now();save();",
    "comanda status async failures are retained": "result.catch(function(e){x.lastError=",
    "status recovery filters type before limit": "mensaje=like.*%22type%22%3A%22comanda-status%22*",
    "status recovery preserves legacy null sender rows": "or=(device_id.is.null,device_id.neq.",
    "status recovery checks payload sender": "String(parsed.statusDeviceId||'')!==DEVICE_ID",
    "state recovery excludes own device": "device_id=neq.'+encodeURIComponent(DEVICE_ID)",
    "Caja UI refreshes remote cash": 'window.addEventListener("argentas-caja-sync",refreshCaja)',
    "Caja UI refreshes closures": 'localStorage.getItem("argentas_closures")',
    "Caja merge emits refresh event": "window.dispatchEvent(new Event('argentas-caja-sync'))",
}
missing = [name for name, marker in checks.items() if marker not in html]
if missing:
    for name in missing:
        print("FAIL:", name)
    raise SystemExit(f"{len(missing)} synchronization integrity checks failed")
for name in checks:
    print("PASS:", name)
print(f"PASS: {len(checks)} synchronization integrity checks")
