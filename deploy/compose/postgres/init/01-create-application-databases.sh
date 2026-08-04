#!/usr/bin/env bash
set -Eeuo pipefail

: "${POSTGRES_APP_USER:?POSTGRES_APP_USER is required}"
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD is required}"
: "${POSTGRES_MIGRATION_USER:?POSTGRES_MIGRATION_USER is required}"
: "${POSTGRES_MIGRATION_PASSWORD:?POSTGRES_MIGRATION_PASSWORD is required}"
: "${POSTGRES_PLATFORM_ADMIN_ROLE:?POSTGRES_PLATFORM_ADMIN_ROLE is required}"
: "${KEYCLOAK_DB:?KEYCLOAK_DB is required}"
: "${KEYCLOAK_DB_USER:?KEYCLOAK_DB_USER is required}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD is required}"

psql --set ON_ERROR_STOP=1 \
  --username "${POSTGRES_USER}" \
  --dbname "${POSTGRES_DB}" \
  --set app_database="${POSTGRES_DB}" \
  --set migration_user="${POSTGRES_MIGRATION_USER}" \
  --set migration_password="${POSTGRES_MIGRATION_PASSWORD}" \
  --set app_user="${POSTGRES_APP_USER}" \
  --set app_password="${POSTGRES_APP_PASSWORD}" \
  --set platform_admin_role="${POSTGRES_PLATFORM_ADMIN_ROLE}" \
  --set keycloak_database="${KEYCLOAK_DB}" \
  --set keycloak_user="${KEYCLOAK_DB_USER}" \
  --set keycloak_password="${KEYCLOAK_DB_PASSWORD}" <<'SQL'
SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'migration_user',
  :'migration_password'
)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'migration_user')
\gexec

SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'app_user',
  :'app_password'
)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user')
\gexec

SELECT format(
  'CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'platform_admin_role'
)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'platform_admin_role')
\gexec

SELECT format('ALTER DATABASE %I OWNER TO %I', :'app_database', :'migration_user')
\gexec

SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'app_database')
\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'app_database', :'migration_user')
\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'app_database', :'app_user')
\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'app_database', :'platform_admin_role')
\gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'keycloak_user', :'keycloak_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'keycloak_user')
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'keycloak_database', :'keycloak_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'keycloak_database')
\gexec

REVOKE CONNECT ON DATABASE :"keycloak_database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"keycloak_database" TO :"keycloak_user";
SQL

psql --set ON_ERROR_STOP=1 \
  --username "${POSTGRES_USER}" \
  --dbname "${POSTGRES_DB}" \
  --set migration_user="${POSTGRES_MIGRATION_USER}" \
  --set platform_admin_role="${POSTGRES_PLATFORM_ADMIN_ROLE}" \
  --set app_user="${POSTGRES_APP_USER}" <<'SQL'
ALTER SCHEMA public OWNER TO :"migration_user";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO :"migration_user";
GRANT USAGE ON SCHEMA public TO :"app_user", :"platform_admin_role";
SQL
