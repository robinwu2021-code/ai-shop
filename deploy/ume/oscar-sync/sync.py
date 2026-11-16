"""Oscar 迁移同步（TDD-商户迁移同步与渠道关联 WP1 只读 + WP6 --apply）。

默认只调运营 API 的 GET。每个迁移对象 / 关联输出一种结论：
  REUSE        唯一且归属一致，复用
  PLAN_CREATE  零条，待新建
  APPLIED      --apply 下已调用新建 / 业务命令并回读
  CONFLICT     多条、归属或字段不一致，停本项
  BLOCKED      HTTP 错误 / 超时 / 无权限 / 前置项未定，不能当作零条

--apply 只做以下写入，每步先查后建、requestNo 稳定（重跑沿用原单）：
  O3 资质只新建 · H 归属修复申请（审批由另一名运营完成；已批准时加 --execute-repair 执行）
  D 门店同步到支付侧 · O5 运营代建支付商户 · G 门店绑定渠道账户
其余（账号、主体、门店、渠道账户、凭据、产品、来源核验）只报告，不自动写。

用法：
  NEARSHOP_BASE=https://nearshop.umeshop.ai NEARSHOP_TOKEN=... \
    python3 -I deploy/ume/oscar-sync/sync.py deploy/ume/oscar-sync/oscar.example.json [--json out.json] [--apply [--execute-repair]]

令牌只从环境变量读，不写入报告；报告只含编号与状态，不含联系人、证件、账号、密钥。
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REUSE, PLAN_CREATE, APPLIED, CONFLICT, BLOCKED = 'REUSE', 'PLAN_CREATE', 'APPLIED', 'CONFLICT', 'BLOCKED'


class Blocked(Exception):
    def __init__(self, msg, status=None):
        super().__init__(msg)
        self.status = status


class Api:
    """GET-only 客户端。shop 前缀对应 ai-shop 运营，pay 前缀对应 near-pay pay-ops/v2。"""

    def __init__(self, base, token, shop_prefix='/ops-shop', pay_prefix='/oms-api/pay-ops/v2', timeout=15):
        self.base, self.token, self.timeout = base.rstrip('/'), token, timeout
        self.shop_prefix, self.pay_prefix = shop_prefix, pay_prefix

    def shop(self, path, **params):
        return self._get(self.shop_prefix + path, params)

    def pay(self, path, **params):
        return self._get(self.pay_prefix + path, params)

    def shop_post(self, path, body):
        return self._call('POST', self.shop_prefix + path, {}, body)

    def pay_post(self, path, body):
        return self._call('POST', self.pay_prefix + path, {}, body)

    def _get(self, path, params):
        return self._call('GET', path, params, None)

    def _call(self, method, path, params, body):
        query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        url = self.base + path + ('?' + query if query else '')
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/json'}
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body).encode('utf-8')
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode('utf-8')
        except urllib.error.HTTPError as e:
            raise Blocked('%s %s -> HTTP %s' % (method, path, e.code), e.code)
        except (urllib.error.URLError, OSError) as e:
            raise Blocked('%s %s -> %s' % (method, path, type(e).__name__))
        try:
            return unwrap(json.loads(raw) if raw else None, path)
        except ValueError:
            raise Blocked('%s %s -> 非 JSON 响应' % (method, path))


def unwrap(body, path=''):
    """ai-shop 包成 {code,msg,data}；near-pay 包成 PortalResponse {.., data}。业务失败一律 BLOCKED。"""
    if isinstance(body, dict) and 'data' in body:
        code = body.get('code')
        if body.get('success') is False or (code is not None and str(code) not in ('0', '200', 'OK', 'SUCCESS')):
            raise Blocked('%s -> code %s %s' % (path, code, body.get('msg') or body.get('message') or ''))
        return body['data']
    return body


def records(data):
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        if isinstance(data.get('records'), list):
            return data['records']
        if isinstance(data.get('page'), dict):
            return records(data['page'])
    raise Blocked('unexpected list shape')


def decide(candidates, same):
    """查询结果 → 结论。same(c) 为 True 表示归属与关键字段都一致。"""
    if not candidates:
        return PLAN_CREATE, None
    if len(candidates) == 1 and same(candidates[0]):
        return REUSE, candidates[0]
    return CONFLICT, None


class Report:
    def __init__(self):
        self.items = []

    def add(self, key, title, verdict, ids=None, note=''):
        self.items.append({'key': key, 'title': title, 'verdict': verdict, 'ids': ids or {}, 'note': note})
        return verdict

    def verdict(self, key):
        return next((i['verdict'] for i in self.items if i['key'] == key), None)


def step(report, key, title, fn):
    try:
        return fn()
    except Blocked as e:
        report.add(key, title, BLOCKED, note=str(e))
        return None


def detail(data):
    """near-pay Detail<T> 取 record；其他原样。"""
    return data.get('record', data) if isinstance(data, dict) else {}


def run(api, t, apply=False, execute_repair=False):
    """t：目标描述（oscar.example.json）。返回 (Report, 选定编号)。"""
    r, ids = Report(), {}
    rq = t.get('requestPrefix', 'oscar-sync')

    def req_no(*parts):
        return '-'.join((rq,) + parts)[:64]

    def account():
        rows = records(api.shop('/ops/customers/users', keyword=t['email'], size=20))
        if t.get('userNo'):
            rows = [x for x in rows if x.get('userNo') == t['userNo']] or rows
        v, hit = decide(rows, lambda x: not t.get('userNo') or x.get('userNo') == t['userNo'])
        if hit:
            ids['userNo'] = hit['userNo']
        r.add('O1', '账号', v, {'userNo': ids.get('userNo')}, '候选 %d 条' % len(rows))
    step(r, 'O1', '账号', account)

    def entity():
        rows = records(api.shop('/ops/merchants', keyword=t['entityName'], size=50))
        rows = [x for x in rows if x.get('name') == t['entityName'] and not str(x.get('name', '')).startswith('TEST ONLY')]
        v, hit = decide(rows, lambda x: x.get('merchantNo') == t['entityNo'])
        if hit:
            ids['entityNo'] = hit['merchantNo']
        note = '同名主体 %s' % ','.join(x.get('merchantNo', '?') for x in rows) if v == CONFLICT else ''
        if v == PLAN_CREATE:
            note = '主体必须先关联门店：走 apply-on-behalf 并带门店资料 storeProfile，不单独建主体'
        r.add('O2', '经营主体', v, {'entityNo': ids.get('entityNo'), 'status': hit and hit.get('status')}, note)
    step(r, 'O2', '经营主体', entity)

    def owner():
        if 'userNo' not in ids or 'entityNo' not in ids:
            raise Blocked('账号或主体未定')
        links = api.shop('/ops/customers/users/%s/stores' % ids['userNo'])
        ok = any(x.get('entityNo') == ids['entityNo'] for x in links or [])
        r.add('A', '账号↔主体', REUSE if ok else PLAN_CREATE, {}, '' if ok else '账号名下无该主体门店；补店主接口待定')
    step(r, 'A', '账号↔主体', owner)

    def qualifications():
        if 'entityNo' not in ids:
            raise Blocked('主体未定')
        rows = api.shop('/ops/merchants/%s/qualifications' % ids['entityNo']) or []
        have = {}
        for q in rows:
            if str(q.get('status', 'ACTIVE')).upper() in ('ACTIVE', 'VALID', 'EFFECTIVE'):
                have.setdefault(q.get('qualType'), []).append(q)
        specs = {q['qualType']: q for q in t.get('qualifications', [])}
        for qt in t.get('requiredQualTypes', []):
            v, _ = decide(have.get(qt, []), lambda x: True)
            if v == PLAN_CREATE and apply:
                if qt not in specs:
                    r.add('O3:' + qt, '资质 ' + qt, BLOCKED, {}, '配置缺资质资料，不生成假资料')
                    continue
                q = specs[qt]
                vo = api.shop_post('/ops/merchants/%s/qualification-creations' % ids['entityNo'],
                                   {k: q.get(k) for k in ('qualType', 'qualName', 'qualNumber', 'imageUrl', 'expireAt')})
                r.add('O3:' + qt, '资质 ' + qt, APPLIED, {'qualNo': vo.get('qualNo')})
                continue
            r.add('O3:' + qt, '资质 ' + qt, v, {}, '新建须 N2 只新建接口' if v == PLAN_CREATE else '')
    step(r, 'O3', '资质', qualifications)

    def store():
        if r.verdict('O2') == PLAN_CREATE:
            r.add('O4', '门店（含 B 主体↔门店）', PLAN_CREATE, {}, '随主体申请一起建（storeProfile）')
            return
        if 'entityNo' not in ids:
            raise Blocked('主体未定')
        rows = records(api.shop('/ops/stores', merchantNo=ids['entityNo'], keyword=t['storeName'], size=50))
        rows = [x for x in rows if x.get('name') == t['storeName']]
        v, hit = decide(rows, lambda x: x.get('storeNo') == t['storeNo'] and x.get('merchantNo') == ids['entityNo'])
        if hit:
            ids['storeNo'] = hit['storeNo']
        r.add('O4', '门店（含 B 主体↔门店）', v, {'storeNo': ids.get('storeNo'), 'payMerchantNo': hit and hit.get('payMerchantNo')})
    step(r, 'O4', '门店', store)

    def projection():
        if 'storeNo' not in ids:
            raise Blocked('门店未定')
        try:
            s = detail(api.pay('/stores/%s' % ids['storeNo']))
        except Blocked as e:
            if e.status != 404:
                raise
            if not apply:
                r.add('D', '门店投影', PLAN_CREATE, {}, '门店未同步到支付侧')
                return
            res = api.shop_post('/ops/stores/nearpay-sync', {'storeNos': [ids['storeNo']]})
            r.add('D', '门店投影', APPLIED, {'queued': res and res.get('queued')}, '已排队同步（异步），稍后重跑核对')
            return
        same = s.get('entityNo') == ids.get('entityNo')
        ids['storeProjected'] = True
        ids['projectionMerchantNo'] = s.get('merchantNo')
        ids['storePayMerchantNos'] = s.get('payMerchantNos') or []
        r.add('D', '门店投影', REUSE if same else CONFLICT,
              {'merchantNo': s.get('merchantNo'), 'syncIssue': s.get('syncIssue')}, '' if same else '投影主体与目标不一致')
    step(r, 'D', '门店投影', projection)

    def repair(other):
        """H：同名支付商户挂在旧主体 → 归属修复申请（N5）。只建申请，审批由另一名运营完成。"""
        spec = t.get('repair') or {}
        if not apply or spec.get('merchantNo') != other['merchantNo']:
            return
        no = req_no('repair', other['merchantNo'])
        if not spec.get('evidence') or spec['evidence'].startswith('待填写'):
            raise Blocked('修复申请缺真实资料依据（repair.evidence），不提交')
        found = records(api.pay('/source-association-repair-requests', requestNo=no, size=5))
        if not found:
            links = detail(api.pay('/merchants/%s' % other['merchantNo'])).get('sourceLinks') or []
            link = [x for x in links if x.get('externalEntityId') == spec['fromEntityNo']]
            if len(link) != 1:
                raise Blocked('来源关联不唯一（%d 条），不建修复申请' % len(link))
            created = detail(api.pay_post('/source-association-repair-requests', {
                'requestNo': no, 'merchantNo': other['merchantNo'], 'fromOperatingEntityId': spec['fromEntityNo'],
                'toOperatingEntityId': t['entityNo'], 'storeNos': spec.get('storeNos', [t['storeNo']]),
                'evidence': spec['evidence'], 'expectedVersion': link[0]['version']}))
            r.add('H:apply', '归属修复申请', APPLIED, {'repairNo': created.get('repairNo'), 'status': created.get('status')},
                  '已提交，待另一名运营审批')
            return
        cur = found[0]
        if cur.get('status') == 'APPROVED' and execute_repair:
            api.pay_post('/source-association-repair-requests/%s/execute' % cur['repairNo'],
                         {'requestNo': req_no('repair-exec', other['merchantNo']), 'expectedVersion': cur['version'],
                          'reason': '迁移脚本执行已批准的归属修复'})
            after = detail(api.pay('/source-association-repair-requests/%s' % cur['repairNo']))
            r.add('H:apply', '归属修复执行', APPLIED, {'repairNo': cur['repairNo'], 'status': after.get('status')})
            return
        r.add('H:apply', '归属修复申请', REUSE, {'repairNo': cur.get('repairNo'), 'status': cur.get('status')},
              '已批准，加 --execute-repair 执行' if cur.get('status') == 'APPROVED' else '沿用原申请')

    def pay_merchant():
        rows = records(api.pay('/merchants', keyword=t['entityName'], size=50))
        mine = [x for x in rows if x.get('entityNo') == t['entityNo']]
        others = [x for x in rows if x.get('entityNo') != t['entityNo'] and x.get('displayName') == t['entityName']]
        if others:
            r.add('H', '支付商户归属冲突', CONFLICT,
                  {'merchantNo': ','.join(x['merchantNo'] for x in others),
                   'entityNo': ','.join(str(x.get('entityNo')) for x in others)},
                  '同名支付商户挂在其他主体，须走归属修复申请（N5 / ADR-060），不得再建')
        for o in others:
            step(r, 'H:apply', '归属修复申请', lambda o=o: repair(o))
        if others and not mine:
            raise Blocked('同名支付商户归属冲突未解决，暂停新建判断')
        v, hit = decide(mine, lambda x: True)
        if v == PLAN_CREATE:
            # 运营代建支付商户的前置：主体已关联门店，且门店已同步到支付侧（N6）
            if r.verdict('O4') != REUSE or 'storeNo' not in ids:
                raise Blocked('主体尚未关联门店，不能代建支付商户')
            if not ids.get('storeProjected'):
                raise Blocked('门店未同步到支付侧，先 POST /ops/stores/nearpay-sync 再代建')
            if apply:
                api.pay_post('/merchant-creations', {'requestNo': req_no('merchant', t['entityNo']),
                                                     'operatingEntityId': t['entityNo'], 'name': t['entityName']})
                res = detail(api.pay('/merchant-creations/by-request/%s' % req_no('merchant', t['entityNo'])))
                ids['merchantNo'] = res.get('merchantNo')
                r.add('O5', '支付商户（C 主体↔商户）', APPLIED, {'merchantNo': ids['merchantNo'], 'status': res.get('status')})
                return
        if hit and ids.get('projectionMerchantNo') and ids['projectionMerchantNo'] != hit['merchantNo']:
            r.add('D:merchant', '门店投影商户', CONFLICT, {'merchantNo': ids['projectionMerchantNo']}, '门店投影挂在其他支付商户')
        if hit:
            ids['merchantNo'] = hit['merchantNo']
        r.add('O5', '支付商户（C 主体↔商户）', v, {'merchantNo': ids.get('merchantNo'), 'status': hit and hit.get('status')})
    step(r, 'O5', '支付商户', pay_merchant)


    def channel_accounts():
        if 'merchantNo' not in ids:
            raise Blocked('支付商户未定')
        rows = records(api.pay('/channel-accounts', merchantNo=ids['merchantNo'], size=100))
        for want in t.get('channels', []):
            cand = [x for x in rows if x.get('payChannel') == want['payChannel']]
            v, hit = decide(cand, lambda x: not want.get('payMerchantNo') or x.get('payMerchantNo') == want['payMerchantNo'])
            ch = want['payChannel']
            r.add('O6:' + ch, '渠道账户 ' + ch, v,
                  {'payMerchantNo': hit and hit.get('payMerchantNo'), 'status': hit and hit.get('status'),
                   'verify': hit and hit.get('verificationStatus')})
            if hit:
                r.add('O7:' + ch, '凭据 ' + ch, REUSE if hit.get('credentialKeyId') else PLAN_CREATE)
                bound = hit.get('payMerchantNo') in ids.get('storePayMerchantNos', [])
                if bound or not apply:
                    r.add('G:' + ch, '门店↔渠道账户 ' + ch, REUSE if bound else PLAN_CREATE)
                    continue
                if not ids.get('storeProjected'):
                    r.add('G:' + ch, '门店↔渠道账户 ' + ch, BLOCKED, {}, '门店未同步到支付侧')
                    continue
                res = detail(api.pay_post('/stores/%s/bindings' % ids['storeNo'], {
                    'requestNo': req_no('bind', ids['storeNo'], ch), 'payChannel': ch,
                    'payMerchantNo': hit['payMerchantNo'], 'reason': '迁移关联门店与渠道账户'}))
                r.add('G:' + ch, '门店↔渠道账户 ' + ch, APPLIED, {'bindingNo': res.get('bindingNo'), 'status': res.get('status')})
    step(r, 'O6', '渠道账户', channel_accounts)
    return r, ids


def render(report):
    lines = ['%-14s %-12s %-28s %s' % ('项', '结论', '编号', '说明')]
    for i in report.items:
        idtxt = ' '.join('%s=%s' % (k, v) for k, v in i['ids'].items() if v)
        lines.append('%-14s %-12s %-28s %s' % (i['key'], i['verdict'], idtxt, i['title'] + ('：' + i['note'] if i['note'] else '')))
    return '\n'.join(lines)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    base, token = os.environ.get('NEARSHOP_BASE'), os.environ.get('NEARSHOP_TOKEN')
    if not base or not token:
        print('需要环境变量 NEARSHOP_BASE 与 NEARSHOP_TOKEN', file=sys.stderr)
        return 2
    with open(argv[1], encoding='utf-8') as f:
        target = json.load(f)
    apply = '--apply' in argv
    report, ids = run(Api(base, token), target, apply=apply, execute_repair=apply and '--execute-repair' in argv)
    print(render(report))
    if '--json' in argv:
        out = argv[argv.index('--json') + 1]
        with open(out, 'w', encoding='utf-8') as f:
            json.dump({'ids': ids, 'items': report.items}, f, ensure_ascii=False, indent=1)
    return 1 if any(i['verdict'] in (CONFLICT, BLOCKED) for i in report.items) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
