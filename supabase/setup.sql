-- UoR Call 3.0 – internet mode (run once in Supabase → SQL Editor)
-- Stores only: 8-digit ID, display name, public key, optional phone hash.
-- No messages, no audio, no video ever touch this database.

create table if not exists public.users (
  code        text primary key check (code ~ '^[0-9]{8}$'),
  name        text not null check (char_length(name) between 1 and 60),
  pubkey      text not null check (char_length(pubkey) between 80 and 200),
  phone_hash  text check (phone_hash is null or phone_hash ~ '^[0-9a-f]{64}$'),
  created_at  timestamptz not null default now()
);

create index if not exists users_phone_hash_idx on public.users (phone_hash);

alter table public.users enable row level security;

-- anyone can look up an ID (needed to call it)
drop policy if exists "lookup" on public.users;
create policy "lookup" on public.users for select using (true);

-- anyone can register a NEW ID; nobody can edit or delete someone else's
drop policy if exists "register" on public.users;
create policy "register" on public.users for insert with check (true);

-- ============ Release 4: internet messages (run this part too) ============
-- Offline inbox: end-to-end encrypted envelopes wait here until the phone comes online (max 14 days).
create table if not exists public.inbox (
  id          uuid primary key,
  to_code     text not null check (to_code ~ '^[0-9]{8}$'),
  from_code   text not null check (from_code ~ '^[0-9]{8}$'),
  env         text not null check (char_length(env) < 32000),
  created_at  timestamptz not null default now()
);
create index if not exists inbox_to_idx on public.inbox (to_code, created_at);
alter table public.inbox enable row level security;
drop policy if exists "inbox read" on public.inbox;
create policy "inbox read" on public.inbox for select using (true);      -- contents are encrypted
drop policy if exists "inbox send" on public.inbox;
create policy "inbox send" on public.inbox for insert with check (true);

-- automatic clean-up of messages older than 14 days
create or replace function public.inbox_cleanup() returns trigger language plpgsql security definer as $$
begin
  delete from public.inbox where created_at < now() - interval '14 days';
  return null;
end $$;
drop trigger if exists inbox_cleanup_trg on public.inbox;
create trigger inbox_cleanup_trg after insert on public.inbox for each statement execute function public.inbox_cleanup();

-- Encrypted photos / videos / files (random names, AES-256-GCM encrypted before upload, max 25 MB)
insert into storage.buckets (id, name, public, file_size_limit)
values ('media', 'media', true, 26214400)
on conflict (id) do nothing;
drop policy if exists "media upload" on storage.objects;
create policy "media upload" on storage.objects for insert to anon with check (bucket_id = 'media');
