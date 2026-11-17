from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old_flush = """  function flush(){
    if(!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return;
    Object.keys(comandaStatusPending).forEach(function(key){
      var x=comandaStatusPending[key];if(!x)return;
      try{x.attempts=Number(x.attempts||0)+1;x.lastSentAt=Date.now();
        window.ArgentasSupabaseTransport.send(JSON.stringify({type:'comanda-status',deliveryId:x.deliveryId,id:x.id,status:x.status,statusUpdatedAt:x.statusUpdatedAt,updatedAt:x.updatedAt,statusDeviceId:x.statusDeviceId||'',preparingAt:Number(x.preparingAt)||0}));
        save();
      }catch(e){}
    });
  }
  function ack(deliveryId){if(!deliveryId)return;delete comandaStatusPending[String(deliveryId)];save();}
  function queue(o){
    if(!o||!o.id)return;
    var deviceId='';
    try{deviceId=window.ArgentasSupabaseTransport&&window.ArgentasSupabaseTransport.getDeviceId?window.ArgentasSupabaseTransport.getDeviceId():''}catch(e){}
    comandaStatusPending[String(o.id)]={deliveryId:'cs:'+String(o.id)+':'+String(o.statusUpdatedAt),id:String(o.id),status:o.status,statusUpdatedAt:Number(o.statusUpdatedAt)||Date.now(),updatedAt:Number(o.updatedAt)||Date.now(),statusDeviceId:String(deviceId||''),attempts:0,lastSentAt:0};
    save();flush();
  }
  function startRetry(){setInterval(function(){
    if(!connected)return;
    var now=Date.now();
    Object.keys(comandaStatusPending).forEach(function(key){
      var x=comandaStatusPending[key],a=Number(x&&x.attempts||0),delay=Math.min(30000,2000*Math.pow(2,Math.min(a,4)));
      if(x&&(!x.lastSentAt||now-Number(x.lastSentAt)>=delay))flush();
    });
  },1000)}"""

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
  }
  function ack(deliveryId){
    if(!deliveryId)return;
    var wanted=String(deliveryId),removed=false;
    Object.keys(comandaStatusPending).forEach(function(key){
      var x=comandaStatusPending[key];
      if(x&&String(x.deliveryId||'')===wanted){delete comandaStatusPending[key];removed=true}
    });
    if(removed)save();
  }
  function queue(o){
    if(!o||!o.id)return;
    var deviceId='',id=String(o.id),stamp=Number(o.statusUpdatedAt)||Date.now();
    try{deviceId=window.ArgentasSupabaseTransport&&window.ArgentasSupabaseTransport.getDeviceId?window.ArgentasSupabaseTransport.getDeviceId():''}catch(e){}
    comandaStatusPending[id]={deliveryId:'cs:'+id+':'+String(stamp),id:id,status:o.status,statusUpdatedAt:stamp,updatedAt:Number(o.updatedAt)||Date.now(),statusDeviceId:String(deviceId||''),attempts:0,lastSentAt:0};
    save();flush(id);
  }
  function startRetry(){setInterval(function(){
    if(!connected)return;
    var now=Date.now();
    Object.keys(comandaStatusPending).forEach(function(key){
      var x=comandaStatusPending[key],a=Number(x&&x.attempts||0),delay=Math.min(30000,2000*Math.pow(2,Math.min(a,4)));
      if(x&&(!x.lastSentAt||now-Number(x.lastSentAt)>=delay))flush(key);
    });
  },1000)}"""

if html.count(old_flush) != 1:
    if new_flush in html:
        print("Comanda status queue hardening: already applied")
    else:
        raise SystemExit(f"Expected one comanda status block, found {html.count(old_flush)}")
else:
    html = html.replace(old_flush, new_flush, 1)

checks = {
    "ACK matches stored delivery id": "String(x.deliveryId||'')===wanted" in html,
    "status retry sends only eligible key": "if(x&&(!x.lastSentAt||now-Number(x.lastSentAt)>=delay))flush(key);" in html,
    "status queue uses same normalized timestamp": "var deviceId='',id=String(o.id),stamp=Number(o.statusUpdatedAt)||Date.now();" in html,
    "status send handles async failures": "result.catch(function(e){x.lastError=" in html,
    "status send accepts one key": "var keys=onlyKey?[String(onlyKey)]:Object.keys(comandaStatusPending);" in html,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Comanda status regression guards failed: " + ", ".join(failed))

path.write_text(html, encoding="utf-8")
print(f"Comanda status queue hardening applied; {len(checks)} regression guards passed.")
