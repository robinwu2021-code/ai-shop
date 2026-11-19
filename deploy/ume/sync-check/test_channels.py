"""python3 -I -m unittest discover -s deploy/ume/sync-check -p 'test_*.py'"""
import csv, json, os, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import channels  # noqa: E402


def scan(alias, rid, ids, name='創科', status='1'):
    return {'channel_alias': alias, 'channel_merchant_id': rid, 'channel_merchant_name': name, 'channel_merchant_status': status,
            'provider_identifiers': {alias.lower(): ids}}


def card(no, name='創科', status='1'):
    return {'channel_alias': 'spgateway', 'channel_merchant_no': no, 'channel_merchant_name': name, 'channel_merchant_status': status}


class PlanTest(unittest.TestCase):
    def run_plan(self, stg_rows, mapping):
        d = tempfile.mkdtemp()
        stg, mp, out = (os.path.join(d, n) for n in ('stg.tsv', 'map.tsv', 'plan.tsv'))
        with open(stg, 'w', encoding='utf-8') as f:
            for m, s, c in stg_rows:
                f.write('%s\t%s\t%s\n' % (m, json.dumps(s, ensure_ascii=False), json.dumps(c, ensure_ascii=False)))
        with open(mp, 'w', encoding='utf-8') as f:
            for k, (name, pm) in mapping.items():
                f.write('%s\t%s\t%s\n' % (k, name, pm))
        channels.plan(stg, mp, out)
        return list(csv.DictReader(open(out, encoding='utf-8'), delimiter='\t'))

    def test_maps_real_ids_and_skips_disabled_unknown_and_merchants_without_payment(self):
        rows = self.run_plan([
            ('M1', [scan('linepay', 'CM1', {'channel_id': '1629714284'}), scan('fbwechat', 'CM2', {'store_code': 'FB01', 'terminal_code': 'A001'}),
                    scan('fbwechat', 'CM6', {'store_code': 'FB01', 'terminal_code': 'A002'}),
                    scan('gamapay', 'CM3', {'merchant_id': '136'}, status='0'), scan('', 'CM4', {'merchant_id': '9'})],
             [card('MS1')]),
            ('M2', [scan('ezPay', 'CM5', {'merchant_id': 'PG1'})], []),
        ], {'M1': ('創科系統有限公司', 'PM1'), 'M2': ('乙', '')})
        by = {(r['merchantId'], r['payChannel'] or r['alias']): r for r in rows}
        self.assertEqual(by[('M1', 'LINEPAY')]['channelMerchantId'], '1629714284')
        self.assertEqual(by[('M1', 'LINEPAY')]['evidenceRef'], 'file:ume/msm_mms/chn_mer_scan_linepay_info/CM1')
        fb = [r for r in rows if r['payChannel'] == 'FUBON_WECHAT']
        self.assertEqual([(r['channelMerchantId'], r['subMerchantId'], r['plan']) for r in fb],
                         [('FB01', 'A001', 'REGISTER'), ('FB01', 'A002', 'REGISTER')], '富邦微信用 store_code + 终端号，两台终端各一个账户')
        self.assertEqual(by[('M1', 'NEWEBPAY')]['evidenceRef'], 'file:ume/msm_mms/chn_mer_card_inf/MS1')
        self.assertEqual(by[('M1', 'GAMAPAY')]['plan'], 'SKIP')
        self.assertEqual(by[('M1', '')]['plan'], 'SKIP')
        self.assertEqual(by[('M2', 'EZPAY')]['note'], '主体还没有支付商户')
        self.assertEqual({r['plan'] for r in rows if r['merchantId'] == 'M1' and r['payChannel'] in ('LINEPAY', 'FUBON_WECHAT', 'NEWEBPAY')},
                         {'REGISTER'})

    def test_a_shared_merchant_id_goes_only_to_the_entity_named_in_the_ume_record(self):
        rows = self.run_plan([('A', [], [card('MS354053380', name='创科')]), ('B', [], [card('MS354053380', name='创科')]),
                              ('C', [], [card('MSX', name='丙')]), ('D', [], [card('MSX', name='丙')])],
                             {'A': ('创科系统有限公司', 'PMA'), 'B': ('别家', 'PMB'), 'C': ('丁', 'PMC'), 'D': ('戊', 'PMD')})
        plan = {r['merchantId']: r for r in rows}
        self.assertEqual(plan['A']['plan'], 'REGISTER')
        self.assertIn('归属本户', plan['A']['note'])
        self.assertEqual(plan['B']['plan'], 'SKIP')
        self.assertEqual({plan['C']['plan'], plan['D']['plan']}, {'SKIP'}, '判不出唯一归属的都不登记')


if __name__ == '__main__':
    unittest.main()
