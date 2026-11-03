-- Read all backup categories in one statement so auto-sync comparisons see a
-- single database snapshot. SECURITY INVOKER preserves the caller's RLS identity.
create or replace function public.app_read_my_backup()
returns jsonb
language sql
stable
security invoker
set search_path = pg_catalog, public
as $$
    select jsonb_build_object(
        'settings', coalesce((
            select payload from public.app_cloud_settings
            where user_id = auth.uid()
        ), '{}'::jsonb),
        'profiles', jsonb_build_object('items', coalesce((
            select jsonb_agg(payload order by record_id)
            from public.app_cloud_profiles where user_id = auth.uid()
        ), '[]'::jsonb)),
        'schedules', jsonb_build_object('items', coalesce((
            select jsonb_agg(payload order by record_id)
            from public.app_cloud_schedules where user_id = auth.uid()
        ), '[]'::jsonb)),
        'focusModes', jsonb_build_object('items', coalesce((
            select jsonb_agg(payload order by record_id)
            from public.app_cloud_focus_modes where user_id = auth.uid()
        ), '[]'::jsonb))
    );
$$;

revoke all on function public.app_read_my_backup() from public, anon, authenticated;
grant execute on function public.app_read_my_backup() to authenticated;
