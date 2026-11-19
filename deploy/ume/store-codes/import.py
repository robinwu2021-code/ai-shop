"""门店英文代码批量导入（TDD-门店代码与商户渠道展示 A3）。

读 CSV（表头 storeNo,code），逐行校验后调运营接口 PUT /ops/stores/{storeNo}/basics 写入 slug；
门店同步会把代码挂到该门店启用中的整店收款码上，形成 tpay…/s/<code>。

默认只校验、不写；加 --apply 才写。每行一个结论：
  OK         已写入并回读一致（--apply），或校验通过（默认，不调接口）
  INVALID    代码不合规（小写字母、数字、连字符，3–32 位，首尾非连字符；保留词）或门店号为空
  DUPLICATE  清单内同一代码出现多次，或同一门店出现多次
  FAILED     接口拒绝（如代码已被其他门店占用）或调用失败，附原因

用法：
  NEARSHOP_BASE=https://nearshop.umeshop.ai/ops-shop NEARSHOP_TOKEN=... \\
    python3 -I deploy/ume/store-codes/import.py codes.csv [--apply] [--json out.json]
令牌只从环境变量读，不写入报告。
"""
import csv
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

PATTERN = re.compile(r'^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$')
# 与 ai-shop StoreSlugs.RESERVED 同口径
RESERVED = {'s', 'c', 'b', 'dl', 'api', 'ops', 'ops-web', 'admin', 'login', 'download', 'static', 'assets', 'media',
            'uploads', 'internal'}
REASON = 'UME 导入：门店英文代码（支付链接用；批量清单）'


class Api:
    def __init__(self, base, token, timeout=15):
        self.base, self.token, self.timeout = base.rstrip('/'), token, timeout

    def set_code(self, store_no, code):
        return self._call('PUT', '/ops/stores/%s/basics' % urllib.parse.quote(store_no), {'slug': code, 'reason': REASON})

    def _call(self, method, path, body):
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/json'}
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body).encode('utf-8')
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                payload = json.loads(resp.read().decode('utf-8') or 'null')
        except urllib.error.HTTPError as e:
            raise RuntimeError('HTTP %s' % e.code)
        except (urllib.error.URLError, OSError) as e:
            raise RuntimeError(type(e).__name__)
        if isinstance(payload, dict) and 'code' in payload and str(payload.get('code')) not in ('0', '200'):
            raise RuntimeError('code %s %s' % (payload.get('code'), payload.get('msg') or ''))
        return payload.get('data') if isinstance(payload, dict) and 'data' in payload else payload


def normalize(code):
    return (code or '').strip().lower()


def check(rows):
    """校验清单本身；返回 [(storeNo, code, verdict or None, note)]。None 表示待调用接口。"""
    out, codes, stores = [], {}, {}
    for r in rows:
        store_no, code = (r.get('storeNo') or '').strip(), normalize(r.get('code'))
        codes[code] = codes.get(code, 0) + 1
        stores[store_no] = stores.get(store_no, 0) + 1
    for r in rows:
        store_no, code = (r.get('storeNo') or '').strip(), normalize(r.get('code'))
        if not store_no:
            out.append((store_no, code, 'INVALID', '门店号为空'))
        elif not PATTERN.match(code) or '--' in code:
            out.append((store_no, code, 'INVALID', '代码须为小写字母、数字、连字符，3–32 位，首尾非连字符'))
        elif code in RESERVED:
            out.append((store_no, code, 'INVALID', '保留词'))
        elif codes[code] > 1:
            out.append((store_no, code, 'DUPLICATE', '清单内代码重复'))
        elif stores[store_no] > 1:
            out.append((store_no, code, 'DUPLICATE', '清单内门店重复'))
        else:
            out.append((store_no, code, None, ''))
    return out


def run(api, rows, apply=False):
    report = []
    for store_no, code, verdict, note in check(rows):
        if verdict is None:
            if not apply:
                verdict, note = 'OK', '校验通过（未写入）'
            else:
                # 运营门店详情不回显代码，故不预读；写同一个值是幂等的，以写接口返回的代码回读核对
                try:
                    after = api.set_code(store_no, code) or {}
                    verdict, note = ('OK', '') if after.get('slug') == code else ('FAILED', '回读不一致')
                except RuntimeError as e:
                    verdict, note = 'FAILED', str(e)
        report.append({'storeNo': store_no, 'code': code, 'verdict': verdict, 'note': note})
    return report


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    base, token = os.environ.get('NEARSHOP_BASE'), os.environ.get('NEARSHOP_TOKEN')
    if not base or not token:
        print('需要环境变量 NEARSHOP_BASE 与 NEARSHOP_TOKEN', file=sys.stderr)
        return 2
    with open(argv[1], encoding='utf-8-sig', newline='') as f:
        rows = list(csv.DictReader(f))
    report = run(Api(base, token), rows, apply='--apply' in argv)
    for r in report:
        print('%-28s %-34s %-10s %s' % (r['storeNo'], r['code'], r['verdict'], r['note']))
    if '--json' in argv:
        with open(argv[argv.index('--json') + 1], 'w', encoding='utf-8') as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
    return 1 if any(r['verdict'] in ('INVALID', 'DUPLICATE', 'FAILED') for r in report) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
