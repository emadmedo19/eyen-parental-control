create table if not exists public.pairing_rate_limits (
  key_hash text primary key,
  attempt_count integer not null default 0,
  window_started_at timestamptz not null default now()
);
alter table public.pairing_rate_limits enable row level security;
revoke all on table public.pairing_rate_limits from anon, authenticated;
grant all on table public.pairing_rate_limits to service_role;

create or replace function public.consume_eyen_rate_limit(
  p_key_hash text, p_max integer, p_window_seconds integer
) returns boolean
language plpgsql security definer set search_path = public
as $$
declare current_count integer;
begin
  insert into public.pairing_rate_limits (key_hash, attempt_count, window_started_at)
  values (p_key_hash, 1, now())
  on conflict (key_hash) do update
    set attempt_count = case
          when pairing_rate_limits.window_started_at <= now() - make_interval(secs => p_window_seconds) then 1
          else pairing_rate_limits.attempt_count + 1 end,
        window_started_at = case
          when pairing_rate_limits.window_started_at <= now() - make_interval(secs => p_window_seconds) then now()
          else pairing_rate_limits.window_started_at end
  returning attempt_count into current_count;
  return current_count <= p_max;
end;
$$;
revoke all on function public.consume_eyen_rate_limit(text, integer, integer) from public, anon, authenticated;
grant execute on function public.consume_eyen_rate_limit(text, integer, integer) to service_role;
