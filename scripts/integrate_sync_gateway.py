#!/usr/bin/env python3
"""Connect the staged native gateway to Argentas' existing Supabase message transport."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/assets/index.html"
html = TARGET.read_text(encoding="utf-8")

if 'id="argentas-gateway-adapter"' in html:
    raise SystemExit("Gateway adapter is already present; refusing duplicate injection")
if "ArgentasSupabaseTransport" not in html or "onSupabaseMessage" not in html:
    raise SystemExit("The generated app has no recognized Supabase transport/router; refusing unsafe integration")
if html.count("</body>") != 1:
    raise SystemExit(f"Expected exactly one </body>, found {html.count('</body>')}")

# Make the existing router report whether it actually applied a message.
router_start = "handle:function(raw){raw.trim().split('\\n').filter(Boolean).forEach(function(line){try{"
router_start_new = "handle:function(raw){var handled=true;raw.trim().split('\\n').filter(Boolean).forEach(function(line){try{"
if html.count(router_start) == 1:
    html = html.replace(router_start, router_start_new, 1)
elif html.count(router_start_new) != 1:
    raise SystemExit(f"Router start anchor mismatch: {html.count(router_start)}")

known_draft = "if(!ok)ok=knownDraft;"
if html.count(known_draft) == 1:
    html = html.replace(known_draft, known_draft + "if(!ok)handled=false;", 1)
elif html.count(known_draft + "if(!ok)handled=false;") != 1:
    raise SystemExit("Could not safely guard unhandled VENDER draft messages")

known_sale = "if(!okSale)okSale=knownSale;"
if html.count(known_sale) == 1:
    html = html.replace(known_sale, known_sale + "if(!okSale)handled=false;", 1)
elif html.count(known_sale + "if(!okSale)handled=false;") != 1:
    raise SystemExit("Could not safely guard unhandled VENDER sale messages")

hello_ack = """}else if(m.type==='hello-ack'){
    if(connected){flushReliableState()}
  }
}catch(e){console.warn('Mensaje de sincronización inválido',e)}"""
hello_ack_new = """}else if(m.type==='hello-ack'){
    if(connected){flushReliableState()}
  }else{handled=false}
}catch(e){handled=false;console.warn('Mensaje de sincronización inválido',e)}"""
if html.count(hello_ack) == 1:
    html = html.replace(hello_ack, hello_ack_new, 1)
elif html.count(hello_ack_new) != 1:
    raise SystemExit(f"Router dispatch anchor mismatch: {html.count(hello_ack)}")

router_end = "}catch(e){handled=false;console.warn('Mensaje de sincronización inválido',e)}})}"
router_end_new = "}catch(e){handled=false;console.warn('Mensaje de sincronización inválido',e)}});return handled}"
if html.count(router_end) == 1:
    html = html.replace(router_end, router_end_new, 1)
elif html.count(router_end_new) != 1:
    raise SystemExit(f"Router return anchor mismatch: {html.count(router_end)}")

adapter = r"""
<script id="argentas-gateway-adapter">
(function(){
  "use strict";
  var native=window.ArgentasNativeGateway;
  var ENABLE_KEY="argentas_gateway_staging_enabled";
  var polling=false;
  var transportHooked=false;
  var pollTimer=null;

  function debugBuild(){
    try{return !!(native&&typeof native.isDebugBuild==="function"&&native.isDebugBuild())}
    catch(e){return false}
  }
  function status(){
    try{return native&&typeof native.gatewayStatus==="function"?JSON.parse(native.gatewayStatus()||"{}"):{configured:false}}
    catch(e){return {configured:false,error:"status_unavailable"}}
  }
  function enabled(){return localStorage.getItem(ENABLE_KEY)==="1"}
  function configured(){return !!status().configured}
  function configure(endpoint,token){
    if(!debugBuild()||!native||typeof native.configureGateway!=="function")return false;
    endpoint=String(endpoint||"").trim();token=String(token||"").trim();
    if(!endpoint||!token)return false;
    try{return native.configureGateway(endpoint,token)===true}catch(e){return false}
  }
  function enableStaging(){
    if(!debugBuild()||!configured()||!wrapTransport())return false;
    localStorage.setItem(ENABLE_KEY,"1");
    if(native&&typeof native.syncGatewayNow==="function")native.syncGatewayNow();
    if(typeof window.flushReliableState==="function"){
      try{window.flushReliableState()}catch(e){console.warn("No se pudo solicitar snapshot inicial",e)}
    }
    poll();
    return true;
  }
  function disableStaging(){localStorage.removeItem(ENABLE_KEY);return true}

  function deliveryId(message){
    if(message.deliveryId)return String(message.deliveryId);
    if(message.msgId)return String(message.type||"message")+":"+String(message.msgId);
    if(message.requestId)return String(message.type||"request")+":"+String(message.requestId);
    if(message.type==="state"){
      var version=message.revision||message.updatedAt||message.sentAt||message.timestamp;
      if(version!==undefined&&version!==null)return "state:"+String(message.sessionId||message.deviceId||message.device_id||"device")+":"+String(version);
    }
    return "";
  }
  function enqueueRaw(raw){
    if(!enabled()||!configured()||!native||typeof native.enqueueGatewayMessage!=="function")return;
    String(raw||"").split("\n").forEach(function(line){
      if(!line.trim())return;
      var message;
      try{message=JSON.parse(line)}catch(e){return}
      if(!message||typeof message!=="object"||Array.isArray(message))return;
      if(message.type==="hello"||message.type==="hello-ack")return;
      var id=deliveryId(message);
      if(!id||id.length>200)return;
      try{
        var payloadJson=JSON.stringify(message);
        if(new TextEncoder().encode(payloadJson).length>240000){
          console.warn("Argentas gateway: mensaje demasiado grande; se conserva el transporte anterior",id);
          return;
        }
        var accepted=native.enqueueGatewayMessage(id,payloadJson);
        if(!accepted)console.warn("Argentas gateway: no se pudo encolar",id);
      }catch(e){console.warn("Argentas gateway enqueue:",e)}
    });
    if(native&&typeof native.syncGatewayNow==="function")native.syncGatewayNow();
  }
  function wrapTransport(){
    var transport=window.ArgentasSupabaseTransport;
    if(!transport||typeof transport.send!=="function")return false;
    if(transport.send.__argentasGatewayWrapped){transportHooked=true;return true}
    var original=transport.send;
    var wrapped=function(raw){
      try{enqueueRaw(raw)}catch(e){console.warn("Argentas gateway dual-write:",e)}
      return original.apply(this,arguments);
    };
    wrapped.__argentasGatewayWrapped=true;
    try{transport.send=wrapped;transportHooked=transport.send===wrapped}
    catch(e){transportHooked=false}
    return transportHooked;
  }

  function poll(){
    if(polling||!enabled()||!configured()||!native||typeof native.gatewayInbox!=="function")return;
    polling=true;
    try{
      var rows=JSON.parse(native.gatewayInbox()||"[]");
      if(!Array.isArray(rows))return;
      var ack=[];
      rows.forEach(function(row){
        if(!row||row.id===undefined||row.id===null)return;
        var payload=row.payload;
        if(typeof payload==="string"){try{payload=JSON.parse(payload)}catch(e){return}}
        if(!payload||typeof payload!=="object")return;
        if(typeof window.onSupabaseMessage!=="function")return;
        try{
          if(window.onSupabaseMessage(JSON.stringify(payload))===true)ack.push(String(row.id));
        }catch(e){console.warn("Argentas gateway message apply:",e)}
      });
      if(ack.length&&typeof native.acknowledgeGatewayInbox==="function"){
        native.acknowledgeGatewayInbox(JSON.stringify(ack));
      }
    }catch(e){console.warn("Argentas gateway inbox:",e)}
    finally{polling=false}
  }

  window.ArgentasGatewayTransport=Object.freeze({
    configure:configure,enableStaging:enableStaging,disableStaging:disableStaging,
    isEnabled:enabled,status:status,poll:poll
  });

  function addDebugPanel(){
    if(!debugBuild()||document.getElementById("argentas-gateway-debug"))return;
    var panel=document.createElement("details");
    panel.id="argentas-gateway-debug";
    panel.style.cssText="position:fixed;right:10px;bottom:10px;z-index:99999;width:min(360px,calc(100vw - 20px));padding:10px;background:#171717;color:#fff;border:1px solid #b88b32;border-radius:12px;font:13px system-ui;box-shadow:0 5px 24px #0009";
    panel.innerHTML=
      '<summary style="cursor:pointer;font-weight:800;color:#e8b84a">Gateway · staging (debug)</summary>'+
      '<p style="font-size:11px;color:#bbb">Pruebas únicamente. El token se cifra en Android Keystore y no se guarda en el almacenamiento web.</p>'+
      '<input id="arg-gateway-endpoint" type="url" autocomplete="off" placeholder="https://PROYECTO.supabase.co/functions/v1/argentas-sync-gateway" style="width:100%;padding:8px;margin:4px 0;background:#090909;color:#fff;border:1px solid #555;border-radius:7px">'+
      '<input id="arg-gateway-token" type="password" autocomplete="new-password" placeholder="Token individual del dispositivo" style="width:100%;padding:8px;margin:4px 0;background:#090909;color:#fff;border:1px solid #555;border-radius:7px">'+
      '<div style="display:flex;gap:5px;flex-wrap:wrap;margin-top:7px"><button id="arg-gateway-save" type="button">Guardar</button><button id="arg-gateway-on" type="button">Activar</button><button id="arg-gateway-off" type="button">Desactivar</button></div>'+
      '<p id="arg-gateway-status" style="font-size:11px;color:#bbb;margin:7px 0 0">Comprobando…</p>';
    document.body.appendChild(panel);
    var endpoint=document.getElementById("arg-gateway-endpoint");
    var token=document.getElementById("arg-gateway-token");
    var statusLine=document.getElementById("arg-gateway-status");
    function refresh(){
      wrapTransport();
      var s=status();
      statusLine.textContent="Credencial: "+(s.configured?"guardada":"no configurada")+
        " · transporte: "+(transportHooked?"conectado":"esperando")+
        " · staging: "+(enabled()?"activo":"inactivo")+
        " · cola: "+(Number(s.pending)||0)+" · recibidos: "+(Number(s.inbox)||0)+
        (s.lastError?" · error: "+String(s.lastError).slice(0,100):"");
    }
    document.getElementById("arg-gateway-save").onclick=function(){
      var ok=configure(endpoint.value,token.value);token.value="";
      alert(ok?"Credencial guardada cifrada en este equipo.":"No se pudo guardar: revisá endpoint y token.");
      refresh();
    };
    document.getElementById("arg-gateway-on").onclick=function(){
      if(!enableStaging())alert("Falta una credencial válida o el transporte no está disponible.");
      refresh();
    };
    document.getElementById("arg-gateway-off").onclick=function(){disableStaging();refresh()};
    refresh();setInterval(refresh,5000);
  }

  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",addDebugPanel,{once:true});
  else addDebugPanel();
  var attempts=0;
  var hookTimer=setInterval(function(){
    attempts++;
    if(wrapTransport()||attempts>=40)clearInterval(hookTimer);
  },500);
  if(enabled())setTimeout(function(){
    wrapTransport();
    if(typeof window.flushReliableState==="function"){
      try{window.flushReliableState()}catch(e){}
    }
    poll();
  },1200);
  setInterval(poll,5000);
})();
</script>
"""
html = html.replace("</body>", adapter + "</body>", 1)
TARGET.write_text(html, encoding="utf-8")
print("Native gateway adapter injected; legacy Supabase transport remains authoritative during staging.")
