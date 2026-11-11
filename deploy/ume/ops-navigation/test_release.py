import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ops_release', Path(__file__).with_name('release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class RollbackTests(unittest.TestCase):
    def test_stop_failure_before_copy_restarts_only_stopped_service(self):
        state = {'ai-shop-api': True, 'ai-shop-ops': False}
        def docker(action, name):
            self.assertEqual(action, 'start')
            state[name] = True
        with patch.object(release, 'inspect', lambda n: {'State': {'Running': state[n]}}), patch.object(release, 'docker', docker), patch.object(release, 'wait'), patch.object(release.shutil, 'copyfile') as copy:
            release.restore_backend(Path('jar'), Path('backup'), False)
            copy.assert_not_called()
        self.assertTrue(all(state.values()))

    def test_changed_artifact_restores_before_starting_both_services(self):
        state = {'ai-shop-api': True, 'ai-shop-ops': False}
        events = []
        def docker(action, name):
            events.append((action, name))
            state[name] = action == 'start'
        def copy(source, destination):
            self.assertFalse(any(state.values()))
            events.append(('restore', 'jar'))
        with patch.object(release, 'inspect', lambda n: {'State': {'Running': state[n]}}), patch.object(release, 'docker', docker), patch.object(release, 'wait'), patch.object(release.shutil, 'copyfile', copy):
            release.restore_backend(Path('jar'), Path('backup'), True)
        self.assertLess(events.index(('restore', 'jar')), events.index(('start', 'ai-shop-api')))
        self.assertTrue(all(state.values()))

    def test_failed_rollback_start_still_attempts_other_service(self):
        events = []
        def docker(action, name):
            events.append(name)
            if name == 'ai-shop-api':
                raise subprocess.CalledProcessError(1, 'docker')
        with patch.object(release, 'inspect', lambda n: {'State': {'Running': False}}), patch.object(release, 'docker', docker), patch.object(release, 'wait'):
            with self.assertRaisesRegex(RuntimeError, 'Rollback needs attention'):
                release.restore_backend(Path('jar'), Path('backup'), False)
        self.assertEqual(events, ['ai-shop-api', 'ai-shop-ops'])

    def test_atomic_link_preserves_chained_and_relative_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'old').mkdir()
            (root / 'new').mkdir()
            (root / 'chain').symlink_to('old')
            current = root / 'current'
            current.symlink_to('chain')
            previous = current.readlink()
            release.link(current, root / 'new')
            release.link(current, previous)
            self.assertEqual(current.resolve(), (root / 'old').resolve())


class MigrationArtifactTests(unittest.TestCase):
    MANIFEST = {'releaseId': 'ops-nav-v658-20261111',
                'migration': {'file': 'V658__ops_menu_matrix_consolidation.sql', 'version': '658', 'expectedMenuMatches': 151}}

    def test_manifest_migration_is_validated(self):
        self.assertEqual(release.migration_of(self.MANIFEST)['version'], '658')
        for bad in ({**self.MANIFEST, 'releaseId': 'ops-nav-v657-x'},
                    {**self.MANIFEST, 'releaseId': 'ops-nav-v658/../x'},
                    {**self.MANIFEST, 'migration': {**self.MANIFEST['migration'], 'file': '../V658__x.sql'}},
                    {**self.MANIFEST, 'migration': {**self.MANIFEST['migration'], 'file': 'V659__x.sql'}}):
            with self.assertRaises(AssertionError):
                release.migration_of(bad)

    def test_next_jar_adds_only_the_declared_migration_and_keeps_old_bytes(self):
        import zipfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / 'before.jar', 'w') as jar:
                jar.writestr('BOOT-INF/classes/db/migration/V657__ops_compact_navigation.sql', b'-- v657')
                jar.writestr('BOOT-INF/lib/a.jar', b'\x00\x01')
            entry = release.build_next_jar(root / 'before.jar', root / 'next.jar',
                                           'V658__ops_menu_matrix_consolidation.sql', b'-- v658')
            self.assertEqual(entry, 'BOOT-INF/classes/db/migration/V658__ops_menu_matrix_consolidation.sql')
            with zipfile.ZipFile(root / 'next.jar') as jar:
                self.assertEqual(jar.read(entry), b'-- v658')
                self.assertEqual(jar.read('BOOT-INF/lib/a.jar'), b'\x00\x01')
            with self.assertRaises(AssertionError):   # 已存在的迁移不能被覆盖
                release.build_next_jar(root / 'next.jar', root / 'again.jar',
                                       'V658__ops_menu_matrix_consolidation.sql', b'-- other')

    def test_backend_proof_must_match_manifest(self):
        proof = {'flyway': {'version': '658', 'success': 1, 'checksum': -783404707},
                 'checks': {'menuMappings': {'matched': 151, 'allMatched': True}}, 'problems': []}
        manifest = {**self.MANIFEST, 'flywayChecksum': -783404707}
        release.check_proof(proof, manifest)
        for broken in ({**proof, 'problems': ['x']},
                       {**proof, 'flyway': {**proof['flyway'], 'checksum': 1}},
                       {**proof, 'checks': {'menuMappings': {'matched': 150, 'allMatched': False}}}):
            with self.assertRaises(AssertionError):
                release.check_proof(broken, manifest)


if __name__ == '__main__':
    unittest.main()
