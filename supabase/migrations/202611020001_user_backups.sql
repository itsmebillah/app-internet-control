-- Feature 10: narrowly scoped, owner-only backup records.
-- Usage history and analytics are intentionally excluded.
create table if not exists public.app_cloud_settings (
    user_id uuid primary key references auth.users(id) on delete cascade,
    payload jsonb not null,
    revision bigint not null default 1 check (revision > 0),
    updated_at timestamptz not null default now()
);

create table if not exists public.app_cloud_profiles (
    user_id uuid not null references auth.users(id) on delete cascade,
    record_id text not null,
    payload jsonb not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, record_id)
);

create table if not exists public.app_cloud_schedules (
    user_id uuid not null references auth.users(id) on delete cascade,
    record_id text not null,
    payload jsonb not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, record_id)
);

create table if not exists public.app_cloud_focus_modes (
    user_id uuid not null references auth.users(id) on delete cascade,
    record_id text not null,
    payload jsonb not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, record_id)
);

alter table public.app_cloud_settings enable row level security;
alter table public.app_cloud_profiles enable row level security;
alter table public.app_cloud_schedules enable row level security;
alter table public.app_cloud_focus_modes enable row level security;

drop policy if exists "app cloud owner access" on public.app_cloud_settings;
create policy "app cloud owner access" on public.app_cloud_settings
    for all to authenticated using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "app cloud owner access" on public.app_cloud_profiles;
create policy "app cloud owner access" on public.app_cloud_profiles
    for all to authenticated using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "app cloud owner access" on public.app_cloud_schedules;
create policy "app cloud owner access" on public.app_cloud_schedules
    for all to authenticated using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "app cloud owner access" on public.app_cloud_focus_modes;
create policy "app cloud owner access" on public.app_cloud_focus_modes
    for all to authenticated using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

-- Remove any default/PUBLIC grants first, then add only the four operations
-- required by the client. In particular, authenticated must not retain TRUNCATE.
revoke all on public.app_cloud_settings, public.app_cloud_profiles,
    public.app_cloud_schedules, public.app_cloud_focus_modes from public, anon, authenticated;
grant select, insert, update, delete on public.app_cloud_settings,
    public.app_cloud_profiles, public.app_cloud_schedules, public.app_cloud_focus_modes to authenticated;

-- One transaction for a user-confirmed full backup replacement. SECURITY INVOKER
-- keeps the caller's RLS identity in force; user_id always comes from auth.uid().
create or replace function public.app_replace_my_backup(
    p_settings jsonb,
    p_profiles jsonb,
    p_schedules jsonb,
    p_focus_modes jsonb
) returns void
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
    owner_id uuid := auth.uid();
begin
    if owner_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    insert into public.app_cloud_settings(user_id, payload, revision, updated_at)
    values (owner_id, coalesce(p_settings, '{}'::jsonb), 1, now())
    on conflict (user_id) do update
        set payload = excluded.payload,
            revision = public.app_cloud_settings.revision + 1,
            updated_at = excluded.updated_at;

    delete from public.app_cloud_profiles where user_id = owner_id;
    insert into public.app_cloud_profiles(user_id, record_id, payload)
        select owner_id, item ->> 'id', item
        from jsonb_array_elements(coalesce(p_profiles, '[]'::jsonb)) as rows(item);

    delete from public.app_cloud_schedules where user_id = owner_id;
    insert into public.app_cloud_schedules(user_id, record_id, payload)
        select owner_id, item ->> 'id', item
        from jsonb_array_elements(coalesce(p_schedules, '[]'::jsonb)) as rows(item);

    delete from public.app_cloud_focus_modes where user_id = owner_id;
    insert into public.app_cloud_focus_modes(user_id, record_id, payload)
        select owner_id, item ->> 'id', item
        from jsonb_array_elements(coalesce(p_focus_modes, '[]'::jsonb)) as rows(item);
end;
$$;

revoke all on function public.app_replace_my_backup(jsonb, jsonb, jsonb, jsonb) from public, anon, authenticated;
grant execute on function public.app_replace_my_backup(jsonb, jsonb, jsonb, jsonb) to authenticated;
