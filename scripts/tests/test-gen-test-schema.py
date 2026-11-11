"""The H2 schema must replay reviewed metadata-driven CHECK replacement faithfully."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / 'backend/scripts/gen-test-schema.py'
MIGRATIONS = ROOT / 'backend/shop-app/src/main/resources/db/migration'
spec = importlib.util.spec_from_file_location('test_schema_generator', GENERATOR)
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class StatementBoundaryTest(unittest.TestCase):
    def test_compact_create_definitions_convert_every_json_column_and_keep_constraint(self):
        tables, order = {}, []
        generator.create_table("CREATE TABLE compact (id BIGINT, label VARCHAR(80) DEFAULT 'comma, (quote)', a JSON, b JSON, CONSTRAINT ck CHECK(JSON_LENGTH(b)>=0))", tables, order)
        self.assertEqual(['    id BIGINT', "    label VARCHAR(80) DEFAULT 'comma, (quote)'",
                          '    a TEXT', '    b TEXT', '    CONSTRAINT ck CHECK(H2_JSON_LENGTH(b)>=0)'], tables['compact'])

    def test_json_business_backfill_is_omitted_only_for_an_owner_without_seed_rows(self):
        baseline = 'CREATE TABLE owner (id INT, payload JSON);'
        backfill = "UPDATE owner SET payload=JSON_SET(payload,'$.id',id);"
        tables, order, seeds, renames, altered = {}, [], [], {}, set()
        generator.replay(baseline + backfill, tables, order, seeds, renames, altered)
        self.assertEqual([], seeds)
        generator.replay("INSERT INTO owner(id,payload) VALUES(1,'{}');", tables, order, seeds, renames, altered)
        with self.assertRaisesRegex(ValueError, 'Unsupported JSON mutation of retained H2 seed data'):
            generator.replay(backfill, tables, order, seeds, renames, altered)

    def test_inline_mysql_charset_is_removed_only_from_sql_code(self):
        self.assertEqual("name VARCHAR(64)  DEFAULT 'CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'",
                         generator._h2_type("name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'"))


    def test_actual_inventory_comment_semicolon_preserves_column_widening(self):
        tables, order, seeds, renames, altered = {}, [], [], {}, set()
        inventory = ROOT / 'backend/inventory/inventory-domain/src/main/resources/db/inventory'
        generator.replay((inventory / 'V1__inventory_baseline.sql').read_text(),
                         tables, order, seeds, renames, altered)
        generator.replay((inventory / 'V15__production_ledger_reason_width.sql').read_text(),
                         tables, order, seeds, renames, altered)
        reason = [c for c in tables['inv_ledger'] if c.strip().startswith('reason_code ')][0]
        self.assertIn('VARCHAR(32) NOT NULL', reason)
        self.assertNotIn('COMMENT', reason)

    def test_statement_boundaries_respect_quoted_semicolons_and_doubled_quotes(self):
        sql = "SELECT 'a;''b'; SELECT \"c;d\"; SELECT `e;f`; SELECT 1;"
        self.assertEqual(["SELECT 'a;''b'", 'SELECT "c;d"', 'SELECT `e;f`', 'SELECT 1'],
                         generator.split_statements(sql))


class PermissionSeedProjectionTest(unittest.TestCase):
    def test_joined_permission_seed_keeps_predicates_without_treating_business_backfill_as_seed(self):
        migration = (MIGRATIONS / 'V655__ops_store_industry_maintenance.sql').read_text()
        statements = list(generator.sql_statements(migration))
        projected = [generator._h2_permission_seed(stmt) for stmt in statements]
        self.assertEqual(3, sum(stmt is not None for stmt in projected))
        delete = next(stmt for stmt in statements if stmt.startswith('DELETE menu_grant'))
        suffix = delete.split('FROM sys_role_point menu_grant', 1)[1].lstrip()
        self.assertEqual('DELETE FROM sys_role_point WHERE id IN (SELECT menu_grant.id '
                         'FROM sys_role_point menu_grant ' + suffix + ')',
                         generator._h2_permission_seed(delete))
        for sql in ['DELETE target FROM orders target JOIN sys_role_point p ON p.id=target.id',
                    'INSERT IGNORE INTO sys_role_point(role_code) SELECT DISTINCT role_code FROM orders',
                    'DELETE other FROM sys_role_point target JOIN sys_function_point p ON p.point_code=target.point_code']:
            self.assertIsNone(generator._h2_permission_seed(sql))


class ReleaseSourceConstraintTest(unittest.TestCase):
    def setUp(self):
        self.tables, self.order, self.seeds, self.renames, self.altered = {}, [], [], {}, set()
        # Reuse both original release table definitions verbatim; the rest of
        # V549 alters older issuer tables outside this focused fixture.
        self.baseline = (MIGRATIONS / 'V549__stored_value_issuer_ledger_dimensions.sql').read_text().split(
            'CREATE TABLE sv_issue_quota (', 1)[0]
        self.migration = (MIGRATIONS / 'V550__stored_value_legacy_activity_release.sql').read_text()
        self.replay(self.baseline)

    def replay(self, sql):
        generator.replay(sql, self.tables, self.order, self.seeds, self.renames, self.altered)

    def source_checks(self):
        return [column for column in self.tables['sv_release']
                if 'CHECK' in column and 'source_kind' in column]

    def test_real_migration_replaces_only_source_check_and_preserves_additive_ddl(self):
        tier_checks = [column for column in self.tables['sv_release_tier'] if 'CHECK' in column]
        old_uniques = [column for column in self.tables['sv_release'] if 'UNIQUE' in column]
        self.tables['sv_release'].append('CHECK (release_index > 0)')
        self.replay(self.migration)
        self.assertEqual(1, len(self.source_checks()))
        check = self.source_checks()[0]
        self.assertIn('ck_sv_release_source', check)
        self.assertIn("source_kind='V2_ACTIVITY'", check)
        self.assertIn("source_kind='LEGACY_PLAN'", check)
        self.assertIn("source_kind='LEGACY_ACTIVITY'", check)
        self.assertIn('activity_version_no=legacy_activity_version_no', check)
        self.assertIn('CHECK (release_index > 0)', self.tables['sv_release'])
        self.assertEqual(tier_checks, [column for column in self.tables['sv_release_tier'] if 'CHECK' in column])
        for constraint in old_uniques:
            self.assertIn(constraint, self.tables['sv_release'])
        self.assertTrue(any('legacy_activity_version_no VARCHAR(64)' in c for c in self.tables['sv_release']))
        self.assertTrue(any('uk_legacy_activity_version' in c and 'UNIQUE' in c for c in self.tables['sv_release']))
        self.assertTrue(any('legacy_tier_key VARCHAR(128)' in c for c in self.tables['sv_release_tier']))
        self.assertTrue(any('uk_legacy_activity_tier' in c and 'UNIQUE' in c for c in self.tables['sv_release_tier']))

    def test_cli_generates_real_v549_and_v550_without_session_sql(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            (fixture / 'V549__baseline.sql').write_text(self.baseline)
            (fixture / 'V550__release.sql').write_text(self.migration)
            output = fixture / 'schema.sql'
            subprocess.run([sys.executable, str(GENERATOR), str(output), str(fixture)], check=True, capture_output=True, text=True)
            sql = output.read_text()
            self.assertEqual(1, sql.count('CONSTRAINT ck_sv_release_source CHECK'))
            self.assertEqual(1, sql.count("source_kind='LEGACY_PLAN'"))
            self.assertIn("source_kind='LEGACY_ACTIVITY'", sql)
            self.assertIn('paid_amount_minor<=9223372036854775807-bonus_amount_minor', sql)
            self.assertNotIn('@sv_release_', sql)
            self.assertNotIn('information_schema', sql)

    def test_changed_dynamic_sql_is_rejected_without_dropping_existing_check(self):
        for before, after in [
            ("tc.TABLE_NAME='sv_release'", "tc.TABLE_NAME='sv_release_tier'"),
            ("tc.CONSTRAINT_TYPE='CHECK'", "tc.CONSTRAINT_TYPE='UNIQUE'"),
            ('DROP CHECK', 'DROP COLUMN'),
            ('EXECUTE sv_release_statement;', 'EXECUTE other_statement;'),
        ]:
            with self.subTest(change=after):
                with self.assertRaisesRegex(SystemExit, 'Unknown release source constraint migration'):
                    self.replay(self.migration.replace(before, after))
                self.assertEqual(1, len(self.source_checks()))
                self.assertNotIn('LEGACY_ACTIVITY', self.source_checks()[0])

    def test_missing_or_changed_variable_cleanup_is_not_silently_discarded(self):
        for before, after in [
            ('SET @sv_release_sql = NULL;', ''),
            ('SET @sv_release_check = NULL;', 'SET @sv_release_check = 1;'),
        ]:
            with self.subTest(change=before):
                with self.assertRaisesRegex(SystemExit, 'Unknown release source constraint migration'):
                    self.replay(self.migration.replace(before, after))
                self.assertEqual(1, len(self.source_checks()))

    def test_missing_original_check_fails_instead_of_adding_unverified_replacement(self):
        self.tables['sv_release'].remove(self.source_checks()[0])
        with self.assertRaisesRegex(SystemExit, 'exactly one sv_release source_kind CHECK'):
            self.replay(self.migration)

    def test_ambiguous_metadata_match_fails_instead_of_deleting_multiple_checks(self):
        self.tables['sv_release'].append("CHECK (source_kind <> '')")
        with self.assertRaisesRegex(SystemExit, 'exactly one sv_release source_kind CHECK'):
            self.replay(self.migration)
        self.assertEqual(2, len(self.source_checks()))

    def test_unknown_session_statement_still_fails(self):
        with self.assertRaises(SystemExit):
            self.replay('SET @unreviewed = NULL;')


class SessionSafeH2ConstraintTest(unittest.TestCase):
    def test_json_length_keeps_count_constraint_and_only_supports_one_column_argument(self):
        self.assertEqual(
            "CONSTRAINT ck_counts CHECK (planned=skipped+H2_JSON_LENGTH(recipients_json))",
            generator._h2_type("CONSTRAINT ck_counts CHECK (planned=skipped+JSON_LENGTH(recipients_json))"))
        literal = "label VARCHAR(80) DEFAULT 'JSON_LENGTH(recipients_json)'"
        self.assertEqual(literal, generator._h2_type(literal))
        for expression in ["CHECK (JSON_LENGTH(payload,'$.items')=1)",
                           "CHECK (JSON_LENGTH(JSON_EXTRACT(payload,'$.items'))=1)"]:
            with self.assertRaisesRegex(ValueError, 'Unsupported JSON function'):
                generator._h2_type(expression)

    def test_constant_scope_check_keeps_null_result_and_column_nullability(self):
        self.assertEqual(
            "    CHECK ((CASE scope_type WHEN 'BRAND' THEN TRUE WHEN 'STORE' THEN TRUE "
            "ELSE CASE WHEN scope_type IS NULL THEN NULL ELSE FALSE END END))",
            generator._h2_type("    CHECK (scope_type IN ('BRAND','STORE'))"))
        self.assertEqual("    scope_type VARCHAR(24) NOT NULL",
                         generator._h2_type("    scope_type VARCHAR(24) NOT NULL"))

    def test_conversion_preserves_named_constraints_and_composite_guards(self):
        converted = generator._h2_type(
            "    CONSTRAINT guard CHECK (status NOT IN ('PAID','REFUNDED') OR receipt IS NOT NULL)")
        self.assertTrue(converted.startswith("    CONSTRAINT guard CHECK (NOT (CASE status"))
        self.assertTrue(converted.endswith("END END) OR receipt IS NOT NULL)"))
        self.assertEqual("    CHECK (principal_minor >= 0)",
                         generator._h2_type("    CHECK (principal_minor >= 0)"))

    def test_numeric_lists_and_generated_columns_use_current_session_comparisons(self):
        converted = generator._h2_type("    CHECK (deleted IN (0,1) AND version >= 0)")
        self.assertIn("CASE deleted WHEN 0 THEN TRUE WHEN 1 THEN TRUE", converted)
        self.assertTrue(converted.endswith("END END) AND version >= 0)"))
        converted = generator._h2_type(
            "    pending VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('OPEN','PENDING') "
            "THEN store_no ELSE NULL END) STORED")
        self.assertNotIn(' IN ', converted)
        self.assertNotIn('STORED', converted)
        self.assertIn('THEN store_no ELSE NULL END)', converted)

    def test_literals_and_nonconstant_lists_are_not_reinterpreted(self):
        for expression in ["CHECK (note = 'status IN (''OPEN'',''PENDING'')')",
                           "CHECK (status IN (allowed_status,other_status))",
                           "CHECK (status IN (SELECT status FROM allowed))",
                           "CHECK (status IN ('OPEN',NULL))"]:
            self.assertEqual(expression, generator._h2_type(expression))
        converted = generator._h2_type("CHECK (note IN ('it''s active','it is pending'))")
        self.assertIn("WHEN 'it''s active' THEN TRUE", converted)

    def test_complex_and_numeric_operands_are_preserved_without_suffix_matches(self):
        for expression in ["CHECK (a + b IN (1,2))", "CHECK (a-b NOT IN (1,2))",
                           "CHECK (-1 IN (1,2))", "CHECK (+1 IN (1,2))",
                           "CHECK (-status IN ('A','B'))", "CHECK (+status IN ('A','B'))",
                           "CHECK (t.status IN ('A','B'))", "CHECK (t . status NOT IN ('A','B'))",
                           "CHECK (lower(status) NOT IN ('A','B'))",
                           "CHECK (CAST(status AS VARCHAR) IN ('A','B'))",
                           "CHECK ((a + b) NOT IN (1,2))"]:
            with self.subTest(expression=expression):
                self.assertEqual(expression, generator._h2_type(expression))

    def test_quoted_identifiers_and_their_escapes_are_not_rewritten(self):
        expressions = [
            "CHECK (\"status IN ('A','B')\"=1)",
            "CHECK (\"prefix\"\"status IN ('A','B')\"=1)",
            "CHECK (`status IN ('A','B')`=1)",
            "CHECK (`prefix``status IN ('A','B')`=1)",
            "CHECK (\"status\" IN ('A','B'))",
            "CHECK (`status` NOT IN ('A','B'))",
        ]
        for expression in expressions:
            with self.subTest(expression=expression):
                self.assertEqual(expression, generator._h2_type(expression))

    def test_conversion_is_idempotent(self):
        once = generator._h2_type("CHECK (status IN ('OPEN','PENDING'))")
        self.assertEqual(once, generator._h2_type(once))


class JsonLengthCheckTest(unittest.TestCase):
    """V652 ck_brand_audience_counts: H2 has no JSON_LENGTH, so keep the strongest equivalent it can check."""

    def test_count_equals_base_plus_json_length_becomes_lower_bound(self):
        col = ("    CONSTRAINT ck_brand_audience_counts CHECK(planned_count>=0 AND pre_skipped_count>=0 "
               "AND pre_skipped_count<=planned_count\n AND planned_count=pre_skipped_count+JSON_LENGTH(recipients_json))")
        out = generator._h2_type(col)
        self.assertNotIn('JSON_LENGTH', out)
        self.assertIn('AND planned_count>=pre_skipped_count', out)
        self.assertIn('pre_skipped_count<=planned_count', out)

    def test_other_json_length_shapes_still_fail_loudly(self):
        for col in ("    CONSTRAINT c CHECK(JSON_LENGTH(items)>0)",
                    "    CONSTRAINT c CHECK(n=JSON_LENGTH(items))",
                    "    CONSTRAINT c CHECK(n=m+JSON_EXTRACT(items,'$.a'))"):
            with self.assertRaises(ValueError, msg=col):
                generator._h2_type(col)

    def test_real_v652_replays(self):
        tables, order, seeds, renames, altered = {}, [], [], {}, set()
        generator.replay((MIGRATIONS / 'V652__brand_promotion.sql').read_text(), tables, order, seeds, renames, altered)
        snapshot = [t for t in tables if 'audience' in t]
        self.assertTrue(snapshot)
        ddl = '\n'.join(tables[snapshot[0]])
        self.assertNotIn('JSON_LENGTH', ddl)
        self.assertIn('planned_count>=pre_skipped_count', ddl)


class MultiTableDeleteTest(unittest.TestCase):
    def test_real_v655_grant_migration_replays_with_h2_delete(self):
        tables, order, seeds, renames, altered = {}, [], [], {}, set()
        sql = (MIGRATIONS / 'V655__ops_store_industry_maintenance.sql').read_text()
        generator.replay(sql, tables, order, seeds, renames, altered)
        replayed = [seed[2] for seed in seeds]
        self.assertFalse(any('DELETE menu_grant' in stmt for stmt in replayed))
        deletes = [stmt for stmt in replayed if stmt.startswith('DELETE FROM sys_role_point')]
        self.assertEqual(1, len(deletes))
        self.assertIn("role_code<>'SUPER_ADMIN'", deletes[0])
        self.assertIn('NOT EXISTS', deletes[0])
        grants = [stmt for stmt in replayed if stmt.startswith('INSERT IGNORE INTO sys_role_point')]
        self.assertEqual(2, len(grants))
        # 顺序与迁移一致：补 param:read → 撤菜单授权 → 按新读码补授权
        order_of = [replayed.index(grants[0]), replayed.index(deletes[0]), replayed.index(grants[1])]
        self.assertEqual(sorted(order_of), order_of)

    def test_unregistered_multi_table_delete_fails_loudly(self):
        tables, order, seeds, renames, altered = {}, [], [], {}, set()
        with self.assertRaises(SystemExit):
            generator.replay("DELETE g FROM sys_role_point g JOIN sys_function_point p ON p.point_code=g.point_code;",
                             tables, order, seeds, renames, altered)


class JsonBackfillUpdateTest(unittest.TestCase):
    def test_real_v650_json_backfill_on_unseeded_table_is_skipped(self):
        tables, order, seeds, renames, altered = {}, [], [], {}, set()
        generator.replay("CREATE TABLE mch_plan_purchase (id BIGINT, buyer_no VARCHAR(32), snapshot_json JSON);",
                         tables, order, seeds, renames, altered)
        generator.replay((MIGRATIONS / 'V650__entity_plan_purchase_creator.sql').read_text(),
                         tables, order, seeds, renames, altered)
        self.assertFalse(any('JSON_SET' in str(seed) for seed in seeds))

    def test_json_update_on_seeded_table_fails_loudly(self):
        tables, order, seeds, renames, altered = {}, [], [], {}, set()
        generator.replay("CREATE TABLE t (id BIGINT, j JSON);\nINSERT INTO t VALUES (1, '{}');",
                         tables, order, seeds, renames, altered)
        with self.assertRaises(SystemExit):
            generator.replay("UPDATE t SET j=JSON_SET(j,'$.a',1);", tables, order, seeds, renames, altered)


if __name__ == '__main__':
    unittest.main()
