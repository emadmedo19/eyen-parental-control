alter table public.pairing_sessions
  add column if not exists last_seen_at timestamptz,
  add column if not exists battery_percent smallint,
  add column if not exists is_charging boolean,
  add column if not exists location_lat double precision,
  add column if not exists location_lon double precision,
  add column if not exists location_accuracy_m double precision,
  add column if not exists location_updated_at timestamptz;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'pairing_sessions_battery_percent_check') then
    alter table public.pairing_sessions add constraint pairing_sessions_battery_percent_check
      check (battery_percent is null or battery_percent between 0 and 100);
  end if;
  if not exists (select 1 from pg_constraint where conname = 'pairing_sessions_location_lat_check') then
    alter table public.pairing_sessions add constraint pairing_sessions_location_lat_check
      check (location_lat is null or location_lat between -90 and 90);
  end if;
  if not exists (select 1 from pg_constraint where conname = 'pairing_sessions_location_lon_check') then
    alter table public.pairing_sessions add constraint pairing_sessions_location_lon_check
      check (location_lon is null or location_lon between -180 and 180);
  end if;
end $$;

create index if not exists pairing_sessions_last_seen_idx on public.pairing_sessions (last_seen_at desc);
