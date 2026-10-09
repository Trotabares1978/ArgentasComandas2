from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

start = "function merge(remote,sales,authoritativeOrders){"
end = "\nfunction renderConnectionLed()"
if "var ArgentasSyncReconcile=(function(){function merge(remote,sales,authoritativeOrders){" in html:
    print("Stage 3 reconcile extraction already applied")
elif html.count(start) != 1 or html.count(end) != 1:
    raise SystemExit("Stage 3 anchors are not unique; refusing to modify generated app")
else:
    html = html.replace(
        start,
        "var ArgentasSyncReconcile=(function(){function merge(remote,sales,authoritativeOrders){",
        1,
    )
    html = html.replace(
        end,
        "\nreturn Object.freeze({merge:merge});})();\n"
        "function merge(remote,sales,authoritativeOrders){return ArgentasSyncReconcile.merge(remote,sales,authoritativeOrders)}"
        + end,
        1,
    )
    path.write_text(html, encoding="utf-8")
    print("Extracted reconciliation into ArgentasSyncReconcile; compatibility wrapper preserved")
