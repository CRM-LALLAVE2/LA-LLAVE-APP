-- La Llave · GPS: escritura atómica y liviana (reemplaza "bajar toda la fila y volver a subirla")
-- Ejecutar UNA vez en Supabase ▸ SQL Editor ▸ New query ▸ Run. Es seguro volver a ejecutarlo.
-- Cada teléfono manda ~300 bytes; el servidor los mezcla en gps_track_<sucursal> sin pisar lo de otros.

create or replace function public.gps_merge_list(existing jsonb, incoming jsonb, cutoff bigint, maxn int)
returns jsonb
language sql
immutable
as $$
  select coalesce(
    (select jsonb_agg(e order by ord)
       from (
         select e, row_number() over () as ord
         from (
           select i.e, i.o
           from jsonb_array_elements(coalesce(incoming, '[]'::jsonb)) with ordinality as i(e, o)
           where not exists (select 1 from jsonb_array_elements(coalesce(existing, '[]'::jsonb)) x where x->>'id' = i.e->>'id')
           union all
           select x.e, 1000000 + x.o
           from jsonb_array_elements(coalesce(existing, '[]'::jsonb)) with ordinality as x(e, o)
         ) u
         where coalesce((e->>'ts')::bigint, 0) >= cutoff
         order by o
         limit maxn
       ) s
    ), '[]'::jsonb);
$$;

create or replace function public.gps_apply(p_sid text, p_veh text, p_token text, p jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_row text := 'gps_track_' || p_sid;
  v_ok boolean := false;
  v_tok text;
  now_ms bigint := (extract(epoch from now()) * 1000)::bigint;
  pl jsonb; live jsonb; trips jsonb; t jsonb; elem jsonb; pts jsonb; zs jsonb; np jsonb;
  idx int; i int; n int;
begin
  if p_sid is null or p_sid !~ '^[A-Za-z0-9_-]{1,40}$' or p_veh is null or length(p_veh) > 80 or p_token is null or p is null then
    raise exception 'bad_request';
  end if;

  -- El token del vehículo debe coincidir (fila pública de tokens, o fila de vehículos)
  select payload->'tokens'->p_veh->>'token' into v_tok from clientes where id = 'gps_public_' || p_sid;
  if v_tok is not null and v_tok = p_token then v_ok := true; end if;
  if not v_ok then
    perform 1 from clientes c, jsonb_array_elements(coalesce(c.payload->'vehiculos', '[]'::jsonb)) v
      where c.id = 'vehiculos_' || p_sid and v->>'id' = p_veh and v->>'gpsToken' = p_token limit 1;
    v_ok := found;
  end if;
  if not v_ok then raise exception 'forbidden'; end if;

  select payload into pl from clientes where id = v_row for update;
  if pl is null then
    pl := jsonb_build_object('id', v_row, 'tipoRegistro', 'gps_flota', 'sucursal', p_sid,
                             'live', '{}'::jsonb, 'trips', '[]'::jsonb, 'alertas', '[]'::jsonb, 'geofences', '[]'::jsonb);
    insert into clientes(id, payload) values (v_row, pl) on conflict (id) do nothing;
    select payload into pl from clientes where id = v_row for update;
  end if;

  -- posición en vivo (se mezcla con lo que ya había de este vehículo)
  if p ? 'live' then
    live := coalesce(pl->'live', '{}'::jsonb);
    live := jsonb_set(live, array[p_veh], coalesce(live->p_veh, '{}'::jsonb) || (p->'live'), true);
    pl := jsonb_set(pl, '{live}', live, true);
  end if;

  -- puntos del viaje (sin duplicar por marca de tiempo)
  if p ? 'trip' and (p->'trip') ? 'id' then
    t := p->'trip';
    trips := coalesce(pl->'trips', '[]'::jsonb);
    idx := null;
    n := jsonb_array_length(trips);
    for i in 0 .. n - 1 loop
      if trips->i->>'id' = t->>'id' then idx := i; exit; end if;
    end loop;
    if idx is null then
      elem := jsonb_build_object('id', t->>'id', 'vehId', p_veh,
                                 'startTs', coalesce((t->>'startTs')::bigint, now_ms),
                                 'endTs', 'null'::jsonb, 'points', '[]'::jsonb);
      trips := jsonb_build_array(elem) || trips;
      idx := 0;
    end if;
    elem := trips->idx;
    pts := coalesce(elem->'points', '[]'::jsonb);
    for np in select value from jsonb_array_elements(coalesce(t->'points', '[]'::jsonb)) loop
      if not exists (select 1 from jsonb_array_elements(pts) e where e->0 = np->0) then
        pts := pts || jsonb_build_array(np);
      end if;
    end loop;
    n := jsonb_array_length(pts);
    if n > 2500 then
      pts := (select jsonb_agg(e order by o) from jsonb_array_elements(pts) with ordinality as x(e, o) where o > n - 2500);
    end if;
    elem := jsonb_set(elem, '{points}', pts, true);
    if (t ? 'endTs') and jsonb_typeof(t->'endTs') = 'number' then
      elem := jsonb_set(elem, '{endTs}', t->'endTs', true);
    end if;
    trips := jsonb_set(trips, array[idx::text], elem, true);
    -- máximo 40 viajes, de los últimos 7 días
    trips := coalesce((
      select jsonb_agg(e order by o)
      from jsonb_array_elements(trips) with ordinality as x(e, o)
      where o <= 40 and coalesce((e->>'endTs')::bigint, (e->>'startTs')::bigint, now_ms) >= now_ms - 7*24*3600*1000
    ), '[]'::jsonb);
    pl := jsonb_set(pl, '{trips}', trips, true);
  end if;

  if p ? 'alerts' then
    pl := jsonb_set(pl, '{alertas}', gps_merge_list(pl->'alertas', p->'alerts', now_ms - 7*24*3600*1000, 80), true);
  end if;
  if p ? 'events' then
    pl := jsonb_set(pl, '{events}', gps_merge_list(pl->'events', p->'events', now_ms - 14*24*3600*1000, 400), true);
  end if;
  if p ? 'flags' then
    pl := jsonb_set(pl, '{flags}', gps_merge_list(pl->'flags', p->'flags', 0, 100), true);
  end if;

  if p ? 'zone' then
    zs := coalesce(pl->'zoneState', '{}'::jsonb);
    zs := jsonb_set(zs, array[p_veh], coalesce(zs->p_veh, '{}'::jsonb) || (p->'zone'), true);
    pl := jsonb_set(pl, '{zoneState}', zs, true);
  end if;

  if coalesce(p->>'clearCommand', '') = 'true' and pl ? 'commands' then
    pl := jsonb_set(pl, '{commands}', (pl->'commands') - p_veh, true);
    if pl->'commands' = '{}'::jsonb then pl := pl - 'commands'; end if;
  end if;

  pl := jsonb_set(pl, '{updatedAt}', to_jsonb(now_ms), true);
  update clientes set payload = pl where id = v_row;
end;
$$;

revoke all on function public.gps_apply(text, text, text, jsonb) from public;
grant execute on function public.gps_apply(text, text, text, jsonb) to anon, authenticated;
