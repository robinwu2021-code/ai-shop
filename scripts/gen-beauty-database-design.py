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
             '> 状态：原41项目标基线保留；Task58正式V1–V8共28项私表，增量另列 · 2026-10-24',
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
    lines += ['## BM03-a1增量字段（私有V2）', '',
              '原41项结构/16项V1保持冻结；另追加2项私有结构，合计43项设计、18项已迁移私表。增量真源：[beauty-resource-intent-schema.json](./schema/beauty-resource-intent-schema.json)，生命周期与验收见主TDD§2.15/§26。以下是内部存储，不代表已支持预约营业。', '']
    increment = json.loads((SOURCE.parent / 'beauty-resource-intent-schema.json').read_text())
    for table in increment['tables']:
        lines += ['### ' + table['name'], '', '| 字段 | 类型 | 可空 | 含义 |', '|---|---|---|---|']
        for field in table['fields']:
            lines.append(f'| {field["name"]} | {field["type"]} | {field["nullable"]} | {field["description"]} |')
        lines += ['', '唯一键均含tenant_no/entity_no：' + '；'.join(', '.join(key) for key in table['unique']) + '。', '']
    lines += ['## BM03业务与冻结决策增量（私有V3/V4）', '',
              '原41项目标及V1/V2保持冻结。V3追加准备、操作、当前指针、不可变安排4表；V4追加不可变来源决策1表。当前私表16+2+4+1=23，行业历史表另计；并非把49任务/160接口目标宣称全部交付。', '',
              '正式迁移与严格物理manifest由BeautySchemaValidator核验；生成器为gen-beauty-appointment-business-schema.mjs、gen-beauty-appointment-decision-schema.mjs。原生升级及部署证据见[Task53报告](../../../.superpowers/sdd/beauty-backend/task-53-report.md)。', '']
    for filename in ['beauty-appointment-business-schema.json', 'beauty-appointment-decision-schema.json']:
        increment = json.loads((SOURCE.parent / filename).read_text())
        lines += ['增量真源：[' + filename + '](./schema/' + filename + ')。', '']
        for table in increment['tables']:
            lines += ['### ' + table['name'], '', '| 字段 | 类型 | 可空 | 含义 |', '|---|---|---|---|']
            for field in table['fields']:
                lines.append(f'| {field["name"]} | {field["type"]} | {field["nullable"]} | {field["description"]} |')
            lines += ['', '唯一键均含tenant_no/entity_no：' + '；'.join(', '.join(key) for key in table['unique']) + '。', '']
    lines += ['## Task55A接待身份基础（私有V5）', '',
              '四项增量表使私表合计27，历史表另计。这里只交付登记/认领回执/到店码及基础表；签到、工单、恢复扫描尚未交付。V1–V4保持冻结。', '',
              '真源：[beauty-reception-schema.json](./schema/beauty-reception-schema.json)；生成器gen-beauty-reception-schema.mjs；正式迁移SHA及原生测试见Task55A报告。', '']
    reception = json.loads((SOURCE.parent / 'beauty-reception-schema.json').read_text())
    for table in reception['tables']:
        lines += ['### ' + table['name'], '', '| 字段 | 类型 | 可空 | 含义 |', '|---|---|---|---|']
        for field in table['fields']:
            lines.append(f'| {field["name"]} | {field["type"]} | {field["nullable"]} | {field["description"]} |')
        lines += ['', '唯一键：' + '；'.join(', '.join(key) for key in table['unique']) + '。',
                  'CHECK：' + '；'.join(table['checks']) + '。', '']
    lines += ['## Task55D walk-in and recovery persistence', '']
    lines += [rule + '\n' for rule in reception.get('walkInRecoveryContract', [])]
    guard = json.loads((SOURCE.parent / 'beauty-reception-identity-guard-schema.json').read_text())
    lines += ['## Task55A修复增量（私有V6）', '',
              'V5保持原字节；其nullable identity CHECK允许UNKNOWN。本版本追加明确kind非空分支，四种NULL组合按同空或合法非空处理。无新表，无数据修复/伪baseline；非法V5存量阻断升级。', '',
              '真源：[beauty-reception-identity-guard-schema.json](./schema/beauty-reception-identity-guard-schema.json)。', '',
              '`' + guard['table'] + '` / `' + guard['constraint'] + '`：', '', guard['expression'], '']
    units = json.loads((SOURCE.parent / 'beauty-appointment-assignment-units-schema.json').read_text())
    lines += ['## Task55B容量单位修复（私有V7）', '',
              'V1–V6字节冻结；同一项目需求可按容量使用同资源多份，每份保留独立planned_assignment_no。只将冲突的资源组合唯一索引改为同列普通索引；不合并、不回写既有行，无新列或新表。', '',
              '真源：[beauty-appointment-assignment-units-schema.json](./schema/beauty-appointment-assignment-units-schema.json)。', '',
              '`' + units['table'] + '`：移除唯一索引 `' + units['dropUniqueIndex'] + '`；新增普通索引 `' + units['addIndex'] + '`（' + ', '.join(units['columns']) + '）；保留业务号唯一索引 `' + units['retainedUniqueIndex'] + '`。', '']
    source = json.loads((SOURCE.parent / 'beauty-source-observation-schema.json').read_text())
    table = source['table']
    lines += ['## Task58 source observation watermark (private V8)', '',
              'V1-V7 and platform539/540 remain frozen. V8 adds one private table; 28 private tables plus industry history. '
              'No money, receipt amount, raw customer proof or consumer token is stored here. '
              'Canonical caller/operation and source identities are both permanent unique domains.', '',
              'Source: [beauty-source-observation-schema.json](./schema/beauty-source-observation-schema.json). '
              'Generator: gen-beauty-source-observation-schema.mjs. Runtime design and exact pin are in Task58 report.', '',
              '### ' + table['name'], '', '| Field | Type | Nullable | Meaning |', '|---|---|---|---|']
    for field in table['fields']:
        lines.append(f"| {field['name']} | {field['type']} | {field['nullable']} | {field['description']} |")
    lines += ['', 'Highest owner pair never decreases; verified pair/quantity lines preserve historical evidence. '
              'Current UNKNOWN/BLOCKED does not erase that history. No automatic allocation release; canStart=false.', '']
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
