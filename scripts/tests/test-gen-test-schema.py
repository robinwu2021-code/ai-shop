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


if __name__ == '__main__':
    unittest.main()
