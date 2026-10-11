from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")


def replace_once(old, new, label):
    global html
    count = html.count(old)
    if count == 0 and new in html:
        print(f"{label}: already applied")
        return
    if count != 1:
        raise SystemExit(f"{label}: expected one exact anchor, found {count}; refusing to patch")
    html = html.replace(old, new, 1)
    print(f"{label}: patched")


# 1) A blank/stale VENDER draft must never delete a real comanda.
replace_once(
    "if(existing){if(!lines.length){var ei=db.orders.indexOf(existing);if(ei>=0)db.orders.splice(ei,1);save();renderAll();sync();return true}var incomingMethod=",
    "if(existing){if(!lines.length){/* Un borrador vacío no puede borrar una comanda existente: la baja debe ser explícita. */return false}var incomingMethod=",
    "Preserve comanda on empty VENDER draft",
)

# 2) Do not replace existing sale lines with an empty/incomplete payload.
replace_once(
    "var lines=(s.items||existing.lines||[]).map(function(x){return{productId:String(x.productId||x.id||''),name:String(x.name||''),price:Number(x.price)||0,qty:Number(x.qty)||0,note:String(x.note||''),noLechuga:!!x.noLechuga,noTomate:!!x.noTomate,cheese:String(x.cheese||'')}}).filter(function(x){return x.qty>0});if(JSON.stringify(existing.lines||[])===JSON.stringify(lines)",
    "var saleItems=Array.isArray(s.items)&&s.items.length?s.items:(Array.isArray(existing.lines)?existing.lines:[]),lines=saleItems.map(function(x){return{productId:String(x.productId||x.id||''),name:String(x.name||''),price:Number(x.price)||0,qty:Number(x.qty)||0,note:String(x.note||''),noLechuga:!!x.noLechuga,noTomate:!!x.noTomate,cheese:String(x.cheese||'')}}).filter(function(x){return x.qty>0});if(!lines.length)return false;if(JSON.stringify(existing.lines||[])===JSON.stringify(lines)",
    "Preserve existing sale lines on empty payload",
)

# 2b) A known order is enough to ACK a duplicate VENDER event, even if old records lack source metadata.
replace_once(
    "var draftId='v'+String(m.draft.id),knownDraft=db.orders.some(function(o){return o&&o.id===draftId&&o.source==='vender'});",
    "var draftId='v'+String(m.draft.id),knownDraft=db.orders.some(function(o){return o&&o.id===draftId});",
    "ACK known VENDER draft IDs",
)
replace_once(
    "var saleId='v'+String(m.sale.comandaId||m.sale.id),knownSale=db.orders.some(function(o){return o&&o.id===saleId&&o.source==='vender'});",
    "var saleId='v'+String(m.sale.comandaId||m.sale.id),knownSale=db.orders.some(function(o){return o&&o.id===saleId});",
    "ACK known VENDER sale IDs",
)

# 3) Status queue: ACK by deliveryId, but entries are keyed by order ID.
replace_once(
    "function ack(deliveryId){if(!deliveryId)return;delete comandaStatusPending[String(deliveryId)];save();}",
    "function ack(deliveryId){if(!deliveryId)return;var target=String(deliveryId),removed=false;Object.keys(comandaStatusPending).forEach(function(key){var item=comandaStatusPending[key];if(item&&String(item.deliveryId||'')===target){delete comandaStatusPending[key];removed=true}});if(removed)save();}",
    "Match status ACK to queued delivery",
)

# 4) Retry only the overdue status item; the old loop sent the entire queue once per overdue item.
replace_once(
    "function flush(){\n    if(!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return;\n    Object.keys(comandaStatusPending).forEach(function(key){",
    "function flush(onlyKey){\n    if(!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return;\n    var keys=onlyKey?[String(onlyKey)]:Object.keys(comandaStatusPending);\n    keys.forEach(function(key){",
    "Retry status entries individually",
)
replace_once(
    "if(x&&(!x.lastSentAt||now-Number(x.lastSentAt)>=delay))flush();",
    "if(x&&(!x.lastSentAt||now-Number(x.lastSentAt)>=delay))flush(key);",
    "Avoid quadratic status retry bursts",
)
replace_once(
    "comandaStatusPending[String(o.id)]={deliveryId:'cs:'+String(o.id)+':'+String(o.statusUpdatedAt),id:String(o.id),status:o.status,statusUpdatedAt:Number(o.statusUpdatedAt)||Date.now(),updatedAt:Number(o.updatedAt)||Date.now(),statusDeviceId:String(deviceId||''),attempts:0,lastSentAt:0};\n    save();flush();",
    "comandaStatusPending[String(o.id)]={deliveryId:'cs:'+String(o.id)+':'+String(o.statusUpdatedAt),id:String(o.id),status:o.status,statusUpdatedAt:Number(o.statusUpdatedAt)||Date.now(),updatedAt:Number(o.updatedAt)||Date.now(),statusDeviceId:String(deviceId||''),preparingAt:Number(o.preparingAt)||0,attempts:0,lastSentAt:0};\n    save();flush(String(o.id));",
    "Persist preparation time in status queue",
)

# 5) The receiver must retain the preparation timestamp sent in a direct status event.
replace_once(
    "var incomingDevice=String(m.statusDeviceId||''),localDevice=String(remoteOrder&&remoteOrder.statusDeviceId||'');\n    var newer=",
    "var incomingDevice=String(m.statusDeviceId||''),localDevice=String(remoteOrder&&remoteOrder.statusDeviceId||'');\n    if(!remoteOrder)return; /* No ACK until the full snapshot has restored this comanda. */\n    var incomingPreparingAt=Number(m.preparingAt)||0;if(m.status==='preparando'&&incomingPreparingAt>0&&!Number(remoteOrder.preparingAt)){remoteOrder.preparingAt=incomingPreparingAt;save();refreshComandasUI()}\n    var newer=",
    "Do not ACK status for unknown comanda",
)
replace_once(
    "remoteOrder.status=m.status;remoteOrder.statusUpdatedAt=incomingTs;remoteOrder.statusDeviceId=incomingDevice;remoteOrder.updatedAt=Math.max(Number(remoteOrder.updatedAt||0),Number(m.updatedAt||0),incomingTs);",
    "remoteOrder.status=m.status;remoteOrder.statusUpdatedAt=incomingTs;remoteOrder.statusDeviceId=incomingDevice;if(incomingPreparingAt>0)remoteOrder.preparingAt=incomingPreparingAt;remoteOrder.updatedAt=Math.max(Number(remoteOrder.updatedAt||0),Number(m.updatedAt||0),incomingTs);",
    "Apply preparation timestamp on status receipt",
)

# 6) Preserve preparingAt when a same-version snapshot wins a tie but lacks that field.
replace_once(
    "else{merged.statusUpdatedAt=Math.max(ls,rs);merged.statusDeviceId=rd>ld?rd:ld;if(!merged.status)merged.status=l.status}\n      if(stableSignature(merged)!==stableSignature(l)){om.set(id,merged);changed=true}",
    "else{merged.statusUpdatedAt=Math.max(ls,rs);merged.statusDeviceId=rd>ld?rd:ld;if(!merged.status)merged.status=l.status}\n      if(merged.status==='preparando'&&!Number(merged.preparingAt)){var knownPreparingAt=Number(l.preparingAt)||Number(x.preparingAt)||0;if(knownPreparingAt>0)merged.preparingAt=knownPreparingAt}\n      if(stableSignature(merged)!==stableSignature(l)){om.set(id,merged);changed=true}",
    "Preserve preparation timestamp during reconciliation",
)

# 7) ACK stale snapshots safely, and record a revision only after merge succeeds.
replace_once(
    "if(rev>0&&rev<=lastRemoteRevision)return;\n      if(rev>0)try{localStorage.setItem(remoteSeenKey,String(rev))}catch(e){}\n      merge(m.db,m.sales||[],false);\n      if(m.msgId)markReliableSeen(m.msgId);\n      if(window.ArgentasSupabaseTransport)window.ArgentasSupabaseTransport.send(JSON.stringify({type:'ack',msgId:m.msgId||null}));",
    "if(rev>0&&rev<=lastRemoteRevision){if(m.msgId){markReliableSeen(m.msgId);if(window.ArgentasSupabaseTransport)try{window.ArgentasSupabaseTransport.send(JSON.stringify({type:'ack',msgId:m.msgId}))}catch(e){}}return}\n      merge(m.db,m.sales||[],false);\n      if(rev>0)try{localStorage.setItem(remoteSeenKey,String(rev))}catch(e){}\n      if(m.msgId)markReliableSeen(m.msgId);\n      if(window.ArgentasSupabaseTransport)window.ArgentasSupabaseTransport.send(JSON.stringify({type:'ack',msgId:m.msgId||null}));",
    "ACK stale state revisions after safe deduplication",
)

# Regression guards: fail the build if any critical unsafe path returns.
checks = {
    "empty draft no longer splices orders": "if(existing){if(!lines.length){var ei=db.orders.indexOf(existing);if(ei>=0)db.orders.splice(ei,1)" not in html,
    "empty sale payload cannot erase lines": "var saleItems=Array.isArray(s.items)&&s.items.length?s.items:(Array.isArray(existing.lines)?existing.lines:[])" in html and "if(!lines.length)return false;if(JSON.stringify(existing.lines||[])===JSON.stringify(lines)" in html,
    "known VENDER IDs are ACKed independent of legacy source metadata": "knownDraft=db.orders.some(function(o){return o&&o.id===draftId})" in html and "knownSale=db.orders.some(function(o){return o&&o.id===saleId})" in html,
    "reconciliation keeps union by order ID": "var om=new Map((db.orders||[]).map(function(x){return[String(x&&x.id||''),x]}))" in html and "(remote.orders||[]).forEach(function(x){" in html,
    "status ACK matches deliveryId": "String(item.deliveryId||'')===target" in html,
    "status retries are per-key": ">=delay))flush(key);" in html,
    "preparingAt persisted in status queue": "preparingAt:Number(o.preparingAt)||0,attempts:0,lastSentAt:0" in html,
    "unknown status target remains unacked": "if(!remoteOrder)return; /* No ACK until the full snapshot has restored this comanda. */" in html,
    "preparingAt backfilled from direct event": "if(m.status==='preparando'&&incomingPreparingAt>0&&!Number(remoteOrder.preparingAt))" in html,
    "preparingAt merged on reconciliation": "var knownPreparingAt=Number(l.preparingAt)||Number(x.preparingAt)||0" in html,
    "stale snapshots receive ACK": "if(rev>0&&rev<=lastRemoteRevision){if(m.msgId)" in html,
    "20/30-minute alarms remain": "setInterval(updateComandaAlarms,5000)" in html and "30*60*1000" in html and "20*60*1000" in html,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Regression guards failed: " + ", ".join(failed))

path.write_text(html, encoding="utf-8")
print(f"Comanda sync safety patch applied; {len(checks)} regression guards passed.")
