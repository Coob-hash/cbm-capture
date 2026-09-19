-- The API's database login, cbm_app_api, can do exactly one thing: call the entry functions below.
-- It holds no table privileges in either schema. The workflows' functions in public run with the
-- caller's rights, so this login cannot use them to reach workflow tables either.
-- The role is created without a password here; the installer sets one (never stored in Git).
-- Repeatable. Apply after 001_app_schema.sql.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cbm_app_api') THEN
  CREATE ROLE cbm_app_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
 END IF;
END $$;

REVOKE ALL ON SCHEMA cbm_app FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA cbm_app FROM PUBLIC, cbm_app_api;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA cbm_app FROM PUBLIC, cbm_app_api;
-- PostgreSQL grants EXECUTE on every new function to PUBLIC. Withdraw it from all app functions,
-- then grant back only the entry points.
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA cbm_app FROM PUBLIC, cbm_app_api;
GRANT USAGE ON SCHEMA cbm_app TO cbm_app_api;
GRANT EXECUTE ON FUNCTION
 cbm_app.sign_up(jsonb),
 cbm_app.login(jsonb),
 cbm_app.select_membership(text, uuid),
 cbm_app.me(text),
 cbm_app.authenticate(text, text[]),
 cbm_app.logout(text),
 cbm_app.decide_membership(jsonb),
 cbm_app.claim_capture(text, jsonb),
 cbm_app.store_capture(text, jsonb),
 cbm_app.reporter_reports(text, integer)
TO cbm_app_api;
COMMIT;
