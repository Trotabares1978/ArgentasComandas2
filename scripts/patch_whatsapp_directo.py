from pathlib import Path
import re

p = Path("app/src/main/assets/index.html")
html = p.read_text(encoding="utf-8")

pattern = re.compile(
    r'  function initWhatsappDirecto\(\) \{.*?\n  \}\n\n  // ---------------------------------------------------------------\n  // 2b\) TÍTULO:',
    re.S,
)

replacement = r'''  function initWhatsappDirecto() {
    var PHONE = "5492214775865";

    function abrirWhatsApp(texto) {
      try {
        if (typeof texto !== "string" || texto.indexOf("ARGENTAS") === -1) return;
        var textoRecortado = texto;
        var match = texto.match(/[\s\S]*✨ Ganancia:[^\n]*/);
        if (match) textoRecortado = match[0];

        var url = "https://wa.me/" + PHONE + "?text=" + encodeURIComponent(textoRecortado);

        if (window.ArgentasNativeBluetooth && typeof window.ArgentasNativeBluetooth.openExternalUrl === "function") {
          window.ArgentasNativeBluetooth.openExternalUrl(url);
          return;
        }
        if (window.ArgentasAndroid && typeof window.ArgentasAndroid.openExternalUrl === "function") {
          window.ArgentasAndroid.openExternalUrl(url);
          return;
        }
        window.open(url, "_blank");
      } catch (e) {}
    }

    try {
      if (navigator.clipboard && navigator.clipboard.__argentasPatched) return;

      if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
        var original = navigator.clipboard.writeText.bind(navigator.clipboard);
        navigator.clipboard.writeText = function (text) {
          var result;
          try { result = original(text); } catch (e) { result = Promise.resolve(); }
          abrirWhatsApp(text);
          return result;
        };
        navigator.clipboard.__argentasPatched = true;
        return;
      }

      var shim = {
        __argentasPatched: true,
        writeText: function (text) {
          abrirWhatsApp(text);
          return Promise.resolve();
        }
      };

      try {
        Object.defineProperty(navigator, "clipboard", {
          value: shim,
          configurable: true
        });
      } catch (e) {
        try { navigator.clipboard = shim; } catch (_) {}
      }
    } catch (e) {}
  }

  // ---------------------------------------------------------------
  // 2b) TÍTULO:'''

new_html, count = pattern.subn(lambda _m: replacement, html, count=1)
if count != 1:
    raise SystemExit("No se encontró exactamente el bloque initWhatsappDirecto esperado; se cancela el parche para no tocar la 232.")

native_share_patch = r'''
<script id="argentas-native-share-patch">
(function(){
  try {
    if (window.ArgentasNativeBluetooth && typeof window.ArgentasNativeBluetooth.shareText === "function") {
      var nativeShare = function(data){
        data = data || {};
        var parts = [];
        if (data.title) parts.push(String(data.title));
        if (data.text) parts.push(String(data.text));
        if (data.url) parts.push(String(data.url));
        var text = parts.join("\n");
        window.ArgentasNativeBluetooth.shareText(text);
        return Promise.resolve();
      };
      if (!navigator.share || !navigator.share.__argentasNativeShare) {
        nativeShare.__argentasNativeShare = true;
        try { navigator.share = nativeShare; } catch(e) {}
      }
    }
  } catch(e) {}
})();
</script>
'''
if "</body>" in new_html:
    new_html = new_html.replace("</body>", native_share_patch + "</body>", 1)
else:
    raise SystemExit("No se encontró </body> después del parche WhatsApp.")

p.write_text(new_html, encoding="utf-8")
print("WhatsApp directo parcheado para el número predefinido.")
