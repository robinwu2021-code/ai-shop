#!/usr/bin/env python3
"""
鲸跃（CETUSTEK）真实发票的接口联调脚本 —— 不经 App，直接打 v2 的 B 端接口（TDD-发票模块 §25.1）。

流程：预检 → [切到鲸跃 + 核验账号 + 登记号段] → 建门店零售单 → 现金付清 → 开票（同步上传）
      → 向鲸跃查单 → 模拟打印出 PDF → 作废（同步上传）→ 再查单 → 报告。

**谁来跑**：由用户本人在自己的终端跑。登录令牌与鲸跃账号只从环境变量 / 用户指定的 env 文件读，
脚本不打印、不写进报告。默认只做只读预检；开出真实发票必须显式加 --real。

用法：
  export BIZ_TOKEN=...                       # B 端登录令牌（App 登录后取得），或改用 --login
  python3 scripts/invoice/cetustek_v2_e2e.py --store ST2611210W463ZZ12A            # 只读预检
  python3 scripts/invoice/cetustek_v2_e2e.py --store ST… --setup --cred-file ~/x.env --real
  python3 scripts/invoice/cetustek_v2_e2e.py --store ST… --sale --real            # 已切好时只跑开票流程

--cred-file 内容（KEY=VALUE 行，# 开头为注释）：
  CETUSTEK_ACCOUNT_CODE=…   # 鲸跃 officeid（加值中心代号）
  CETUSTEK_USER=…           # posno
  CETUSTEK_PASSWORD=…       # posid
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

DEFAULT_BASE = "https://tw.neargo.ai/shop-api-v2"
SECRET_KEYS = {"providerUser", "providerPassword", "providerAccountCode", "credential", "token", "accessToken"}


class Fail(Exception):
    pass


class Api:
    def __init__(self, base, token, store):
        self.base, self.token, self.store = base.rstrip("/"), token, store
        self.log = []

    def call(self, method, path, body=None, idem=False, expect_code=0, raw=False):
        url = path if path.startswith("http") else self.base + path
        headers = {"Accept": "application/json", "X-Store-No": self.store}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if idem:
            headers["Idempotency-Key"] = str(uuid.uuid4())
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        started = time.time()
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, payload = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, payload = e.code, e.read()
        ms = int((time.time() - started) * 1000)
        if raw:
            self.log.append({"step": f"{method} {path.split('?')[0]}", "http": status, "ms": ms, "bytes": len(payload)})
            return status, payload
        try:
            env = json.loads(payload.decode("utf-8") or "{}")
        except ValueError:
            env = {"code": None, "msg": payload[:200].decode("utf-8", "replace")}
        entry = {"step": f"{method} {path.split('?')[0]}", "http": status, "ms": ms,
                 "code": env.get("code"), "msg": env.get("msg")}
        if isinstance(env.get("data"), dict) and env.get("code") not in (0, None):
            entry["data"] = redact(env["data"])
        self.log.append(entry)
        if expect_code is not None and env.get("code") != expect_code:
            raise Fail(f"{method} {path} → http {status} code {env.get('code')} {env.get('msg')} {redact(env.get('data'))}")
        return env.get("data")


def redact(v):
    if isinstance(v, dict):
        return {k: ("***" if k in SECRET_KEYS else redact(x)) for k, x in v.items()}
    if isinstance(v, list):
        return [redact(x) for x in v]
    return v


def read_creds(path):
    out = {}
    with open(os.path.expanduser(path), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    missing = [k for k in ("CETUSTEK_ACCOUNT_CODE", "CETUSTEK_USER", "CETUSTEK_PASSWORD") if not out.get(k)]
    if missing:
        raise Fail("cred-file 缺少：" + ", ".join(missing))
    return out


def say(msg):
    print(msg, flush=True)


def check(cond, msg):
    if not cond:
        raise Fail(msg)
    say("  ✓ " + msg)


def preflight(api, a):
    say("[1] 预检（只读）")
    opts = api.call("GET", "/biz/invoice-setup/providers")
    codes = [o.get("code") for o in (opts or {}).get("options", [])]
    say(f"  供应商：{codes}（默认 {(opts or {}).get('defaultGatewayCode')}）")
    check("CETUSTEK" in codes, "可选供应商里有 CETUSTEK")
    prof = api.call("GET", "/biz/invoice-setup/profile")
    say(f"  当前设定：{redact({k: (prof or {}).get(k) for k in ('sellerTaxId', 'sellerName', 'gatewayCode', 'autoIssue', 'status', 'currentPeriod', 'currentRemaining')})}")
    ranges = api.call("GET", "/biz/invoice-setup/ranges") or []
    say("  号段：" + "; ".join(f"{r.get('track')} {r.get('startNo')}-{r.get('endNo')} next={r.get('nextNo')} {r.get('status')} store={r.get('storeNo')}" for r in ranges))
    return prof, ranges


def setup(api, a):
    say("[2] 切到鲸跃、核验账号、登记号段")
    creds = read_creds(a.cred_file)
    body = {"regimeCode": "TW_EGUI", "sellerTaxId": a.seller_tax_id, "sellerName": a.seller_name,
            "gatewayCode": "CETUSTEK", "autoIssue": False,
            "providerAccountCode": creds["CETUSTEK_ACCOUNT_CODE"],
            "providerUser": creds["CETUSTEK_USER"], "providerPassword": creds["CETUSTEK_PASSWORD"]}
    prof = api.call("PUT", "/biz/invoice-setup/profile", body)
    check(prof.get("gatewayCode") == "CETUSTEK", "发票设定已切到 CETUSTEK")
    v = api.call("POST", "/biz/invoice-setup/provider-verification")
    check(v.get("taxId") == a.seller_tax_id, f"鲸跃核验通过：{v.get('taxId')} {v.get('name')}，QR 金钥已存={v.get('qrKeyStored')}")
    if a.range_start is not None:
        r = api.call("POST", "/biz/invoice-setup/ranges",
                     {"regimeCode": "TW_EGUI", "periodCode": a.period, "track": a.track,
                      "startNo": a.range_start, "endNo": a.range_end, "storeNo": a.store})
        check(r.get("status") == "ACTIVE", f"号段 {a.track}{a.range_start:08d}–{a.range_end:08d} 已登记（{r.get('rangeNo')}）")


def wait_export(api, export_no):
    for _ in range(30):
        v = api.call("GET", f"/biz/print/exports/{export_no}")
        if v.get("status") == "READY":
            return v
        if v.get("status") == "FAILED":
            raise Fail("打印导出失败")
        time.sleep(0.5)
    raise Fail("打印导出 15 秒内未就绪")


def print_proof(api, a, invoice_no, out_dir):
    facts = api.call("GET", f"/biz/invoices/{invoice_no}/print-facts") or []
    scenes = [f.get("scene") for f in facts]
    check("INVOICE_PROOF" in scenes, f"可打印件：{scenes}")
    fact = next(f for f in facts if f.get("scene") == "INVOICE_PROOF")
    op = api.call("POST", "/biz/print/simulations", {
        "storeNo": a.store, "scene": "INVOICE_PROOF", "variantCode": "STANDARD_58", "fixtureCode": "SUCCESS",
        "source": {"type": "FACT", "no": None, "factRef": fact["factRef"], "expectedRevision": None},
        "templateSelection": None, "outputOptions": {"pdfEnabled": True, "pdfProfile": "ORIGINAL_ROLL"}}, idem=True)
    check(op.get("status") == "SUCCEEDED", "模拟打印提交成功")
    export_no = json.loads(op["result"]["canonicalJson"])["exportNos"][0]
    ready = wait_export(api, export_no)
    grant = api.call("POST", f"/biz/print/exports/{export_no}/download-grants")
    url = grant["url"] if grant["url"].startswith("http") else api.base + grant["url"]
    status, pdf = api.call("GET", url, raw=True)
    check(status == 200 and pdf[:4] == b"%PDF", f"证明联 PDF 取到（{len(pdf)} 字节，{ready.get('pageCount')} 页）")
    path = os.path.join(out_dir, f"{invoice_no}-proof-58.pdf")
    with open(path, "wb") as f:
        f.write(pdf)
    say(f"  PDF 存到 {path}")


def sale(api, a, out_dir):
    say("[3] 建单 → 现金付清")
    client_no = "E2E-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    order = api.call("POST", "/biz/store-orders", {
        "clientOrderNo": client_no, "channel": "POS", "currency": "TWD",
        "lines": [{"kind": "CUSTOM", "quantity": 1, "unitPriceMinor": a.amount_minor,
                   "name": a.item_name, "taxType": "TAXABLE", "storeItemNo": None}],
        "adjustments": [], "member": None, "invoice": {"buyer": None, "timing": "MANUAL"}}, idem=True)
    sub = order["subOrderNo"]
    check(order.get("status") == "AWAITING_PAYMENT", f"门店零售单 {order['orderNo']} / {sub} 待付款 NT${a.amount_minor / 100:g}")
    api.call("POST", "/biz/payment/offline-records", {
        "amountMinor": a.amount_minor, "currency": "TWD", "method": "CASH", "orderNo": sub,
        "note": "鲸跃接口联调", "receivedAt": datetime.now(timezone.utc).isoformat()}, idem=True)
    order = api.call("GET", f"/biz/store-orders/{order['orderNo']}")
    ready = order.get("invoiceReadiness", {})
    check(order.get("status") == "PAID" and ready.get("state") == "READY", f"付清：status={order.get('status')} readiness={ready.get('state')}")

    say("[4] 开票（同步上传鲸跃）")
    inv = api.call("POST", "/biz/invoices", {"sourceType": "SUB_ORDER", "sourceNo": sub, "buyer": None}, idem=True)
    no, fiscal = inv["invoiceNo"], inv.get("fiscalNumber")
    say(f"  发票 {no} 号码 {fiscal} 随机码 {inv.get('randomCode')} 总计 {inv.get('totalMinor')} 上传 {inv.get('uploadStatus')}")
    check(fiscal and fiscal.startswith(a.track), f"号码取自 {a.track} 号段")
    check(inv.get("uploadStatus") in ("SENT", "DELIVERED", "ACCEPTED"), f"开立已当场上传：{inv.get('uploadStatus')}")
    pc = api.call("POST", f"/biz/invoices/{no}/provider-checks")
    say(f"  鲸跃查单：{redact(pc)}")
    check(pc.get("verdict") in ("CONSISTENT", "MATCH", "OK"), "鲸跃查单一致（已开立）")

    say("[5] 模拟打印证明联")
    print_proof(api, a, no, out_dir)

    say("[6] 作废（同步上传鲸跃）")
    v = api.call("POST", f"/biz/invoices/{no}/void", {"reason": "接口联调測試"})
    check(v.get("status") == "VOIDED", f"本地已作废，上传 {v.get('uploadStatus')}")
    pc = api.call("POST", f"/biz/invoices/{no}/provider-checks")
    say(f"  鲸跃查单：{redact(pc)}")
    check(pc.get("verdict") in ("CONSISTENT", "MATCH", "OK"), "鲸跃查单一致（已作废）")
    return {"orderNo": order["orderNo"], "subOrderNo": sub, "invoiceNo": no, "fiscalNumber": fiscal}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default=os.environ.get("API_BASE", DEFAULT_BASE))
    p.add_argument("--store", required=True)
    p.add_argument("--setup", action="store_true", help="切到鲸跃、核验账号、登记号段")
    p.add_argument("--sale", action="store_true", help="建单 → 付清 → 开票 → 打印 → 作废")
    p.add_argument("--real", action="store_true", help="确认会开出真实电子发票（上传财政部）")
    p.add_argument("--cred-file", help="鲸跃账号 env 文件（见文件头）")
    p.add_argument("--seller-tax-id", default="50766768")
    p.add_argument("--seller-name", default="創科系統有限公司")
    p.add_argument("--period", default="11512")
    p.add_argument("--track", default="HR")
    p.add_argument("--range-start", type=int, default=22644552)
    p.add_argument("--range-end", type=int, default=22644799)
    p.add_argument("--no-range", action="store_true", help="--setup 时不登记号段（已登记过）")
    p.add_argument("--amount-minor", type=int, default=100, help="金额（分），默认 NT$1")
    p.add_argument("--item-name", default="接口聯調測試品項")
    p.add_argument("--out", default=".")
    a = p.parse_args()
    if a.no_range:
        a.range_start = None
    token = os.environ.get("BIZ_TOKEN")
    if not token:
        print("需要环境变量 BIZ_TOKEN（B 端登录令牌）", file=sys.stderr)
        return 2
    if (a.setup or a.sale) and not a.real:
        print("--setup / --sale 会改正式环境、开出真实发票，确认后加 --real", file=sys.stderr)
        return 2
    if a.setup and not a.cred_file:
        print("--setup 需要 --cred-file", file=sys.stderr)
        return 2
    api = Api(a.base, token, a.store)
    result, ok = {}, True
    try:
        preflight(api, a)
        if a.setup:
            setup(api, a)
        if a.sale:
            result = sale(api, a, a.out)
        say("全部通过")
    except Fail as e:
        ok = False
        say("✗ " + str(e))
    report = os.path.join(a.out, "cetustek-e2e-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    with open(report, "w", encoding="utf-8") as f:
        json.dump({"ok": ok, "base": a.base, "store": a.store, "result": result, "calls": api.log},
                  f, ensure_ascii=False, indent=2)
    say(f"报告：{report}（不含令牌与账号）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
