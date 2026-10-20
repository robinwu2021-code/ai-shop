#!/usr/bin/env python3
"""Render the proposed beauty Java structure; never creates Java/POM files."""
import argparse
import json
from collections import defaultdict
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/technical/design/schema/beauty-java-project-design.json'
DOC = ROOT / 'docs/technical/design/美业系统-Java项目结构与类职责.md'
SVG = ROOT / 'docs/technical/diagrams/beauty-java-module-dependencies.svg'


def generate():
    data=json.loads(SOURCE.read_text())
    api=json.loads((SOURCE.parent/'beauty-api-design.json').read_text())
    schema=json.loads((SOURCE.parent/'beauty-database-design.json').read_text())
    apis={a['id']:a for m in api['modules'] for a in m['apis']}
    assert len(apis)==len(data['apiBindings'])
    assert set(apis)=={b['apiId'] for b in data['apiBindings']}
    assert set(t['table'] for t in data['tables'])=={t['name'] for t in schema['tables'] if t['name'].startswith('bty_')}
    modules={m['name']:m for m in data['modules']}
    assert set(modules)=={'beauty-'+s for s in ('api','core','web','channel','server')}
    assert {m['code'] for m in data['businessModules']}==set('ABCDEFGHIJKLMNOPQRST')
    graph={n:[dep for dep in m['dependencies'] if dep in modules]+[dep.split('（')[0] for dep in m['optionalDependencies'] if dep.split('（')[0] in modules] for n,m in modules.items()}
    def visit(n,stack):
        assert n not in stack,'模块循环依赖';
        for dep in graph[n]:visit(dep,stack+[n])
    for n in modules:visit(n,[])
    assert 'beauty-core' not in graph['beauty-web'] and not graph['beauty-api']
    controllers=defaultdict(list)
    for b in data['apiBindings']:
        a=apis[b['apiId']]
        owned=a['method']!='CONTRACT' and '/x/beauty/' in a['path']
        assert owned==(b['owner']=='beauty-web')
        if owned:
            assert b['controller'].startswith('ai.neargo.shop.beauty.api.') and b['service']
            controllers[b['controller']].append(b)
        else:assert b['controller'] is None and b['service'] is None
    for bs in controllers.values():
        assert len({apis[b['apiId']]['path'].split('/x/beauty/')[1].split('/')[0] for b in bs})<=3
    lines=['# 美业 Java 项目结构与类职责', '', '> 状态：候选目录与类职责，待评审 · 2026-10-20 · 未创建Java模块、POM、Controller或迁移。',
           '> 由 `python3 scripts/gen-beauty-java-project-design.py` 生成，勿手改。真源：[项目结构JSON](./schema/beauty-java-project-design.json)。',
           '> 总体决策：[美业独立项目与包依赖方案](../TDD-美业系统-独立项目与包依赖方案.md)；接口：[分模块API](./美业系统-API清单.md)；数据库：[结构设计](./美业系统-数据库结构设计.md)。', '',
           '## 一、定位', '',
           f'以`{data["root"]}`为独立Maven reactor，五个jar、一个聚合POM；美业根包`{data["package"]}`。20业务模块按职责归包，不拆20个工程。名称仅是实施定位，不要求一次生成全部空类。', '',
           '![美业模块依赖](../diagrams/beauty-java-module-dependencies.svg)', '',
           '箭头表示使用方依赖提供方，虚线表示按部署选择的协议依赖。业务实现与用户Web同级依赖接口；独立业务server不引用户web。共享平台依赖在下表列出，未把共享实现藏进美业core。', '',
           '## 二、Maven模块', '', '| 构件 | 职责 | 必需直接依赖 | 按需依赖 |', '|---|---|---|---|']
    for m in data['modules']:
        lines.append(f'| {m["name"]} | {m["responsibility"]} | '+ '、'.join(m['dependencies'])+' | '+('、'.join(m['optionalDependencies']) or '—')+' |')
    lines += ['', '## 三、完整包路径', '', '| 模块 | Java包 | 内容 |', '|---|---|---|']
    for p in data['packages']:lines.append(f'| {p["module"]} | `{p["package"]}` | {p["content"]} |')
    lines += ['', '## 四、20业务模块主责', '', '下列接口若属美业，完整名为`ai.neargo.shop.beauty.service.XxxService`，文件归beauty-api；同名`XxxServiceImpl`完整名为`ai.neargo.shop.beauty.service.impl.XxxServiceImpl`，归beauty-core。标注共用的能力不在美业生成同名接口/实体。', '', '| 模块 | Service或共用接入 | 表/事实/边界 |', '|---|---|---|']
    for m in data['businessModules']:lines.append(f'| {m["code"]} | {m["services"]} | {m["boundary"]} |')
    lines += ['', '## 五、用户Controller归属', '',
              f'{len(controllers)}个候选资源Controller承接{sum(len(bs) for bs in controllers.values())}条美业候选HTTP接口；共用人员/资源HTTP与共用CONTRACT不转移到美业。Controller完整包名是类身份，Spring装配时还须验证跨行业Bean名不冲突。', '',
              '| beauty-web内完整类名 | 调用的Service | API编号 |', '|---|---|---|']
    for name,bs in sorted(controllers.items()):lines.append(f'| `{name}` | '+ '、'.join(sorted({b['service'] for b in bs}))+' | '+'、'.join(b['apiId'] for b in bs)+' |')
    lines += ['', '## 六、全部API/合同的所有权对照', '', '| 编号 | 路由或合同 | 源模块 | 所有者 |', '|---|---|---|---|']
    for b in sorted(data['apiBindings'],key=lambda b:b['apiId']):
        a=apis[b['apiId']];lines.append(f'| {b["apiId"]} | `{a["method"]} {a["path"]}` | {b["module"]} | {b["owner"]} |')
    lines += ['', '## 七、美业持久化候选类', '',
              '只映射25项bty表，全部在beauty-core；实体继承现有BaseEntity。Mapper集合完整名为`ai.neargo.shop.beauty.mapper.BeautyMappers`，以下Mapper为其内嵌接口，不生成等义Repository。14项共用表结构（13新、1扩展）全部排除。逻辑聚合/状态机与数据库Entity按职责组合，不再为每张表强制复制一个Domain对象。', '',
              '| 表 | Entity完整类名 | 内嵌Mapper |', '|---|---|---|']
    for t in data['tables']:lines.append(f'| {t["table"]} | `{t["entity"]}` | `{t["mapper"]}` |')
    lines += ['', '## 八、生成物边界', '',
              '本清单不生成Java源文件。API输入输出细节沿API清单和工作台契约；具体类与方法签名已整理到[Java类与方法清单](./美业系统-Java类与方法清单.md)及[Controller-Service-Mapper方法清单](./美业系统-Controller-Service-Mapper方法清单.md)，以方法设计JSON为真源；不使用BAPI序号作类名或方法名。共享权限、资金、资源、打印和任务等合同须先核验真实提供方，不能仅由类名推断其已实现。', '']
    svg=['<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="510" viewBox="0 0 1080 510" role="img" aria-labelledby="title desc">', '<title id="title">美业五模块Java依赖设计</title>', '<desc id="desc">web和core使用api，channel使用api，server装配core和channel，web按需使用channel。图由模块设计JSON生成。</desc>', '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#56769b"/></marker></defs>', '<rect width="1080" height="510" fill="#f5f8fc"/>','<style>text{font-family:"PingFang SC","Noto Sans CJK SC",sans-serif;fill:#24364d;font-size:16px}.sub{font-size:13px;fill:#566b84}.head{font-size:23px;font-weight:700}</style>', '<text x="30" y="36" class="head">美业独立项目：五模块、无环依赖</text>']
    pos={'beauty-web':(40,75),'beauty-core':(820,75),'beauty-api':(430,230),'beauty-channel':(40,380),'beauty-server':(820,380)}
    for n,deps in graph.items():
        for dep in deps:
            x,y=pos[n];x2,y2=pos[dep];x+=105;y+=40;x2+=105;y2+=40
            dx=x2-x;dy=y2-y
            scale=max(abs(dx)/105,abs(dy)/40)
            ex=x2-dx/scale;ey=y2-dy/scale
            dash=' stroke-dasharray="7 5"' if dep not in modules[n]['dependencies'] else ''
            svg.append(f'<path d="M {x} {y} L {ex} {ey}" stroke="#56769b" stroke-width="2" fill="none" marker-end="url(#arrow)"{dash}/>')
    labels={'beauty-api':'Service / Req / VO / 自有SPI','beauty-core':'业务、持久化、内部入口','beauty-web':'C / B / 平台接入','beauty-channel':'协议客户端与远程适配','beauty-server':'唯一独立业务启动进程'}
    for name,(x,y) in pos.items():svg.append(f'<rect x="{x}" y="{y}" width="210" height="80" rx="10" fill="white" stroke="#9bb8d7"/><text x="{x+15}" y="{y+30}">{escape(name)}</text><text x="{x+15}" y="{y+57}" class="sub">{escape(labels[name])}</text>')
    svg += ['<text x="310" y="485" class="sub">实线：必须依赖　虚线：按需依赖　平台依赖见模块表</text>','</svg>']
    return {'doc':'\n'.join(lines),'svg':'\n'.join(svg)+'\n','count':len(controllers)}


def main():
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');args=p.parse_args();out=generate()
    for target,key in ((DOC,'doc'),(SVG,'svg')):
        if args.check:assert target.exists() and target.read_text()==out[key],f'生成物过期：{target}'
        else:target.write_text(out[key])
    print(f'PASS: 五模块无环、20模块归属、160项API/合同归属、25私有表；{out["count"]}个候选Controller；生成物一致')


if __name__=='__main__':main()
