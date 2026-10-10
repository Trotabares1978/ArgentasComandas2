from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old_flush = """  function flush(){
    if(!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return;
    Object.keys(comandaStatusPending).forEach(function(key){"""
new_flush = """  function flush(targetKey){
    if(!connected||!window.ArgentasSupabaseTransport||typeof window.ArgentasSupabaseTransport.send!=='function')return;
    var keys=targetKey?[String(targetKey)]:Object.keys(comandaStatusPending);
    keys.forEach(function(key){"""

old_retry = "if(x&&(!x.lastSentAt||now-Number(x.lastSentAt)>=delay))flush();"
new_retry = "if(x&&(!x.lastSentAt||now-Number(x.lastSentAt)>=delay))flush(key);"

if new_flush in html and new_retry in html:
    print("Per-delivery comanda-status retry already applied")
else:
    if html.count(old_flush) != 1:
        raise SystemExit("Comanda-status flush anchor is not unique; refusing to modify generated app")
    if html.count(old_retry) != 1:
        raise SystemExit("Comanda-status retry anchor is not unique; refusing to modify generated app")
    html = html.replace(old_flush, new_flush, 1)
    html = html.replace(old_retry, new_retry, 1)
    path.write_text(html, encoding="utf-8")
    print("Fixed per-delivery status retries to avoid repeatedly flushing the entire queue")
