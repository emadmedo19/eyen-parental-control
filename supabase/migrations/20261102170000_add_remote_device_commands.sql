create table if not exists public.device_commands (
  id uuid primary key default gen_random_uuid(),
  child_device_id uuid not null,
  command_type text not null check (command_type in ('LOCATION_REQUEST')),
  status text not null default 'pending' check (status in ('pending','claimed','completed','failed','expired')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '2 minutes'),
  claimed_at timestamptz,
  completed_at timestamptz,
  result jsonb,
  constraint device_commands_expiry_check check (expires_at > created_at)
);
create index if not exists device_commands_device_pending_idx
  on public.device_commands (child_device_id, status, created_at desc);
alter table public.device_commands enable row level security;
revoke all on public.device_commands from anon, authenticated;
grant all on public.device_commands to service_role;