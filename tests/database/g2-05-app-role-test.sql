\set ON_ERROR_STOP on

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'application test must start without tenant context';
    END IF;
    IF EXISTS (SELECT FROM public.workflow_definition)
       OR EXISTS (SELECT FROM public.workflow_version)
       OR EXISTS (SELECT FROM public.trigger_config)
       OR EXISTS (SELECT FROM public.connector)
       OR EXISTS (SELECT FROM public.secret_reference) THEN
        RAISE EXCEPTION 'G2-05 tables must fail closed without tenant context';
    END IF;

    BEGIN
        INSERT INTO public.workflow_definition (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, status, draft_dsl, draft_checksum
        ) VALUES (
            '019fb1d7-d968-81f0-81f0-0000000000f0',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:no-context', 'oidc:no-context',
            '019fb1d7-d968-7b01-8b01-000000000001',
            'No context', 'no-context', 'DRAFT', '{}'::jsonb, repeat('f', 64)
        );
        RAISE EXCEPTION 'insert without tenant context was accepted';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
END;
$test$;

-- Seed tenant B first so tenant A can prove that composite references reject existing B rows.
BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
INSERT INTO public.workflow_definition (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, status, draft_dsl, draft_checksum
) VALUES (
    '019fb1d7-d968-8102-8102-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:author-b', 'oidc:author-b',
    '019fb1d7-d968-7b02-8b02-000000000002',
    'Workflow B', 'workflow-b', 'ACTIVE', '{"nodes":[]}'::jsonb, repeat('b', 64)
);
INSERT INTO public.workflow_version (
    id, tenant_id, created_by, definition_id, version_no, schema_version,
    dsl, checksum, executor_catalog_version, published_at, published_by
) VALUES (
    '019fb1d7-d968-8202-8202-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:author-b',
    '019fb1d7-d968-8102-8102-000000000002',
    1, 1, '{"nodes":[]}'::jsonb, repeat('c', 64), 'catalog-1',
    transaction_timestamp(), 'oidc:author-b'
);
INSERT INTO public.trigger_config (
    id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type, config
) VALUES (
    '019fb1d7-d968-8302-8302-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:author-b',
    '019fb1d7-d968-8202-8202-000000000002',
    'manual_start', 'manual', '{}'::jsonb
);
INSERT INTO public.connector (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, connector_type, endpoint, config, status
) VALUES (
    '019fb1d7-d968-8402-8402-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:author-b', 'oidc:author-b', NULL,
    'Tenant B HTTP', 'tenant-b-http', 'http', 'https://tenant-b.invalid', '{}'::jsonb, 'ACTIVE'
);
INSERT INTO public.secret_reference (
    id, tenant_id, created_by, updated_by, project_id, name,
    normalized_name, provider, external_key, ciphertext, key_version, status
) VALUES (
    '019fb1d7-d968-8502-8502-000000000002',
    '019fb1d7-d968-7a02-8a02-000000000002',
    'oidc:author-b', 'oidc:author-b', NULL,
    'Tenant B Reference', 'tenant-b-reference', 'vault',
    'tenant-b/reference', NULL, 'vault-v1', 'ACTIVE'
);
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
DECLARE
    affected_rows integer;
BEGIN
    IF (SELECT count(*) FROM public.tenant) <> 1
       OR (SELECT count(*) FROM public.project) <> 1 THEN
        RAISE EXCEPTION 'V1 tenant A fixture was not preserved during V2 upgrade';
    END IF;

    INSERT INTO public.workflow_definition (
        id, tenant_id, created_by, updated_by, project_id, name,
        normalized_name, status, draft_dsl, draft_checksum
    ) VALUES (
        '019fb1d7-d968-8101-8101-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', 'oidc:author-a',
        '019fb1d7-d968-7b01-8b01-000000000001',
        'Workflow A', 'workflow-a', 'DRAFT', '{"nodes":[]}'::jsonb, repeat('a', 64)
    );
    UPDATE public.workflow_definition
       SET status = 'ACTIVE',
           draft_dsl = '{"nodes":[{"id":"start"}]}'::jsonb,
           draft_revision = draft_revision + 1,
           version = version + 1,
           updated_at = transaction_timestamp(),
           updated_by = 'oidc:author-a'
     WHERE id = '019fb1d7-d968-8101-8101-000000000001';
    IF NOT EXISTS (
        SELECT FROM public.workflow_definition
         WHERE id = '019fb1d7-d968-8101-8101-000000000001'
           AND status = 'ACTIVE'
           AND draft_revision = 1
           AND version = 1
    ) THEN
        RAISE EXCEPTION 'mutable definition revision/version update failed';
    END IF;

    BEGIN
        INSERT INTO public.workflow_definition (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, status, draft_dsl, draft_checksum
        ) VALUES (
            '019fb1d7-d968-8190-8190-000000000090',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a',
            '019fb1d7-d968-7b02-8b02-000000000002',
            'Cross tenant project', 'cross-tenant-project', 'DRAFT', '{}'::jsonb,
            repeat('0', 64)
        );
        RAISE EXCEPTION 'cross-tenant definition project FK was accepted';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_definition (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, status, draft_dsl, draft_checksum
        ) VALUES (
            '019fb1d7-d968-8191-8191-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a',
            '019fb1d7-d968-7b01-8b01-000000000001',
            'Bad checksum', 'bad-checksum', 'DRAFT', '{}'::jsonb, repeat('G', 64)
        );
        RAISE EXCEPTION 'invalid draft checksum was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_definition (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, status, draft_dsl, draft_checksum
        ) VALUES (
            '019fb1d7-d968-8192-8192-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a',
            '019fb1d7-d968-7b01-8b01-000000000001',
            'Bad DSL', 'bad-dsl', 'DRAFT', '[]'::jsonb, repeat('1', 64)
        );
        RAISE EXCEPTION 'non-object draft DSL was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_definition (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, status, draft_dsl, draft_revision, draft_checksum
        ) VALUES (
            '019fb1d7-d968-8193-8193-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a',
            '019fb1d7-d968-7b01-8b01-000000000001',
            'Bad revision', 'bad-revision', 'DRAFT', '{}'::jsonb, -1, repeat('2', 64)
        );
        RAISE EXCEPTION 'negative draft revision was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.workflow_definition
           SET status = 'UNKNOWN'
         WHERE id = '019fb1d7-d968-8101-8101-000000000001';
        RAISE EXCEPTION 'invalid definition status was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    INSERT INTO public.workflow_version (
        id, tenant_id, created_by, definition_id, version_no, schema_version,
        dsl, checksum, executor_catalog_version, published_at, published_by
    ) VALUES (
        '019fb1d7-d968-8201-8201-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a',
        '019fb1d7-d968-8101-8101-000000000001',
        1, 1, '{"nodes":[{"id":"start"}]}'::jsonb, repeat('a', 64), 'catalog-1',
        transaction_timestamp(), 'oidc:author-a'
    );

    BEGIN
        INSERT INTO public.workflow_version (
            id, tenant_id, created_by, definition_id, version_no, schema_version,
            dsl, checksum, executor_catalog_version, published_at, published_by
        ) VALUES (
            '019fb1d7-d968-8291-8291-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8101-8101-000000000001',
            1, 1, '{}'::jsonb, repeat('b', 64), 'catalog-1',
            transaction_timestamp(), 'oidc:author-a'
        );
        RAISE EXCEPTION 'duplicate workflow version number was accepted';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_version (
            id, tenant_id, created_by, definition_id, version_no, schema_version,
            dsl, checksum, executor_catalog_version, published_at, published_by
        ) VALUES (
            '019fb1d7-d968-8292-8292-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8101-8101-000000000001',
            2, 1, '{}'::jsonb, repeat('a', 64), 'catalog-1',
            transaction_timestamp(), 'oidc:author-a'
        );
        RAISE EXCEPTION 'duplicate workflow version checksum was accepted';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_version (
            id, tenant_id, created_by, definition_id, version_no, schema_version,
            dsl, checksum, executor_catalog_version, published_at, published_by
        ) VALUES (
            '019fb1d7-d968-8293-8293-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8101-8101-000000000001',
            0, 1, '{}'::jsonb, repeat('3', 64), 'catalog-1',
            transaction_timestamp(), 'oidc:author-a'
        );
        RAISE EXCEPTION 'non-positive workflow version number was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_version (
            id, tenant_id, created_by, definition_id, version_no, schema_version,
            dsl, checksum, executor_catalog_version, published_at, published_by
        ) VALUES (
            '019fb1d7-d968-8294-8294-000000000094',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8101-8101-000000000001',
            3, 0, '{}'::jsonb, repeat('4', 64), 'catalog-1',
            transaction_timestamp(), 'oidc:author-a'
        );
        RAISE EXCEPTION 'non-positive schema version was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_version (
            id, tenant_id, created_by, definition_id, version_no, schema_version,
            dsl, checksum, executor_catalog_version, published_at, published_by
        ) VALUES (
            '019fb1d7-d968-8295-8295-000000000095',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8101-8101-000000000001',
            4, 1, '{}'::jsonb, repeat('G', 64), 'catalog-1',
            transaction_timestamp(), 'oidc:author-a'
        );
        RAISE EXCEPTION 'invalid published checksum was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_version (
            id, tenant_id, created_by, definition_id, version_no, schema_version,
            dsl, checksum, executor_catalog_version, published_at, published_by
        ) VALUES (
            '019fb1d7-d968-8296-8296-000000000096',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8101-8101-000000000001',
            5, 1, '[]'::jsonb, repeat('5', 64), 'catalog-1',
            transaction_timestamp(), 'oidc:author-a'
        );
        RAISE EXCEPTION 'non-object published DSL was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.workflow_version (
            id, tenant_id, created_by, definition_id, version_no, schema_version,
            dsl, checksum, executor_catalog_version, published_at, published_by
        ) VALUES (
            '019fb1d7-d968-8297-8297-000000000097',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8101-8101-000000000001',
            6, 1, '{}'::jsonb, repeat('6', 64), 'catalog-1',
            transaction_timestamp() - interval '1 second', 'oidc:author-a'
        );
        RAISE EXCEPTION 'published_at earlier than created_at was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.workflow_version
           SET published_by = 'oidc:mutator'
         WHERE id = '019fb1d7-d968-8201-8201-000000000001';
        RAISE EXCEPTION 'application role updated immutable workflow version';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
        DELETE FROM public.workflow_version
         WHERE id = '019fb1d7-d968-8201-8201-000000000001';
        RAISE EXCEPTION 'application role deleted immutable workflow version';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    INSERT INTO public.trigger_config (
        id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type, config
    ) VALUES (
        '019fb1d7-d968-8301-8301-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', '019fb1d7-d968-8201-8201-000000000001',
        'manual_start', 'manual', '{}'::jsonb
    );
    INSERT INTO public.trigger_config (
        id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type,
        config, timezone, misfire_policy
    ) VALUES (
        '019fb1d7-d968-8303-8303-000000000003',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', '019fb1d7-d968-8201-8201-000000000001',
        'daily_sync', 'cron', '{"expression":"0 0 * * *"}'::jsonb, 'UTC', 'skip'
    );

    BEGIN
        INSERT INTO public.trigger_config (
            id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type, config
        ) VALUES (
            '019fb1d7-d968-8391-8391-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8201-8201-000000000001',
            'manual_start', 'manual', '{}'::jsonb
        );
        RAISE EXCEPTION 'duplicate trigger key was accepted';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.trigger_config (
            id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type, config
        ) VALUES (
            '019fb1d7-d968-8392-8392-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8201-8201-000000000001',
            'Bad-Key', 'manual', '{}'::jsonb
        );
        RAISE EXCEPTION 'invalid trigger key was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.trigger_config (
            id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type, config
        ) VALUES (
            '019fb1d7-d968-8393-8393-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8201-8201-000000000001',
            'bad_type', 'queue', '{}'::jsonb
        );
        RAISE EXCEPTION 'invalid trigger type was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.trigger_config (
            id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type,
            config, timezone, misfire_policy
        ) VALUES (
            '019fb1d7-d968-8394-8394-000000000094',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8201-8201-000000000001',
            'bad_cron', 'cron', '{}'::jsonb, NULL, NULL
        );
        RAISE EXCEPTION 'cron trigger without timezone/misfire policy was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.trigger_config (
            id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type,
            config, timezone, misfire_policy
        ) VALUES (
            '019fb1d7-d968-8395-8395-000000000095',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8201-8201-000000000001',
            'bad_manual_fields', 'manual', '{}'::jsonb, 'UTC', 'skip'
        );
        RAISE EXCEPTION 'manual trigger accepted cron-only fields';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.trigger_config (
            id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type, config
        ) VALUES (
            '019fb1d7-d968-8396-8396-000000000096',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8201-8201-000000000001',
            'bad_config', 'manual', '[]'::jsonb
        );
        RAISE EXCEPTION 'non-object trigger config was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.trigger_config (
            id, tenant_id, created_by, workflow_version_id, trigger_key, trigger_type, config
        ) VALUES (
            '019fb1d7-d968-8397-8397-000000000097',
            '019fb1d7-d968-7a01-8a01-000000000001', 'oidc:author-a',
            '019fb1d7-d968-8202-8202-000000000002',
            'cross_version', 'manual', '{}'::jsonb
        );
        RAISE EXCEPTION 'cross-tenant workflow version FK was accepted';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.trigger_config
           SET trigger_key = 'mutated'
         WHERE id = '019fb1d7-d968-8301-8301-000000000001';
        RAISE EXCEPTION 'application role updated immutable trigger config';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
        DELETE FROM public.trigger_config
         WHERE id = '019fb1d7-d968-8301-8301-000000000001';
        RAISE EXCEPTION 'application role deleted immutable trigger config';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    INSERT INTO public.connector (
        id, tenant_id, created_by, updated_by, project_id, name,
        normalized_name, connector_type, endpoint, config, status
    ) VALUES (
        '019fb1d7-d968-8401-8401-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', 'oidc:author-a', NULL,
        'Shared HTTP', 'shared-http', 'http', 'https://tenant-a.invalid', '{}'::jsonb, 'ACTIVE'
    );

    BEGIN
        INSERT INTO public.connector (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, connector_type, endpoint, config, status
        ) VALUES (
            '019fb1d7-d968-8491-8491-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Duplicate shared', 'shared-http', 'http', 'https://duplicate.invalid',
            '{}'::jsonb, 'ACTIVE'
        );
        RAISE EXCEPTION 'tenant-wide connector NULL uniqueness was not enforced';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.connector (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, connector_type, endpoint, config, status
        ) VALUES (
            '019fb1d7-d968-8492-8492-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Bad type', 'bad-type', 'HTTP-Type', 'https://bad.invalid', '{}'::jsonb, 'ACTIVE'
        );
        RAISE EXCEPTION 'invalid connector type was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.connector (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, connector_type, endpoint, config, status
        ) VALUES (
            '019fb1d7-d968-8493-8493-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Bad config', 'bad-config', 'http', 'https://bad.invalid', '[]'::jsonb, 'ACTIVE'
        );
        RAISE EXCEPTION 'non-object connector config was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.connector (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, connector_type, endpoint, config, status
        ) VALUES (
            '019fb1d7-d968-8494-8494-000000000094',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Blank endpoint', 'blank-endpoint', 'http', '   ', '{}'::jsonb, 'ACTIVE'
        );
        RAISE EXCEPTION 'blank connector endpoint was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.connector (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, connector_type, endpoint, config, status
        ) VALUES (
            '019fb1d7-d968-8495-8495-000000000095',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Bad status', 'bad-status', 'http', 'https://bad.invalid', '{}'::jsonb, 'UNKNOWN'
        );
        RAISE EXCEPTION 'invalid connector status was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.connector (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, connector_type, endpoint, config, status
        ) VALUES (
            '019fb1d7-d968-8496-8496-000000000096',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a',
            '019fb1d7-d968-7b02-8b02-000000000002',
            'Cross project', 'cross-project', 'http', 'https://bad.invalid', '{}'::jsonb, 'ACTIVE'
        );
        RAISE EXCEPTION 'cross-tenant connector project FK was accepted';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.connector
           SET deleted_at = transaction_timestamp(),
               deleted_by = '',
               deletion_reason = 'test'
         WHERE id = '019fb1d7-d968-8401-8401-000000000001';
        RAISE EXCEPTION 'blank connector deletion actor was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    UPDATE public.connector
       SET deleted_at = transaction_timestamp(),
           deleted_by = 'oidc:author-a',
           deletion_reason = 'integration replacement',
           updated_at = transaction_timestamp(),
           updated_by = 'oidc:author-a',
           version = version + 1
     WHERE id = '019fb1d7-d968-8401-8401-000000000001';
    IF NOT EXISTS (
        SELECT FROM public.connector
         WHERE id = '019fb1d7-d968-8401-8401-000000000001'
           AND deleted_at IS NOT NULL
           AND version = 1
    ) THEN
        RAISE EXCEPTION 'connector soft deletion did not preserve history';
    END IF;

    INSERT INTO public.connector (
        id, tenant_id, created_by, updated_by, project_id, name,
        normalized_name, connector_type, endpoint, config, status
    ) VALUES (
        '019fb1d7-d968-8403-8403-000000000003',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', 'oidc:author-a', NULL,
        'Shared HTTP replacement', 'shared-http', 'http', 'https://replacement.invalid',
        '{}'::jsonb, 'ACTIVE'
    );
    INSERT INTO public.connector (
        id, tenant_id, created_by, updated_by, project_id, name,
        normalized_name, connector_type, endpoint, config, status
    ) VALUES (
        '019fb1d7-d968-8404-8404-000000000004',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', 'oidc:author-a',
        '019fb1d7-d968-7b01-8b01-000000000001',
        'Project HTTP', 'shared-http', 'http', 'https://project.invalid', '{}'::jsonb, 'ACTIVE'
    );

    BEGIN
        DELETE FROM public.connector
         WHERE id = '019fb1d7-d968-8403-8403-000000000003';
        RAISE EXCEPTION 'application role physically deleted connector';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    INSERT INTO public.secret_reference (
        id, tenant_id, created_by, updated_by, project_id, name,
        normalized_name, provider, external_key, ciphertext, key_version, status
    ) VALUES (
        '019fb1d7-d968-8501-8501-000000000001',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', 'oidc:author-a', NULL,
        'API token', 'api-token', 'local', NULL, decode('01020304', 'hex'), 'key-v1', 'ACTIVE'
    );
    INSERT INTO public.secret_reference (
        id, tenant_id, created_by, updated_by, project_id, name,
        normalized_name, provider, external_key, ciphertext, key_version, status
    ) VALUES (
        '019fb1d7-d968-8504-8504-000000000004',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', 'oidc:author-a', NULL,
        'Vault token', 'vault-token', 'vault', 'tenant-a/reference', NULL, 'vault-v1', 'ACTIVE'
    );

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8591-8591-000000000091',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Duplicate token', 'api-token', 'local', NULL, decode('05', 'hex'), 'key-v1', 'ACTIVE'
        );
        RAISE EXCEPTION 'tenant-wide secret NULL uniqueness was not enforced';
    EXCEPTION
        WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8592-8592-000000000092',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Missing local material', 'missing-local', 'local', NULL, NULL, 'key-v1', 'ACTIVE'
        );
        RAISE EXCEPTION 'local secret without ciphertext was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8589-8589-000000000089',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Bad secret status', 'bad-secret-status', 'vault', 'valid/reference', NULL,
            'vault-v1', 'UNKNOWN'
        );
        RAISE EXCEPTION 'invalid secret status was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8593-8593-000000000093',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Empty local material', 'empty-local', 'local', NULL, decode('', 'hex'), 'key-v1', 'ACTIVE'
        );
        RAISE EXCEPTION 'empty local ciphertext was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8594-8594-000000000094',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Local both materials', 'local-both', 'local', 'unexpected/reference',
            decode('06', 'hex'), 'key-v1', 'ACTIVE'
        );
        RAISE EXCEPTION 'local secret accepted both material forms';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8595-8595-000000000095',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'External ciphertext', 'external-ciphertext', 'vault', NULL,
            decode('07', 'hex'), 'vault-v1', 'ACTIVE'
        );
        RAISE EXCEPTION 'external provider accepted ciphertext';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8596-8596-000000000096',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Blank external key', 'blank-external', 'vault', '   ', NULL, 'vault-v1', 'ACTIVE'
        );
        RAISE EXCEPTION 'blank external key was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8597-8597-000000000097',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Bad provider', 'bad-provider', 'Vault-Type', 'valid/reference', NULL,
            'vault-v1', 'ACTIVE'
        );
        RAISE EXCEPTION 'invalid secret provider was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8598-8598-000000000098',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a', NULL,
            'Blank key version', 'blank-key-version', 'vault', 'valid/reference', NULL,
            '   ', 'ACTIVE'
        );
        RAISE EXCEPTION 'blank secret key version was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO public.secret_reference (
            id, tenant_id, created_by, updated_by, project_id, name,
            normalized_name, provider, external_key, ciphertext, key_version, status
        ) VALUES (
            '019fb1d7-d968-8599-8599-000000000099',
            '019fb1d7-d968-7a01-8a01-000000000001',
            'oidc:author-a', 'oidc:author-a',
            '019fb1d7-d968-7b02-8b02-000000000002',
            'Cross secret project', 'cross-secret-project', 'vault', 'valid/reference', NULL,
            'vault-v1', 'ACTIVE'
        );
        RAISE EXCEPTION 'cross-tenant secret project FK was accepted';
    EXCEPTION
        WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        UPDATE public.secret_reference
           SET deleted_at = transaction_timestamp(),
               deleted_by = 'oidc:author-a',
               deletion_reason = ''
         WHERE id = '019fb1d7-d968-8501-8501-000000000001';
        RAISE EXCEPTION 'blank secret deletion reason was accepted';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    UPDATE public.secret_reference
       SET deleted_at = transaction_timestamp(),
           deleted_by = 'oidc:author-a',
           deletion_reason = 'integration replacement',
           updated_at = transaction_timestamp(),
           updated_by = 'oidc:author-a',
           version = version + 1
     WHERE id = '019fb1d7-d968-8501-8501-000000000001';
    INSERT INTO public.secret_reference (
        id, tenant_id, created_by, updated_by, project_id, name,
        normalized_name, provider, external_key, ciphertext, key_version, status
    ) VALUES (
        '019fb1d7-d968-8503-8503-000000000003',
        '019fb1d7-d968-7a01-8a01-000000000001',
        'oidc:author-a', 'oidc:author-a', NULL,
        'API token replacement', 'api-token', 'local', NULL, decode('0809', 'hex'),
        'key-v2', 'ACTIVE'
    );

    BEGIN
        DELETE FROM public.secret_reference
         WHERE id = '019fb1d7-d968-8503-8503-000000000003';
        RAISE EXCEPTION 'application role physically deleted secret reference';
    EXCEPTION
        WHEN insufficient_privilege THEN NULL;
    END;

    UPDATE public.workflow_definition
       SET name = 'cross-tenant-write-must-not-happen'
     WHERE id = '019fb1d7-d968-8102-8102-000000000002';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 0 THEN
        RAISE EXCEPTION 'tenant A updated tenant B definition';
    END IF;
END;
$test$;
COMMIT;

DO $test$
BEGIN
    IF public.app_tenant_id() IS NOT NULL THEN
        RAISE EXCEPTION 'tenant context leaked through transaction boundary';
    END IF;
    IF EXISTS (SELECT FROM public.workflow_definition)
       OR EXISTS (SELECT FROM public.workflow_version)
       OR EXISTS (SELECT FROM public.trigger_config)
       OR EXISTS (SELECT FROM public.connector)
       OR EXISTS (SELECT FROM public.secret_reference) THEN
        RAISE EXCEPTION 'G2-05 rows leaked without tenant context';
    END IF;
END;
$test$;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a01-8a01-000000000001');
DO $test$
BEGIN
    IF (SELECT count(*) FROM public.workflow_definition) <> 1
       OR (SELECT count(*) FROM public.workflow_version) <> 1
       OR (SELECT count(*) FROM public.trigger_config) <> 2
       OR (SELECT count(*) FROM public.connector) <> 3
       OR (SELECT count(*) FROM public.secret_reference) <> 3 THEN
        RAISE EXCEPTION 'tenant A row counts or RLS isolation drifted';
    END IF;
    IF EXISTS (
        SELECT FROM public.workflow_definition
         WHERE id = '019fb1d7-d968-8102-8102-000000000002'
    ) THEN
        RAISE EXCEPTION 'tenant A can see tenant B definition';
    END IF;
END;
$test$;
COMMIT;

BEGIN;
SELECT public.app_set_tenant_context('019fb1d7-d968-7a02-8a02-000000000002');
DO $test$
DECLARE
    affected_rows integer;
BEGIN
    IF (SELECT count(*) FROM public.workflow_definition) <> 1
       OR (SELECT count(*) FROM public.workflow_version) <> 1
       OR (SELECT count(*) FROM public.trigger_config) <> 1
       OR (SELECT count(*) FROM public.connector) <> 1
       OR (SELECT count(*) FROM public.secret_reference) <> 1 THEN
        RAISE EXCEPTION 'tenant B row counts or RLS isolation drifted';
    END IF;
    UPDATE public.workflow_definition
       SET name = 'tenant-b-must-not-update-a'
     WHERE id = '019fb1d7-d968-8101-8101-000000000001';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 0 THEN
        RAISE EXCEPTION 'tenant B updated tenant A definition';
    END IF;
END;
$test$;
COMMIT;

SELECT 'G2-05 application-role tests PASS (secret material not emitted)' AS result;
