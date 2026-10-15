-- DR manifest (Phase 11, plan 11.2): one JSON row describing the database content that must survive a restore.
-- Used by scripts/dr-backup.ps1 (inside the pg_dump snapshot) and scripts/dr-restore.ps1 (against the restored
-- database); the two outputs are compared. Run as the owner (superuser in the local container, so RLS does not hide rows).
select json_build_object(
    'taken_at', to_char(now() at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    -- Row counts of every base table in the application schemas (partition children included).
    'tables', (
        select json_object_agg(t.name, t.n order by t.name)
        from (
            select table_schema || '.' || table_name as name,
                   (xpath('/row/c/text()', query_to_xml(
                       format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text::bigint as n
            from information_schema.tables
            where table_type = 'BASE TABLE'
              and table_schema in ('iam', 'world', 'ops', 'evt', 'audit', 'cmd', 'sim', 'rec', 'kpi', 'ai', 'integ')
        ) t
    ),
    -- Event chain: content digest, newest ingestion time, and per-aggregate sequence continuity (1..n without gaps).
    'events_digest', (select md5(coalesce(string_agg(event_id::text || ':' || coalesce(sequence::text, '-'), ',' order by event_id), '')) from evt.events),
    'events_max_ingested_at', (select to_char(max(ingested_at) at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') from evt.events),
    'event_sequence_gaps', (
        select count(*) from (
            select 1 from evt.events where sequence is not null
            group by aggregate_type, aggregate_id
            having min(sequence) <> 1 or max(sequence) <> count(*)
        ) g
    ),
    'duplicate_event_ids', (select count(*) - count(distinct event_id) from evt.events),
    'outbox_unpublished', (select count(*) from evt.outbox where published_at is null),
    -- Audit chain: append-only content digest and newest row.
    'audit_digest', (select md5(coalesce(string_agg(id::text || ':' || action || ':' || decision, ',' order by id), '')) from audit.audit_log),
    'audit_max_occurred_at', (select to_char(max(occurred_at) at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') from audit.audit_log),
    'audit_append_only_triggers', (
        select count(*) from pg_trigger
        where tgrelid = 'audit.audit_log'::regclass and not tgisinternal and tgname in ('audit_log_no_update_delete', 'audit_log_no_truncate')
    ),
    'command_idempotency_keys', (select count(*) from cmd.commands)
)::text;
