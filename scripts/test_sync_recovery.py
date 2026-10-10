from pathlib import Path

html_path = Path("app/src/main/assets/index.html")
if not html_path.is_file():
    raise SystemExit("Generated Argentas HTML is missing")

html = html_path.read_text(encoding="utf-8")

checks = {
    "ACK is matched by deliveryId, not queue key": "String(item.deliveryId||'')===wanted",
    "status retries can target one delivery": "function flush(targetKey)",
    "retry timer flushes only its own delivery": "flush(key)",
    "missing remote order is not acknowledged": "No confirmar: el emisor debe reintentar hasta que llegue la comanda.",
    "duplicate/stale state snapshot is ACKed": "type:'ack',msgId:m.msgId",
    "status recovery query is type-filtered": "mensaje=like.*%22type%22%3A%22comanda-status%22*",
    "status recovery selects sender device": "select=mensaje,created_at,device_id",
    "status recovery checks row and payload sender": "String(parsed.statusDeviceId||'')!==DEVICE_ID",
    "state recovery ignores own snapshots": "String(row.device_id||'')===DEVICE_ID",
    "remote Caja updates refresh React state": 'window.addEventListener("argentas-caja-sync",refreshCaja)',
    "Caja refresh reads closures from local storage": 'localStorage.getItem("argentas_closures")',
    "Caja refresh updates closure state": "p(closuresValue)",
}

missing = [label for label, marker in checks.items() if marker not in html]
if missing:
    for label in missing:
        print("FAIL:", label)
    raise SystemExit(f"{len(missing)} sync recovery regression check(s) failed")

# Ensure the recovery query uses a bounded result set and the JS was generated.
if "order=created_at.desc&limit=500" not in html:
    raise SystemExit("FAIL: status recovery query is not bounded")
if len(html.strip()) < 1000:
    raise SystemExit("FAIL: generated HTML is unexpectedly small")

for label in checks:
    print("PASS:", label)
print(f"PASS: {len(checks)} synchronization recovery checks")
