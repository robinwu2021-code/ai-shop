#!/usr/bin/env python3
"""Generate proposed field dictionary and relationship SVGs from beauty schema; never connects to a database."""
import argparse
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'docs/technical/design/schema/beauty-database-design.json'
OUTPUT = ROOT / 'docs/technical/design/美业系统-数据库字段字典.md'
GROUPS = {'人员': 'personnel', '资源': 'resource', '服务': 'service', '护理疗程': 'care', '业绩提成': 'performance'}
EXTERNAL = {'mch_account', 'mch_store', 'prd_goods', 'prd_sku'}


def generate():
    schema = json.loads(SOURCE.read_text())
    tables = schema['tables']
    names = {t['name'] for t in tables}
    assert len(names) == len(tables), 'Duplicate table'
    for t in tables:
        columns = {f['name'] for f in t['fields']}
        assert len(columns) == len(t['fields']) and t['business_key'] in columns, t['name']
        assert t['group'] in GROUPS and t['mode'] in {'NEW', 'EXTEND_EXISTING'}
        for key in t['unique'] + t['indexes']:
            assert set(key) <= columns | {c[0] for c in schema['common_fields']}, (t['name'], key)
        for f in t['fields']:
            assert f.get('references', '') in names | EXTERNAL | {''}, (t['name'], f)
    lines = ['# 美业与共用人员资源数据库字段字典', '',
             '> 状态：数据库结构草稿，未执行 · 2026-10-20',
             '> 由 `python3 scripts/gen-beauty-database-design.py` 生成，勿手改；真源：[数据库设计源](./schema/beauty-database-design.json)。',
             '> 业务约束及复用边界：[数据库结构设计](./美业系统-数据库结构设计.md)。本册不是Flyway迁移，不输出可执行建表SQL。', '',
             '## 一、公共字段与约束', '',
             '新增表合并下列公共字段；扩展既有表只表示所列目标，不重建原表。所有候选唯一键和普通索引默认前置tenant_no、entity_no，以下省略该前缀。id是物理主键；业务号不复用。关系均为服务校验的逻辑引用，不自动创建跨域外键。', '',
             '| 字段 | 类型 | 说明 |', '|---|---|---|']
    lines += [f'| {n} | {ty} | {d} |' for n, ty, d in schema['common_fields']]
    lines += ['', '所有新增公共字段NOT NULL，id按现有BaseEntity生成，其他默认值沿项目装配。状态、审计和事实保留规则见设计册；NULL不得代替有效状态仲裁。', '',
              '## 二、按组关系图', '', '图由同一表/引用源生成；每图最多七表。箭头为逻辑引用，跨图及既有表引用见字段列；没有图内连线不表示没有外部依赖。', '']
    outputs = {}
    for group, slug in GROUPS.items():
        subset = [t for t in tables if t['group'] == group]
        for offset in range(0, len(subset), 7):
            chunk = subset[offset:offset + 7]
            number = offset // 7 + 1
            filename = f'beauty-db-{slug}-{number}.svg'
            lines += [f'### {group} {number}', '', f'![{group}数据库关系](../diagrams/{filename})', '']
            width, height = 1200, 120 + ((len(chunk) + 1) // 2) * 155
            coords = {t['name']: (85 + (i % 2) * 560, 90 + (i // 2) * 155) for i, t in enumerate(chunk)}
            svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
                   f'<title>{html.escape(group)}数据库逻辑引用 {number}</title><desc>从数据库设计JSON生成，展示候选表及图内引用，跨图引用见字段字典；非已执行DDL。</desc>',
                   '<defs><marker id="a" markerWidth="7" markerHeight="7" refX="6" refY="3" orient="auto"><path d="M0 0L6 3L0 6" fill="none" stroke="#8497a8"/></marker></defs>',
                   '<rect width="100%" height="100%" fill="#f5f8fb"/>',
                   f'<text x="45" y="40" font-family="sans-serif" font-size="24">{html.escape(group)} · 目标结构 {number}</text>']
            for t in chunk:
                x, y = coords[t['name']]
                for f in t['fields']:
                    target = f.get('references')
                    if target in coords and target != t['name']:
                        tx, ty = coords[target]
                        if x != tx:
                            sx, ex = (x + 470, tx) if x < tx else (x, tx + 470)
                            path = f'M{sx},{y+52} C600,{y+52} 600,{ty+52} {ex},{ty+52}'
                        else:
                            lane = x - 25
                            path = f'M{x},{y+52} H{lane} V{ty+52} H{x}'
                        svg.append(f'<path d="{path}" fill="none" stroke="#8497a8" stroke-width="1.5" marker-end="url(#a)"/>')
            for t in chunk:
                x, y = coords[t['name']]
                summary = ', '.join(f['name'] for f in t['fields'][:3])
                svg += [f'<rect x="{x}" y="{y}" width="470" height="110" rx="10" fill="white" stroke="#b9cbd7"/>',
                        f'<text x="{x+14}" y="{y+28}" font-family="sans-serif" font-size="18" font-weight="bold">{html.escape(t["name"])}</text>',
                        f'<text x="{x+14}" y="{y+57}" font-family="sans-serif" font-size="16">{html.escape(t["description"])}</text>',
                        f'<text x="{x+14}" y="{y+85}" font-family="sans-serif" font-size="12">{html.escape(summary)}</text>']
            svg += ['</svg>']
            outputs[ROOT / 'docs/technical/diagrams' / filename] = '\n'.join(svg) + '\n'
    lines += ['## 三、逐表字段与索引', '']
    for t in tables:
        lines += [f'### {t["name"]} · {t["description"]}', '',
                  f'归属：{"共用merchant能力" if t["name"].startswith("mch_") else "美业行业能力"}；阶段：{t["stage"]}；目标：{t["mode"]}。', '',
                  '| 字段 | 类型 | 可空 | 含义 | 逻辑引用 |', '|---|---|---|---|---|']
        lines += [f'| {f["name"]} | {f["type"]} | {"是" if f["nullable"] else "否"} | {f["description"]} | {f.get("references", "—")} |' for f in t['fields']]
        lines += ['', '唯一键：' + '；'.join('`' + ', '.join(k) + '`' for k in t['unique']) + '。',
                  '普通索引：' + ('；'.join('`' + ', '.join(k) + '`' for k in t['indexes']) or '按主关系及业务号访问，不另预建') + '。', '',
                  '业务约束：' + t['rules'], '']
    lines += ['## 四、边界', '', '字段、索引及表名为候选设计；实施前核对现有迁移、引用键类型、原子SQL、实际查询计划和存量。禁止按本字典直接覆盖既有表或执行资金迁移。', '']
    outputs[OUTPUT] = '\n'.join(lines)
    return outputs, tables


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    outputs, tables = generate()
    stale = []
    for path, content in outputs.items():
        if args.check:
            if not path.exists() or path.read_text() != content:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.write_text(content)
    if stale:
        raise SystemExit('Stale generated files: ' + ', '.join(stale))
    print(f'{len(tables)} table designs validated; {len(outputs)} generated files ' + ('current' if args.check else 'written'))


if __name__ == '__main__':
    main()
