create table if not exists public.argentas_comanda_status (
  event_id text primary key,
  order_id text not null,
  status text not null check (status in ('recibido','preparando','entregada','oculta','cancelada')),
  status_updated_at bigint not null,
  device_id text not null,
  created_at timestamptz not null default now()
);

create index if not exists idx_argentas_comanda_status_order_time
  on public.argentas_comanda_status (order_id, status_updated_at desc);
create index if not exists idx_argentas_comanda_status_created_at
  on public.argentas_comanda_status (created_at desc);

alter table public.argentas_comanda_status enable row level security;

drop policy if exists "argentas status insert" on public.argentas_comanda_status;
create policy "argentas status insert"
  on public.argentas_comanda_status
  for insert to anon, authenticated
  with check (
    length(event_id) between 8 and 160
    and length(order_id) between 1 and 200
    and status in ('recibido','preparando','entregada','oculta','cancelada')
    and status_updated_at > 0
    and length(device_id) between 8 and 100
  );

drop policy if exists "argentas status select" on public.argentas_comanda_status;
create policy "argentas status select"
  on public.argentas_comanda_status
  for select to anon, authenticated
  using (created_at > now() - interval '7 days');

revoke update, delete on public.argentas_comanda_status from anon, authenticated;
grant select, insert on public.argentas_comanda_status to anon, authenticated;

do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname='supabase_realtime' and schemaname='public' and tablename='argentas_comanda_status'
  ) then
    alter publication supabase_realtime add table public.argentas_comanda_status;
  end if;
end $$;

create or replace function public.argentas_comanda_status_prune()
returns trigger language plpgsql security invoker as $$
begin
  delete from public.argentas_comanda_status where created_at < now() - interval '7 days';
  return new;
end;
$$;

drop trigger if exists trg_argentas_comanda_status_prune on public.argentas_comanda_status;
create trigger trg_argentas_comanda_status_prune
after insert on public.argentas_comanda_status
for each statement execute function public.argentas_comanda_status_prune();