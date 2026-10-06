# Protocolo de sincronización de Argentas

Objetivo: mantener dos terminales en el mismo estado comercial con latencia baja y sin pérdida silenciosa de operaciones.

Regla principal: enviar un JSON no significa que el otro terminal lo recibió.

Cada operación pasa por: LOCAL -> PENDIENTE -> ENVIADA -> CONFIRMADA.
Si el transporte falla vuelve a PENDIENTE. Nunca se elimina una operación por agotar un número arbitrario de reintentos.

## Envelope
protocol, kind, eventId, origin, createdAt, entity, entityId, operation, version y payload.

## ACK
El ACK confirma recepción y persistencia local. El emisor retira el evento de la cola solamente después del ACK.

## Reconexión
Al conectar: hello -> capacidades -> operaciones pendientes -> ACK -> snapshot de recuperación -> flujo normal.

## Idempotencia
El receptor registra eventId procesados. Si recibe el mismo evento otra vez, no lo aplica dos veces pero vuelve a responder ACK.

## Conflictos
No se reemplaza ciegamente la base local por un snapshot remoto. Las entidades se fusionan por versión y metadatos de modificación.

## Cierre de caja
El cierre de caja es una entidad sincronizable de alta prioridad.