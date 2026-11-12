create table if not exists public.petguard_user_data (
    firebase_uid text primary key,
    payload jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now()
);

alter table public.petguard_user_data enable row level security;

do $$
begin
    if not exists (
        select 1 from pg_policies
        where schemaname = 'public'
          and tablename = 'petguard_user_data'
          and policyname = 'petguard_user_data_owner'
    ) then
        create policy petguard_user_data_owner
            on public.petguard_user_data
            for all
            to authenticated
            using (firebase_uid = (auth.jwt() ->> 'sub'))
            with check (firebase_uid = (auth.jwt() ->> 'sub'));
    end if;
end
$$;

grant select, insert, update, delete on public.petguard_user_data to authenticated;
