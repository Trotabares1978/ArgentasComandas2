(function(){
  'use strict';

  var BACKUP_VERSION=2;
  var PREFIXES=['argentas_'];
  var EXTRA_KEYS=['argentas_comandas_v2'];
  var TOMBSTONE_KEY='argentas_sync_tombstones';
  var WATCH_KEYS=['argentas_sales','argentas_expenses','argentas_closures','argentas_products_v6','argentas_comandas_v2'];

  function readJson(k,fallback){
    try{
      var v=JSON.parse(localStorage.getItem(k)||'null');
      return v===null?fallback:v;
    }catch(e){return fallback}
  }

  function identity(x){
    if(!x||typeof x!=='object')return null;
    return String(x.id||x.comandaId||x.productId||x.timestamp||x.fecha||x.date||JSON.stringify(x));
  }

  function tombstones(){
    var t=readJson(TOMBSTONE_KEY,{});
    return t&&typeof t==='object'&&!Array.isArray(t)?t:{};
  }

  function saveTombstones(t){
    try{localStorage.setItem(TOMBSTONE_KEY,JSON.stringify(t))}catch(e){}
  }

  function compactTombstones(){
    var t=tombstones(),cut=Date.now()-1000*60*60*24*30,changed=false;
    Object.keys(t).forEach(function(key){var ids=t[key];if(!ids||typeof ids!=='object')return;Object.keys(ids).forEach(function(id){if(Number(ids[id]||0)<cut){delete ids[id];changed=true}});if(!Object.keys(ids).length){delete t[key];changed=true}});
    if(changed)saveTombstones(t);
  }

  function recordVersion(x){
    return Number(x&&x.updatedAt)
        ||Number(x&&x.createdAt)
        ||Number(x&&x.timestamp)
        ||Date.parse((x&&x.date)||(x&&x.fecha)||'')
        ||0;
  }

  function tombstoneWins(ids,item){
    var id=identity(item),deletedAt=id&&Number(ids[id]||0);
    if(!deletedAt)return false;
    var version=recordVersion(item);
    return !version||deletedAt>=version;
  }

  function rememberDeletion(key,item){
    var id=identity(item);
    if(!id)return;
    var t=tombstones();
    if(!t[key])t[key]={};
    t[key][id]=Date.now();
    saveTombstones(t);
  }

  function compareRemoved(key,oldValue,newValue){
    if(WATCH_KEYS.indexOf(key)<0)return;
    var oldList=null,newList=null;
    if(key==='argentas_comandas_v2'){
      oldList=oldValue&&Array.isArray(oldValue.orders)?oldValue.orders:null;
      newList=newValue&&Array.isArray(newValue.orders)?newValue.orders:null;
    }else{
      oldList=Array.isArray(oldValue)?oldValue:null;
      newList=Array.isArray(newValue)?newValue:null;
    }
    if(!oldList||!newList)return;
    var present={};
    newList.forEach(function(x){var id=identity(x);if(id)present[id]=true});
    oldList.forEach(function(x){
      var id=identity(x);
      if(id&&!present[id])rememberDeletion(key,x);
    });
  }

  function installStorageWatch(){
    if(window.__argentasTombstoneStorageWatch)return;
    window.__argentasTombstoneStorageWatch=true;
    var originalSet=Storage.prototype.setItem;
    Storage.prototype.setItem=function(key,value){
      var old=readJson(key,null),parsed;
      try{parsed=JSON.parse(value)}catch(e){parsed=value}
      compareRemoved(key,old,parsed);
      var result=originalSet.call(this,key,value);if(WATCH_KEYS.indexOf(key)>=0){autoBackupLastChange=Date.now();scheduleAutoBackup()}if(key==='argentas_products_v6'){try{window.dispatchEvent(new Event('argentas-products-sync'))}catch(e){}}return result;
    };
    var originalRemove=Storage.prototype.removeItem;
    Storage.prototype.removeItem=function(key){
      if(WATCH_KEYS.indexOf(key)>=0){
        var old=readJson(key,null);
        if(Array.isArray(old))old.forEach(function(x){rememberDeletion(key,x)});
        else if(key==='argentas_comandas_v2'&&old&&Array.isArray(old.orders))old.orders.forEach(function(x){rememberDeletion(key,x)});
      }
      var result=originalRemove.call(this,key);if(WATCH_KEYS.indexOf(key)>=0){autoBackupLastChange=Date.now();scheduleAutoBackup()}if(key==='argentas_products_v6'){try{window.dispatchEvent(new Event('argentas-products-sync'))}catch(e){}}return result;
    };
  }

  function applyTombstones(t){
    if(!t||typeof t!=='object')return false;
    var changed=false;
    Object.keys(t).forEach(function(key){
      var ids=t[key];
      if(!ids||typeof ids!=='object')return;
      var current=readJson(key,null);
      if(key==='argentas_comandas_v2'){
        if(!current||!Array.isArray(current.orders))return;
        var filtered=current.orders.filter(function(x){return !tombstoneWins(ids,x)});
        if(filtered.length!==current.orders.length){
          current.orders=filtered;
          try{localStorage.setItem(key,JSON.stringify(current));changed=true}catch(e){}
        }
      }else if(Array.isArray(current)){
        var filtered=current.filter(function(x){return !tombstoneWins(ids,x)});
        if(filtered.length!==current.length){
          try{localStorage.setItem(key,JSON.stringify(filtered));changed=true}catch(e){}
        }
      }
    });
    if(changed){
      window.dispatchEvent(new Event('argentas-sales-sync'));
      window.dispatchEvent(new Event('argentas-caja-sync'));
    }
    return changed;
  }

  function checksum(text){var h=2166136261;for(var i=0;i<text.length;i++){h^=text.charCodeAt(i);h+=(h<<1)+(h<<4)+(h<<7)+(h<<8)+(h<<24);h=h>>>0}return ('00000000'+h.toString(16)).slice(-8)}

  function collect(){
    var data={};
    for(var i=0;i<localStorage.length;i++){
      var k=localStorage.key(i);
      if(!k)continue;
      if(k===AUTO_BACKUP_KEY||k===AUTO_BACKUP_DATE_KEY)continue;
      if(PREFIXES.some(function(p){return k.indexOf(p)===0})||EXTRA_KEYS.indexOf(k)>=0){
        try{data[k]=JSON.parse(localStorage.getItem(k));}
        catch(e){data[k]=localStorage.getItem(k);}
      }
    }
    var raw=JSON.stringify(data);
    return {format:'argentas-comandas-backup',version:BACKUP_VERSION,createdAt:new Date().toISOString(),checksum:checksum(raw),keys:Object.keys(data).length,data:data};
  }

  var AUTO_BACKUP_KEY='argentas_auto_backup';
  var AUTO_BACKUP_DATE_KEY='argentas_auto_backup_date';

  function todayKey(){
    var d=new Date();
    return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  }

  function automaticBackup(force){
    try{
      var today=todayKey();
      if(!force&&localStorage.getItem(AUTO_BACKUP_DATE_KEY)===today)return false;
      var payload=collect();
      payload.automatic=true;
      payload.createdAt=new Date().toISOString();
      localStorage.setItem(AUTO_BACKUP_KEY,JSON.stringify(payload));
      localStorage.setItem(AUTO_BACKUP_DATE_KEY,today);
      return true;
    }catch(e){
      console.warn('No se pudo guardar el respaldo automático',e);
      return false;
    }
  }

  function download(){
    var payload=collect();
    var blob=new Blob([JSON.stringify(payload,null,2)],{type:'application/json;charset=utf-8'});
    var url=URL.createObjectURL(blob),a=document.createElement('a'),d=new Date();
    var stamp=d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')+'_'+String(d.getHours()).padStart(2,'0')+'-'+String(d.getMinutes()).padStart(2,'0');
    a.href=url;a.download='Argentas-Comandas-respaldo-'+stamp+'.json';
    document.body.appendChild(a);a.click();a.remove();
    setTimeout(function(){URL.revokeObjectURL(url)},1000);
  }

  var autoBackupLastChange=0;
  function scheduleAutoBackup(){
    clearTimeout(window.__argentasAutoBackupTimer);
    window.__argentasAutoBackupTimer=setTimeout(function(){
      if(autoBackupLastChange)automaticBackup(true);
    },2000);
  }

  function restore(file){
    if(!file)return;
    var reader=new FileReader();
    reader.onload=function(){
      try{
        var payload=JSON.parse(reader.result);
        if(!payload||payload.format!=='argentas-comandas-backup'||!payload.data||typeof payload.data!=='object')throw new Error('Formato de respaldo no reconocido');
        var keys=Object.keys(payload.data);
        if(!keys.length)throw new Error('El respaldo está vacío');
        if(payload.checksum&&payload.checksum!==checksum(JSON.stringify(payload.data)))throw new Error('El respaldo está corrupto o fue modificado');
        var rollback=collect().data;
        if(!window.confirm('Se van a restaurar '+keys.length+' datos de Argentas-Comandas. La aplicación se reiniciará. ¿Continuar?'))return;
        try{
          keys.forEach(function(k){if(k.indexOf('argentas_')!==0)throw new Error('Clave no permitida: '+k);var v=payload.data[k];localStorage.setItem(k,typeof v==='string'?v:JSON.stringify(v))});
        }catch(writeError){
          try{Object.keys(rollback).forEach(function(k){localStorage.setItem(k,typeof rollback[k]==='string'?rollback[k]:JSON.stringify(rollback[k]))})}catch(rollbackError){}
          throw writeError;
        }
        alert('Respaldo restaurado correctamente. Argentas-Comandas se reiniciará.');
        location.reload();
      }catch(e){alert('No se pudo restaurar el respaldo: '+(e&&e.message?e.message:'archivo inválido'))}
    };
    reader.onerror=function(){alert('No se pudo leer el archivo de respaldo.')};
    reader.readAsText(file);
  }

  function addUi(){
    var section=document.getElementById('ac-bt');
    if(!section||document.getElementById('ac-backup-card'))return;
    var card=document.createElement('div');
    card.id='ac-backup-card';card.className='ac-card';card.style.marginTop='10px';
    card.innerHTML='<b>Respaldo y recuperación</b><div class="ac-muted" style="margin-top:8px">Guardá una copia de todos los datos locales de Argentas-Comandas para recuperarlos después de reinstalar o cambiar de equipo.</div><div class="ac-muted" id="ac-auto-backup-status" style="margin-top:6px">💾 Respaldo automático diario: '+(localStorage.getItem(AUTO_BACKUP_DATE_KEY)||'pendiente')+'</div><div class="ac-actions"><button type="button" class="ac-btn ac-primary" id="ac-backup-export">⬇️ GUARDAR RESPALDO</button><button type="button" class="ac-btn ac-dark" id="ac-backup-import">⬆️ RESTAURAR RESPALDO</button></div><input id="ac-backup-file" type="file" accept=".json,application/json" style="display:none">';
    section.appendChild(card);
    document.getElementById('ac-backup-export').onclick=download;
    document.getElementById('ac-backup-import').onclick=function(){document.getElementById('ac-backup-file').click()};
    document.getElementById('ac-backup-file').onchange=function(e){restore(e.target.files&&e.target.files[0]);e.target.value=''};

    var online=document.createElement('div');
    online.id='ac-pizarra-tools';
    online.className='ac-card';
    online.style.marginTop='10px';
    online.innerHTML='<b>🧾 Pizarra de comandas online</b><div class="ac-muted" style="margin-top:8px">Abrí la pizarra desde cualquier teléfono o compartí su dirección por WhatsApp, Messenger u otra aplicación.</div><div class="ac-actions"><button type="button" class="ac-btn ac-primary" id="ac-open-pizarra">🧾 ABRIR PIZARRA</button><button type="button" class="ac-btn ac-dark" id="ac-share-pizarra">📤 COMPARTIR PIZARRA</button></div>';
    section.appendChild(online);
    var PIZARRA_URL='https://trotabares1978.github.io/ArgentasComandas2/pizarra.html?v=013e2d4';
    function openExternalUrl(url){
      if(window.ArgentasAndroid&&typeof window.ArgentasAndroid.openExternalUrl==='function'){
        window.ArgentasAndroid.openExternalUrl(url);
        return;
      }
      window.location.href=url;
    }
    function openShareSheet(){
      var text='🧾 Pizarra de Comandas ARGENTAS\\nAbrí este enlace para ver las comandas online en tiempo real:\\n'+PIZARRA_URL;
      if(window.ArgentasAndroid&&typeof window.ArgentasAndroid.shareText==='function'){
        window.ArgentasAndroid.shareText('Argentas · Pizarra de Comandas',text);
        return;
      }
      if(navigator.share){
        navigator.share({title:'Argentas · Pizarra de Comandas',text:text,url:PIZARRA_URL}).catch(function(e){if(e&&e.name!=='AbortError')alert('No se pudo abrir el menú de compartir.')});
        return;
      }
      alert('La función de compartir no está disponible en este dispositivo.');
    }
    document.getElementById('ac-open-pizarra').onclick=function(){openExternalUrl(PIZARRA_URL)};
    document.getElementById('ac-share-pizarra').onclick=openShareSheet;
  }

  installStorageWatch();
  compactTombstones();
  automaticBackup();
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',addUi,{once:true});else addUi();
  window.argentasBackup=collect;
  window.argentasRestore=restore;
  window.argentasSyncTombstones=tombstones;
})();