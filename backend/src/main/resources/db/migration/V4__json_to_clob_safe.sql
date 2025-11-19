-- liquibase formatted sql

-- changeset app:20251104-001-json-to-clob-interactions
-- preconditions onFail:MARK_RAN onError:MARK_RAN
-- precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM user_tab_columns WHERE table_name = 'INTERACTIONS' AND column_name = 'PARAMS_JSON' AND data_type NOT IN ('CLOB','JSON')
-- comment: Safely migrate INTERACTIONS.PARAMS_JSON to CLOB if currently non-CLOB/JSON type
ALTER TABLE interactions ADD (params_json_clob CLOB);
UPDATE interactions SET params_json_clob = TO_CLOB(params_json);
ALTER TABLE interactions DROP COLUMN params_json;
ALTER TABLE interactions RENAME COLUMN params_json_clob TO params_json;
-- rollback NOP

-- changeset app:20251104-002-json-to-clob-memory-kv
-- preconditions onFail:MARK_RAN onError:MARK_RAN
-- precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM user_tab_columns WHERE table_name = 'MEMORY_KV' AND column_name = 'VALUE_JSON' AND data_type NOT IN ('CLOB','JSON')
-- comment: Safely migrate MEMORY_KV.VALUE_JSON to CLOB if currently non-CLOB/JSON type
ALTER TABLE memory_kv ADD (value_json_clob CLOB);
UPDATE memory_kv SET value_json_clob = TO_CLOB(value_json);
ALTER TABLE memory_kv DROP COLUMN value_json;
ALTER TABLE memory_kv RENAME COLUMN value_json_clob TO value_json;
-- rollback NOP
