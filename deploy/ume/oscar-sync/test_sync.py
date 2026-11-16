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
    def __init__(self, shop, pay, posts=None):
        self.shop_map, self.pay_map = shop, pay
        self.posted = []
        self.post_map = posts or {}

    def _post(self, side, path, body):
        self.posted.append((side, path, body))
        v = self.post_map.get(path, {})
        if isinstance(v, Exception):
            raise v
        return v(body) if callable(v) else v

    def shop_post(self, path, body):
        return self._post('shop', path, body)

    def pay_post(self, path, body):
        return self._post('pay', path, body)

    def _lookup(self, table, path):
        v = table.get(path)
        if isinstance(v, Exception):
            raise v
        if v is None:
            raise sync.Blocked('GET %s -> HTTP 404' % path, 404)
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


class StoreFirstTest(unittest.TestCase):
    def test_new_entity_is_planned_together_with_its_store(self):
        shop = healthy_shop()
        shop['/ops/merchants'] = page()
        v, _ = verdicts(shop, healthy_pay())
        self.assertEqual(v['O2'], sync.PLAN_CREATE)
        self.assertEqual(v['O4'], sync.PLAN_CREATE)

    def test_merchant_creation_blocked_when_entity_has_no_store(self):
        shop, pay = healthy_shop(), healthy_pay()
        shop['/ops/stores'] = page()
        pay['/merchants'] = page()
        v, _ = verdicts(shop, pay)
        self.assertEqual(v['O4'], sync.PLAN_CREATE)
        self.assertEqual(v['O5'], sync.BLOCKED)

    def test_merchant_creation_blocked_until_store_synced_to_pay(self):
        pay = healthy_pay()
        pay['/merchants'] = page()
        del pay['/stores/ST1']
        v, _ = verdicts(healthy_shop(), pay)
        self.assertEqual(v['O5'], sync.BLOCKED)

    def test_merchant_creation_planned_when_store_is_synced(self):
        pay = healthy_pay()
        pay['/merchants'] = page()
        v, _ = verdicts(healthy_shop(), pay)
        self.assertEqual(v['O5'], sync.PLAN_CREATE)


def run_apply(shop, pay, posts=None, target=None, **kw):
    api = FakeApi(shop, pay, posts)
    report, ids = sync.run(api, target or TARGET, apply=True, **kw)
    return {i['key']: i['verdict'] for i in report.items}, ids, api.posted


REPAIR_TARGET = dict(TARGET, repair={'merchantNo': 'PM13A', 'fromEntityNo': 'M_OLD', 'evidence': '經營主體變更證明'},
                     requestPrefix='oscar')


class ApplyTest(unittest.TestCase):
    def test_read_only_mode_never_posts(self):
        api = FakeApi(healthy_shop(), dict(healthy_pay(), **{'/merchants': page()}))
        sync.run(api, TARGET)
        self.assertEqual(api.posted, [])

    def test_creates_merchant_when_store_is_synced(self):
        pay = healthy_pay()
        pay['/merchants'] = page()
        pay['/merchant-creations/by-request/oscar-sync-merchant-M_NEW'] = {'merchantNo': 'PM9', 'status': 'ACTIVE'}
        v, ids, posted = run_apply(healthy_shop(), pay)
        self.assertEqual(v['O5'], sync.APPLIED)
        self.assertEqual(ids['merchantNo'], 'PM9')
        self.assertIn(('pay', '/merchant-creations',
                       {'requestNo': 'oscar-sync-merchant-M_NEW', 'operatingEntityId': 'M_NEW', 'name': '創勢'}), posted)

    def test_unsynced_store_is_queued_and_merchant_not_created(self):
        pay = healthy_pay()
        pay['/merchants'] = page()
        del pay['/stores/ST1']
        v, _, posted = run_apply(healthy_shop(), pay, {'/ops/stores/nearpay-sync': {'queued': 1}})
        self.assertEqual(v['D'], sync.APPLIED)
        self.assertEqual(v['O5'], sync.BLOCKED)
        self.assertEqual([p[1] for p in posted], ['/ops/stores/nearpay-sync'])

    def test_projection_server_error_is_blocked_without_writes(self):
        pay = healthy_pay()
        pay['/merchants'] = page()
        pay['/stores/ST1'] = sync.Blocked('GET -> HTTP 500', 500)
        v, _, posted = run_apply(healthy_shop(), pay)
        self.assertEqual(v['D'], sync.BLOCKED)
        self.assertEqual(posted, [])

    def test_conflicting_merchant_gets_a_repair_request_not_a_second_merchant(self):
        pay = healthy_pay()
        pay['/merchants'] = page({'merchantNo': 'PM13A', 'entityNo': 'M_OLD', 'displayName': '創勢'})
        pay['/source-association-repair-requests'] = page()
        pay['/merchants/PM13A'] = {'record': {'sourceLinks': [{'externalEntityId': 'M_OLD', 'version': 4}]}}
        posts = {'/source-association-repair-requests': {'record': {'repairNo': 'R1', 'status': 'PENDING_APPROVAL'}}}
        v, _, posted = run_apply(healthy_shop(), pay, posts, REPAIR_TARGET)
        self.assertEqual(v['H:apply'], sync.APPLIED)
        self.assertEqual(v['O5'], sync.BLOCKED)
        self.assertEqual(len(posted), 1)
        body = posted[0][2]
        self.assertEqual((body['merchantNo'], body['fromOperatingEntityId'], body['toOperatingEntityId'], body['expectedVersion']),
                         ('PM13A', 'M_OLD', 'M_NEW', 4))
        self.assertEqual(body['requestNo'], 'oscar-repair-PM13A')

    def test_approved_repair_runs_only_with_execute_flag(self):
        pay = healthy_pay()
        pay['/merchants'] = page({'merchantNo': 'PM13A', 'entityNo': 'M_OLD', 'displayName': '創勢'})
        pay['/source-association-repair-requests'] = page({'repairNo': 'R1', 'status': 'APPROVED', 'version': 2})
        pay['/source-association-repair-requests/R1'] = {'record': {'status': 'EXECUTED'}}
        v, _, posted = run_apply(healthy_shop(), pay, None, REPAIR_TARGET)
        self.assertEqual(v['H:apply'], sync.REUSE)
        self.assertEqual(posted, [])
        v, _, posted = run_apply(healthy_shop(), pay, None, REPAIR_TARGET, execute_repair=True)
        self.assertEqual(v['H:apply'], sync.APPLIED)
        self.assertEqual(posted[0][1], '/source-association-repair-requests/R1/execute')
        self.assertEqual(posted[0][2]['expectedVersion'], 2)

    def test_unbound_store_gets_binding(self):
        pay = healthy_pay()
        pay['/stores/ST1'] = {'record': {'entityNo': 'M_NEW', 'merchantNo': 'PM1', 'payMerchantNos': []}}
        v, _, posted = run_apply(healthy_shop(), pay, {'/stores/ST1/bindings': {'record': {'bindingNo': 'B1'}}})
        self.assertEqual(v['G:NEWEBPAY'], sync.APPLIED)
        self.assertEqual(posted[0][2]['payMerchantNo'], 'PMD1')

    def test_missing_qualification_needs_real_data_before_create(self):
        v, _, posted = run_apply(healthy_shop(), healthy_pay())
        self.assertEqual(v['O3:BIZ_LICENSE'], sync.BLOCKED)
        self.assertEqual(posted, [])
        t = dict(TARGET, qualifications=[{'qualType': 'BIZ_LICENSE', 'qualName': '營業登記', 'qualNumber': '12345678'}])
        v, _, posted = run_apply(healthy_shop(), healthy_pay(), {'/ops/merchants/M_NEW/qualification-creations': {'qualNo': 'Q1'}}, t)
        self.assertEqual(v['O3:BIZ_LICENSE'], sync.APPLIED)


if __name__ == '__main__':
    unittest.main()
