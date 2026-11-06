#!/usr/bin/env python3
"""台湾站商城上线目录种子（docs/technical/商城上线开关与商品清单.md 〇节，2026-11-06 用户确认）。

只走运营端公开接口（与运营在后台手工录入同一条校验链），不直接写库。

  OPS_BASE=http://127.0.0.1:8092 OPS_TOKEN=<运营令牌> python3 scripts/seed/seed-mall-catalog-tw.py          # 预演，只读
  OPS_BASE=...                   OPS_TOKEN=...       python3 scripts/seed/seed-mall-catalog-tw.py --apply  # 写入

令牌：运营在后台登录后取得，或由执行人自己登录；脚本不收密码、不打印令牌。
要求的运营权限：commerce:catalog:read / edit / publish。

可重复执行：已存在且内容一致的对象跳过；已存在但内容不一致的 SKU 不覆盖，报出来由运营决定。
不做：银行收款资料（运营在「收款资料」录入，付款期限填 24 小时 = 86400000 ms）、开关配置、市场启用。

环境前提（缺了会在发布时被服务端拒绝，脚本会先检查并提示）：
  - shop.commerce.mall.required-ends.RETAIL[0]=END_BIZ_APP、required-ends.FOOD[0]=END_BIZ_APP
  - accepted-deployments 里 END_BIZ_APP 的 allowed-business-types 含 RETAIL 与 FOOD，END_APP_POS 含 RETAIL
  - TW 市场已启用（TWD 金额网格可用）
  - END_BIZ_APP 一旦被方案引用，适用行业就不能再改（服务端规则）。所以必须在空目录上第一次跑，
    一次注册零售 + 餐饮。已有零售方案的环境（如本机主库）会在第 2 步报 INVALID_ARGUMENT。

本机验证（2026-11-06）：在空目录副本库上 --apply 一次全部成功；再跑一次全部跳过。
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("OPS_BASE", "http://127.0.0.1:8092").rstrip("/")
TOKEN = os.environ.get("OPS_TOKEN", "")
APPLY = "--apply" in sys.argv
CUR = "TWD"
OP = "seed-tw-2026-11-06"


def nt(v):  # 新台币元 → 最小单位（与现有 TWD SKU 一致：×100）
    return {"currency": CUR, "minor": str(v * 100)}


# ── 期望目录 ──────────────────────────────────────────────────────────────
TYPES = [("RETAIL", "零售", 1), ("FOOD", "餐飲", 2)]

BIZ, POS = "END_BIZ_APP", "END_APP_POS"
# (行业, 档位) → (显示名, 简介, 业务端, 是否在售)
PLANS = {
    ("RETAIL", "FREE"): ("零售免費版", "商戶 App 基礎功能，建店自動開通", [BIZ], True),
    ("RETAIL", "PRO"): ("零售專業版", "商戶 App 全功能 + POS", [BIZ, POS], True),
    ("RETAIL", "CHAIN"): ("零售連鎖版", "多門店經營 + POS", [BIZ, POS], True),
    # 餐饮本期只开 FREE。服务端要求一次发布三档，PRO/CHAIN 与 FREE 同权益、发布后立即下架。
    ("FOOD", "FREE"): ("餐飲免費版", "商戶 App 基礎功能，建店自動開通", [BIZ], True),
    ("FOOD", "PRO"): ("餐飲專業版（未開放）", "暫未開放", [BIZ], False),
    ("FOOD", "CHAIN"): ("餐飲連鎖版（未開放）", "暫未開放", [BIZ], False),
}

CATEGORIES = [
    # code, parent, names, sort —— 商品保存要求分类已存在且启用（V11 起）
    ("subscriptions", None, {"zh-TW": "訂閱方案", "zh-CN": "订阅方案", "en": "Plans"}, 10),
    ("hardware", None, {"zh-TW": "硬體設備", "zh-CN": "硬件设备", "en": "Hardware"}, 20),
    ("bundles", None, {"zh-TW": "開店套餐", "zh-CN": "开店套餐", "en": "Bundles"}, 5),
]

PRODUCTS = [
    # productNo, category, kind, name, description, allowedTypes, sort
    ("tw-retail-pro", "subscriptions", "PLAN", "零售專業版", "商戶 App 全功能 + POS", ["RETAIL"], 10),
    ("tw-retail-chain", "subscriptions", "PLAN", "零售連鎖版", "多門店經營 + POS", ["RETAIL"], 20),
    ("tw-printer-80", "hardware", "HARDWARE", "熱敏印表機 80mm", "小票與廚房單通用；人工安排出貨，免運", ["RETAIL", "FOOD"], 30),
    ("tw-paper-80", "hardware", "HARDWARE", "熱敏紙 80mm（50 卷/箱）", "按箱出售；人工安排出貨，免運", ["RETAIL", "FOOD"], 40),
    ("tw-starter-bundle", "bundles", "BUNDLE", "開店套餐", "零售專業版年付 + 80mm 印表機 + 熱敏紙 1 箱", ["RETAIL"], 5),
]

REFUND = {"unusedDigital": "MANUAL_ONLY", "usedDigital": "MANUAL_ONLY"}


def plan_c(tier, days, weight=100):
    return {"componentNo": None, "kind": "PLAN", "planVersionNo": ("RETAIL", tier), "entitlementCode": None,
            "hardwareSkuNo": None, "periodDays": days, "unitCount": 1, "allocationWeight": weight,
            "gift": False, "activationPolicy": "ON_FULFILLMENT"}


def hw_c(hw, weight=100, units=1):
    return {"componentNo": None, "kind": "HARDWARE", "planVersionNo": None, "entitlementCode": None,
            "hardwareSkuNo": hw, "periodDays": None, "unitCount": units, "allocationWeight": weight,
            "gift": False, "activationPolicy": "ON_FULFILLMENT"}


# skuNo → (productNo, 标题, 原价, 售价, 行业, 组件, 每单上限)
SKUS = {
    "tw-retail-pro-y": ("tw-retail-pro", "零售專業版・年付", 2388, 1990, ["RETAIL"], [plan_c("PRO", 365)], 1),
    "tw-retail-pro-m": ("tw-retail-pro", "零售專業版・月付", 199, 199, ["RETAIL"], [plan_c("PRO", 30)], 1),
    "tw-retail-chain-y": ("tw-retail-chain", "零售連鎖版・年付", 7188, 5990, ["RETAIL"], [plan_c("CHAIN", 365)], 1),
    "tw-retail-chain-m": ("tw-retail-chain", "零售連鎖版・月付", 599, 599, ["RETAIL"], [plan_c("CHAIN", 30)], 1),
    "tw-printer-80": ("tw-printer-80", "熱敏印表機 80mm", 2400, 2400, ["RETAIL", "FOOD"], [hw_c("HW-PRINTER-80")], 10),
    "tw-paper-80-box": ("tw-paper-80", "熱敏紙 80mm × 50 卷", 560, 560, ["RETAIL", "FOOD"], [hw_c("HW-PAPER-80-50")], 20),
    # 套餐分摊权重按单买价：1990 / 2400 / 560（退款按比例拆分用）
    "tw-starter-bundle": ("tw-starter-bundle", "開店套餐", 4950, 3990, ["RETAIL"],
                          [plan_c("PRO", 365, 1990), hw_c("HW-PRINTER-80", 2400), hw_c("HW-PAPER-80-50", 560)], 1),
}
# 跟随行业发布的 SKU（含方案组件）；其余单独发布
INDUSTRY_SKUS = {"RETAIL": ["tw-retail-pro-y", "tw-retail-pro-m", "tw-retail-chain-y", "tw-retail-chain-m",
                            "tw-starter-bundle"], "FOOD": []}


# ── HTTP ────────────────────────────────────────────────────────────────
def call(method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            out = json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            out = json.loads(raw)
        except Exception:
            out = {"code": e.code, "msg": raw[:300].decode(errors="replace")}
    if not isinstance(out, dict) or out.get("code") not in (0, "0"):
        sys.exit(f"✗ {method} {path}: {json.dumps(out, ensure_ascii=False)[:600]}")
    return out.get("data")


def write(what, method, path, body):
    if not APPLY:
        print(f"  [预演] {what}")
        return None
    d = call(method, path, body)
    print(f"  ✓ {what}")
    return d


def overview():
    return call("GET", "/ops/biz-mall/catalog")


# ── 步骤 ────────────────────────────────────────────────────────────────
def step_types(cat):
    print("1. 行业")
    have = {t["draft"]["typeCode"]: t for t in cat["businessTypes"]}
    for code, name, sort in TYPES:
        if code not in have:
            write(f"新建行业 {code}（发布后启用）", "POST", "/ops/business-types",
                  {"typeCode": code, "name": name, "sortOrder": sort, "enabled": False,
                   "expectedVersion": 0, "reason": OP})
        elif have[code]["draft"]["name"] != name or have[code]["draft"]["sortOrder"] != sort:
            t = have[code]
            write(f"行业 {code} 改名为 {name}", "PUT", f"/ops/business-types/{code}",
                  {"name": name, "sortOrder": sort, "enabled": t["draft"]["enabled"],
                   "expectedVersion": t["version"], "reason": OP})
        else:
            print(f"  = 行业 {code}")


def step_ends(cat):
    print("2. 业务端适用行业")
    opts = {o["code"]: o for o in call("GET", "/ops/business-ends/deployment-options")}
    for need, types in ((BIZ, {"RETAIL", "FOOD"}), (POS, {"RETAIL"})):
        o = opts.get(need)
        if not o or not types <= set(o["allowedBusinessTypes"]):
            sys.exit(f"✗ 环境缺已验收部署：{need} 需覆盖 {sorted(types)}，现为 "
                     f"{o and o['allowedBusinessTypes']}。请先配置 shop.commerce.mall.accepted-deployments。")
    ends = {e["draft"]["code"]: e for e in cat["businessEnds"]}
    for code, types in ((BIZ, ["RETAIL", "FOOD"]), (POS, ["RETAIL"])):
        o, e = opts[code], ends.get(code)
        if e and set(types) <= set(e["draft"]["allowedBusinessTypes"]) and e["draft"]["releaseStatus"] == "VERIFIED":
            print(f"  = 业务端 {code}")
            continue
        merged = sorted(set(types) | set(e["draft"]["allowedBusinessTypes"] if e else []))
        write(f"业务端 {code} 适用行业 → {merged}", "POST", "/ops/business-ends", {
            "code": code, "entitlementCode": o["entitlementCode"], "name": o["name"], "scope": "STORE",
            "allowedBusinessTypes": merged, "releaseStatus": "VERIFIED",
            "saleEnabled": code != BIZ, "bootstrapRequired": code == BIZ, "enabled": True,
            "deploymentNo": o["deploymentNo"], "expectedVersion": e["version"] if e else 0, "reason": OP})


def find_plan_version(cat, type_code, tier):
    """当前发布版内容一致 → 用它；否则找内容一致的草稿；都没有 → None。"""
    name, summary, ends, _ = PLANS[(type_code, tier)]
    defs = {p["planNo"]: p for p in cat["planDefinitions"]}
    cur = (defs.get(f"{type_code}:{tier}") or {}).get("currentVersionNo")
    same = lambda d: (d["typeCode"] == type_code and d["tier"] == tier and d["displayName"] == name
                      and sorted(d["entitlementCodes"]) == sorted(ends))
    for v in cat["planVersions"]:
        if v["draft"]["planVersionNo"] == cur and same(v["draft"]):
            return v
    for v in cat["planVersions"]:
        if v["status"] == "DRAFT" and same(v["draft"]):
            return v
    return None


def step_plans(cat):
    print("3. 方案版本")
    for (t, tier), (name, summary, ends, _) in PLANS.items():
        if find_plan_version(cat, t, tier):
            print(f"  = {t}:{tier} {name}")
            continue
        write(f"方案草稿 {t}:{tier} {name} {ends}", "POST", f"/ops/industry-plans/{t}:{tier}/versions", {
            "planVersionNo": None, "typeCode": t, "tier": tier, "displayName": name, "summary": summary,
            "entitlementCodes": ends, "policyVersion": "tw-launch-1", "expectedVersion": 0, "reason": OP})


def step_categories(cat):
    print("3b. 商品分类")
    have = {c["categoryCode"]: c for c in cat.get("categories", [])}
    for code, parent, names, sort in CATEGORIES:
        c = have.get(code)
        body = {"parentCode": parent, "names": names, "icon": c and c["icon"], "sortOrder": sort,
                "enabled": True, "expectedVersion": c["version"] if c else 0, "reason": OP}
        if not c:
            write(f"新建分类 {code} {names['zh-TW']}", "POST", "/ops/biz-mall/categories",
                  dict(body, categoryCode=code))
        elif (c["names"] != names or c["sortOrder"] != sort or c["parentCode"] != parent
              or c["status"] != "ENABLED"):
            write(f"分类 {code} → {names['zh-TW']}", "PUT", f"/ops/biz-mall/categories/{code}", body)
        else:
            print(f"  = 分类 {code}")


def step_products(cat):
    print("4. 商品")
    have = {p["draft"]["productNo"] for p in cat["products"]}
    for no, category, kind, name, desc, types, sort in PRODUCTS:
        if no in have:
            print(f"  = 商品 {no}")
            continue
        write(f"商品 {no} {name}", "POST", "/ops/biz-mall/products", {
            "productNo": no, "categoryCode": category, "kind": kind, "name": name, "description": desc,
            "allowedBusinessTypes": types, "sortOrder": sort, "expectedVersion": 0, "reason": OP})


def resolve(components, cat):
    out = []
    for c in components:
        c = dict(c)
        if isinstance(c["planVersionNo"], tuple):
            v = find_plan_version(cat, *c["planVersionNo"])
            c["planVersionNo"] = v["draft"]["planVersionNo"] if v else f"<{c['planVersionNo']}>"
        out.append(c)
    return out


def sku_matches(draft, spec):
    _, title, lst, price, types, _, mx = spec
    return (draft["title"] == title and int(draft["price"]["minor"]) == price * 100
            and int(draft["listPrice"]["minor"]) == lst * 100 and draft["price"]["currency"] == CUR
            and sorted(draft["allowedBusinessTypes"]) == sorted(types) and draft["maxPerOrder"] == mx)


def sku_version(cat, sku_no):
    """返回 (当前发布版, 最新草稿)。"""
    defs = {s["skuNo"]: s for s in cat["skuDefinitions"]}
    cur = (defs.get(sku_no) or {}).get("currentVersionNo")
    pub = draft = None
    for v in cat["skuVersions"]:
        if v["draft"]["skuNo"] != sku_no:
            continue
        if v["draft"]["skuVersionNo"] == cur:
            pub = v
        elif v["status"] == "DRAFT":
            draft = v
    return pub, draft


CONFLICTS = []


def step_skus(cat):
    print("5. SKU")
    for no, spec in SKUS.items():
        product, title, lst, price, types, comps, mx = spec
        pub, draft = sku_version(cat, no)
        if pub and sku_matches(pub["draft"], spec):
            print(f"  = SKU {no} 已在售 NT${price}")
            continue
        if draft and sku_matches(draft["draft"], spec):
            print(f"  = SKU {no} 草稿已就绪")
            continue
        if pub or draft:
            CONFLICTS.append(no)
            print(f"  ! SKU {no} 已存在但内容不同，不覆盖（请运营核对）")
            continue
        write(f"SKU 草稿 {no} {title} 原价 {lst} 售价 NT${price}", "POST", f"/ops/biz-mall/products/{product}/skus", {
            "skuNo": no, "skuVersionNo": None, "title": title, "listPrice": nt(lst), "price": nt(price),
            "allowedBusinessTypes": types, "refundPolicy": REFUND, "components": resolve(comps, cat),
            "maxPerOrder": mx, "expectedVersion": 0, "reason": OP})


def step_publish_industry(cat, t):
    versions = [find_plan_version(cat, t, tier) for tier in ("FREE", "PRO", "CHAIN")]
    defs = {p["planNo"]: p for p in cat["planDefinitions"]}
    plans_live = all(v and v["status"] == "PUBLISHED" and defs.get(f"{t}:{v['draft']['tier']}", {})
                     .get("currentVersionNo") == v["draft"]["planVersionNo"] for v in versions)
    cands = []
    for no in INDUSTRY_SKUS[t]:
        pub, draft = sku_version(cat, no)
        if draft and not (pub and sku_matches(pub["draft"], SKUS[no])):
            cands.append(draft)
    if plans_live and not cands:
        print(f"  = 行业 {t} 已发布")
        return
    if not all(versions):
        print(f"  [预演] 发布行业 {t}（方案草稿创建后才能发布）")
        return
    # catalogVersion 由运营目录回读（BusinessTypeView.catalogVersion，2026-11-06 补）
    type_view = next(x for x in cat["businessTypes"] if x["draft"]["typeCode"] == t)
    sku_defs = {s["skuNo"]: s["version"] for s in cat["skuDefinitions"]}
    write(f"发布行业 {t}：三档方案 + {len(cands)} 个 SKU", "POST", f"/ops/business-types/{t}/plan-publications", {
        "operationNo": f"{OP}-{t}", "freePlanVersionNo": versions[0]["draft"]["planVersionNo"],
        "proPlanVersionNo": versions[1]["draft"]["planVersionNo"],
        "chainPlanVersionNo": versions[2]["draft"]["planVersionNo"],
        "candidateSkuVersionNos": [c["draft"]["skuVersionNo"] for c in cands],
        "expectedCatalogVersion": type_view["catalogVersion"],
        "expectedPlanVersions": {v["draft"]["planVersionNo"]: v["version"] for v in versions},
        "expectedSkuVersions": {c["draft"]["skuVersionNo"]: c["version"] for c in cands},
        "expectedPlanDefinitionVersions": {p["planNo"]: p["version"] for p in cat["planDefinitions"]
                                           if p["typeCode"] == t},
        "expectedSkuDefinitionVersions": {c["draft"]["skuNo"]: sku_defs[c["draft"]["skuNo"]] for c in cands},
        "reason": OP})


def step_publish_rest(cat):
    for no in SKUS:
        if any(no in v for v in INDUSTRY_SKUS.values()):
            continue
        pub, draft = sku_version(cat, no)
        if pub and sku_matches(pub["draft"], SKUS[no]):
            print(f"  = SKU {no} 已在售")
        elif draft:
            sku_def = next(s for s in cat["skuDefinitions"] if s["skuNo"] == no)
            write(f"发布 SKU {no}", "POST", f"/ops/biz-mall/sku-versions/{draft['draft']['skuVersionNo']}/publish",
                  {"operationNo": f"{OP}-{no}", "expectedVersion": draft["version"],
                   "expectedSkuVersion": sku_def["version"], "reason": OP})
        else:
            print(f"  [预演] 发布 SKU {no}")
    for (t, tier), (*_, on_sale) in PLANS.items():
        d = next((p for p in cat["planDefinitions"] if p["planNo"] == f"{t}:{tier}"), None)
        if on_sale or (d and d["saleStatus"] != "ON_SALE"):
            continue
        if d is None:
            print(f"  [预演] 下架 {t}:{tier}")
            continue
        write(f"下架 {t}:{tier}", "POST", f"/ops/industry-plans/{t}:{tier}/off-sale",
              {"operationNo": f"{OP}-off-{t}-{tier}", "expectedVersion": d["version"], "reason": OP})


def step_enable(cat):
    for t in cat["businessTypes"]:
        d = t["draft"]
        if d["typeCode"] in dict((c, 1) for c, _, _ in TYPES) and not d["enabled"]:
            write(f"启用行业 {d['typeCode']}", "PUT", f"/ops/business-types/{d['typeCode']}",
                  {"name": d["name"], "sortOrder": d["sortOrder"], "enabled": True,
                   "expectedVersion": t["version"], "reason": OP})


def report(cat):
    known = set(SKUS)
    extra = [s for s in cat["skuDefinitions"] if s["saleStatus"] == "ON_SALE" and s["skuNo"] not in known]
    print("\n在售 SKU：")
    for s in cat["skuDefinitions"]:
        if s["saleStatus"] == "ON_SALE":
            mark = "" if s["skuNo"] in known else "   ← 不在本清单，请运营决定是否下架"
            print(f"  {s['skuNo']:<22} {s['title']}{mark}")
    print("方案：")
    for p in cat["planDefinitions"]:
        print(f"  {p['planNo']:<14} {p['saleStatus']}")
    if CONFLICTS:
        print(f"\n! 未覆盖的冲突 SKU：{CONFLICTS}")
    return extra


def main():
    if not TOKEN:
        sys.exit("✗ 缺 OPS_TOKEN（运营令牌）")
    print(f"{'写入' if APPLY else '预演（只读）'}  {BASE}\n")
    step_types(overview())
    step_ends(overview())
    step_plans(overview())
    step_categories(overview())
    step_products(overview())
    step_skus(overview())
    print("6. 发布")
    for t, _, _ in TYPES:
        step_publish_industry(overview(), t)
    step_publish_rest(overview())
    print("7. 启用行业")
    step_enable(overview())
    report(overview())
    sys.exit(1 if CONFLICTS else 0)


if __name__ == "__main__":
    main()
