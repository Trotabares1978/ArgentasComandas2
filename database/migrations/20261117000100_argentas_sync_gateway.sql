-- Argentas multi-device sync gateway (staged; do not apply to production until client rollout is ready)
-- Purpose: server-side idempotent ingestion and device-scoped authorization.
-- Existing argentas_conexion remains untouched during migration/rollback window.

create table if not exists public.argentas_sync_devices (
  device_id text primary key check (length(device_id) between 8 and 100),
  token_hash text not null unique check (length(token_hash) = 64),
  label text not null default '',
  active boolean not null default true,
  created_at timestamptz not null default now(),
  revoked_at timestamptz
);

create table if not exists public.argentas_sync_messages (
  id bigint generated always as identity primary key,
  sender_device_id text not null references public.argentas_sync_devices(device_id),
  delivery_id text not null check (length(delivery_id) between 1 and 200),
  payload jsonb not null,
  created_at timestamptz not null default now(),
  constraint argentas_sync_messages_delivery_unique unique (sender_device_id, delivery_id)
);

create index if not exists argentas_sync_messages_created_idx
  on public.argentas_sync_messages (created_at desc);
create index if not exists argentas_sync_messages_sender_created_idx
  on public.argentas_sync_messages (sender_device_id, created_at desc);

alter table public.argentas_sync_devices enable row level security;
alter table public.argentas_sync_messages enable row level security;

-- Only the server-side Edge Function uses service_role. No public client may read/write these tables.
revoke all on public.argentas_sync_devices from anon, authenticated;
revoke all on public.argentas_sync_messages from anon, authenticated;
grant all on public.argentas_sync_devices to service_role;
grant all on public.argentas_sync_messages to service_role;
grant usage, select on sequence public.argentas_sync_messages_id_seq to service_role;

comment on table public.argentas_sync_devices is
  'Private Argentas device registry. token_hash is SHA-256 of a high-entropy per-device bearer token; never store raw tokens.';
comment on table public.argentas_sync_messages is
  'Idempotent Argentas device message inbox. Unique sender_device_id + delivery_id suppresses duplicate retries.';
