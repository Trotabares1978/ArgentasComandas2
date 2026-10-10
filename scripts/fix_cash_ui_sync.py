from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

anchor = 'window.addEventListener("argentas-sales-sync",h);return()=>window.removeEventListener("argentas-sales-sync",h)},[]),q.useEffect(()=>{try{let d=n.map'
patch = '''window.addEventListener("argentas-sales-sync",h);return()=>window.removeEventListener("argentas-sales-sync",h)},[]),q.useEffect(()=>{let refreshCaja=()=>{try{var cashRaw=localStorage.getItem("argentas_cash");if(cashRaw!==null){var cashValue=JSON.parse(cashRaw);if(cashValue&&typeof cashValue==="object"&&!Array.isArray(cashValue))l(cashValue)}var expensesRaw=localStorage.getItem("argentas_expenses");if(expensesRaw!==null){var expensesValue=JSON.parse(expensesRaw);if(Array.isArray(expensesValue))i(expensesValue)}var closuresRaw=localStorage.getItem("argentas_closures");if(closuresRaw!==null){var closuresValue=JSON.parse(closuresRaw);if(Array.isArray(closuresValue))p(closuresValue)}}catch(e){console.warn("No se pudo refrescar Caja sincronizada",e)}};window.addEventListener("argentas-caja-sync",refreshCaja);return()=>window.removeEventListener("argentas-caja-sync",refreshCaja)},[]),q.useEffect(()=>{try{let d=n.map'''

if 'window.addEventListener("argentas-caja-sync",refreshCaja)' in html:
    print("Cash UI synchronization listener already installed")
elif html.count(anchor) == 1:
    html = html.replace(anchor, patch, 1)
    path.write_text(html, encoding="utf-8")
    print("Installed targeted Caja UI listener for remote cash, expenses, and closure updates")
else:
    raise SystemExit(f"Expected unique React sales-sync anchor, found {html.count(anchor)}; refusing to patch blindly")
