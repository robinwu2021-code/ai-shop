"""python3 -I -m unittest discover -s deploy/ume/store-codes -p 'test_*.py'"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib  # noqa: E402

codes = importlib.import_module('import')


class FakeApi:
    def __init__(self, slugs, taken=()):
        self.slugs, self.taken, self.writes = dict(slugs), set(taken), []

    def set_code(self, store_no, code):
        if store_no not in self.slugs:
            raise RuntimeError('code 10404 数据不存在')
        if code in self.taken:
            raise RuntimeError('code 40921 门店代码已被使用')
        self.writes.append((store_no, code))
        self.slugs[store_no] = code
        return {'storeNo': store_no, 'slug': code}


class ImportTest(unittest.TestCase):
    def test_valid_rows_are_written_only_with_apply(self):
        api = FakeApi({'ST1': None, 'ST2': 'old-code'})
        rows = [{'storeNo': 'ST1', 'code': 'Ume-Cafe '}, {'storeNo': 'ST2', 'code': 'jeff-first-home'}]
        dry = codes.run(api, rows)
        self.assertEqual([r['verdict'] for r in dry], ['OK', 'OK'])
        self.assertEqual(api.writes, [])
        done = codes.run(api, rows, apply=True)
        self.assertEqual([r['verdict'] for r in done], ['OK', 'OK'])
        self.assertEqual(api.writes, [('ST1', 'ume-cafe'), ('ST2', 'jeff-first-home')])

    def test_invalid_reserved_duplicate_and_taken_codes_are_reported_and_others_still_written(self):
        api = FakeApi({'ST1': None, 'ST2': None, 'ST3': None, 'ST4': None, 'ST5': 'same'}, taken={'chuangke-pay'})
        rows = [{'storeNo': 'ST1', 'code': '早餐店'}, {'storeNo': 'ST2', 'code': 'admin'},
                {'storeNo': 'ST3', 'code': 'dup'}, {'storeNo': 'ST4', 'code': 'dup'},
                {'storeNo': 'ST5', 'code': 'same'}, {'storeNo': 'ST6', 'code': 'ghost'},
                {'storeNo': 'ST1', 'code': 'chuangke-pay'}, {'storeNo': '', 'code': 'x-y'}]
        got = {(r['storeNo'], r['code']): r for r in codes.run(api, rows, apply=True)}
        self.assertEqual(got[('ST1', '早餐店')]['verdict'], 'INVALID')
        self.assertEqual(got[('ST2', 'admin')]['verdict'], 'INVALID')
        self.assertEqual(got[('ST3', 'dup')]['verdict'], 'DUPLICATE')
        self.assertEqual(got[('ST5', 'same')]['verdict'], 'OK', '同一个值重写是幂等的')
        self.assertEqual(got[('ST6', 'ghost')]['verdict'], 'FAILED')
        self.assertEqual(got[('ST1', 'chuangke-pay')]['verdict'], 'DUPLICATE', '同一门店出现两次')
        self.assertEqual(got[('', 'x-y')]['verdict'], 'INVALID')
        self.assertEqual(api.writes, [('ST5', 'same')], '只有合规且清单内不重复的行会写')

    def test_a_code_taken_by_another_store_fails_that_row_only(self):
        api = FakeApi({'ST1': None, 'ST2': None}, taken={'chuangke-pay'})
        got = codes.run(api, [{'storeNo': 'ST1', 'code': 'chuangke-pay'}, {'storeNo': 'ST2', 'code': 'chuangke-sam'}], apply=True)
        self.assertEqual([r['verdict'] for r in got], ['FAILED', 'OK'])
        self.assertIn('已被使用', got[0]['note'])


if __name__ == '__main__':
    unittest.main()
