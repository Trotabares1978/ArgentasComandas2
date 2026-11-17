-- Recipient receipts close the commit-order hole in cursor-only delivery.
create table if not exists public.argentas_sync_receipts (
  message_id bigint not null references public.argentas_sync_messages(id) on delete cascade,
  recipient_device_id text not null references public.argentas_sync_devices(device_id),
  acked_at timestamptz not null default clock_timestamp(),
  primary key (message_id, recipient_device_id)
);
create index if not exists argentas_sync_receipts_recipient_idx
  on public.argentas_sync_receipts (recipient_device_id, acked_at desc);
alter table public.argentas_sync_receipts enable row level security;
revoke all on public.argentas_sync_receipts from anon, authenticated;
grant all on public.argentas_sync_receipts to service_role;

create or replace function public.argentas_sync_pending_messages(
  p_recipient_device_id text,
  p_limit integer default 10
)
returns table (
  id bigint,
  sender_device_id text,
  delivery_id text,
  payload jsonb,
  created_at timestamptz
)
language sql
security definer
set search_path = public, pg_temp
as $$
  select m.id, m.sender_device_id, m.delivery_id, m.payload, m.created_at
  from public.argentas_sync_messages m
  join public.argentas_sync_devices sender
    on sender.device_id = m.sender_device_id
   and sender.active = true
   and sender.revoked_at is null
  join public.argentas_sync_devices recipient
    on recipient.device_id = p_recipient_device_id
   and recipient.active = true
   and recipient.revoked_at is null
  where m.sender_device_id <> p_recipient_device_id
    and not exists (
      select 1 from public.argentas_sync_receipts r
      where r.message_id = m.id
        and r.recipient_device_id = p_recipient_device_id
    )
  order by m.id asc
  limit greatest(1, least(coalesce(p_limit, 10), 100));
$$;

create or replace function public.argentas_sync_ack_messages(
  p_recipient_device_id text,
  p_message_ids bigint[]
)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  inserted_count integer := 0;
begin
  if not exists (
    select 1 from public.argentas_sync_devices d
    where d.device_id = p_recipient_device_id
      and d.active = true and d.revoked_at is null
  ) then
    raise exception 'recipient_device_inactive';
  end if;

  insert into public.argentas_sync_receipts(message_id, recipient_device_id)
  select distinct m.id, p_recipient_device_id
  from public.argentas_sync_messages m
  join public.argentas_sync_devices sender
    on sender.device_id = m.sender_device_id
   and sender.active = true and sender.revoked_at is null
  where m.id = any(coalesce(p_message_ids, '{}'::bigint[]))
    and m.sender_device_id <> p_recipient_device_id
  on conflict (message_id, recipient_device_id) do nothing;

  get diagnostics inserted_count = row_count;
  return inserted_count;
end;
$$;

revoke all on function public.argentas_sync_pending_messages(text, integer) from public, anon, authenticated;
revoke all on function public.argentas_sync_ack_messages(text, bigint[]) from public, anon, authenticated;
grant execute on function public.argentas_sync_pending_messages(text, integer) to service_role;
grant execute on function public.argentas_sync_ack_messages(text, bigint[]) to service_role;
