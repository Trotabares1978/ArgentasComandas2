from pathlib import Path

p = Path("source/argentas-original-07.part")
s = p.read_text(encoding="utf-8")
bad = """    }catch(e){console.warn('No se pudo responder al cotejo de Pizarra',e)}
    return
  
    syncDiag.lastReceive=Date.now();syncDiag.receivedStates++;"""
good = """    }catch(e){console.warn('No se pudo responder al cotejo de Pizarra',e)}
    return
  }else if(m.type==='state'){
    syncDiag.lastReceive=Date.now();syncDiag.receivedStates++;"""
if bad in s:
    s = s.replace(bad, good, 1)
elif "  }else if(m.type==='reconcile-request'){" in s and "  }else if(m.type==='state'){" in s:
    pass
else:
    raise SystemExit("No se encontro el bloque de reconciliacion")
p.write_text(s, encoding="utf-8")
print("Reconciliacion activa validada")
