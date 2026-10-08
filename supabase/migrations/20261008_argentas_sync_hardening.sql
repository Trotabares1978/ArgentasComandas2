-- Argentas Comandas: hardening de sincronización Supabase
-- 2026-10-08

alter table public.argentas_conexion
  add column if not exists device_id text;

alter table public.argentas_conexion
  alter column device_id set default ('d_' || replace(gen_random_uuid()::text,'-',''));

update public.argentas_conexion
set device_id='legacy_' || replace(gen_random_uuid()::text,'-','')
where device_id is null;

alter table public.argentas_conexion
  alter column device_id set not null;

alter table public.argentas_conexion
  drop constraint if exists argentas_conexion_device_id_check;

alter table public.argentas_conexion
  add constraint argentas_conexion_device_id_check
  check (length(device_id) between 8 and 100);

create index if not exists idx_argentas_conexion_created_at
  on public.argentas_conexion(created_at);

create index if not exists idx_argentas_conexion_device_id
  on public.argentas_conexion(device_id);

drop policy if exists argentas_conexion_insert on public.argentas_conexion;
create policy argentas_conexion_insert
  on public.argentas_conexion
  for insert to anon, authenticated
  with check (
    dispositivo = any(array['tablet','celular'])
    and length(device_id) between 8 and 100
    and length(mensaje) between 1 and 1000000
  );

drop policy if exists argentas_conexion_select on public.argentas_conexion;
create policy argentas_conexion_select
  on public.argentas_conexion
  for select to anon, authenticated
  using (created_at > now() - interval '7 days');

revoke update, delete on public.argentas_conexion from anon, authenticated;
grant select, insert on public.argentas_conexion to anon, authenticated;

create or replace function public.argentas_conexion_prune()
returns trigger
language plpgsql
security definer
set search_path=public
as $$
begin
  delete from public.argentas_conexion
  where created_at < now() - interval '7 days';
  return new;
end;
$$;

revoke all on function public.argentas_conexion_prune() from public, anon, authenticated;

drop trigger if exists trg_argentas_conexion_prune on public.argentas_conexion;
create trigger trg_argentas_conexion_prune
after insert on public.argentas_conexion
for each row execute function public.argentas_conexion_prune();

-- Las funciones de reparto no necesitan SECURITY DEFINER:
-- sus comprobaciones auth.uid() y las políticas RLS ya controlan el acceso.
create or replace function public.abrir_reparto(p_reparto_id uuid)
returns void language plpgsql security invoker set search_path=public as $$
begin
  if auth.uid() is null then raise exception 'No autorizado'; end if;
  update public.repartos set is_open=true, closed_at=null where id=p_reparto_id;
end;
$$;

create or replace function public.cerrar_reparto(p_reparto_id uuid)
returns void language plpgsql security invoker set search_path=public as $$
begin
  if auth.uid() is null then raise exception 'No autorizado'; end if;
  update public.repartos set is_open=false, closed_at=now()
  where id=p_reparto_id and is_open=true;
end;
$$;

create or replace function public.abrir_reparto_admin(p_reparto_id uuid)
returns void language plpgsql security invoker set search_path=public as $$
begin
  if auth.uid() <> '37c1c818-cc07-4e91-992d-2f9eb0c14573'::uuid then
    raise exception 'No autorizado';
  end if;
  update public.repartos set is_open=true, closed_at=null where id=p_reparto_id;
end;
$$;

create or replace function public.cerrar_reparto_admin(p_reparto_id uuid)
returns void language plpgsql security invoker set search_path=public as $$
begin
  if auth.uid() <> '37c1c818-cc07-4e91-992d-2f9eb0c14573'::uuid then
    raise exception 'No autorizado';
  end if;
  update public.repartos set is_open=false, closed_at=now()
  where id=p_reparto_id and is_open=true;
end;
$$;

create or replace function public.eliminar_pedido_admin(p_pedido_id uuid)
returns void language plpgsql security invoker set search_path=public as $$
begin
  if auth.uid() <> '37c1c818-cc07-4e91-992d-2f9eb0c14573'::uuid then
    raise exception 'No autorizado';
  end if;
  delete from public.pedidos where id=p_pedido_id;
end;
$$;

revoke execute on function public.abrir_reparto(uuid), public.cerrar_reparto(uuid),
  public.abrir_reparto_admin(uuid), public.cerrar_reparto_admin(uuid),
  public.eliminar_pedido_admin(uuid) from public, anon;

grant execute on function public.abrir_reparto(uuid), public.cerrar_reparto(uuid),
  public.abrir_reparto_admin(uuid), public.cerrar_reparto_admin(uuid),
  public.eliminar_pedido_admin(uuid) to authenticated, service_role;

create index if not exists idx_pedidos_reparto_id on public.pedidos(reparto_id);
