"""Validate pricing configuration wire contracts independently of Java DTO implementation."""
import importlib.util
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SPEC = json.loads((ROOT / 'docs/api/pricing-config.openapi.json').read_text())


class PricingConfigContractTest(unittest.TestCase):
    def test_business_error_envelope_is_valid_for_every_operation(self):
        for path, methods in SPEC['paths'].items():
            for method, operation in methods.items():
                with self.subTest(method=method, path=path):
                    schema = dict(operation['responses']['200']['content']['application/json']['schema'])
                    schema['components'] = SPEC['components']
                    Draft202012Validator.check_schema(schema)
                    errors = list(Draft202012Validator(schema).iter_errors({
                        'code': 40190, 'msg': 'Verified publication evidence missing', 'data': None}))
                    self.assertFalse(errors, str(errors))

    def test_published_operations_match_real_handlers_and_have_no_consumer_configuration(self):
        spec = importlib.util.spec_from_file_location('contract', ROOT / 'scripts/check-app-contract.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        handlers = set(module.backend())
        operations = {(m.upper(), module.norm(p)) for p, methods in SPEC['paths'].items() for m in methods}
        self.assertEqual(len(operations), 21)
        self.assertTrue(operations.issubset(handlers), operations - handlers)
        self.assertFalse(any(path.startswith('/mp/') for _, path in operations))

    def test_tax_management_rates_are_explicit_and_owner_writes_are_documented(self):
        save = SPEC['components']['schemas']['TaxSave']
        validator = Draft202012Validator(save)
        valid = {'expectedVersion': 0, 'rateBp': 500, 'priceTaxMode': 'EXCLUSIVE'}
        self.assertTrue(validator.is_valid(valid))
        for field in valid:
            incomplete = dict(valid)
            del incomplete[field]
            self.assertFalse(validator.is_valid(incomplete))
        for rate in [None, '500', 500.5, -1, 10001]:
            self.assertFalse(validator.is_valid(dict(valid, rateBp=rate)))
        for method, path in [('put', '/biz/pricing-tax-settings'),
                             ('post', '/biz/pricing-tax-settings/reset')]:
            self.assertIn('entity owner only', SPEC['paths'][path][method]['description'])

    def test_requests_are_closed_and_modifying_operations_require_idempotency(self):
        for name in ['Create', 'Save', 'Preview', 'Publish', 'Initialize', 'Reset', 'Upgrade', 'Seed', 'TaxSave', 'TaxReset']:
            schema = SPEC['components']['schemas'][name]
            self.assertIs(schema['additionalProperties'], False)
            if 'expectedDraftVersion' in schema['properties']:
                self.assertIn('expectedDraftVersion', schema['required'])
        for path, methods in SPEC['paths'].items():
            for method, operation in methods.items():
                if method == 'get' or path.endswith(('/preview', '/seed-import')):
                    continue
                self.assertTrue(any(p['name'] == 'Idempotency-Key' and p['required']
                                    for p in operation['parameters']))


if __name__ == '__main__':
    unittest.main()
