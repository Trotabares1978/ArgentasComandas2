# ArgentasComandas2

Versión Android de Argentas que conserva la aplicación original y suma el módulo de Comandas.

- Misma base visual y funcional de Argentas.
- Comandas para tomar, preparar, cobrar, entregar y anular pedidos.
- La misma APK se adapta a celular y tablet Android.
- **supabase-online usa Supabase como transporte online exclusivo.**
- Sincronización bidireccional entre los equipos: cualquiera puede enviar y recibir cambios.
- Cada instalación conserva un device_id persistente para distinguir dispositivos aunque tengan el mismo tipo de pantalla.
- La conexión Realtime se recupera automáticamente y dispone de heartbeat y recuperación de mensajes recientes.
- Los estados de las comandas tienen un canal liviano propio (comanda-status) con cola persistente, reintentos y ACK; la sincronización completa queda como respaldo.
- La sincronización completa usa mensajes persistentes, ACK, reintentos y deduplicación.
- Las comandas recibidas se incorporan a las ventas de la caja diaria una sola vez y, al cobrar, registran efectivo o transferencia.
- El respaldo local permite guardar y restaurar los datos de Argentas-Comandas.
- La fuente original se reconstruye automáticamente durante el build desde source/argentas-original-*.part.
- La rama main conserva el transporte local original y no debe modificarse desde este flujo.
- El APK de prueba se genera automáticamente mediante GitHub Actions.

## Rama supabase-online

Esta es la rama de desarrollo online. El puente activo es source/supabase-bridge.html y el generador fija explícitamente ese transporte en scripts/prepare_argentas.py.

La tabla de transporte es public.argentas_conexion, protegida mediante RLS y limitada a las operaciones cliente necesarias para la sincronización.
