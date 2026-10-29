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


if __name__ == '__main__':
    unittest.main()
