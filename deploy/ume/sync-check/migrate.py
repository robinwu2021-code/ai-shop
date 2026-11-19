"""UME 迁移建档批量（TDD-UME迁移建档）：逐个 UME 导入主体 迁移建档 → 核验来源关联 → 回读。可重复执行（每步先查后做）。

输入：对账输出（reconcile.sql 的 TSV，取 stage=5_NO_PAY_MERCHANT 的行）或主体号清单（每行一个 MUME…）。
运行（自动化票据登录，令牌只在进程内）：
  AUTOMATION_KEY=~/work/env/ume/automation-ume-ed25519.pem OPS_BASE=http://127.0.0.1:28081 PAY_BASE=http://127.0.0.1:28093 \\
    python3 -I deploy/ume/sync-check/migrate.py entities.tsv --out result.json [--limit N] [--sub ST-UME-CONFIG]
每户结论：DONE（已有核验的来源关联与支付商户）、MIGRATED（本次建档并核验）、FAILED（附原因）。
"""
import json, os, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'scripts', 'automation'))
import _ticket as t  # noqa: E402

REASON = 'UME 迁移：来源主体 %s 即 UME 商户 %s，名称与门店按 UME 导入核对一致（TDD-UME迁移建档）'


class Ops:
    def __init__(self, ops_base, pay_base, sub):
        self.ops_base, self.pay_base = ops_base, pay_base
        t.BASE_URL = ops_base
        self.tok = t.session('OPS', sub)

    def shop(self, m, p, b=None):
        t.BASE_URL = self.ops_base
        return t.request(m, p, self.tok, b, timeout=60)

    def pay(self, m, p, b=None):
        t.BASE_URL = self.pay_base
        return t.request(m, p, self.tok, b, timeout=60)


def data(r):
    r = r or {}
    d = r.get('data') if isinstance(r, dict) else None
    return d.get('record', d) if isinstance(d, dict) else (d or {})


def one(api, entity):
    st, r = api.shop('POST', '/ops/merchant/%s/pay-service/migrate' % entity, {'requestNo': ('ume-migrate-' + entity[-40:])[:64]})
    if st != 200 or (r or {}).get('code') not in (0, None):
        return {'entity': entity, 'verdict': 'FAILED', 'note': 'migrate %s %s %s' % (st, (r or {}).get('code'), (r or {}).get('msg'))}
    merchant = ((r or {}).get('data') or {}).get('merchantRef')
    stores = ((r or {}).get('data') or {}).get('storeLinks') or []
    if not merchant:
        return {'entity': entity, 'verdict': 'FAILED', 'note': 'no merchantRef'}
    st, r = api.pay('GET', '/pay-ops/v2/merchants/%s' % merchant)
    links = [x for x in data(r).get('sourceLinks') or [] if x.get('externalEntityId') == entity]
    if len(links) != 1:
        return {'entity': entity, 'merchant': merchant, 'verdict': 'FAILED', 'note': 'source links %d' % len(links)}
    link = links[0]
    verdict = 'DONE'
    if link.get('verifyStatus') != 'VERIFIED':
        src = entity[4:] if entity.startswith('MUME') else entity
        st, r = api.pay('POST', '/pay-ops/v2/merchants/%s/source-links/%s/verify' % (merchant, link['linkNo']),
                        {'requestNo': ('ume-verify-' + link['linkNo'])[:64], 'expectedVersion': link.get('version', 0),
                         'result': 'VERIFIED', 'reason': REASON % (entity, src)})
        if st != 200 or (r or {}).get('code') not in (0, None):
            return {'entity': entity, 'merchant': merchant, 'verdict': 'FAILED',
                    'note': 'verify %s %s %s' % (st, (r or {}).get('code'), (r or {}).get('message'))}
        verdict = 'MIGRATED'
    return {'entity': entity, 'merchant': merchant, 'link': link['linkNo'], 'stores': len(stores),
            'storesActive': sum(1 for s in stores if s.get('status') == 'ACTIVE'), 'verdict': verdict}


def entities(path):
    out = []
    for line in open(path, encoding='utf-8'):
        cols = line.rstrip('\n').split('\t')
        if len(cols) == 1 and cols[0].startswith('MUME'):
            out.append(cols[0])
        elif len(cols) > 2 and cols[-1] == '5_NO_PAY_MERCHANT' and cols[1].startswith('MUME'):
            out.append(cols[1])
    return out


def main(argv):
    if len(argv) < 2:
        print(__doc__); return 2
    arg = lambda k, d=None: argv[argv.index(k) + 1] if k in argv else d
    todo = entities(argv[1])[:int(arg('--limit', 10 ** 6))]
    api = Ops(os.environ.get('OPS_BASE', 'http://127.0.0.1:28081'), os.environ.get('PAY_BASE', 'http://127.0.0.1:28093'),
              arg('--sub', 'ST-UME-CONFIG'))
    results = []
    for i, e in enumerate(todo, 1):
        try:
            res = one(api, e)
        except Exception as x:  # noqa: BLE001 —— 一户失败不影响其余，原因进报告
            res = {'entity': e, 'verdict': 'FAILED', 'note': type(x).__name__ + ': ' + str(x)[:160]}
        results.append(res)
        print('%4d/%d %-28s %-9s %s' % (i, len(todo), e, res['verdict'], res.get('note', res.get('merchant', ''))), flush=True)
        time.sleep(0.2)
    if arg('--out'):
        json.dump(results, open(arg('--out'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    counts = {}
    for r in results:
        counts[r['verdict']] = counts.get(r['verdict'], 0) + 1
    print('SUMMARY', json.dumps(counts))
    return 1 if counts.get('FAILED') else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
