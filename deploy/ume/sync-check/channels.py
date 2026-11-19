"""UME 渠道账户批量登记（TDD-门店代码与商户渠道展示 §8；做法同 oscar、roger）。

  plan  STG.tsv MAP.tsv PLAN.tsv   由 UME 导出生成逐条清单（只取编号类字段，不读任何密钥）
  apply PLAN.tsv [--out R.json]    调运营接口 POST /pay-ops/v2/channel-accounts 登记，状态均为 UNVERIFIED（待补密钥核验 / 待开通）

STG.tsv：merchant_id<TAB>scan_channels<TAB>card_channels（nearpay_prod.stg_ume_active_payment_merchants_20261112）
MAP.tsv：merchant_id<TAB>entity_name<TAB>pay_merchant_no（主体在支付侧已核验的支付商户；空表示还没有）
规则：UME 已停用（channel_merchant_status≠1）、无别名、无渠道商户号的不登记；同一渠道商户号出现在多个商户时，
只登记给 UME 记录里渠道商户名与主体名一致的那一户，判不出唯一归属的不登记。每条都进清单并给出结论。
"""
import csv, json, os, sys, time

ALIAS = {  # (kind, alias) -> (near-pay 渠道, 渠道商户号字段)
    ('CARD', 'spgateway'): ('NEWEBPAY', 'channel_merchant_no'),
    ('SCAN', 'linepay'): ('LINEPAY', 'channel_id'),
    ('SCAN', 'gamapay'): ('GAMAPAY', 'merchant_id'),
    ('SCAN', 'ezpay'): ('EZPAY', 'merchant_id'),
    ('SCAN', 'pipay'): ('PIPAY', 'partner_id'),
    ('SCAN', 'lanxinalipay'): ('EZPAY_CROSSBORDER', 'merchant_id'),
    ('SCAN', 'fbwechat'): ('FUBON_WECHAT', 'store_code'),
    ('SCAN', 'alipay'): ('ALIPAY', 'store_id'),
}
SUB = {  # 同一渠道商户号下区分多台终端：作为子商户号登记
    ('SCAN', 'fbwechat'): 'terminal_code',
    ('SCAN', 'alipay'): 'terminal_id',
    ('SCAN', 'atome'): ('ATOME', 'merchant_no'),
}
FIELDS = ['merchantId', 'payMerchantNo', 'kind', 'alias', 'payChannel', 'channelMerchantId', 'subMerchantId', 'recordId', 'evidenceRef', 'plan', 'note']


def norm(s):
    return ''.join((s or '').split()).replace('股份有限公司', '').replace('有限公司', '').replace('（', '(').replace('）', ')').lower()


def mid_of(kind, c, field):
    if kind == 'CARD':
        return (c.get('channel_merchant_no') or '').strip()
    for v in (c.get('provider_identifiers') or {}).values():
        if isinstance(v, dict) and v.get(field) not in (None, ''):
            return str(v[field]).strip()
    return ''


def plan(stg, mapping, out):
    m = {}
    for row in csv.reader(open(mapping, encoding='utf-8'), delimiter='\t'):
        if row:
            m[row[0]] = (row[1] if len(row) > 1 else '', row[2] if len(row) > 2 and row[2] not in ('NULL', '') else '')
    rows = []
    for line in open(stg, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 3:
            continue
        merchant = p[0]
        for kind, raw in (('SCAN', p[1]), ('CARD', p[2])):
            if raw in ('NULL', ''):
                continue
            for c in json.loads(raw.replace('\\\\', '\\')):
                alias = (c.get('channel_alias') or '').strip()
                key = (kind, alias.lower())
                r = {'merchantId': merchant, 'payMerchantNo': m.get(merchant, ('', ''))[1], 'kind': kind, 'alias': alias,
                     'payChannel': '', 'channelMerchantId': '', 'subMerchantId': '', 'recordId': c.get('channel_merchant_id') or '', 'evidenceRef': '',
                     'plan': 'REGISTER', 'note': '', '_name': c.get('channel_merchant_name') or ''}
                if key not in ALIAS:
                    r.update(plan='SKIP', note='无对应渠道（别名为空或未知）')
                else:
                    ch, field = ALIAS[key]
                    r['payChannel'] = ch
                    r['channelMerchantId'] = mid_of(kind, c, field)
                    r['subMerchantId'] = mid_of(kind, c, SUB[key]) if key in SUB else ''
                    r['evidenceRef'] = ('file:ume/msm_mms/chn_mer_card_inf/%s' % r['channelMerchantId'] if kind == 'CARD'
                                        else 'file:ume/msm_mms/chn_mer_scan_%s_info/%s' % (alias.lower(), r['recordId']))
                    if str(c.get('channel_merchant_status')) != '1':
                        r.update(plan='SKIP', note='UME 已停用')
                    elif not r['channelMerchantId']:
                        r.update(plan='SKIP', note='UME 无渠道商户号')
                    elif not r['payMerchantNo']:
                        r.update(plan='SKIP', note='主体还没有支付商户')
                rows.append(r)
    by = {}
    for r in rows:
        if r['plan'] == 'REGISTER':
            by.setdefault((r['payChannel'], r['channelMerchantId'], r['subMerchantId']), []).append(r)
    for (ch, mid, _sub), group in by.items():
        if len({g['merchantId'] for g in group}) <= 1:
            for g in group[1:]:
                g.update(plan='SKIP', note='同一商户内重复')
            continue
        owners = [g for g in group if norm(g['_name']) and norm(g['_name']) in norm(m.get(g['merchantId'], ('', ''))[0])]
        winners = {g['merchantId'] for g in owners}
        for g in group:
            if len(winners) == 1 and g['merchantId'] in winners and g is owners[0]:
                g['note'] = '多户共用，按 UME 渠道商户名「%s」归属本户' % g['_name']
            else:
                g.update(plan='SKIP', note='多户共用（%d 户），按 UME 数据%s' % (len({x['merchantId'] for x in group}),
                                                                       '归属其他户' if len(winners) == 1 else '判不出唯一归属'))
    with open(out, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, delimiter='\t', extrasaction='ignore')
        w.writeheader()
        w.writerows(rows)
    counts = {}
    for r in rows:
        counts[(r['plan'], r['note'][:12])] = counts.get((r['plan'], r['note'][:12]), 0) + 1
    for k, v in sorted(counts.items()):
        print(k, v)


def apply(path, out):
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'scripts', 'automation'))
    import _ticket as t
    ops, pay = os.environ.get('OPS_BASE', 'http://127.0.0.1:28081'), os.environ.get('PAY_BASE', 'http://127.0.0.1:28093')
    t.BASE_URL = ops
    tok = t.session('OPS', os.environ.get('OPS_SUB', 'ST-UME-CONFIG'))
    t.BASE_URL = pay
    results = []
    rows = [r for r in csv.DictReader(open(path, encoding='utf-8'), delimiter='\t') if r['plan'] == 'REGISTER']
    for i, r in enumerate(rows, 1):
        body = {'requestNo': ('ume-ch-%s-%s' % (r['payChannel'].lower(), r['recordId'] or r['channelMerchantId']))[:64],
                'ownerType': 'MERCHANT', 'merchantNo': r['payMerchantNo'], 'payChannel': r['payChannel'],
                'channelMerchantId': r['channelMerchantId'], 'subMerchantId': r.get('subMerchantId') or None, 'accountMode': 'MERCHANT_OWN',
                'fundsPath': 'DIRECT', 'parentPayMerchantNo': None, 'evidenceRef': r['evidenceRef'],
                'reason': ('UME 渠道迁移：按 UME 导出登记（快照 nearpay_prod.stg_ume_active_payment_merchants_20261112）'
                           + ('；' + r['note'] if r['note'] else ''))[:255]}
        try:
            st, res = t.request('POST', '/pay-ops/v2/channel-accounts', tok, body, timeout=60)
            code, msg = (res or {}).get('code'), (res or {}).get('message')
            if st == 200 and code == 0:
                verdict = 'REGISTERED'
            elif '已登记' in str(msg):
                verdict = 'EXISTS'
            else:
                verdict = 'FAILED'
        except Exception as x:  # noqa: BLE001
            st, code, msg, verdict = None, None, type(x).__name__ + ': ' + str(x)[:120], 'FAILED'
        results.append({**{k: r[k] for k in ('merchantId', 'payMerchantNo', 'payChannel', 'channelMerchantId')},
                        'verdict': verdict, 'note': '' if verdict == 'REGISTERED' else '%s %s %s' % (st, code, msg)})
        print('%4d/%d %-20s %-18s %-22s %s %s' % (i, len(rows), r['payMerchantNo'], r['payChannel'], r['channelMerchantId'], verdict,
                                                 results[-1]['note']), flush=True)
        time.sleep(0.15)
    if out:
        json.dump(results, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    counts = {}
    for r in results:
        counts[r['verdict']] = counts.get(r['verdict'], 0) + 1
    print('SUMMARY', json.dumps(counts))
    return 1 if counts.get('FAILED') else 0


if __name__ == '__main__':
    a = sys.argv
    if len(a) >= 5 and a[1] == 'plan':
        plan(a[2], a[3], a[4])
    elif len(a) >= 3 and a[1] == 'apply':
        sys.exit(apply(a[2], a[a.index('--out') + 1] if '--out' in a else None))
    else:
        print(__doc__)
        sys.exit(2)
