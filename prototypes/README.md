# 界面原型 · 唯一入口

> 三端所有界面原型的**真源在这个目录**；claude.ai 上的 artifact 是发布出去的副本。
> 总览页：`prototypes/index.html`（由 `scripts/gen-proto-index.py` 从 `registry.json` 生成），
> 发布版：[https://claude.ai/artifact/B9kA5pc3EQvRo4hoK8YQC9](https://claude.ai/artifact/B9kA5pc3EQvRo4hoK8YQC9)（连同全部稿子与 proto.css 一起发，每一屏可点）；文字版：[docs/technical/design/原型清单.md](../docs/technical/design/原型清单.md)。

## 为什么要有这个目录

此前十几份原型只存在于 claude.ai，各自一份样式、各自一个链接，散在 PRD / TDD /
`gen-ui-catalog.py` 的硬编码表里。找一屏要先记得它在哪份稿子里；改一处样式约定
（比如「类型不做标签」）要在每份稿子里各改一遍，而且没有一处能说清「现在有效的是哪几份」。

## 目录

```
prototypes/
  README.md          本文
  proto.css          共用样式：文档层 + 屏内层（与产品令牌同一套）
  registry.json      登记表：每份原型一条（真源，手工维护）
  index.html         总览页（生成物，别手改）
  <slug>.html        各份原型正文（只写内容，样式来自 proto.css）
```

## 三条规矩

1. **样式只在 `proto.css`**。原型文件里不写 `<style>`；需要新积木先看 `proto.css` 有没有，
   没有就加进去（加的是给所有原型用的，命名与产品类名对齐，见 `docs/technical/design/规范-组件.md`）。
   原型里出现规范之外的圆角、字号、描边，落地时前端只能二选一：破规范或白画。
2. **每一屏一个锚点** `<figure id="sNN">`，图注 `<figcaption><b>NN 标题</b>…</figcaption>`。
   `registry.json` 里登记这一屏对应的端与路由，界面清单（`gen-ui-catalog.py`）就能从页面跳到这一屏。
   画布式（无锚点）的原型也要登记，只是 `screens[].id` 留空。
3. **状态写在登记表里**，不写在稿子标题里：`在建`（页面还没落地）/ `已落地` / `参考`（分析、对比、方案，
   不是页面稿）/ `作废`（被后来的稿子取代，登记 `supersededBy`）。作废的稿子**不删文件**，
   读旧代码的人要能找到它。

## 加一份原型

```bash
cp prototypes/_template.html prototypes/<slug>.html   # 只写内容
# 在 registry.json 加一条（slug / title / clients / status / screens / docs）
python3 scripts/gen-proto-index.py                     # 重生成 index.html 与 原型清单.md
python3 scripts/gen-ui-catalog.py                      # 清单跟着更新
```

发布：用 Artifact 工具把 `<slug>.html` 连同 `proto.css` 一起发（`files: {"proto.css": ...}`），
把得到的地址写回 `registry.json` 的 `artifact`。已发布过的原型**更新到同一个地址**，
PRD / TDD 里的链接才不会失效。

## 校验

`python3 scripts/gen-proto-index.py --check`：登记表里的文件都在、每个 `screens[].id`
在文件里确有 `<figure id>`、文件里没有内联 `<style>`、生成物没陈。挂在 pre-push。
