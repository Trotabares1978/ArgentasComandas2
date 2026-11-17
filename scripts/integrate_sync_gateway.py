#!/usr/bin/env python3
"""Inject the staging-only native gateway adapter into the generated Argentas UI."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/assets/index.html"
html = TARGET.read_text(encoding="utf-8")

if 'id="argentas-gateway-adapter"' in html:
    raise SystemExit("Gateway adapter is already present; refusing duplicate injection")

sync_old = "function sync(){if(syncing)return;if(connected&&window.ArgentasNativeBluetooth)window.ArgentasNativeBluetooth.send(JSON.stringify({type:'state',role:'caja',db:db}))}"
sync_new = "function sync(){if(syncing)return;if(window.ArgentasGatewayTransport)window.ArgentasGatewayTransport.sendState(db);if(connected&&window.ArgentasNativeBluetooth)window.ArgentasNativeBluetooth.send(JSON.stringify({type:'state',role:'caja',db:db}))}"
if html.count(sync_old) != 1:
    raise SystemExit(f"Expected exactly one sync() anchor, found {html.count(sync_old)}")
if html.count("</body>") != 1:
    raise SystemExit(f"Expected exactly one </body>, found {html.count('</body>')}")

adapter = r"""
<script id="argentas-gateway-adapter">
(function(){
  "use strict";
  var native=window.ArgentasNativeGateway;
  var ENABLE_KEY="argentas_gateway_staging_enabled";
  var polling=false;
  var lastStatus="";

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
    endpoint=String(endpoint||"").trim();
    token=String(token||"").trim();
    if(!endpoint||!token)return false;
    try{return native.configureGateway(endpoint,token)===true}
    catch(e){return false}
  }
  function enableStaging(){
    if(!debugBuild()||!configured())return false;
    localStorage.setItem(ENABLE_KEY,"1");
    if(native&&typeof native.syncGatewayNow==="function")native.syncGatewayNow();
    sendState(typeof db!=="undefined"?db:null);
    poll();
    return true;
  }
  function disableStaging(){
    localStorage.removeItem(ENABLE_KEY);
    return true;
  }
  function sendState(db){
    if(!enabled()||!configured()||!db||!Array.isArray(db.orders)||!Array.isArray(db.products))return false;
    var stamp=Number(db.updatedAt)||Date.now();
    var payload={type:"state",role:"caja",db:db};
    var json;
    try{
      json=JSON.stringify(payload);
      if(new TextEncoder().encode(json).length>240000)return false;
      var hashA=2166136261,hashB=2246822519;
      for(var i=0;i<json.length;i++){
        var code=json.charCodeAt(i);
        hashA=Math.imul(hashA^code,16777619);
        hashB=Math.imul(hashB^code,3266489917);
      }
      var deliveryId="state:"+stamp+":"+(hashA>>>0).toString(16)+(hashB>>>0).toString(16);
      var accepted=native.enqueueGatewayMessage(deliveryId,json)===true;
      if(accepted&&typeof native.syncGatewayNow==="function")native.syncGatewayNow();
      return accepted;
    }catch(e){return false}
  }
  function applyPayload(payload){
    if(!payload||payload.type!=="state"||!payload.db)return false;
    if(!Array.isArray(payload.db.orders)||!Array.isArray(payload.db.products))return false;
    if(typeof window.merge!=="function")return false;
    try{window.merge(payload.db);return true}catch(e){return false}
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
        if(applyPayload(payload))ack.push(String(row.id));
      });
      if(ack.length&&typeof native.acknowledgeGatewayInbox==="function"){
        native.acknowledgeGatewayInbox(JSON.stringify(ack));
      }
    }catch(e){console.warn("Argentas gateway inbox:",e)}
    finally{polling=false}
  }

  window.ArgentasGatewayTransport=Object.freeze({
    configure:configure,
    enableStaging:enableStaging,
    disableStaging:disableStaging,
    isEnabled:enabled,
    status:status,
    sendState:sendState,
    poll:poll
  });

  function addDebugPanel(){
    if(!debugBuild())return;
    var card=document.querySelector("#bt .card");
    if(!card||document.getElementById("argentas-gateway-debug"))return;
    var panel=document.createElement("div");
    panel.id="argentas-gateway-debug";
    panel.style.cssText="margin-top:14px;padding-top:12px;border-top:1px solid #444";
    panel.innerHTML=
      '<b>Sincronización segura · staging</b>'+
      '<p class="small">Solo para pruebas. El token se guarda cifrado en Android Keystore; no queda en el almacenamiento web.</p>'+
      '<input id="arg-gateway-endpoint" type="url" autocomplete="off" placeholder="https://PROYECTO.supabase.co/functions/v1/argentas-sync-gateway" style="width:100%;padding:9px;margin:5px 0;background:#111;color:#fff;border:1px solid #444;border-radius:8px">'+
      '<input id="arg-gateway-token" type="password" autocomplete="new-password" placeholder="Token individual de este dispositivo" style="width:100%;padding:9px;margin:5px 0;background:#111;color:#fff;border:1px solid #444;border-radius:8px">'+
      '<div class="actions"><button type="button" id="arg-gateway-save">Guardar credencial</button><button type="button" id="arg-gateway-on" class="primary">Activar staging</button><button type="button" id="arg-gateway-off" class="dark">Desactivar</button></div>'+
      '<div id="arg-gateway-status" class="small" style="margin-top:8px">Gateway sin configurar</div>';
    card.appendChild(panel);
    var endpoint=document.getElementById("arg-gateway-endpoint");
    var token=document.getElementById("arg-gateway-token");
    var statusLine=document.getElementById("arg-gateway-status");
    function refresh(){
      var s=status();
      statusLine.textContent="Gateway: "+(s.configured?"configurado":"sin configurar")+
        " · staging: "+(enabled()?"activo":"inactivo")+
        " · pendientes: "+(Number(s.pending)||0)+
        " · recibidos: "+(Number(s.inbox)||0)+
        (s.lastError?" · último error: "+String(s.lastError).slice(0,120):"");
    }
    document.getElementById("arg-gateway-save").onclick=function(){
      var secret=token.value;
      var ok=configure(endpoint.value,secret);
      token.value="";
      if(!ok)alert("No se pudo guardar. Verificá el endpoint HTTPS de Supabase y el token individual.");
      else alert("Credencial guardada cifrada en este dispositivo. Todavía falta activar staging.");
      refresh();
    };
    document.getElementById("arg-gateway-on").onclick=function(){
      if(!enableStaging())alert("Primero guardá una credencial válida en esta compilación de prueba.");
      refresh();
    };
    document.getElementById("arg-gateway-off").onclick=function(){disableStaging();refresh()};
    refresh();
    setInterval(refresh,5000);
  }

  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",addDebugPanel,{once:true});
  else addDebugPanel();
  if(enabled())setTimeout(function(){sendState(typeof db!=="undefined"?db:null);poll()},1200);
  setInterval(poll,5000);
})();
</script>
"""
html = html.replace(sync_old, sync_new, 1)
html = html.replace("</body>", adapter + "</body>", 1)
TARGET.write_text(html, encoding="utf-8")
print("Gateway staging adapter injected; legacy Bluetooth transport preserved.")
