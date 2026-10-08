# Argentas Comandas 2

Aplicación Android de Argentas con gestión de comandas, ventas y caja.

## Arquitectura actual

- La misma APK se adapta a celular y tablet Android.
- La interfaz y lógica principal se reconstruyen durante el build desde `source/argentas-original-*.part`.
- La persistencia local utiliza el almacenamiento de la aplicación.
- La sincronización multidispositivo actual utiliza Supabase como transporte.
- El protocolo de sincronización incluye mensajes persistentes, ACK, reintentos, deduplicación, versiones, tombstones y reconciliación.
- Las comandas pueden incorporarse a las ventas de Caja y registrar sus estados de cobro.
- El puente Android se limita actualmente a las funciones nativas necesarias para abrir URLs externas y compartir texto.

## Build

GitHub Actions valida los scripts JavaScript embebidos y genera el APK mediante Gradle.

La fuente generada durante el build es deliberada: `app/src/main/assets/index.html` es un artefacto de compilación y la fuente canónica vive en `source/`.

## Nota de mantenimiento

El transporte P2P heredado (Wi-Fi Direct/TCP/Nearby) ya no forma parte de la arquitectura actual y no debe reintroducirse como dependencia o camino paralelo sin una decisión arquitectónica explícita.
