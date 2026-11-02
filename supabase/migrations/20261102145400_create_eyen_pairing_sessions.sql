create table if not exists public.pairing_sessions (
  id uuid primary key,
  code_hash text not null unique,
  poll_token_hash text not null,
  expires_at timestamptz not null,
  claimed_at timestamptz,
  child_device_id uuid unique,
  child_device_name text,
  child_token_hash text,
  created_at timestamptz not null default now(),
  constraint pairing_claim_fields_consistent check (
    (claimed_at is null and child_device_id is null and child_token_hash is null)
    or
    (claimed_at is not null and child_device_id is not null and child_token_hash is not null)
  )
);
create index if not exists pairing_sessions_expiry_idx on public.pairing_sessions (expires_at);
alter table public.pairing_sessions enable row level security;
revoke all on table public.pairing_sessions from anon, authenticated;
grant all on table public.pairing_sessions to service_role;
