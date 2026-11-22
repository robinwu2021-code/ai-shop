#!/usr/bin/env python3
"""发票服务设计稿：由两份真源生成表清单、DDL 草案与接口文档。

真源（改这两份，再跑本脚本）：
  docs/technical/design/schema/invoice-service-database.json   表结构
  docs/api/invoice-service.openapi.json                        接口（营业人 / 供应商账户 / 凭据 / 绑定）

产物（勿手改）：
  docs/technical/TDD-发票服务-数据库表结构说明.md
  docs/technical/design/schema/invoice-service.sql
  docs/technical/TDD-发票服务-API文档.md

用法：python3 scripts/gen-invoice-service-docs.py [--check]
"""
import json
import pathlib
import sys
from collections import OrderedDict

ROOT = pathlib.Path(__file__).resolve().parents[1]
DB = ROOT / "docs/technical/design/schema/invoice-service-database.json"
API = ROOT / "docs/api/invoice-service.openapi.json"
OUT_TABLES = ROOT / "docs/technical/TDD-发票服务-数据库表结构说明.md"
OUT_SQL = ROOT / "docs/technical/design/schema/invoice-service.sql"
OUT_API = ROOT / "docs/technical/TDD-发票服务-API文档.md"
STAMP = "由 `python3 scripts/gen-invoice-service-docs.py` 生成"


def cell(v):
    return str(v).replace("|", "\\|").replace("\n", " ")


def tables_md(db):
    t = db["tables"]
    groups = OrderedDict()
    for x in t:
        groups.setdefault(x["group"], []).append(x)
    count = {a: sum(1 for x in t if x["action"] == a) for a in ("新", "改", "沿用")}
    out = [
        "# TDD-发票服务-数据库表结构说明",
        "",
        "状态：草稿 · 设计稿未落库（`DESIGN_ONLY_NOT_APPLIED`）",
        f"> {STAMP} · 真源 `docs/technical/design/schema/invoice-service-database.json` · **勿手改**",
        f"> 设计依据：[TDD-发票服务-总体设计](./TDD-发票服务-总体设计.md) §5、§13 · DDL 草案：[invoice-service.sql](./design/schema/invoice-service.sql)",
        "",
        "## 一、总览",
        "",
        f"库 `{db['database']}`，Flyway `{db['flywayLocation']}`（历史表 `{db['flywayHistoryTable']}`）。"
        f"共 {len(t)} 张表：新增 {count['新']}、改 {count['改']}、沿用 {count['沿用']}；"
        + "；".join(f"删除 `{r['name']}`（{r['reason']}）" for r in db.get("removed", [])) + "。",
        "",
        "约定：",
        "",
    ]
    out += [f"- {c}" for c in db["conventions"]]
    out += ["", "## 二、表清单", "", "| # | 分组 | 表 | 动作 | 用途 | 列数 |", "|---|---|---|---|---|---|"]
    n = 0
    for g, xs in groups.items():
        for x in xs:
            n += 1
            out.append(f"| {n} | {g} | [`{x['name']}`](#{x['name']}) | {x['action']} | {cell(x['purpose'])} | {len(x['columns'])} |")
    out += ["", "## 三、逐表详情", ""]
    for g, xs in groups.items():
        out += [f"### {g}", ""]
        for x in xs:
            out += [f"#### {x['name']}", "", f"{x['action']} · {x['purpose']}", "",
                    "| 列 | 类型 | 说明 |", "|---|---|---|"]
            out += [f"| `{c['name']}` | `{cell(c['sqlType'])}` | {cell(c['meaning'])} |" for c in x["columns"]]
            if x["keys"] or x["checks"]:
                out += ["", "键与约束：", ""]
                out += [f"- `{cell(k)}`" for k in x["keys"] + x["checks"]]
            out.append("")
    return "\n".join(out).rstrip() + "\n"


def sql(db):
    out = [f"-- 【设计稿，未落库，勿手改】{STAMP.replace('`', '')}",
           f"-- 目标状态 DDL（库 {db['database']}）；实际迁移为 db/invoice/V6+ 的增量脚本，不改已应用的 V1–V5。", ""]
    for x in db["tables"]:
        lines = ["  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" if c["name"] == "id"
                 else f"  {c['name']} {c['sqlType']} COMMENT '{c['meaning'].replace(chr(39), chr(39) * 2)}'"
                 if "GENERATED" not in c["sqlType"] else f"  {c['name']} {c['sqlType']}"
                 for c in x["columns"]]
        lines += [f"  {k}" for k in x["keys"] + x["checks"]]
        out += [f"CREATE TABLE {x['name']} (", ",\n".join(lines),
                f") COMMENT='{x['purpose'].replace(chr(39), chr(39) * 2)}';", ""]
    return "\n".join(out)


def schema_name(sch):
    if not sch:
        return "—"
    if "$ref" in sch:
        return f"`{sch['$ref'].split('/')[-1]}`"
    if sch.get("type") == "array":
        return f"{schema_name(sch['items'])}[]"
    return f"`{sch.get('type', 'object')}`"


def props_table(name, sch, schemas, seen):
    if name in seen:
        return []
    seen.add(name)
    out = [f"#### {name}", ""]
    if sch.get("description"):
        out += [sch["description"], ""]
    props = sch.get("properties", {})
    if not props:
        extra = sch.get("additionalProperties")
        out += [f"键值对象，值类型 {schema_name(extra)}。" if extra else "（自由对象）", ""]
        return out
    req = set(sch.get("required", []))
    out += ["| 字段 | 类型 | 必填 | 说明 |", "|---|---|---|---|"]
    for k, v in props.items():
        extra = []
        for key in ("enum", "pattern", "maxLength", "format"):
            if key in v:
                extra.append(f"{key}={'/'.join(v[key]) if key == 'enum' else v[key]}")
        desc = cell(v.get("description", "")) + (f"（{', '.join(extra)}）" if extra else "")
        out.append(f"| `{k}` | {schema_name(v)} | {'是' if k in req else ''} | {desc} |")
    out.append("")
    return out


def api_md(api):
    errors = api.get("x-error-codes", {})
    ops = [(p, m, o) for p, ms in api["paths"].items() for m, o in ms.items()]
    out = [
        "# TDD-发票服务-API文档",
        "",
        "状态：草稿 · 设计稿未实现",
        f"> {STAMP} · 真源 `docs/api/invoice-service.openapi.json` · **勿手改**",
        "> 设计依据：[TDD-发票服务-总体设计](./TDD-发票服务-总体设计.md) §13 · 表结构：[TDD-发票服务-数据库表结构说明](./TDD-发票服务-数据库表结构说明.md)",
        "",
        "## 一、总览",
        "",
        api["info"]["description"],
        "",
        "通用约定：",
        "",
        "- 响应统一包 `{code, message, data}`，下文「响应」指 `data`；失败时 `code` 为错误码。",
        "- B 端（`/biz/*`）主体取登录上下文，带 `X-Store-No`，入参不收主体号；运营端显式传 `entityNo`。",
        "- 写接口支持 `Idempotency-Key` 头。",
        "- 凭据秘密字段只写不读：响应里只有 `credential.configured / version / fingerprint / masks`。",
        "- `editable` 由后端按 `managedBy` 与调用方算好：B 端只能改 `MERCHANT` 账户，运营端只能改 `PLATFORM` 账户（运营端另可暂停任意账户、接管 / 交还）。",
        "",
        f"## 二、接口清单（{len(ops)} 个）",
        "",
        "| # | 分组 | 方法 | 路径 | 说明 | 权限 |",
        "|---|---|---|---|---|---|",
    ]
    for i, (p, m, o) in enumerate(ops, 1):
        out.append(f"| {i} | {o['tags'][0]} | {m.upper()} | `{p}` | {o['summary']} | `{o['x-permission']}` |")
    out += ["", "## 三、逐个接口", ""]
    cur = None
    for i, (p, m, o) in enumerate(ops, 1):
        if o["tags"][0] != cur:
            cur = o["tags"][0]
            out += [f"### {cur}", ""]
        out += [f"#### {i}. {m.upper()} `{p}` · {o['summary']}", "", f"权限：`{o['x-permission']}`", ""]
        if o.get("description"):
            out += [o["description"], ""]
        if o.get("parameters"):
            out += ["| 参数 | 位置 | 类型 | 必填 | 说明 |", "|---|---|---|---|---|"]
            out += [f"| `{x['name']}` | {x['in']} | `{x['schema']['type']}` | {'是' if x.get('required') else ''} | {cell(x.get('description', ''))} |"
                    for x in o["parameters"]]
            out.append("")
        body = o.get("requestBody", {}).get("content", {}).get("application/json", {}).get("schema")
        resp = o["responses"]["200"]["content"]["application/json"]["schema"]
        out += [f"请求体：{schema_name(body)}　响应：{schema_name(resp)}", ""]
        if o.get("x-errors"):
            out += ["错误码：" + "、".join(f"`{e}` {errors.get(str(e), {}).get('key', '')}" for e in o["x-errors"]), ""]
    out += ["## 四、数据结构", ""]
    seen = set()
    for name, sch in api["components"]["schemas"].items():
        out += props_table(name, sch, api["components"]["schemas"], seen)
    out += ["## 五、错误码", "", "| 码 | 键 | 何时 |", "|---|---|---|"]
    out += [f"| {k} | `{v['key']}` | {v['when']} |" for k, v in errors.items()]
    return "\n".join(out).rstrip() + "\n"


def main():
    db = json.loads(DB.read_text(encoding="utf-8"))
    api = json.loads(API.read_text(encoding="utf-8"))
    targets = {OUT_TABLES: tables_md(db), OUT_SQL: sql(db), OUT_API: api_md(api)}
    if "--check" in sys.argv:
        stale = [str(p.relative_to(ROOT)) for p, text in targets.items()
                 if not p.exists() or p.read_text(encoding="utf-8") != text]
        if stale:
            print("过期，请重新生成：\n  " + "\n  ".join(stale))
            sys.exit(1)
        print("✓ 发票服务设计文档是最新的")
        return
    for p, text in targets.items():
        p.write_text(text, encoding="utf-8")
        print("wrote", p.relative_to(ROOT))


if __name__ == "__main__":
    main()
