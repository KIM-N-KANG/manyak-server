#!/bin/sh
set -eu
test "${PGHOST:?}" = "${EXPECTED_LOADTEST_HOST:?}"
case "$PGHOST" in manyak-loadtest-pg.*.rds.amazonaws.com) ;; *) echo 'Not a loadtest RDS host' >&2; exit 1 ;; esac
test "${LOADTEST_SEED_CONFIRM:-no}" = yes
export PGOPTIONS="-c manyak.loadtest_guard=manyak-loadtest-pg"
export PGSSLMODE=require
exec psql -X --no-password -f "$(dirname "$0")/seed.sql" "$@"
