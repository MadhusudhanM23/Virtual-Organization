#!/bin/sh
# Runs INSIDE the postgres container (piped by scripts/dr-backup.ps1). Takes a consistent online backup:
# one REPEATABLE READ transaction exports a snapshot and computes the manifest; pg_dump uses the same snapshot, so the
# manifest describes exactly the dump even while the platform keeps writing.
# Needs: PGUSER, PGPASSWORD, PGDATABASE in the environment; the manifest SQL on /tmp/manifest.sql.
set -eu
rm -f /tmp/q /tmp/snap.out /tmp/vorg.dump /tmp/globals.sql
mkfifo /tmp/q
psql -q -At -v ON_ERROR_STOP=1 < /tmp/q > /tmp/snap.out 2>/tmp/snap.err &
PSQL_PID=$!
exec 3> /tmp/q
echo "BEGIN ISOLATION LEVEL REPEATABLE READ;" >&3
echo "SELECT pg_export_snapshot();" >&3
i=0
while [ ! -s /tmp/snap.out ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
SNAP=$(head -n 1 /tmp/snap.out)
[ -n "$SNAP" ] || { echo "no snapshot id"; cat /tmp/snap.err; exit 1; }
cat /tmp/manifest.sql >&3
i=0
while [ "$(wc -l < /tmp/snap.out)" -lt 2 ] && [ $i -lt 600 ]; do sleep 0.1; i=$((i + 1)); done
sed -n 2p /tmp/snap.out > /tmp/manifest.json
pg_dump --snapshot="$SNAP" --format=custom --compress=6 --file=/tmp/vorg.dump
echo "COMMIT;" >&3
exec 3>&-
wait $PSQL_PID
# Cluster-level objects (roles with their password hashes). Treat the backup directory as secret.
pg_dumpall --roles-only --file=/tmp/globals.sql
echo "snapshot=$SNAP"
