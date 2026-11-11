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


if __name__ == '__main__':
    unittest.main()
