#!/usr/bin/env python3
"""Validate beauty task ownership and render its implementation trace index."""
import argparse
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'docs/technical/design/schema/beauty-development-plan.json'
TARGET=ROOT/'docs/technical/design/美业系统-开发任务对照索引.md'


def render():
    d=json.loads(SOURCE.read_text());api=json.loads((SOURCE.parent/'beauty-api-design.json').read_text());schema=json.loads((SOURCE.parent/'beauty-database-design.json').read_text());project=json.loads((SOURCE.parent/'beauty-java-project-design.json').read_text());method=json.loads((SOURCE.parent/'beauty-java-method-design.json').read_text())
    apis={a['id']:a for m in api['modules'] for a in m['apis']};tasks={t['id']:t for t in d['tasks']}
    assert len(tasks)==len(d['tasks'])
    def visit(key,path):
        assert key in tasks,key
        assert key not in path,('task cycle',path+[key])
        for dep in tasks[key]['dependencies']:visit(dep,path+[key])
    for key in tasks:visit(key,[])
    owner={};table_owner={}
    rank={'M0':0,'P1':1,'P2':2,'P3':3,'P4':4}
    for t in tasks.values():
        assert t['status']=='NOT_STARTED', '设计生成器不推断实施完成；实际结果回填TDD'
        for aid in t['apiIds']:
            assert aid in apis and aid not in owner,aid;owner[aid]=t['id']
            assert rank[t['stage']]<=rank[apis[aid]['phase']],('首次接线不应推迟原阶段而不说明',aid,t['id'])
        for table in t['tables']:
            assert table not in table_owner,table;table_owner[table]=t['id']
            assert (table.startswith('mch_') and t['id'].startswith('SHR-')) or table.startswith('bty_'),table
    assert set(owner)==set(apis)
    assert set(table_owner)=={t['name'] for t in schema['tables']}
    feature_tasks={f:set() for f in api['features']}
    for aid,tid in owner.items():
        for f in apis[aid]['functions']:feature_tasks[f].add(tid)
    assert all(feature_tasks.values())
    methods={e['apiId']:e for e in method['endpoints']};shared={e['apiId']:e for e in method['sharedHttpMethods']};bindings={e['apiId']:e for e in project['apiBindings']}
    for aid in methods:assert aid in owner
    lines=['# 美业系统开发任务对照索引', '', '> 状态：任务定义基线 · 2026-10-20 · 实际实施进度以执行入口的记录为准，本索引不推断工程、接口或迁移已交付。', '> 由 `python3 scripts/gen-beauty-development-plan.py` 生成，勿手改；真源：[开发任务JSON](./schema/beauty-development-plan.json)。', '> 执行入口：[美业后端开发计划](../TDD-美业系统-后端开发计划.md)；[类与方法](./美业系统-Java类与方法清单.md) · [逐API方法](./美业系统-Controller-Service-Mapper方法清单.md)。', '', '## 一、任务、依赖与交付', '',
           f'{len(tasks)}个目标任务，覆盖118项功能、160个API/合同及{len(schema["tables"])}项数据结构；其中{sum(t["name"].startswith("bty_") for t in schema["tables"])}项bty结构归美业，{sum(t["name"].startswith("mch_") for t in schema["tables"])}项人员/资源结构由共用所有者提供。每个API/表只有一个首次实施主责任务，不因多端或多次联合验收重复记完成。P4宽任务是待按正式合同再分解的路线项，不可直接一次编码。', '', '| 任务 | 阶段 | 交付 | 前置 | API/合同数 | 表数 | 验收重点 |', '|---|---|---|---|---:|---:|---|']
    for t in tasks.values():lines.append(f'| {t["id"]} | {t["stage"]} | {t["title"]} | '+('、'.join(t['dependencies']) or '—')+f' | {len(t["apiIds"])} | {len(t["tables"])} | {t["acceptance"]} |')
    lines+=['', '## 二、API/合同首次实施归属', '', '美业路径的Service签名、事务、Mapper调用以方法真源为准。共用HTTP由其原域创建/扩展，共用CONTRACT不机械转为美业Java方法。实施任务不能把“核验合同存在”计作“整个共用能力已交付”。', '', '| API | 阶段 | 首次任务 | 方法与路径/合同 | Java落点/所有者 |', '|---|---|---|---|---|']
    for aid,a in sorted(apis.items()):
        if aid in methods:
            e=methods[aid];mapping=e['controller'].split('.')[-1]+'.'+e['method']+' → '+e['service'].split('.')[-1]+'.'+e['method']
        elif aid in shared:
            e=shared[aid];mapping=e['controller']+'.'+e['name']+'（共用候选）'
        else:mapping=bindings[aid]['owner']+'；正式签名沿权威提供方'
        lines.append(f'| {aid} | {a["phase"]} | {owner[aid]} | `{a["method"]} {a["path"]}` | {mapping} |')
    lines+=['', '## 三、表结构与首次数据实现任务', '', '| 表 | 阶段 | 变更性质 | 首次实现任务 | 所有者 |', '|---|---|---|---|---|']
    for t in schema['tables']:lines.append(f'| {t["name"]} | {t["stage"]} | {t["mode"]} | {table_owner[t["name"]]} | '+('共用人员/资源；不在beauty迁移' if t['name'].startswith('mch_') else 'beauty-core')+' |')
    lines+=['', 'DATA-02负责首批16项P1 bty迁移；DATA-04～07负责同批Entity/Mapper。后续CARE-01/PERF-01/PLAN-01分别新增7项P3与2项P4结构，按当时已执行迁移状态追加版本，不能回改已执行V1。共享mch_appointment_slot是既有表兼容扩展，不能整表重建或与容量桶双扣。', '', '## 四、118项功能到任务', '', '| 功能 | 内容 | 目标阶段 | 关联任务 |', '|---|---|---|---|']
    for f,x in sorted(api['features'].items()):lines.append(f'| {f} | {x["title"]} | {x["phase"]} | '+ '、'.join(sorted(feature_tasks[f]))+' |')
    lines+=['', '## 五、状态回填规则', '', '本索引是计划/归属投影，不扫描目录猜测完成。执行后在TDD登记红灯/绿灯命令、实际测试、隔离环境、变更文件、剩余阻塞及提交。DATA-03接线测试不等于所有Mapper已验证；CAL-01可先做资源占用视图，但需TASK-01具体任务摘要联验后才算工作台准出。首批数据层通过不等于预约、付款、履约或真机打印可用。', '']
    return '\n'.join(lines),{'tasks':len(tasks),'apis':len(owner),'features':len(feature_tasks),'tables':len(table_owner)}


def main():
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');args=p.parse_args();text,stats=render()
    if args.check:assert TARGET.exists() and TARGET.read_text()==text,'任务索引过期，请重新生成'
    else:TARGET.write_text(text)
    print('PASS beauty development task plan:',json.dumps(stats,ensure_ascii=False))


if __name__=='__main__':main()
