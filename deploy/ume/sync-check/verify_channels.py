"""渠道账户对账：channels.py 生成的清单里每条 REGISTER，在支付侧是否存在（渠道 + 渠道商户号 + 子商户号）且挂在应挂的支付商户下。

用法：python3 -I verify_channels.py PLAN.tsv ACCOUNTS.tsv
ACCOUNTS.tsv：platform_merchant_no<TAB>pay_channel<TAB>channel_merchant_id<TAB>sub_merchant_id<TAB>status
（nearpay_prod.pay_merchant，owner_type=MERCHANT、deleted=0）
"""
import collections, csv, sys


def main(plan_path, accounts_path):
    acc = {}
    for line in open(accounts_path, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) >= 5:
            acc[(p[1], p[2], p[3] if p[3] != 'NULL' else '')] = (p[0], p[4])
    plan = [r for r in csv.DictReader(open(plan_path, encoding='utf-8'), delimiter='\t') if r['plan'] == 'REGISTER']
    missing, wrong, status = [], [], collections.Counter()
    for r in plan:
        k = (r['payChannel'], r['channelMerchantId'], r.get('subMerchantId') or '')
        if k not in acc:
            missing.append(r)
        elif acc[k][0] != r['payMerchantNo']:
            wrong.append((r, acc[k][0]))
        else:
            status[acc[k][1]] += 1
    print('planned', len(plan), 'ok', sum(status.values()), 'missing', len(missing), 'on another merchant', len(wrong), dict(status))
    for r in missing[:20]:
        print('  MISSING', r['merchantId'], r['payMerchantNo'], r['payChannel'], r['channelMerchantId'], r.get('subMerchantId'))
    for r, other in wrong[:20]:
        print('  WRONG', r['merchantId'], r['payChannel'], r['channelMerchantId'], 'expected', r['payMerchantNo'], 'found', other)
    return 1 if missing or wrong else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1], sys.argv[2]))
