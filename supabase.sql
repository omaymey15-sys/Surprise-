-- À coller dans Supabase > SQL Editor > New query, puis Run.

create table if not exists surprises (
  code text primary key check (code ~ '^[0-9]{14}$'),
  lat double precision not null,
  lon double precision not null,
  hint text not null default '',
  msg text not null default '',
  photos jsonb not null,
  created_at timestamptz not null default now()
);

-- Sécurité : aucune règle d'accès direct. La clé publique ne peut PAS lister la table.
alter table surprises enable row level security;

-- Créer une surprise
create or replace function create_surprise(
  p_code text, p_lat double precision, p_lon double precision,
  p_hint text, p_msg text, p_photos jsonb
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if p_code !~ '^[0-9]{14}$' then
    raise exception 'code invalide';
  end if;
  if jsonb_typeof(p_photos) <> 'array'
     or jsonb_array_length(p_photos) < 1
     or jsonb_array_length(p_photos) > 5 then
    raise exception 'photos invalides';
  end if;
  insert into surprises (code, lat, lon, hint, msg, photos)
  values (p_code, p_lat, p_lon,
          left(coalesce(p_hint, ''), 500),
          left(coalesce(p_msg, ''), 2000),
          p_photos);
end $$;

-- Lire UNE surprise à partir de son code exact
create or replace function get_surprise(p_code text)
returns table (lat double precision, lon double precision, hint text, msg text, photos jsonb)
language sql security definer set search_path = public as $$
  select s.lat, s.lon, s.hint, s.msg, s.photos
  from surprises s
  where s.code = p_code;
$$;

grant execute on function create_surprise(text, double precision, double precision, text, text, jsonb) to anon;
grant execute on function get_surprise(text) to anon;
