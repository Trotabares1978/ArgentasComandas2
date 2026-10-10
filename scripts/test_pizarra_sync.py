from pathlib import Path

html = Path("pizarra.html").read_text(encoding="utf-8")

checks = {
    "status version is captured before content merge": "var localStatus=c.status,localStatusAt=Number(c.statusUpdatedAt||c.updatedAt||c.createdAt||0)",
    "incoming status has independent version comparison": "var incomingStatusWins=o.status!=null&&(incomingStatusAt>localStatusAt",
    "older snapshot cannot overwrite newer local status": "else{c.status=localStatus;c.statusUpdatedAt=localStatusAt;c.statusDeviceId=localStatusDevice}",
    "only fresh snapshots participate": "var freshCandidates=candidates.filter(function(x){return x.age<=30000})",
    "sessions deduplicate by highest revision": "x.revision>sessions[sid].revision",
    "orders are unioned across active sessions": "x.m.db.orders.forEach(merge)",
    "status fields survive independent reconciliation": "c.updatedAt=Math.max(Number(c.updatedAt||0),Number(o.updatedAt||0),incomingStatusAt,localStatusAt)",
}

missing = [label for label, marker in checks.items() if marker not in html]
if missing:
    for label in missing:
        print("FAIL:", label)
    raise SystemExit(f"{len(missing)} Pizarra regression check(s) failed")

if "var modern=candidates.filter(function(x){return x.modern}),chosen=null;" in html:
    raise SystemExit("FAIL: Pizarra still chooses a single device session")

for label in checks:
    print("PASS:", label)
print(f"PASS: {len(checks)} Pizarra synchronization regression checks")
