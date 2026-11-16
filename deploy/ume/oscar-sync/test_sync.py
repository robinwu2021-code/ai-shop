"""python3 -I -m unittest discover -s deploy/ume/oscar-sync -p 'test_*.py'"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sync  # noqa: E402

TARGET = {
    'email': 'oscar@umeshop.ai', 'userNo': 'U1', 'entityNo': 'M_NEW', 'entityName': '創勢',
    'storeNo': 'ST1', 'storeName': 'ume 早餐店', 'requiredQualTypes': ['BIZ_LICENSE'],
    'channels': [{'payChannel': 'NEWEBPAY', 'payMerchantNo': 'PMD1'}],
}


def page(*rows):
    return {'records': list(rows), 'total': len(rows)}


class FakeApi:
    def __init__(self, shop, pay):
        self.shop_map, self.pay_map = shop, pay

    def _lookup(self, table, path):
        v = table.get(path)
        if isinstance(v, Exception):
            raise v
        if v is None:
            raise sync.Blocked('GET %s -> HTTP 404' % path)
        return v

    def shop(self, path, **_):
        return self._lookup(self.shop_map, path)

    def pay(self, path, **_):
        return self._lookup(self.pay_map, path)


def healthy_shop():
    return {
        '/ops/customers/users': {'page': page({'userNo': 'U1'}), 'total': 1},
        '/ops/merchants': page({'merchantNo': 'M_NEW', 'name': '創勢', 'status': 'ACTIVE'},
                               {'merchantNo': 'MT', 'name': 'TEST ONLY 創勢'}),
        '/ops/customers/users/U1/stores': [{'storeNo': 'ST1', 'entityNo': 'M_NEW'}],
        '/ops/merchants/M_NEW/qualifications': [],
        '/ops/stores': page({'storeNo': 'ST1', 'name': 'ume 早餐店', 'merchantNo': 'M_NEW'}),
    }


def healthy_pay():
    return {
        '/merchants': page({'merchantNo': 'PM1', 'entityNo': 'M_NEW', 'displayName': '創勢', 'status': 'ACTIVE'}),
        '/stores/ST1': {'record': {'entityNo': 'M_NEW', 'merchantNo': 'PM1', 'payMerchantNos': ['PMD1']}},
        '/channel-accounts': page({'payMerchantNo': 'PMD1', 'payChannel': 'NEWEBPAY', 'credentialKeyId': 7}),
    }


def verdicts(shop, pay):
    report, ids = sync.run(FakeApi(shop, pay), TARGET)
    return {i['key']: i['verdict'] for i in report.items}, ids


class DecideTest(unittest.TestCase):
    def test_zero_one_many(self):
        self.assertEqual(sync.decide([], lambda x: True)[0], sync.PLAN_CREATE)
        self.assertEqual(sync.decide([1], lambda x: True)[0], sync.REUSE)
        self.assertEqual(sync.decide([1], lambda x: False)[0], sync.CONFLICT)
        self.assertEqual(sync.decide([1, 2], lambda x: True)[0], sync.CONFLICT)

    def test_unwrap_business_failure_is_blocked_not_empty(self):
        with self.assertRaises(sync.Blocked):
            sync.unwrap({'code': 403, 'msg': 'denied', 'data': None})
        self.assertEqual(sync.unwrap({'code': 0, 'data': [1]}), [1])
        self.assertEqual(sync.records({'page': {'records': [1]}}), [1])


class RunTest(unittest.TestCase):
    def test_all_present_reuses_everything(self):
        v, ids = verdicts(healthy_shop(), healthy_pay())
        for k in ('O1', 'O2', 'A', 'O4', 'O5', 'D', 'O6:NEWEBPAY', 'O7:NEWEBPAY', 'G:NEWEBPAY'):
            self.assertEqual(v[k], sync.REUSE, k)
        self.assertEqual(v['O3:BIZ_LICENSE'], sync.PLAN_CREATE)
        self.assertEqual(ids['merchantNo'], 'PM1')

    def test_same_name_merchant_on_old_entity_is_conflict_and_never_planned(self):
        pay = healthy_pay()
        pay['/merchants'] = page({'merchantNo': 'PM13A', 'entityNo': 'M_OLD', 'displayName': '創勢'})
        v, _ = verdicts(healthy_shop(), pay)
        self.assertEqual(v['H'], sync.CONFLICT)
        self.assertEqual(v['O5'], sync.BLOCKED)
        self.assertEqual(v['O6'], sync.BLOCKED)

    def test_http_error_is_blocked_not_zero(self):
        shop = healthy_shop()
        shop['/ops/customers/users'] = sync.Blocked('GET -> HTTP 403')
        v, _ = verdicts(shop, healthy_pay())
        self.assertEqual(v['O1'], sync.BLOCKED)
        self.assertEqual(v['A'], sync.BLOCKED)

    def test_store_on_other_entity_is_conflict(self):
        shop = healthy_shop()
        shop['/ops/stores'] = page({'storeNo': 'ST1', 'name': 'ume 早餐店', 'merchantNo': 'M_OLD'})
        v, _ = verdicts(shop, healthy_pay())
        self.assertEqual(v['O4'], sync.CONFLICT)

    def test_unbound_store_plans_binding(self):
        pay = healthy_pay()
        pay['/stores/ST1'] = {'record': {'entityNo': 'M_NEW', 'merchantNo': 'PM1', 'payMerchantNos': []}}
        v, _ = verdicts(healthy_shop(), pay)
        self.assertEqual(v['G:NEWEBPAY'], sync.PLAN_CREATE)

    def test_duplicate_entities_conflict(self):
        shop = healthy_shop()
        shop['/ops/merchants'] = page({'merchantNo': 'M_NEW', 'name': '創勢'}, {'merchantNo': 'M_OLD', 'name': '創勢'})
        v, _ = verdicts(shop, healthy_pay())
        self.assertEqual(v['O2'], sync.CONFLICT)


if __name__ == '__main__':
    unittest.main()
