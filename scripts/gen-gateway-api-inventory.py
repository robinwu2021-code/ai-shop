#!/usr/bin/env python3
"""网关接口清单生成器 —— 按「端 × 对象」把网关要代理的每一个入口列出来。

为什么另起一份而不用 docs/api/API清单.md：那一份以前端契约为真源，按业务域分组，回答「接口做了没有、谁在调」；
这一份以**后端实际声明的入口**为真源，按网关的视角分组，回答「这一端要代理哪些入口、各要什么权限、网关判不判得了、
生产的包里有没有」。对齐网关与各业务对象（支付、零售、餐饮、服务业、库存）用的是这一份。

来源只有源码，可复算：
  · 入口   ← backend/ 与 backend-plugins/ 下 src/main 里的 @XxxMapping
  · 权限   ← 紧挨着的 @PreAuthorize（方法上没有就取类上的），常量替换成码值
  · 公开   ← 各端装配类里的 defaultPublicPaths()
  · C 端要不要登录 ← c-app/src/api/endpoints.ts 的 auth 字段（C 端整条链在网关放行，登录由业务层判）

用法：python3 scripts/gen-gateway-api-inventory.py
产物：docs/api/网关接口清单.md 及三端各一份。
"""
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "docs" / "api"
SCAN_ROOTS = [ROOT / "backend", ROOT / "backend-plugins"]

ENDS = [
    ("ops", "运营端", ("/ops/", "/pay-ops/")),
    ("biz", "B 端", ("/biz/",)),
    ("mp", "C 端", ("/mp/",)),
]
OBJECTS = ["支付", "零售", "餐饮", "服务业", "库存", "平台与通用"]

# 注解可能写成全限定名：@org.springframework.web.bind.annotation.GetMapping
MAPPING = re.compile(r'@(?:[\w.]+\.)?(Get|Post|Put|Delete|Patch|Request)Mapping\b')
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
CONST = re.compile(r'public\s+static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"')

# 运营端里属于「平台与通用」的权限码前缀与地址段：账号权限、系统设置、消息、审计这些不属于任何一个业务对象
PLATFORM_PERM_PREFIXES = ("iam:", "system:", "sec:", "message:", "dashboard:", "ops:print", "risk:", "content:")
PLATFORM_SEGMENTS = {
    "auth", "menu", "me", "stream", "perm", "perms", "iam", "system", "audit-log", "audit-logs", "media", "staffs",
    "roles", "test-phones", "test-identities", "app-versions", "api-resources", "jobs", "job", "messages",
    "notify-logs", "notify-channels", "notify", "inapp-messages", "captcha", "dashboard", "appearance",
    "feature-flags", "markets", "rule-texts", "master-data", "industries", "service-scopes", "banned-words",
    "printing", "print", "upload", "uploads", "files", "common", "risk", "contents", "content", "tickets",
}
PAY_SEGMENTS = {
    "pay", "payment", "payments", "pay-ops", "settle", "settlement", "finance", "stored-value", "entitlements",
    "entitlement", "refunds", "refund", "wallet", "cash", "checkout", "collections", "payment-collections",
    "recon", "reconciliation", "channel-accounts", "pay-channels", "pay-service", "invoice", "invoices",
}
SERVICE_SEGMENTS = {"booking", "bookings", "service-tasks", "service-task", "service-items", "services", "service"}


def java_files():
    for root in SCAN_ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.java")):
            parts = path.parts
            if "target" in parts or "test" in parts or "src" not in parts:
                continue
            if "main" not in parts:
                continue
            yield path


def load_constants(files):
    """常量名 → 码值。同名常量在不同类里值不同的话，按「类名.常量名」优先。"""
    by_qualified, by_bare = {}, {}
    for path in files:
        text = path.read_text(encoding="utf-8", errors="ignore")
        if "static final String" not in text:
            continue
        cls = path.stem
        for name, value in CONST.findall(text):
            by_qualified[f"{cls}.{name}"] = value
            by_bare.setdefault(name, value)
    return by_qualified, by_bare


def resolve_expression(raw, by_qualified, by_bare):
    """把 "@perm.can('" + Perms.X + "')" 这种拼接还原成 @perm.can('码')。"""
    pieces = re.split(r'\s*\+\s*', raw.strip())
    out = []
    for piece in pieces:
        piece = piece.strip()
        match = STRING.fullmatch(piece)
        if match:
            out.append(match.group(1))
            continue
        name = piece.strip("() ")
        # 常量可能带包名：ai.neargo.shop.auth.BizPerms.GOODS，只认最后两段「类名.常量名」
        short = ".".join(name.split(".")[-2:])
        out.append(by_qualified.get(short) or by_bare.get(name.split(".")[-1], "?" + name))
    return "".join(out)


def annotation_block(lines, index):
    """映射注解所在的那一串连续注解（含续行与注释）。"""
    def belongs(text):
        text = text.strip()
        return (text.startswith("@") or text.startswith("+") or text.startswith('"') or text.startswith("//")
                or text.startswith("*") or text.startswith("/*") or text.endswith("+") or text == "")
    start = index
    while start > 0 and belongs(lines[start - 1]) and lines[start - 1].strip() != "":
        start -= 1
    end = index
    while end + 1 < len(lines) and lines[end + 1].strip() != "" and belongs(lines[end + 1]):
        end += 1
    return start, end


def pre_authorize(text, by_qualified, by_bare):
    match = re.search(r'@(?:[\w.]+\.)?PreAuthorize\(\s*(.*?)\s*\)\s*(?=$|@|\n\s*(?:public|protected|private|[A-Z@/]))', text, re.S)
    if not match:
        match = re.search(r'@(?:[\w.]+\.)?PreAuthorize\(\s*(.*)\)', text, re.S)
    if not match:
        return None
    inner = " ".join(match.group(1).split())
    return resolve_expression(inner, by_qualified, by_bare)


def paths_of(args):
    if not args:
        return [""]
    # 只取 value / path 的字符串，不取 produces、consumes 里的
    head = re.split(r'\b(?:produces|consumes|params|headers|method|name)\s*=', args)[0]
    found = STRING.findall(head)
    return found or [""]


def scan():
    files = list(java_files())
    by_qualified, by_bare = load_constants(files)
    endpoints = []
    for path in files:
        text = path.read_text(encoding="utf-8", errors="ignore")
        if "Mapping" not in text or "Controller" not in text and "@RestController" not in text:
            continue
        lines = text.split("\n")
        class_line = next((i for i, line in enumerate(lines) if re.search(r'\b(class|interface)\s+\w+', line)
                           and not line.strip().startswith(("*", "//", "/*"))), len(lines))
        header = "\n".join(lines[:class_line])
        base_match = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', header)
        base = base_match.group(1) if base_match else ""
        class_rule = pre_authorize(header, by_qualified, by_bare)
        rel = path.relative_to(ROOT).as_posix()
        for index in range(class_line, len(lines)):
            stripped = lines[index].strip()
            if stripped.startswith(("*", "//", "/*")):
                continue
            match = MAPPING.search(stripped)
            if not match:
                continue
            start, end = annotation_block(lines, index)
            block = "\n".join(lines[start:end + 1])
            # 注解的参数：从它后面的左括号取到配对的右括号（可能跨行）；没有括号就是没有参数
            tail = "\n".join(line.strip() for line in lines[index:end + 2])
            position = tail.index(match.group(0)) + len(match.group(0))
            mapping_args = ""
            if tail[position:position + 1] == "(":
                depth, buffer, in_string = 0, [], False
                for ch in tail[position:]:
                    buffer.append(ch)
                    if ch == '"' and (len(buffer) < 2 or buffer[-2] != "\\"):
                        in_string = not in_string
                    if not in_string:
                        depth += ch == "("
                        depth -= ch == ")"
                        if depth == 0:
                            break
                mapping_args = "".join(buffer)
            verb = match.group(1).upper()
            if verb == "REQUEST":
                verbs = re.findall(r'RequestMethod\.([A-Z]+)', mapping_args) or ["*"]
            else:
                verbs = [verb]
            rule = pre_authorize(block, by_qualified, by_bare) or class_rule
            for sub in paths_of(mapping_args):
                full = (base.rstrip("/") + "/" + sub.lstrip("/")) if sub else base
                full = "/" + full.strip("/")
                for v in verbs:
                    endpoints.append({"method": v, "path": full, "rule": rule, "file": rel, "handler": path.stem})
    # 同一个「方法 + 地址」在多个模块里各声明一次（同进程与独立部署两种形态）时并成一条
    merged = {}
    for endpoint in endpoints:
        key = (endpoint["method"], endpoint["path"])
        if key not in merged:
            merged[key] = dict(endpoint, handlers=[endpoint["handler"]], files=[endpoint["file"]])
        else:
            merged[key]["handlers"].append(endpoint["handler"])
            merged[key]["files"].append(endpoint["file"])
            if not merged[key]["rule"]:
                merged[key]["rule"] = endpoint["rule"]
    return sorted(merged.values(), key=lambda e: (e["path"], e["method"]))


def public_paths():
    """各端在网关上放行的地址（不用登录）。"""
    out = {}
    base = ROOT / "backend/shop-gateway/src/main/java/ai/neargo/shop/gateway"
    for end, rel in (("ops", "ops/OpsGatewayEnd.java"), ("biz", "biz/BizGatewayEnd.java"),
                     ("pay-ops", "ops/PayOpsGatewayEnd.java")):
        path = base / rel
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        match = re.search(r'defaultPublicPaths\(\)\s*\{(.*?)\n    \}', text, re.S)
        out[end] = STRING.findall(match.group(1)) if match else []
    return out


def consumer_auth():
    """C 端每个入口要不要登录：取自 c-app 的接口声明。键是把路径变量抹平后的「方法 地址」。"""
    path = ROOT / "c-app/src/api/endpoints.ts"
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8")
    out = {}
    for _, method, raw, auth in re.findall(
            r'(\w+):\s*\{\s*method:\s*"(\w+)",\s*path:\s*"([^"]+)",\s*auth:\s*(true|false)', text):
        out[(method.upper(), shape(raw))] = auth == "true"
    return out


def shape(path):
    path = re.sub(r'[:{][A-Za-z_]\w*\}?', "{}", path)
    path = re.sub(r'\$\{[^}]*\}', "{}", path)
    return path.rstrip("/") or "/"


def production_index():
    """生产上网关实际入库的接口：取 docs/api 下最新的一份导出。返回（文件名, {(方法, 地址)}）；没有导出就是空。"""
    exports = sorted(OUT_DIR.glob("gateway-prod-index-*.tsv"))
    if not exports:
        return None, set()
    keys = set()
    for line in exports[-1].read_text(encoding="utf-8").split("\n"):
        cells = line.split("\t")
        if line.startswith("#") or len(cells) < 3:
            continue
        keys.add((cells[1], cells[2]))
    return exports[-1].name, keys


def end_of(path):
    for code, _, prefixes in ENDS:
        if any(path.startswith(prefix) or path + "/" == prefix for prefix in prefixes):
            return code
    return None


def codes_of(rule):
    return re.findall(r"'([^']+)'", rule or "")


def object_of(endpoint, end):
    path, files, rule = endpoint["path"], " ".join(endpoint["files"]), endpoint["rule"] or ""
    segments = [s for s in path.split("/") if s]
    after_end = segments[1:] if segments else []
    first = after_end[0] if after_end else ""
    if first in ("v1", "v2") and len(after_end) > 1:
        first = after_end[1]
    codes = codes_of(rule)
    if "/x/food" in path or "shop-industry-food" in files:
        return "餐饮"
    if ("/x/beauty" in path or "shop-industry-beauty" in files or first in SERVICE_SEGMENTS
            or any(c.startswith(("biz:booking", "biz:service")) for c in codes)):
        return "服务业"
    if (path.startswith("/pay-ops/") or first in PAY_SEGMENTS or "/stored-value" in path
            or files.startswith("backend/pay/") or " backend/pay/" in " " + files
            or any(c.startswith(("pay:", "mch:pay:", "ops:pay:", "finance:")) or c == "biz:finance" for c in codes)):
        return "支付"
    if "/inventory" in path or "backend/shop-inventory/" in files or "backend/inventory/" in files \
            or any(c.startswith("inventory:") for c in codes):
        return "库存"
    if end == "ops":
        if any(c.startswith(PLATFORM_PERM_PREFIXES) for c in codes) or (not codes and first in PLATFORM_SEGMENTS) \
                or first in PLATFORM_SEGMENTS:
            return "平台与通用"
        return "零售"
    if first in PLATFORM_SEGMENTS and first not in ("print", "printing"):
        return "平台与通用"
    return "零售"


def gateway_judgement(endpoint, end, publics, consumer):
    """返回（权限要求, 网关怎么判）。"""
    rule = endpoint["rule"]
    path = endpoint["path"]
    if end == "mp":
        need = consumer.get((endpoint["method"], shape(path)))
        requirement = "要登录" if need else "不用登录" if need is False else "端上没有声明"
        return (rule or requirement), "网关放行，登录由业务层判"
    scope = "pay-ops" if path.startswith("/pay-ops/") else end
    if any(path == p or (p.endswith("/**") and path.startswith(p[:-3])) for p in publics.get(scope, [])):
        return "公开", "公开"
    if not rule:
        return "登录即可", "只要求登录"
    allowed = ("can",) if end == "ops" else ("canBiz", "canAnyBiz")
    stripped = rule.replace("(", " ").replace(")", " ")
    calls = re.findall(r'@(\w+)\.(\w+)', rule)
    only_known = all(bean == "perm" and method in allowed for bean, method in calls) and calls
    leftover = re.sub(r"@perm\.\w+\s*\(\s*'[^']*'(?:\s*,\s*'[^']*')*\s*\)", "", rule)
    leftover = re.sub(r'\b(and|or)\b|[()\s]', "", leftover)
    if only_known and not leftover and "?" not in rule:
        return rule, "能提前判"
    if re.fullmatch(r'@(perm\.merchantAccount|managementAccess\.authenticated)\(\)', rule.strip()):
        return "商家身份", "留给入口判（只问是不是商家）"
    return rule, "留给入口判"


def build_of(endpoint):
    files = endpoint["files"]
    if all(f.startswith("backend-plugins/") for f in files):
        plugin = files[0].split("/")[1]
        return f"插件 {plugin}"
    return "默认构建"


def resource_of(path):
    segments = [s for s in path.split("/") if s]
    rest = segments[1:]
    if rest and rest[0] in ("x", "v1", "v2") and len(rest) > 2:
        return "/".join(rest[:3] if rest[0] == "x" else rest[:2])
    if rest and rest[0] == "x" and len(rest) > 1:
        return "/".join(rest[:2])
    return rest[0] if rest else "(根)"


def render_end(code, label, rows):
    lines = [f"# 网关接口清单 · {label}", "",
             "> 由 `python3 scripts/gen-gateway-api-inventory.py` 生成，**请勿手改**。来源是后端源码里实际声明的入口。",
             "> 总览、分类规则与验证清单见 [网关接口清单](网关接口清单.md)。", ""]
    by_object = defaultdict(list)
    for row in rows:
        by_object[row["object"]].append(row)
    lines.append(f"共 **{len(rows)}** 个入口：" + "、".join(
        f"{name} {len(by_object[name])}" for name in OBJECTS if by_object[name]) + "。")
    lines.append("")
    for name in OBJECTS:
        group = by_object[name]
        if not group:
            continue
        judged = Counter(row["judge"] for row in group)
        builds = Counter(row["build"] for row in group)
        live = Counter(row["prod"] for row in group)
        lines += [f"## {name}（{len(group)}）", "",
                  "网关判权：" + "、".join(f"{k} {v}" for k, v in judged.most_common()) + "。"
                  + "构建：" + "、".join(f"{k} {v}" for k, v in builds.most_common()) + "。"
                  + "生产：" + "、".join(f"{k} {v}" for k, v in live.most_common()) + "。", ""]
        by_resource = defaultdict(list)
        for row in group:
            by_resource[row["resource"]].append(row)
        for resource in sorted(by_resource):
            items = by_resource[resource]
            lines += [f"### {resource}（{len(items)}）", "",
                      "| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |", "|---|---|---|---|---|---|---|"]
            for row in items:
                requirement = row["requirement"].replace("|", "\\|")
                lines.append(f"| {row['method']} | `{row['path']}` | {'`' + requirement + '`' if '@' in requirement else requirement} "
                             f"| {row['judge']} | {row['build']} | {row['prod']} | {'、'.join(sorted(set(row['handlers'])))} |")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main():
    endpoints = scan()
    publics = public_paths()
    consumer = consumer_auth()
    export_name, live_keys = production_index()
    per_end = defaultdict(list)
    others = Counter()
    for endpoint in endpoints:
        end = end_of(endpoint["path"])
        if end is None:
            first = [s for s in endpoint["path"].split("/") if s]
            others["/" + (first[0] if first else "")] += 1
            continue
        requirement, judge = gateway_judgement(endpoint, end, publics, consumer)
        if not live_keys:
            prod = "—"
        else:
            prod = "已入网关" if (endpoint["method"], endpoint["path"]) in live_keys else "未部署"
        per_end[end].append(dict(endpoint, object=object_of(endpoint, end), requirement=requirement, judge=judge,
                                 build=build_of(endpoint), resource=resource_of(endpoint["path"]), prod=prod))

    names = {"ops": "运营端", "biz": "B端", "mp": "C端"}
    for code, label, _ in ENDS:
        (OUT_DIR / f"网关接口清单-{names[code]}.md").write_text(render_end(code, label, per_end[code]), encoding="utf-8")

    total = sum(len(v) for v in per_end.values())
    index = ["# 网关接口清单", "",
             "> 由 `python3 scripts/gen-gateway-api-inventory.py` 生成，**请勿手改**。",
             "> 以后端源码里实际声明的入口为准，按网关的视角分组：哪一端、属于哪个业务对象、要什么权限、网关判不判得了、在不在生产的构建里。",
             "> 对齐网关与各业务对象时以这一份为底表；验证清单见 [网关接口验证清单](网关接口验证清单.md)。", "",
             f"三端共 **{total}** 个入口。", "",
             "## 端 × 对象", "",
             "| 端 | " + " | ".join(OBJECTS) + " | 合计 | 清单 |", "|---|" + "---:|" * (len(OBJECTS) + 1) + "---|"]
    for code, label, _ in ENDS:
        counts = Counter(row["object"] for row in per_end[code])
        index.append(f"| {label} | " + " | ".join(str(counts.get(name, 0) or "—") for name in OBJECTS)
                     + f" | {len(per_end[code])} | [网关接口清单-{names[code]}](网关接口清单-{names[code]}.md) |")
    index += ["", "## 网关判权", "",
              "| 端 | 能提前判 | 只要求登录 | 留给入口判 | 公开 | 网关放行（C 端） |", "|---|---:|---:|---:|---:|---:|"]
    for code, label, _ in ENDS:
        judged = Counter(row["judge"] for row in per_end[code])
        defer = sum(v for k, v in judged.items() if k.startswith("留给入口判"))
        index.append(f"| {label} | {judged.get('能提前判', 0) or '—'} | {judged.get('只要求登录', 0) or '—'} | {defer or '—'} "
                     f"| {judged.get('公开', 0) or '—'} | {judged.get('网关放行，登录由业务层判', 0) or '—'} |")
    index += ["", "## 构建", "",
              "「默认构建」是不带任何 Maven profile 打出来的包，也就是今天生产上跑的；「插件」要带对应的 profile 才会打进包里。", "",
              "| 端 | 对象 | 默认构建 | 插件 |", "|---|---|---:|---:|"]
    for code, label, _ in ENDS:
        for name in OBJECTS:
            rows = [row for row in per_end[code] if row["object"] == name]
            if not rows:
                continue
            default = sum(1 for row in rows if row["build"] == "默认构建")
            index.append(f"| {label} | {name} | {default or '—'} | {len(rows) - default or '—'} |")
    index += ["", "## 生产上的状态", "",
              f"「已入网关」指这个入口在生产的网关索引里（取自 `{export_name}`，是网关版本写进正式库的接口资源表的导出）；"
              "「未部署」指源码里有、生产的网关里没有。C 端的入口进了索引只表示网关能定位、能按条停用，网关不替它判登录与权限。", "",
              "| 端 | 对象 | 已入网关 | 未部署 |", "|---|---|---:|---:|"]
    for code, label, _ in ENDS:
        for name in OBJECTS:
            rows = [row for row in per_end[code] if row["object"] == name]
            if not rows:
                continue
            live = sum(1 for row in rows if row["prod"] == "已入网关")
            index.append(f"| {label} | {name} | {live or '—'} | {len(rows) - live or '—'} |")
    index += ["", "## 分类规则", "",
              "一个入口属于哪个对象，按下面的顺序取第一个命中的：", "",
              "1. **餐饮**：地址里有 `/x/food`，或入口类在餐饮插件里。",
              "2. **服务业**：地址里有 `/x/beauty`，或入口类在美业插件里，或地址的第一段是预约、服务任务一类，或权限码以 `biz:booking`、`biz:service` 开头。",
              "3. **支付**：`/pay-ops/**`；地址的第一段是支付、退款、结算、储值、权益、收银、对账一类；入口类在支付模块里；"
              "或权限码以 `pay:`、`mch:pay:`、`ops:pay:`、`finance:` 开头。结算与财务归在这里。",
              "4. **库存**：地址里有 `/inventory`，入口类在库存模块里，或权限码以 `inventory:` 开头。",
              "5. **平台与通用**：账号与权限、系统设置、消息、审计、上传、主数据这些不属于任何一个业务对象的入口。",
              "6. **零售**：其余全部（商品、订单、履约、售后、营销、门店、会员、商城销售等）。", "",
              "「网关判权」一列的含义：", "",
              "| 值 | 含义 |", "|---|---|",
              "| 能提前判 | 权限式只由本端的权限检查调用加「且 / 或」组成，网关可以在进入入口之前判完 |",
              "| 只要求登录 | 入口上没有权限注解，网关只要求登录 |",
              "| 留给入口判 | 权限式里有网关认不出的东西，网关只要求登录，其余由入口上的注解判 |",
              "| 公开 | 写在该端的公开地址清单里，不用登录 |",
              "| 网关放行，登录由业务层判 | C 端的入口网关只定位、可按条停用，不判登录与权限；要不要登录由业务代码决定 |", "",
              "## 三端之外的入口", "",
              "下面这些不属于三端，这份清单不展开：", "",
              "| 前缀 | 入口数 |", "|---|---:|"]
    for prefix, count in sorted(others.items(), key=lambda kv: -kv[1]):
        index.append(f"| `{prefix}` | {count} |")
    (OUT_DIR / "网关接口清单.md").write_text("\n".join(index) + "\n", encoding="utf-8")
    print(f"三端 {total} 个入口：" + "，".join(f"{label} {len(per_end[code])}" for code, label, _ in ENDS))


if __name__ == "__main__":
    main()
