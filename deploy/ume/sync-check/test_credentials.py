"""credentials.py 单测：python3 -m unittest deploy/ume/sync-check/test_credentials.py"""
import io, json, os, sys, tempfile, unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import credentials as c  # noqa: E402

LP_SECRET = 'S' * 32
NW_KEY, NW_IV = 'K' * 32, 'V' * 16


class FakeCursor:
    def __init__(self, db):
        self.db, self.rows = db, []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def execute(self, sql, args):
        self.db.sql.append((sql, list(args)))
        self.rows = self.db.data.get(args[0], [])

    def fetchall(self):
        return self.rows


class FakeDb:
    def __init__(self, data):
        self.data, self.sql = data, []

    def cursor(self):
        return FakeCursor(self)


def row(no, ch, mid, rec):
    return {'accountNo': no, 'merchantNo': 'PM-M', 'payChannel': ch, 'channelMerchantId': mid, 'umeRecord': rec,
            'evidenceRef': 'file:ume/x/%s' % rec}


class FakePay:
    """最小的 near-pay：渠道账户 + 凭据 + 审批，记录每次调用的操作人与路径。"""

    def __init__(self, channel):
        self.calls, self.bodies = [], []
        self.acct = {'status': 'UNVERIFIED', 'verificationStatus': 'PENDING', 'credentialKeyId': None}
        self.ver, self.products, self.versions, self.appr = 3, [], [], {}
        self.channel = channel

    def request(self, method, path, tok, body):
        self.calls.append((tok, method, path.split('?')[0]))
        self.bodies.append(json.dumps(body or {}))
        p = path.split('?')[0]
        ok = lambda d: (200, {'code': 0, 'data': d})  # noqa: E731
        if p.startswith('/pay-ops/v2/channel-accounts/'):
            tail = p[len('/pay-ops/v2/channel-accounts/PM-A'):]
            if method == 'GET':
                return ok({'record': {'account': dict(self.acct), 'products': [{'productCode': x, 'status': 'OPEN'} for x in self.products]},
                           'version': self.ver})
            assert body['expectedVersion'] == self.ver, (p, body, self.ver)
            self.ver += 1
            if tail == '/credential':
                self.acct['credentialKeyId'] = body['keyId']
            elif tail.startswith('/products/'):
                self.products.append(tail.split('/')[2])
            elif tail == '/verify':
                assert tok != 'CONFIG', '登记人不得核验'
                self.acct['verificationStatus'] = 'VERIFIED'
            elif tail == '/activate':
                self.acct['status'] = 'ACTIVE'
            return ok({})
        if p == '/pay-ops/v2/credentials':
            if method == 'GET':
                return ok({'records': []})
            return ok({'keyId': 77})
        if p == '/pay-ops/v2/credentials/77':
            return ok({'versions': [dict(v) for v in self.versions]})
        if p == '/pay-ops/v2/credentials/77/versions':
            self.versions.append({'versionIndex': 1, 'status': 'DRAFT'})
            return ok({'versionIndex': 1, 'status': 'DRAFT'})
        if p.endswith('/verify') and '/credentials/' in p:
            self.versions[0]['status'] = 'VERIFIED'
            return ok({})
        if p.endswith('/enable'):
            self.versions[0]['status'] = 'ACTIVE'
            return ok({})
        if p == '/pay-ops/v2/approvals':
            no = 'AP-%s' % body['subjectType']
            self.appr.setdefault(no, {'approvalNo': no, 'status': 'PENDING_FIRST', 'version': 0, 'materialVersion': 1,
                                      'need': 2 if body['subjectType'] == 'CREDENTIAL_ENABLE' else 1})
            return ok(dict(self.appr[no]))
        if p.startswith('/pay-ops/v2/approvals/'):
            no = p.split('/')[4]
            a = self.appr[no]
            if method == 'GET':
                return ok({'record': dict(a)})
            assert tok != 'CONFIG'
            a['version'] += 1
            a['need'] -= 1
            a['status'] = 'APPROVED' if a['need'] == 0 else 'PENDING_SECOND'
            return ok({})
        return 404, {'code': 404, 'message': 'no route ' + p}


class CredentialsTest(unittest.TestCase):

    def ume(self, data):
        return c.Ume(FakeDb(data), c.DEFAULT_COLUMNS)

    def test_lookup_validates_and_never_reveals(self):
        u = self.ume({'CM1': [('1626862643', LP_SECRET)], 'MS1': [(NW_KEY, 'short')], 'MS2': [(NW_KEY, NW_IV), ('X' * 32, NW_IV)],
                      'CM2': [('999', LP_SECRET)]})
        m, v, _ = u.lookup(row('PM-A', 'LINEPAY', '1626862643', 'CM1'))
        self.assertEqual(v, 'FOUND')
        self.assertNotIn(LP_SECRET, repr(m) + str(m['secret']))
        self.assertEqual(u.lookup(row('PM-B', 'NEWEBPAY', 'MS1', 'MS1'))[1], 'INVALID')
        self.assertEqual(u.lookup(row('PM-C', 'NEWEBPAY', 'MS2', 'MS2'))[1], 'AMBIGUOUS')
        self.assertEqual(u.lookup(row('PM-D', 'LINEPAY', '1626862643', 'CM2'))[1], 'MISMATCH')
        self.assertEqual(u.lookup(row('PM-E', 'LINEPAY', '1', 'CM9'))[1], 'MISSING')
        sql, args = u.conn.sql[1]
        self.assertIn('`chn_id`=%s', sql, '藍新限定 spgateway 渠道')
        self.assertEqual(args, ['MS1', 'CH380202039616995328'])

    def test_apply_runs_the_full_chain_with_separated_operators_and_reports_no_secret(self):
        pay = FakePay('NEWEBPAY')
        api = c.PayOps(pay.request, {'CONFIG': 'CONFIG', 'REVIEW1': 'REVIEW1', 'REVIEW2': 'REVIEW2'})
        u = self.ume({'MS9': [(NW_KEY, NW_IV)]})
        out = os.path.join(tempfile.mkdtemp(), 'r.json')
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = c.run('apply', [row('PM-A', 'NEWEBPAY', 'MS9', 'MS9')], u, api, out, pause=0)
        self.assertEqual(code, 0, buf.getvalue())
        self.assertEqual(pay.acct['status'], 'ACTIVE')
        self.assertEqual(pay.products, ['NEWEBPAY_CARD_ONETIME', 'NEWEBPAY_APPLEPAY_WEB'])
        reviews = [t for t, m, p in pay.calls if p.endswith('/review')]
        self.assertEqual(reviews, ['REVIEW1', 'REVIEW2', 'REVIEW1'], '凭据启用两人，账户核验一人')
        report = open(out, encoding='utf-8').read()
        for s in (NW_KEY, NW_IV):
            self.assertNotIn(s, buf.getvalue())
            self.assertNotIn(s, report)
        self.assertEqual(sum(NW_KEY in b for b in pay.bodies), 1, '密钥只出现在录入版本那一次请求里')

    def test_rerun_on_an_active_account_does_nothing(self):
        pay = FakePay('LINEPAY')
        pay.acct['status'] = 'ACTIVE'
        api = c.PayOps(pay.request, {'CONFIG': 'CONFIG', 'REVIEW1': 'REVIEW1', 'REVIEW2': 'REVIEW2'})
        with redirect_stdout(io.StringIO()):
            c.run('apply', [row('PM-A', 'LINEPAY', '1', 'CM1')], self.ume({'CM1': [('1', LP_SECRET)]}), api, pause=0)
        self.assertEqual([m for _, m, _ in pay.calls], ['GET'])

    def test_check_mode_never_calls_near_pay(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = c.run('check', [row('PM-A', 'LINEPAY', '1', 'CM1')], self.ume({'CM1': [('1', LP_SECRET)]}), None)
        self.assertEqual(code, 0)
        self.assertIn('FOUND', buf.getvalue())
        self.assertNotIn(LP_SECRET, buf.getvalue())


if __name__ == '__main__':
    unittest.main()
