#!/usr/bin/env python3
"""Exercise release-receipt reuse in disposable Git repositories, never the real gate receipt."""
import pathlib,subprocess,tempfile,unittest
HELPER=pathlib.Path(__file__).with_name('backend-gate-cache.sh').resolve()
class CacheTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.repo=pathlib.Path(self.tmp.name)
        self.git('init','-q','-b','main')
        (self.repo/'backend').mkdir();(self.repo/'backend'/'code').write_text('original')
        self.commit('base');self.sha=self.git('rev-parse','HEAD').strip()
    def tearDown(self):self.tmp.cleanup()
    def git(self,*args):
        return subprocess.check_output(['git','-C',str(self.repo),*args],text=True,stderr=subprocess.DEVNULL)
    def commit(self,message):
        self.git('add','.');self.git('-c','user.name=Cache Test','-c','user.email=cache@example.invalid','commit','-qm',message)
    def receipt(self,description='2026-10-18 00:00:00 · 编译 + 全量 3880 跑 / 0 红（基线 0 条）'):
        (self.repo/'.git'/'gate-verified-sha').write_text(self.sha+'\n'+description+'\n')
    def reusable(self):
        return subprocess.run(['bash',str(HELPER),str(self.repo)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True).returncode==0
    def test_absent_receipt_fails_closed(self):self.assertFalse(self.reusable())
    def test_compile_only_receipt_is_not_a_full_gate(self):
        self.receipt('compile only');self.assertFalse(self.reusable())
    def test_same_verified_commit_can_be_reused(self):
        self.receipt();self.assertTrue(self.reusable())
    def test_documentation_descendant_preserves_backend_evidence(self):
        self.receipt();(self.repo/'notes.md').write_text('docs');self.commit('docs');self.assertTrue(self.reusable())
    def test_backend_change_requires_a_new_full_gate(self):
        self.receipt();(self.repo/'backend'/'code').write_text('changed');self.commit('code');self.assertFalse(self.reusable())
    def test_changed_gate_script_requires_a_new_full_gate(self):
        self.receipt();(self.repo/'scripts').mkdir();(self.repo/'scripts'/'check-head-compiles.sh').write_text('new rules')
        self.commit('gate');self.assertFalse(self.reusable())
    def test_receipt_from_another_branch_is_not_evidence(self):
        self.git('checkout','-qb','other');(self.repo/'other').write_text('other');self.commit('other')
        self.sha=self.git('rev-parse','HEAD').strip();self.receipt();self.git('checkout','-q','main');self.assertFalse(self.reusable())
    def test_nonzero_failures_are_not_reused(self):
        self.receipt('2026-10-18 · 编译 + 全量 3880 跑 / 1 红（基线 1 条）');self.assertFalse(self.reusable())
if __name__=='__main__':unittest.main()
