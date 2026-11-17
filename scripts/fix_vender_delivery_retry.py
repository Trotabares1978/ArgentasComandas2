from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old = """  function flush(key){
    var x=pending[key];
    if(!x||!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return false;
    try{x.attempts=Number(x.attempts||0)+1;x.lastAttemptAt=Date.now();
      window.ArgentasSupabaseTransport.send(JSON.stringify({type:x.type,draft:x.type==='vender-draft'?x.payload:undefined,sale:x.type==='vender-sale'?x.payload:undefined,deliveryId:key}));
      save();return true
    }catch(e){x.lastError=String(e&&e.message||e);save();return false}
  }
  function send(type,payload){
    if(!payload||!payload.id)return false;
    var key=type+':'+String(payload.id),entry=pending[key]||{attempts:0,queuedAt:Date.now()};
    entry.type=type;entry.payload=payload;entry.updatedAt=Date.now();pending[key]=entry;save();return flush(key)
  }
  function retry(){if(!connected||!window.ArgentasSupabaseTransport)return;Object.keys(pending).forEach(function(key){flush(key)})}
  function ack(deliveryId){if(!deliveryId)return;delete pending[String(deliveryId)];save()}
  function startRetry(){setInterval(retry,2000)}"""

new = """  function retryDelay(attempts){
    var exponent=Math.max(0,Math.min(8,Number(attempts||0)-1));
    return Math.min(300000,2000*Math.pow(2,exponent));
  }
  function flush(key,force){
    var x=pending[key];
    if(!x||!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return false;
    var now=Date.now(),last=Number(x.lastAttemptAt||0);
    if(!force&&last&&now-last<retryDelay(x.attempts))return false;
    x.attempts=Number(x.attempts||0)+1;x.lastAttemptAt=now;save();
    var raw=JSON.stringify({type:x.type,draft:x.type==='vender-draft'?x.payload:undefined,sale:x.type==='vender-sale'?x.payload:undefined,deliveryId:key});
    try{
      var result=window.ArgentasSupabaseTransport.send(raw);
      if(result&&typeof result.then==='function')result.catch(function(e){x.lastError=String(e&&e.message||e);x.lastFailureAt=Date.now();save()});
      return true;
    }catch(e){x.lastError=String(e&&e.message||e);x.lastFailureAt=Date.now();save();return false}
  }
  function send(type,payload){
    if(!payload||!payload.id)return false;
    var key=type+':'+String(payload.id),entry=pending[key]||{attempts:0,queuedAt:Date.now()};
    entry.type=type;entry.payload=payload;entry.updatedAt=Date.now();pending[key]=entry;save();return flush(key,true)
  }
  function retry(){
    if(!connected||!window.ArgentasSupabaseTransport)return;
    Object.keys(pending).forEach(function(key){flush(key,false)});
  }
  function ack(deliveryId){if(!deliveryId)return;var key=String(deliveryId);if(Object.prototype.hasOwnProperty.call(pending,key)){delete pending[key];save()}}
  function startRetry(){setInterval(retry,1000)}"""

if html.count(old) != 1:
    if new in html:
        print("VENDER delivery retry hardening: already applied")
    else:
        raise SystemExit(f"VENDER delivery retry hardening: expected one exact block, found {html.count(old)}; refusing to patch")
else:
    html = html.replace(old, new, 1)

checks = {
    "retry backoff caps at five minutes": "return Math.min(300000,2000*Math.pow(2,exponent))" in html,
    "send attempts are persisted before network call": "x.attempts=Number(x.attempts||0)+1;x.lastAttemptAt=now;save();" in html,
    "async transport failures are retained": "result.catch(function(e){x.lastError=" in html,
    "retry loop respects per-message backoff": "Object.keys(pending).forEach(function(key){flush(key,false)})" in html,
    "initial send bypasses retry delay": "return flush(key,true)" in html,
    "ACK removes only matching delivery": "Object.prototype.hasOwnProperty.call(pending,key)" in html,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("VENDER sync regression guards failed: " + ", ".join(failed))

path.write_text(html, encoding="utf-8")
print(f"VENDER delivery retry hardening applied; {len(checks)} regression guards passed.")
