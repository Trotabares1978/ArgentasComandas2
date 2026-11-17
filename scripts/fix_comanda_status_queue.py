from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

# fix_comanda_sync_reliability.py runs first and already makes retries per-key
# and matches ACKs by deliveryId. This follow-up adds Promise rejection handling
# and ensures the queue key and delivery timestamp use the same normalized stamp.
old_flush = """  function flush(onlyKey){
    if(!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return;
    var keys=onlyKey?[String(onlyKey)]:Object.keys(comandaStatusPending);
    keys.forEach(function(key){
      var x=comandaStatusPending[key];if(!x)return;
      try{x.attempts=Number(x.attempts||0)+1;x.lastSentAt=Date.now();
        window.ArgentasSupabaseTransport.send(JSON.stringify({type:'comanda-status',deliveryId:x.deliveryId,id:x.id,status:x.status,statusUpdatedAt:x.statusUpdatedAt,updatedAt:x.updatedAt,statusDeviceId:x.statusDeviceId||'',preparingAt:Number(x.preparingAt)||0}));
        save();
      }catch(e){}
    });
  }"""

new_flush = """  function flush(onlyKey){
    if(!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return;
    var keys=onlyKey?[String(onlyKey)]:Object.keys(comandaStatusPending);
    keys.forEach(function(key){
      var x=comandaStatusPending[key];if(!x)return;
      x.attempts=Number(x.attempts||0)+1;x.lastSentAt=Date.now();save();
      try{
        var result=window.ArgentasSupabaseTransport.send(JSON.stringify({type:'comanda-status',deliveryId:x.deliveryId,id:x.id,status:x.status,statusUpdatedAt:x.statusUpdatedAt,updatedAt:x.updatedAt,statusDeviceId:x.statusDeviceId||'',preparingAt:Number(x.preparingAt)||0}));
        if(result&&typeof result.catch==='function')result.catch(function(e){x.lastError=String(e&&e.message||e);x.lastFailureAt=Date.now();save()});
      }catch(e){x.lastError=String(e&&e.message||e);x.lastFailureAt=Date.now();save()}
    });
  }"""

if html.count(old_flush) != 1:
    if new_flush not in html:
        raise SystemExit(f"Expected one post-reliability status flush block, found {html.count(old_flush)}")
else:
    html = html.replace(old_flush, new_flush, 1)

old_queue = """    comandaStatusPending[String(o.id)]={deliveryId:'cs:'+String(o.id)+':'+String(o.statusUpdatedAt),id:String(o.id),status:o.status,statusUpdatedAt:Number(o.statusUpdatedAt)||Date.now(),updatedAt:Number(o.updatedAt)||Date.now(),statusDeviceId:String(deviceId||''),preparingAt:Number(o.preparingAt)||0,attempts:0,lastSentAt:0};
    save();flush(String(o.id));"""
new_queue = """    var id=String(o.id),stamp=Number(o.statusUpdatedAt)||Date.now();
    comandaStatusPending[id]={deliveryId:'cs:'+id+':'+String(stamp),id:id,status:o.status,statusUpdatedAt:stamp,updatedAt:Number(o.updatedAt)||Date.now(),statusDeviceId:String(deviceId||''),preparingAt:Number(o.preparingAt)||0,attempts:0,lastSentAt:0};
    save();flush(id);"""
if html.count(old_queue) != 1:
    if new_queue not in html:
        raise SystemExit(f"Expected one status queue timestamp block, found {html.count(old_queue)}")
else:
    html = html.replace(old_queue, new_queue, 1)

checks = {
    "status ACK matches deliveryId": "String(item.deliveryId||'')===target" in html,
    "status retry remains per-key": ">=delay))flush(key);" in html,
    "status send handles Promise rejection": "result.catch(function(e){x.lastError=" in html,
    "attempt metadata is saved before send": "x.attempts=Number(x.attempts||0)+1;x.lastSentAt=Date.now();save();" in html,
    "queue delivery ID uses normalized timestamp": "var id=String(o.id),stamp=Number(o.statusUpdatedAt)||Date.now();" in html and "deliveryId:'cs:'+id+':'+String(stamp)" in html,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Comanda status async regression guards failed: " + ", ".join(failed))

path.write_text(html, encoding="utf-8")
print(f"Comanda status async delivery hardening applied; {len(checks)} regression guards passed.")
