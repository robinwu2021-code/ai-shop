"""商户端渠道列表 API 验收（TDD-门店代码与商户渠道展示 C1–C6）：以店主身份调 GET /biz/merchant/payment?entityNo=，
与 near-pay 库内的预期（ACCOUNTS.tsv）逐行比对：渠道、渠道商户号全号、状态、待开通原因、沙箱。

用法：
  AUTOMATION_KEY=~/work/env/ume/automation-ume-ed25519.pem OPS_BASE=http://127.0.0.1:28081 BIZ_BASE=http://127.0.0.1:28082 \\
    python3 -I verify_merchant_api.py EXPECTED.tsv ENTITY:USER_NO:STORE_NO [...]
EXPECTED.tsv：entity<TAB>channel<TAB>mid<TAB>sub<TAB>accountStatus<TAB>verification<TAB>channelStatus<TAB>stores（库内复算）
登录走 OPS_BASE 实例（店主用户号须在其自动化白名单里，形如 B:<userNo>），/biz 调用走 BIZ_BASE 实例。令牌只在进程内，不打印。
"""
import collections, os, sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'scripts', 'automation'))


def expected_rows(path):
    exp = collections.defaultdict(list)
    for line in open(path, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 7:
            continue
        live = p[6] == 'ENABLED' and p[4] == 'ACTIVE'
        reason = None if live else ('CHANNEL_NOT_LIVE' if p[6] != 'ENABLED' else 'ACCOUNT_UNVERIFIED')
        status = 'ACTIVE' if live else 'FROZEN' if p[4] == 'SUSPENDED' else 'PENDING_ACTIVATION'
        exp[p[0]].append((p[1], p[2], status, reason))
    return exp


def check(entity, rows, expected):
    got = sorted((r.get('payChannel'), r.get('channelMerchantId'), r.get('applyStatus'), r.get('pendingReason')) for r in rows)
    want = sorted(expected)
    problems = []
    if got != want:
        problems.append('多出 %s；缺少 %s' % (sorted(set(got) - set(want)), sorted(set(want) - set(got))))
    for r in rows:
        if r.get('storeNo') is not None:
            problems.append('%s 行 storeNo 应为 null' % r.get('payChannel'))
        if r.get('channelName') in (None, ''):
            problems.append('%s 行缺 channelName' % r.get('payChannel'))
        if r.get('sandbox') != r.get('payChannel', '').endswith('_TEST'):
            problems.append('%s 行 sandbox=%s' % (r.get('payChannel'), r.get('sandbox')))
    return problems


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    import _ticket as t
    t.BASE_URL = os.environ.get('OPS_BASE', 'http://127.0.0.1:28081')
    exp = expected_rows(argv[0])
    bad = 0
    for spec in argv[1:]:
        entity, user, store = spec.split(':')
        st, r = t.request('POST', '/common/auth/automation', None, {'ticket': t.sign_ticket('B', user)})
        tok = ((r or {}).get('data') or {}).get('token')
        if not tok:
            print(entity, 'LOGIN_REJECTED', st, (r or {}).get('code'), '（店主不在自动化白名单）')
            bad += 1
            continue
        os.environ['AUTOMATION_STORE_NO'] = store
        login_base, t.BASE_URL = t.BASE_URL, os.environ.get('BIZ_BASE', 'http://127.0.0.1:28082')  # /biz 只在商户端实例上
        st, r = t.request('GET', '/biz/merchant/payment?entityNo=' + entity, tok)
        t.BASE_URL = login_base
        rows = (r or {}).get('data') or []
        problems = check(entity, rows, exp.get(entity, [])) if st == 200 and (r or {}).get('code') == 0 \
            else ['HTTP %s code=%s %s' % (st, (r or {}).get('code'), (r or {}).get('msg'))]
        print(entity, 'OK' if not problems else 'MISMATCH', '%d 行' % len(rows), '；'.join(problems))
        for x in rows:
            print('   %-18s %-14s %-19s %-19s sandbox=%s stores=%d' % (x.get('payChannel'), x.get('channelMerchantId'),
                  x.get('applyStatus'), x.get('pendingReason'), x.get('sandbox'), len(x.get('enabledStores') or [])))
        bad += bool(problems)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
