(function(root){
  'use strict';
  var KEY='argentas_v2_sync_queue', DEVICE_KEY='argentas_v2_device_id', seq=0;
  var queue=loadQueue(), seen={}, listeners=[], connected=false, flushing=false;
  function now(){return Date.now()}
  function deviceId(){var d=localStorage.getItem(DEVICE_KEY);if(!d){d='A'+now().toString(36)+Math.random().toString(36).slice(2,8);localStorage.setItem(DEVICE_KEY,d)}return d}
  function loadQueue(){try{var x=JSON.parse(localStorage.getItem(KEY)||'[]');return Array.isArray(x)?x:[]}catch(e){return []}}
  function saveQueue(){try{localStorage.setItem(KEY,JSON.stringify(queue))}catch(e){}}
  function emit(type,payload){listeners.forEach(function(fn){try{fn(type,payload)}catch(e){}})}
  function send(message){if(!connected||!root.ArgentasNativeBluetooth||typeof root.ArgentasNativeBluetooth.send!=='function')return false;try{root.ArgentasNativeBluetooth.send(JSON.stringify(message));return true}catch(e){return false}}
  function enqueue(entity,entityId,operation,payload,version){
    var event={protocol:2,kind:'mutation',eventId:deviceId()+':'+(++seq)+':'+now(),origin:deviceId(),createdAt:now(),entity:String(entity),entityId:String(entityId),operation:String(operation),version:Number(version)||1,payload:payload||{}};
    queue.push({event:event,attempts:0,lastSentAt:0});saveQueue();flush();emit('queued',event);return event.eventId;
  }
  function ack(eventId){var changed=false;queue=queue.filter(function(x){if(x&&x.event&&x.event.eventId===eventId){changed=true;return false}return true});if(changed)saveQueue();emit('ack',eventId)}
  function flush(){if(flushing||!connected)return;flushing=true;try{queue.forEach(function(x){if(!x||!x.event)return;if(x.lastSentAt&&now()-x.lastSentAt<800)return;if(send(x.event)){x.attempts=(x.attempts||0)+1;x.lastSentAt=now()}});saveQueue()}finally{flushing=false}}
  setInterval(flush,800);
  root.ArgentasSync={
    connect:function(){connected=true;emit('connection','CONECTADO');flush()},
    disconnect:function(){connected=false;emit('connection','DESCONECTADO')},
    enqueue:enqueue,
    pending:function(){return queue.length},
    deviceId:deviceId,
    on:function(fn){if(typeof fn!=='function')return function(){};listeners.push(fn);return function(){listeners=listeners.filter(function(x){return x!==fn})}},
    receive:function(message){
      if(!message||Number(message.protocol)!==2)return false;
      if(message.kind==='ack'){ack(String(message.eventId||''));return true}
      if(message.kind==='hello'){send({protocol:2,kind:'hello-ack',origin:deviceId(),createdAt:now()});flush();return true}
      if(message.kind!=='mutation')return false;
      var eventId=String(message.eventId||'');if(!eventId)return false;
      if(seen[eventId]){send({protocol:2,kind:'ack',eventId:eventId,origin:deviceId(),ackAt:now()});return true}
      seen[eventId]=true;emit('mutation',message);send({protocol:2,kind:'ack',eventId:eventId,origin:deviceId(),ackAt:now()});return true;
    },
    hello:function(){return send({protocol:2,kind:'hello',origin:deviceId(),createdAt:now()})}
  };
})(window);