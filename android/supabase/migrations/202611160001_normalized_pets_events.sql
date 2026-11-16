create table if not exists public.pets (
    id text not null,
    user_id text not null,
    name text not null,
    species text not null,
    breed text not null default '',
    age_label text not null default '',
    porte text not null default 'medio' check (porte in ('pequeno', 'medio', 'grande')),
    peso_kg double precision null check (peso_kg is null or (peso_kg > 0 and peso_kg <= 200)),
    created_at timestamptz not null default now(),
    constraint pets_pkey primary key (id),
    constraint pets_id_user_id_unique unique (id, user_id)
);

create table if not exists public.events (
    id text not null,
    user_id text not null,
    pet_id text null,
    date date not null,
    category text not null,
    title text not null,
    detail text not null default '',
    time_label text not null,
    created_at timestamptz not null default now(),
    constraint events_pkey primary key (id, user_id),
    constraint events_pet_owner_fkey foreign key (pet_id, user_id)
        references public.pets (id, user_id) on delete cascade
);

create index if not exists pets_user_id_created_at_idx on public.pets (user_id, created_at);
create index if not exists events_user_id_date_idx on public.events (user_id, date desc, time_label desc);
create index if not exists events_user_id_pet_id_idx on public.events (user_id, pet_id);

alter table public.pets enable row level security;
alter table public.events enable row level security;

drop policy if exists pets_firebase_uid_owner on public.pets;
create policy pets_firebase_uid_owner on public.pets
    for all to authenticated, anon
    using (user_id = (auth.jwt() ->> 'sub'))
    with check (user_id = (auth.jwt() ->> 'sub'));

drop policy if exists events_firebase_uid_owner on public.events;
create policy events_firebase_uid_owner on public.events
    for all to authenticated, anon
    using (user_id = (auth.jwt() ->> 'sub'))
    with check (user_id = (auth.jwt() ->> 'sub'));

grant select, insert, update, delete on public.pets to authenticated, anon;
grant select, insert, update, delete on public.events to authenticated, anon;

-- Migrate existing records from the original aggregate payload table.
insert into public.pets (id, user_id, name, species, breed, age_label, porte, peso_kg)
select pet->>'id', data.firebase_uid, pet->>'name', pet->>'species',
       coalesce(pet->>'breed', ''), coalesce(pet->>'age_label', ''),
       case when pet->>'porte' in ('pequeno', 'medio', 'grande') then pet->>'porte' else 'medio' end,
       case when nullif(pet->>'peso_kg', '') is null then null else (pet->>'peso_kg')::double precision end
from public.petguard_user_data data
cross join lateral jsonb_array_elements(coalesce(data.payload->'pets', '[]'::jsonb)) pet
where nullif(pet->>'id', '') is not null
on conflict (id) do nothing;

insert into public.events (id, user_id, pet_id, date, category, title, detail, time_label)
select event->>'id', data.firebase_uid,
       case when nullif(event->>'pet_id', '') is null then null
            when exists (select 1 from public.pets p where p.id = event->>'pet_id' and p.user_id = data.firebase_uid)
              then event->>'pet_id' else null end,
       (event->>'date')::date, coalesce(event->>'category', 'ACTIVITY'),
       coalesce(event->>'title', 'Evento'), coalesce(event->>'detail', ''), coalesce(event->>'time_label', '')
from public.petguard_user_data data
cross join lateral jsonb_array_elements(coalesce(data.payload->'events', '[]'::jsonb)) event
where nullif(event->>'id', '') is not null and nullif(event->>'date', '') is not null
on conflict (id, user_id) do update set
    pet_id = excluded.pet_id,
    date = excluded.date,
    category = excluded.category,
    title = excluded.title,
    detail = excluded.detail,
    time_label = excluded.time_label;
