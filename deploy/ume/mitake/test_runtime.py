import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch


class ReleaseSafetyTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('sms_runtime', Path(__file__).with_name('runtime.py'))
        self.runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runtime)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        r = self.runtime
        r.BASE = Path(self.directory.name)
        r.JAR = r.BASE / 'shop-app.jar'
        r.JAR.write_bytes(b'before')
        r.JAR.chmod(0o640)
        r.RELEASE = r.BASE / 'release'
        r.RELEASE.mkdir()
        r.LOCK = r.BASE / '.deploy.lock'
        self.state = {'baselineSha256': r.digest(r.JAR), 'candidateSha256': 'candidate',
                      'containers': {'ai-shop-api': {'Id': 'old-api'}, 'ai-shop-ops': {'Id': 'old-ops'}},
                      'deployedContainers': {'ai-shop-api': 'new-api', 'ai-shop-ops': 'new-ops'},
                      'envFiles': [], 'newSecrets': [], 'phase': 'complete'}
        r.save(self.state)

    def test_other_release_jar_refuses_rollback_before_docker_mutation(self):
        r = self.runtime
        r.JAR.write_bytes(b'other-release')
        with patch.object(r, 'docker') as docker:
            with self.assertRaisesRegex(AssertionError, 'different release'):
                r.rollback()
            docker.assert_not_called()
        self.assertEqual(r.JAR.read_bytes(), b'other-release')
        self.assertFalse(r.LOCK.exists())

    def test_missing_backup_does_not_allow_stopping_another_container(self):
        r = self.runtime
        with patch.object(r, 'inspect', return_value={'Id': 'someone-else'}), patch.object(r, 'docker') as docker:
            with self.assertRaisesRegex(AssertionError, 'different release'):
                r.rollback()
            docker.assert_not_called()
        self.assertFalse(r.LOCK.exists())

    def test_other_deploy_lock_is_preserved(self):
        r = self.runtime
        r.LOCK.mkdir()
        (r.LOCK / 'owner').write_text('another-release')
        with patch.object(r, 'docker') as docker:
            with self.assertRaisesRegex(AssertionError, 'owns the lock'):
                r.rollback()
            docker.assert_not_called()
        self.assertEqual((r.LOCK / 'owner').read_text(), 'another-release')

    def test_interrupted_candidate_copy_preserves_live_jar(self):
        r = self.runtime
        source = r.BASE / 'candidate.jar'
        source.write_bytes(b'candidate')
        original_copy = r.shutil.copyfile

        def fail_candidate_copy(src, dst, **kwargs):
            if Path(src) == source:
                Path(dst).write_bytes(b'partial')
                raise OSError('simulated interrupted copy')
            return original_copy(src, dst, **kwargs)

        with patch.object(r.shutil, 'copyfile', side_effect=fail_candidate_copy):
            with self.assertRaises(OSError):
                r.install_jar(source, r.digest(source))
        self.assertEqual(r.JAR.read_bytes(), b'before')
        self.assertFalse((r.BASE / ('.' + r.RID + '-jar.next')).exists())

    def test_atomic_install_preserves_original_file_permissions(self):
        r = self.runtime
        source = r.BASE / 'candidate.jar'
        source.write_bytes(b'candidate')
        r.install_jar(source, r.digest(source))
        self.assertEqual(r.JAR.read_bytes(), b'candidate')
        self.assertEqual(stat.S_IMODE(r.JAR.stat().st_mode), 0o640)


if __name__ == '__main__':
    unittest.main()
