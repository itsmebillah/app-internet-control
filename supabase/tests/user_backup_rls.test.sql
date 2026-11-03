-- Run with Supabase CLI: supabase test db.
-- Tests prove each category table denies anonymous access and limits rows to
-- the current authenticated user. pgTAP runs inside a disposable transaction.
begin;
select plan(22);

insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at,
                        created_at, updated_at, confirmation_token, recovery_token,
                        email_change, email_change_token_new, email_change_token_current,
                        phone_change, phone_change_token, reauthentication_token)
values
 ('00000000-0000-0000-0000-000000000101','authenticated','authenticated','rls-a@example.invalid','',now(),now(),now(),'','','','','','','',''),
 ('00000000-0000-0000-0000-000000000102','authenticated','authenticated','rls-b@example.invalid','',now(),now(),now(),'','','','','','','','')
on conflict (id) do nothing;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000101', true);
select set_config('request.jwt.claim.role', 'authenticated', true);
insert into public.app_cloud_settings(user_id,payload) values
 ('00000000-0000-0000-0000-000000000101','{"test":true}'),
 ('00000000-0000-0000-0000-000000000102','{"test":false}') on conflict do nothing;
insert into public.app_cloud_profiles(user_id,record_id,payload) values
 ('00000000-0000-0000-0000-000000000101','p-a','{}'),
 ('00000000-0000-0000-0000-000000000102','p-b','{}') on conflict do nothing;
insert into public.app_cloud_schedules(user_id,record_id,payload) values
 ('00000000-0000-0000-0000-000000000101','s-a','{}'),
 ('00000000-0000-0000-0000-000000000102','s-b','{}') on conflict do nothing;
insert into public.app_cloud_focus_modes(user_id,record_id,payload) values
 ('00000000-0000-0000-0000-000000000101','f-a','{}'),
 ('00000000-0000-0000-0000-000000000102','f-b','{}') on conflict do nothing;

set local role anon;
select throws_ok($$select * from public.app_cloud_settings$$, '42501', null, 'anonymous role cannot read settings');
select throws_ok($$select * from public.app_cloud_profiles$$, '42501', null, 'anonymous role cannot read profiles');
select throws_ok($$select * from public.app_cloud_schedules$$, '42501', null, 'anonymous role cannot read schedules');
select throws_ok($$select * from public.app_cloud_focus_modes$$, '42501', null, 'anonymous role cannot read focus modes');
select throws_ok($$select public.app_read_my_backup()$$, '42501', null, 'anonymous role cannot call owner backup read RPC');
reset role;
select set_config('request.jwt.claim.role', 'authenticated', true);
set local role authenticated;
select is((select count(*)::int from public.app_cloud_settings), 1, 'settings visible only to owner');
select is((select count(*)::int from public.app_cloud_profiles), 1, 'profiles visible only to owner');
select is((select count(*)::int from public.app_cloud_schedules), 1, 'schedules visible only to owner');
select is((select count(*)::int from public.app_cloud_focus_modes), 1, 'focus modes visible only to owner');
select ok(not has_table_privilege('authenticated', 'public.app_cloud_settings', 'TRUNCATE'), 'authenticated cannot truncate settings');
select ok(not has_table_privilege('authenticated', 'public.app_cloud_profiles', 'TRUNCATE'), 'authenticated cannot truncate profiles');
select ok(not has_table_privilege('authenticated', 'public.app_cloud_schedules', 'TRUNCATE'), 'authenticated cannot truncate schedules');
select ok(not has_table_privilege('authenticated', 'public.app_cloud_focus_modes', 'TRUNCATE'), 'authenticated cannot truncate focus modes');
select throws_ok($$insert into public.app_cloud_profiles(user_id,record_id,payload) values ('00000000-0000-0000-0000-000000000102','attack','{}')$$,
                 '42501', null, 'cannot insert another user profile');
select throws_ok($$update public.app_cloud_settings set user_id='00000000-0000-0000-0000-000000000102' where user_id='00000000-0000-0000-0000-000000000101'$$,
                 '42501', null, 'cannot change ownership of settings');
select throws_ok($$insert into public.app_cloud_schedules(user_id,record_id,payload) values ('00000000-0000-0000-0000-000000000102','attack','{}')$$,
                 '42501', null, 'cannot insert another users schedule');
select throws_ok($$insert into public.app_cloud_focus_modes(user_id,record_id,payload) values ('00000000-0000-0000-0000-000000000102','attack','{}')$$,
                 '42501', null, 'cannot insert another users focus mode');
select is((with changed as (
    update public.app_cloud_settings set payload='{"attack":true}'
    where user_id='00000000-0000-0000-0000-000000000102' returning 1
) select count(*)::int from changed), 0, 'cannot update another users settings');
select is((with removed as (
    delete from public.app_cloud_profiles
    where user_id='00000000-0000-0000-0000-000000000102' returning 1
) select count(*)::int from removed), 0, 'cannot delete another users profiles');
select public.app_replace_my_backup('{"blockedPackages":["x"]}',
  '[{"id":"function-profile","name":"Mine","user_id":"00000000-0000-0000-0000-000000000102"}]', '[]', '[]');
select is((select user_id::text from public.app_cloud_profiles where record_id='function-profile'),
          '00000000-0000-0000-0000-000000000101', 'backup RPC derives ownership from auth.uid');
select is((select count(*)::int from public.app_cloud_profiles), 1, 'backup RPC did not create a row for another user');
select is(public.app_read_my_backup()->'settings'->'blockedPackages'->>0, 'x', 'read RPC returns current owner settings only');
select is(jsonb_array_length(public.app_read_my_backup()->'profiles'->'items'), 1, 'read RPC returns only owner profiles');

select * from finish();
rollback;
