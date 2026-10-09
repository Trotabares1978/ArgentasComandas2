from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

router_anchor = "var ArgentasSyncMessageRouter=(function(){'use strict';"
ack_start = "}else if(m.type==='ack'){"
ack_end = "\n  }else if(m.type==='hello'){"
module_marker = "var ArgentasSyncAck=(function(){'use strict';"

if module_marker in html:
    print("Stage 5 ACK extraction already applied")
else:
    if html.count(router_anchor) != 1:
        raise SystemExit("Stage 5 router anchor is not unique; refusing to modify generated app")
    if html.count(ack_start) != 1 or html.count(ack_end) != 1:
        raise SystemExit("Stage 5 ACK handler anchors are not unique; refusing to modify generated app")
    a = html.index(ack_start)
    b = html.index(ack_end, a)
    original = html[a:b]
    expected = "if(reliablePending&&m.msgId&&m.msgId===reliablePending.msgId)"
    if original.count(expected) != 1 or "persistPending" in original:
        raise SystemExit("Stage 5 ACK handler differs from expected implementation; refusing to modify generated app")
    module = (
        "var ArgentasSyncAck=(function(){'use strict';"
        "function handle(m){"
        "if(reliablePending&&m.msgId&&m.msgId===reliablePending.msgId){"
        "reliablePending=null;persistReliablePending();ackQueue(m.msgId);"
        "syncDiag.lastAck=Date.now();diagSave();updateDiagnostics()"
        "}"
        "}"
        "return Object.freeze({handle:handle})})();\n"
    )
    html = html.replace(router_anchor, module + router_anchor, 1)
    html = html[:a] + "}else if(m.type==='ack'){ArgentasSyncAck.handle(m)" + html[b:]
    path.write_text(html, encoding="utf-8")
    print("Extracted ACK processing into ArgentasSyncAck; router compatibility and behavior preserved")
