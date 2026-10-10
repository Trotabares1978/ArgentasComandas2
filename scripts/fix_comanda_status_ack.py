from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

module_anchor = "var ArgentasComandaStatusSync=(function(){'use strict';"
old = "function ack(deliveryId){if(!deliveryId)return;delete comandaStatusPending[String(deliveryId)];save();}"
new = (
    "function ack(deliveryId){"
    "if(!deliveryId)return;"
    "var wanted=String(deliveryId);"
    "Object.keys(comandaStatusPending).forEach(function(key){"
    "var item=comandaStatusPending[key];"
    "if(item&&String(item.deliveryId||'')===wanted)delete comandaStatusPending[key]"
    "});"
    "save()"
    "}"
)

if "var ArgentasComandaStatusSync=" not in html:
    raise SystemExit("Comanda status sync module not found; refusing to modify generated app")
if html.count(module_anchor) != 1:
    raise SystemExit("Comanda status sync anchor is not unique; refusing to modify generated app")

# Limit replacement to the status-sync module so unrelated ACK handlers stay untouched.
start = html.index(module_anchor)
end = html.find("})();", start)
if end < 0:
    raise SystemExit("Comanda status sync module end not found; refusing to modify generated app")
end += len("})();")
module = html[start:end]

if new in module:
    print("Comanda status ACK queue fix already applied")
elif module.count(old) == 1:
    module = module.replace(old, new, 1)
    html = html[:start] + module + html[end:]
    path.write_text(html, encoding="utf-8")
    print("Fixed comanda-status ACK matching by deliveryId; only matching pending entry is removed")
else:
    raise SystemExit("Expected ACK implementation not found uniquely in status-sync module; refusing to modify generated app")
