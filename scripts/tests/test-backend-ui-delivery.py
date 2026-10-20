import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('backend_orphan', Path(__file__).resolve().parents[1] / 'check-app-backend-orphan.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class DeferredBackendDeliveryTest(unittest.TestCase):
    def manifest(self):
        return {'version': 1, 'stage': 'JAVA_BACKEND_UI_DEFERRED', 'reason': 'Java backend first; UI follows',
                'entries': [{'app': 'c-app', 'method': 'GET', 'path': '/mp/stored-value/stores'}]}

    def validate(self, data=None, published=None, handlers=None, missing=None):
        endpoint = ('GET', '/mp/stored-value/stores')
        return module.validate_deferred(data or self.manifest(), published if published is not None else {endpoint},
                handlers if handlers is not None else {endpoint},
                missing if missing is not None else {'c-app GET /mp/stored-value/stores'})

    def test_explicit_deferred_endpoint_still_requires_real_contract_and_handler(self):
        self.assertEqual(self.validate(), {'c-app GET /mp/stored-value/stores'})
        with self.assertRaises(ValueError): self.validate(published=set())
        with self.assertRaises(ValueError): self.validate(handlers=set())

    def test_delivered_ui_entry_must_leave_staging_list(self):
        with self.assertRaises(ValueError): self.validate(missing=set())

    def test_duplicates_invalid_stage_and_wrong_app_are_rejected(self):
        data = self.manifest(); data['entries'] *= 2
        with self.assertRaises(ValueError): self.validate(data=data)
        data = self.manifest(); data['stage'] = 'IGNORE'
        with self.assertRaises(ValueError): self.validate(data=data)
        data = self.manifest(); data['entries'][0]['app'] = 'b-app'
        with self.assertRaises(ValueError): self.validate(data=data)

if __name__ == '__main__': unittest.main()
