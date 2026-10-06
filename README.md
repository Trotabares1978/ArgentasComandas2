# Argentas

Versión Android de Argentas que conserva la aplicación original y suma un módulo de Comandas.

- Misma base visual y funcional de Argentas.
- Comandas para tomar, preparar, cobrar, entregar y anular pedidos.
- La misma APK se adapta a celular y tablet Android.
- Sincronización bidireccional directa entre los dos equipos mediante Wi-Fi Direct + TCP.
- Ambos equipos ejecutan la misma aplicación y pueden enviar y recibir información.
- La sincronización usa mensajes persistentes, confirmación (ACK), reintentos y deduplicación para evitar perder cambios cuando la conexión se corta.
- Las comandas recibidas se incorporan a las ventas de la caja diaria una sola vez y, al cobrar, registran efectivo o transferencia.
- La fuente original se reconstruye automáticamente durante el build desde `source/argentas-original-*.part`.

El APK de prueba se genera automáticamente mediante GitHub Actions.
