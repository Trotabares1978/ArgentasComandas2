-- Per-device fixed-window throttling for the staged Argentas gateway.
-- Counters live on the device row, avoiding an unbounded rate-limit event table.
alter table public.argentas_sync_devices
  add column if not exists write_window timestamptz,
  add column if not exists write_count integer not null default 0,
  add column if not exists read_window timestamptz,
  add column if not exists read_count integer not null default 0;

create or replace function public.argentas_sync_consume_rate_limit(
  p_device_id text,
  p_operation text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := date_trunc('minute', clock_timestamp());
  v_count integer;
  v_limit integer;
begin
  if p_operation = 'write' then
    v_limit := 120;
    update public.argentas_sync_devices
       set write_count = case when write_window is distinct from v_now then 1 else write_count + 1 end,
           write_window = v_now
     where device_id = p_device_id and active = true and revoked_at is null
     returning write_count into v_count;
  elsif p_operation = 'read' then
    v_limit := 60;
    update public.argentas_sync_devices
       set read_count = case when read_window is distinct from v_now then 1 else read_count + 1 end,
           read_window = v_now
     where device_id = p_device_id and active = true and revoked_at is null
     returning read_count into v_count;
  else
    raise exception 'invalid_rate_limit_operation';
  end if;

  if v_count is null then
    return false;
  end if;
  return v_count <= v_limit;
end;
$$;

revoke all on function public.argentas_sync_consume_rate_limit(text, text) from public, anon, authenticated;
grant execute on function public.argentas_sync_consume_rate_limit(text, text) to service_role;

comment on function public.argentas_sync_consume_rate_limit(text, text) is
  'Atomic per-device fixed-window throttling: 120 writes/minute and 60 reads/minute.';
