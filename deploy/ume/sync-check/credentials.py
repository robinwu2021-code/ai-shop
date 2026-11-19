"""UME 渠道密钥迁移（LINE Pay channel secret、藍新 HashKey/HashIV）→ near-pay 凭据库 → 绑定、核验、启用渠道账户。

由用户本人执行：密钥只在本进程内存里，从 UME 库读出后直接提交 near-pay 凭据库，**不打印、不落盘、不进报告**。
报告只有账户号、渠道商户号、步骤与结论。

  describe                         只列 UME 两张表的列名（不读数据），用来核对 ume-columns.json
  check  TARGETS [--only X] [--limit N] [--out R.json]
                                   只读 UME：逐户确认能唯一查到密钥且格式合法（不调 near-pay）
  apply  TARGETS [--only X] [--limit N] [--out R.json]
                                   逐户调 near-pay 迁移导入端点 POST /pay-ops/v2/channel-accounts/{no}/migration-credential
                                   （一次到可收款，不经审批，需开关 near-pay.credential.migration.enabled）；
                                   UME_KEYS_FLOW=approval 时改走原审批流程。可重复执行。

TARGETS：docs/handoff/ume-sync-2026-11-19/credential-targets.tsv（near-pay 待补密钥的账户，只有编号）。
--only：账户号（PM…，渠道账户）或支付商户号（PM…，主体），只跑这一户 —— 先拿 roger（PM0E0F6CC8F7454D75）试。

环境变量：
  UME_DB_HOST / UME_DB_PORT(3306) / UME_DB_USER   UME msm_mms 只读账号；口令运行时提示输入（或 UME_DB_PASSWORD）
  UME_COLUMNS        列名映射文件（默认同目录 ume-columns.json；不存在用下面 DEFAULT_COLUMNS）
  AUTOMATION_KEY     ~/work/env/ume/automation-ume-ed25519.pem
  OPS_BASE / PAY_BASE   ai-shop 运营 / near-pay 地址（默认隧道 127.0.0.1:28081 / 28093）
  SUB_CONFIG / SUB_REVIEW1 / SUB_REVIEW2   操作人（默认 ST-UME-CONFIG / ST-UME-REVIEW1 / ST-UME-REVIEW2）
"""
import getpass, json, os, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))

DEFAULT_COLUMNS = {
    # LINE Pay：按 UME 记录号（CM…，即 near-pay 账户 evidenceRef 末段）查，channel_id 必须等于渠道商户号
    'LINEPAY': {'table': 'msm_mms.chn_mer_scan_linepay_info', 'key': 'chn_mer_id', 'channelId': 'channel_id',
                'secret': 'channel_secret', 'where': {}},
    # 藍新：按渠道商户号查，限定 spgateway 渠道（CHN_ID 同 oscar 已启用凭据的核验证据）
    'NEWEBPAY': {'table': 'msm_mms.chn_mer_card_inf', 'key': 'chn_mer_no', 'hashKey': 'hash_key', 'hashIv': 'hash_iv',
                 'where': {'chn_id': 'CH380202039616995328'}},
}
CRED_TYPE = {'LINEPAY': 'LINEPAY_MERCHANT_SIGN', 'NEWEBPAY': 'NEWEBPAY_MERCHANT_HASH'}
PRODUCTS = {'LINEPAY': ['LINEPAY_ONLINE'], 'NEWEBPAY': ['NEWEBPAY_CARD_ONETIME', 'NEWEBPAY_APPLEPAY_WEB']}
REASON = 'UME 渠道密钥迁移：按 UME %s 记录 %s 导入（用户执行，密钥不出进程）'


class Secret:
    """密钥只放这里；任何字符串化都不露值。"""
    __slots__ = ('_v',)

    def __init__(self, v):
        self._v = v

    def reveal(self):
        return self._v

    def __repr__(self):
        return '<secret len=%d>' % len(self._v or '')

    __str__ = __repr__


def printable(s):
    return all(33 <= ord(c) <= 126 for c in s)


def validate(channel, material):
    """返回不合规原因（不含值）；None 表示可用。规则同 near-pay PayCredentialService。"""
    if channel == 'NEWEBPAY':
        k, iv = material['hashKey'].reveal(), material['hashIv'].reveal()
        if len(k) != 32 or not printable(k):
            return 'hashKey 须 32 位可见 ASCII（实际 %d 位）' % len(k)
        if len(iv) != 16 or not printable(iv):
            return 'hashIv 须 16 位可见 ASCII（实际 %d 位）' % len(iv)
        return None
    s = material['secret'].reveal()
    if not (16 <= len(s) <= 512):
        return 'channel secret 长度须 16..512（实际 %d）' % len(s)
    return None


def clean_id(v):
    """UME 导出里 LINE Pay channel_id 有尾随制表符或字面的 \\t（如 `1657463861\\t`），比对前去掉；与 channels.py clean() 同一规则。"""
    v = str(v or '').strip()
    while v.endswith('\\t'):
        v = v[:-2].rstrip()
    return v


def columns():
    path = os.environ.get('UME_COLUMNS', os.path.join(HERE, 'ume-columns.json'))
    if os.path.exists(path):
        cfg = json.load(open(path, encoding='utf-8'))
        return {k: {**DEFAULT_COLUMNS[k], **cfg.get(k, {})} for k in DEFAULT_COLUMNS}
    return DEFAULT_COLUMNS


def ident(name):
    if not name.replace('_', '').replace('.', '').isalnum():
        raise SystemExit('列名/表名不合法：%r' % name)
    return '.'.join('`%s`' % p for p in name.split('.'))


class Ume:
    def __init__(self, conn, cols):
        self.conn, self.cols = conn, cols

    @staticmethod
    def connect():
        import pymysql  # pip install pymysql
        pwd = os.environ.get('UME_DB_PASSWORD') or getpass.getpass('UME 库口令（不回显）: ')
        return pymysql.connect(host=os.environ['UME_DB_HOST'], port=int(os.environ.get('UME_DB_PORT', '3306')),
                               user=os.environ['UME_DB_USER'], password=pwd, charset='utf8mb4', read_timeout=30)

    def describe(self):
        for ch, c in self.cols.items():
            schema, table = c['table'].split('.')
            with self.conn.cursor() as cur:
                cur.execute('SELECT column_name FROM information_schema.columns WHERE table_schema=%s AND table_name=%s '
                            'ORDER BY ordinal_position', (schema, table))
                print(ch, c['table'], ' '.join(r[0] for r in cur.fetchall()))

    def lookup(self, row):
        """返回 (material|None, 结论, 说明)。结论：FOUND / MISSING / AMBIGUOUS / MISMATCH / INVALID。"""
        ch = row['payChannel']
        c = self.cols[ch]
        key = row['umeRecord'] if ch == 'LINEPAY' else row['channelMerchantId']
        fields = ['channelId', 'secret'] if ch == 'LINEPAY' else ['hashKey', 'hashIv']
        sql = 'SELECT %s FROM %s WHERE %s=%%s' % (', '.join(ident(c[f]) for f in fields), ident(c['table']), ident(c['key']))
        args = [key]
        for col, val in (c.get('where') or {}).items():
            sql += ' AND %s=%%s' % ident(col)
            args.append(val)
        with self.conn.cursor() as cur:
            cur.execute(sql, args)
            rows = cur.fetchall()
        distinct = {tuple((v or '').strip() if isinstance(v, str) else v for v in r) for r in rows}
        if not distinct:
            return None, 'MISSING', 'UME 无记录 %s' % key
        if len(distinct) > 1:
            return None, 'AMBIGUOUS', 'UME %s 有 %d 组不同取值' % (key, len(distinct))
        vals = next(iter(distinct))
        if ch == 'LINEPAY':
            if clean_id(vals[0]) != row['channelMerchantId']:
                return None, 'MISMATCH', 'UME 记录 %s 的 channel_id 与账户渠道商户号不一致' % key
            material = {'secret': Secret(vals[1] or '')}
        else:
            material = {'hashKey': Secret(vals[0] or ''), 'hashIv': Secret(vals[1] or '')}
        bad = validate(ch, material)
        if bad:
            return None, 'INVALID', bad
        return material, 'FOUND', ''


class PayOps:
    """near-pay 运营接口；三个操作人各自一个会话，令牌只在进程内。"""

    def __init__(self, request, tokens):
        self.request, self.tok = request, tokens

    def call(self, who, method, path, body=None):
        st, r = self.request(method, path, self.tok[who], body)
        r = r or {}
        if st != 200 or r.get('code') not in (0, None):
            raise StepError('%s %s → HTTP %s code=%s %s' % (method, path.split('?')[0], st, r.get('code'),
                                                            str(r.get('message') or r.get('msg') or r.get('raw') or '')[:160]))
        return r.get('data')


class StepError(Exception):
    pass


def rid(*parts):
    return '-'.join(str(p) for p in parts)[:64]


def approve(api, subject_type, subject_ref, revision, action, req, reviewers, reason):
    """提交审批并逐节点通过，返回审批号。同一 requestNo 重放返回同一单。"""
    a = api.call('CONFIG', 'POST', '/pay-ops/v2/approvals', {'requestNo': req, 'subjectType': subject_type,
                 'subjectRef': subject_ref, 'subjectRevision': revision, 'action': action, 'reason': reason})
    no = (a.get('record') or a)['approvalNo']
    for i in range(len(reviewers) + 1):
        d = api.call('CONFIG', 'GET', '/pay-ops/v2/approvals/%s' % no)
        rec = d.get('record') or d
        if rec['status'] == 'APPROVED':
            return no
        if rec['status'] not in ('PENDING_FIRST', 'PENDING_SECOND') or i >= len(reviewers):
            raise StepError('审批 %s 状态 %s' % (no, rec['status']))
        api.call(reviewers[i], 'POST', '/pay-ops/v2/approvals/%s/review' % no,
                 {'requestNo': rid(req, 'r', i + 1), 'expectedVersion': rec['version'], 'decision': 'APPROVE',
                  'reason': reason, 'materialVersion': rec.get('materialVersion')})
    raise StepError('审批 %s 未通过' % no)


def account(api, no):
    d = api.call('CONFIG', 'GET', '/pay-ops/v2/channel-accounts/%s' % no)
    rec = d['record']
    return rec['account'], rec, d['version']


def credential_key(api, row, reason):
    """该渠道账户名下已有的凭据（重跑），否则登记一把。"""
    t = CRED_TYPE[row['payChannel']]
    page = api.call('CONFIG', 'GET', '/pay-ops/v2/credentials?ownerRef=%s&credentialType=%s&size=20' % (row['accountNo'], t))
    live = [k for k in (page or {}).get('records', []) if k.get('status') not in ('REVOKED', 'RETIRED')]
    if live:
        return live[0]['keyId']
    return api.call('CONFIG', 'POST', '/pay-ops/v2/credentials', {'credentialType': t, 'ownerRef': row['accountNo'], 'reason': reason})['keyId']


def already_active(api, no):
    """账户已启用且已绑定凭据：重跑时跳过，避免用同一密钥轮换。"""
    d = api.call('CONFIG', 'GET', '/pay-ops/v2/channel-accounts/%s' % no)
    acct = ((d or {}).get('record') or {}).get('account') or {}
    return acct.get('status') == 'ACTIVE' and bool(acct.get('credentialKeyId'))


def migrate_direct(api, row, material, log):
    """near-pay 迁移导入端点（TDD-UME渠道密钥迁移导入）：一次调用直到账户可收款，不经审批；按 requestNo 与外部编号续做。"""
    no, ch = row['accountNo'], row['payChannel']
    reason = REASON % ('LINE Pay' if ch == 'LINEPAY' else '藍新', row['umeRecord'])
    body = ({'secret': material['secret'].reveal()} if ch == 'LINEPAY'
            else {'hashKey': material['hashKey'].reveal(), 'hashIv': material['hashIv'].reveal()})
    d = api.call('CONFIG', 'POST', '/pay-ops/v2/channel-accounts/%s/migration-credential' % no,
                 {**body, 'requestNo': rid('ume-key', no), 'reason': reason})
    body = None
    acct = ((d or {}).get('record') or {}).get('account') or {}
    log('迁移导入完成')
    return ('ACTIVATED', '') if acct.get('status') == 'ACTIVE' else ('FAILED', '导入后状态 %s' % acct.get('status'))


def migrate_one(api, row, material, log):
    no, ch = row['accountNo'], row['payChannel']
    reason = REASON % ('LINE Pay' if ch == 'LINEPAY' else '藍新', row['umeRecord'])
    acct, detail, _ = account(api, no)
    if acct['status'] == 'ACTIVE':
        return 'DONE', '已启用'
    if acct['status'] != 'UNVERIFIED':
        raise StepError('账户状态 %s，不处理' % acct['status'])

    # 1 凭据：登记 → 录入版本 → 核验 → 启用（CREDENTIAL_ENABLE 两人审批）
    key = acct.get('credentialKeyId') or credential_key(api, row, reason)
    versions = api.call('CONFIG', 'GET', '/pay-ops/v2/credentials/%s' % key).get('versions') or []
    active = [v for v in versions if v['status'] == 'ACTIVE']
    if not active:
        cand = [v for v in versions if v['status'] in ('DRAFT', 'VERIFIED')]
        if cand:
            v = cand[-1]
        else:
            entry = ({'secret': material['secret'].reveal()} if ch == 'LINEPAY'
                     else {'hashKey': material['hashKey'].reveal(), 'hashIv': material['hashIv'].reveal()})
            v = api.call('CONFIG', 'POST', '/pay-ops/v2/credentials/%s/versions' % key, {**entry, 'reason': reason})
            entry = None
            log('录入版本 v%s' % v['versionIndex'])
        vi = v['versionIndex']
        if v['status'] == 'DRAFT':
            api.call('CONFIG', 'POST', '/pay-ops/v2/credentials/%s/versions/%s/verify' % (key, vi),
                     {'evidenceRef': row['evidenceRef'], 'reason': reason})
        appr = approve(api, 'CREDENTIAL_ENABLE', '%s:%s' % (key, vi), vi, 'ENABLE', rid('ume-key-en', key, vi),
                       ['REVIEW1', 'REVIEW2'], reason)
        api.call('REVIEW1', 'POST', '/pay-ops/v2/credentials/%s/versions/%s/enable' % (key, vi),
                 {'approvalRef': appr, 'reason': reason})
        log('凭据 %s v%s 已启用（审批 %s）' % (key, vi, appr))

    # 2 绑定到渠道账户
    acct, detail, ver = account(api, no)
    if acct.get('credentialKeyId') != key:
        api.call('CONFIG', 'PUT', '/pay-ops/v2/channel-accounts/%s/credential' % no,
                 {'requestNo': rid('ume-key-bind', no, key), 'expectedVersion': ver, 'keyId': key, 'reason': reason})
        log('已绑定凭据 %s' % key)

    # 3 开通产品
    opened = {p['productCode'] for p in (detail.get('products') or []) if p.get('status') == 'OPEN'}
    for code in PRODUCTS[ch]:
        if code in opened:
            continue
        _, _, ver = account(api, no)
        api.call('CONFIG', 'POST', '/pay-ops/v2/channel-accounts/%s/products/%s/open' % (no, code),
                 {'requestNo': rid('ume-prod', no, code), 'expectedVersion': ver, 'source': 'OPS_CONFIRMED',
                  'evidenceRef': row['evidenceRef'], 'reason': reason})
        log('开通产品 %s' % code)

    # 4 账户核验（CHANNEL_ACCOUNT_VERIFY 审批；登记人不得核验，由复核人执行）
    acct, detail, ver = account(api, no)
    if acct['verificationStatus'] != 'VERIFIED':
        appr = approve(api, 'CHANNEL_ACCOUNT_VERIFY', no, ver + 1, 'VERIFY', rid('ume-acct-vf', no, ver + 1),
                       ['REVIEW1', 'REVIEW2'], reason)
        api.call('REVIEW1', 'POST', '/pay-ops/v2/channel-accounts/%s/verify' % no,
                 {'requestNo': rid('ume-acct-verify', no, ver), 'expectedVersion': ver, 'result': 'VERIFIED', 'reason': reason})
        log('账户已核验（审批 %s）' % appr)

    # 5 启用
    _, _, ver = account(api, no)
    api.call('CONFIG', 'POST', '/pay-ops/v2/channel-accounts/%s/activate' % no,
             {'requestNo': rid('ume-acct-act', no, ver), 'expectedVersion': ver, 'reason': reason})
    acct, _, _ = account(api, no)
    return ('ACTIVATED', '') if acct['status'] == 'ACTIVE' else ('FAILED', '启用后状态 %s' % acct['status'])


def targets(path, only=None, limit=None):
    import csv
    rows = list(csv.DictReader(open(path, encoding='utf-8'), delimiter='\t'))
    if only:
        rows = [r for r in rows if only in (r['accountNo'], r['merchantNo'])]
    return rows[:limit] if limit else rows


def run(mode, rows, ume, api=None, out=None, pause=0.15):
    results = []
    for i, r in enumerate(rows, 1):
        steps = []
        material, verdict, note = None, 'FAILED', ''
        try:
            if mode == 'apply' and os.environ.get('UME_KEYS_FLOW') != 'approval' and os.environ.get('UME_KEYS_ROTATE') != '1' \
                    and already_active(api, r['accountNo']):
                verdict, note = 'DONE', '已启用，跳过（轮换须 UME_KEYS_ROTATE=1）'
            else:
                material, verdict, note = ume.lookup(r)
                if mode == 'apply' and material is not None:
                    flow = migrate_one if os.environ.get('UME_KEYS_FLOW') == 'approval' else migrate_direct
                    verdict, note = flow(api, r, material, steps.append)
        except StepError as e:
            verdict, note = 'FAILED', str(e)
        except Exception as e:  # noqa: BLE001  —— 只报类型与消息，消息来自库驱动/HTTP，不含密钥
            verdict, note = 'FAILED', '%s: %s' % (type(e).__name__, str(e)[:160])
        finally:
            material = None
        results.append({k: r[k] for k in ('accountNo', 'merchantNo', 'payChannel', 'channelMerchantId', 'umeRecord')}
                       | {'verdict': verdict, 'note': note, 'steps': steps})
        print('%4d/%d %-20s %-9s %-14s %-9s %s' % (i, len(rows), r['accountNo'], r['payChannel'], r['channelMerchantId'],
                                                  verdict, note or '；'.join(steps)), flush=True)
        if mode == 'apply':
            time.sleep(pause)
    counts = {}
    for x in results:
        counts[x['verdict']] = counts.get(x['verdict'], 0) + 1
    print('SUMMARY', json.dumps(counts, ensure_ascii=False))
    if out:
        json.dump(results, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    return 1 if any(v not in ('FOUND', 'DONE', 'ACTIVATED') for v in counts) else 0


def ops_client():
    sys.path.insert(0, os.path.join(HERE, '..', '..', '..', 'scripts', 'automation'))
    import _ticket as t
    ops, pay = os.environ.get('OPS_BASE', 'http://127.0.0.1:28081'), os.environ.get('PAY_BASE', 'http://127.0.0.1:28093')
    # 迁移端点在迁移窗口内免认证（near-pay.credential.migration.anonymous）：UME_KEYS_ANON=1 时不取令牌、不带 Authorization。
    # 审批流程（UME_KEYS_FLOW=approval）仍需运营令牌。
    if os.environ.get('UME_KEYS_ANON') == '1' and os.environ.get('UME_KEYS_FLOW') != 'approval':
        t.BASE_URL = pay
        return PayOps(lambda m, p, tok, b: t.request(m, p, None, b, timeout=60), {k: None for k in ('CONFIG', 'REVIEW1', 'REVIEW2')})
    t.BASE_URL = ops
    tokens = {who: t.session('OPS', os.environ.get('SUB_' + who, 'ST-UME-' + who))
              for who in ('CONFIG', 'REVIEW1', 'REVIEW2')}
    t.BASE_URL = pay
    return PayOps(lambda m, p, tok, b: t.request(m, p, tok, b, timeout=60), tokens)


def main(a):
    def opt(name, conv=str):
        return conv(a[a.index(name) + 1]) if name in a else None
    if not a or a[0] not in ('describe', 'check', 'apply') or (a[0] != 'describe' and len(a) < 2):
        print(__doc__)
        return 2
    ume = Ume(Ume.connect(), columns())
    if a[0] == 'describe':
        ume.describe()
        return 0
    rows = targets(a[1], opt('--only'), opt('--limit', int))
    if not rows:
        print('没有匹配的目标账户')
        return 2
    return run(a[0], rows, ume, ops_client() if a[0] == 'apply' else None, opt('--out'))


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
