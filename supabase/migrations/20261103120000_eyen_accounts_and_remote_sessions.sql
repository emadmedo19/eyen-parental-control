create table if not exists public.eyen_user_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  install_id uuid not null,
  role text not null check (role in ('parent','child')),
  app_id text not null check (app_id in ('app.eyen.parent','app.eyen.child')),
  device_name text not null default 'Android device',
  created_at timestamptz not null default now(),
  last_seen_at timestamptz,
  revoked_at timestamptz,
  unique (user_id, install_id),
  unique (install_id, role)
);
alter table public.pairing_sessions
  add column if not exists parent_user_id uuid references auth.users(id) on delete set null,
  add column if not exists child_user_id uuid references auth.users(id) on delete set null;

create table if not exists public.eyen_remote_sessions (
  id uuid primary key default gen_random_uuid(),
  pairing_session_id uuid not null references public.pairing_sessions(id) on delete cascade,
  child_device_id uuid not null references public.pairing_sessions(child_device_id) on delete cascade,
  parent_user_id uuid not null references auth.users(id) on delete cascade,
  mode text not null check (mode in ('camera','audio','screen')),
  status text not null default 'requested'
    check (status in ('requested','approved','connecting','active','rejected','ended','expired','failed')),
  requested_at timestamptz not null default now(),
  approved_at timestamptz,
  started_at timestamptz,
  ended_at timestamptz,
  expires_at timestamptz not null default (now() + interval '2 minutes'),
  end_reason text
);
create index if not exists eyen_remote_sessions_child_status_idx
  on public.eyen_remote_sessions(child_device_id, status, requested_at desc);
create index if not exists eyen_remote_sessions_parent_idx
  on public.eyen_remote_sessions(parent_user_id, requested_at desc);

create table if not exists public.eyen_webrtc_signals (
  id bigint generated always as identity primary key,
  session_id uuid not null references public.eyen_remote_sessions(id) on delete cascade,
  sender_role text not null check (sender_role in ('parent','child')),
  signal_type text not null check (signal_type in ('offer','answer','ice','hangup')),
  payload jsonb not null,
  created_at timestamptz not null default now()
);
create index if not exists eyen_webrtc_signals_session_idx
  on public.eyen_webrtc_signals(session_id, id);

alter table public.eyen_user_devices enable row level security;
alter table public.eyen_remote_sessions enable row level security;
alter table public.eyen_webrtc_signals enable row level security;
revoke all on public.eyen_user_devices from anon, authenticated;
revoke all on public.eyen_remote_sessions from anon, authenticated;
revoke all on public.eyen_webrtc_signals from anon, authenticated;
