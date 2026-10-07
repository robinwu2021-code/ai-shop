# TDD-商品压缩包导入：结构映射与一键清空

状态：已实现（待真机验压缩包）
关联需求：docs/requirements/PRD-商品快速录入-压缩包与文字识别.md §十二（AC11–AC15）
创建：2026-10-07 · 最后更新：2026-10-07

> 档位：1 · 动了契约：新端点 `POST /biz/goods/zip-plan`、端上类型 `ZipTarget` / `ZipPlanSource`、i18n 词条。
> **不建表、不改保存体。**

## 用户定的（2026-10-07，两轮）

1. 「默认调用 LLM，根据 zip 的文件结构匹配到标准结构」
2. 「逻辑以简洁为主 —— 增加一键删除所有图，可以按区域一键清理（主图、详情）；
   重新上传 zip 直接上传，全部做累加」

于是上一版草稿里的「认包 / 逐文件比对 / 留档表 / 确认弹框」**全部去掉**：
要换一批图 = 先清空、再导入。两个动作各自简单，组合起来就是「更新」。

---

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| AC11 | 压缩包的文件结构默认由 LLM 映射到标准结构（主图 / 详情 / 文字 / 不导入），目录叫什么都行 | `POST /biz/goods/zip-plan` → `GoodsVisionPort#mapZip` → `ZipPlanning#resolve` |
| AC12 | LLM 关闭、超时、输出不合法时退回目录规则，导入照样能用 | 端上 `classifyZip` 的结果随请求带上（`ruleHint`），逐文件兜底；接口失败端上直接用它 |
| AC13 | 导入直接上传、**往后累加**，不弹确认；放不下的说清几张 | `photos.ts#importFromZip` |
| AC14 | 主图、详情图各能一键清空 | `photos.ts#clearPhotos` / `#clearDetail` + 两个字段头上的「清空」 |
| AC15 | 一键清空全部图（主图 + 详情） | 快速录入卡图片那一行的「清空图片」 |

孤立项：无。

## §1 现状与影响面

- 现在的导入：只认目录名「主图/main」「详情/detail」，没有目录全当主图；往后追加；超限截断只弹一句
- 复用：解压（`ports/zip-import.ts`）、规则分类（`classifyZip`，已测）、LLM 网关（`GoodsVisionGateway`，与文字识别同模型同超时）、上传（`/biz/upload/image`）、`confirm()`
- 改到的：编辑页两个「导入压缩包」入口的行为（仍是追加，只是分类换成 LLM）
- 不受影响：文字识别、图片识别按钮、保存体、C 端

## §2 方案

### 导入

```
选 zip → 解压 → 端上：规则分一遍（ruleHint）+ 读图片宽高
       → POST /biz/goods/zip-plan { 商品名, 类目, 文件清单(路径·宽高), txt 前 500 字, ruleHint }
       → 服务端：LLM 分类 → 逐文件校验，不合格的用 ruleHint → 回 { source, items[path,target,order] }
       → 端上：按 items 顺序逐张上传，主图、详情各自往后追加，满了就停
       → toast「已导入 主图 3 · 详情 8」/「…，2 张放不下」
```

- **LLM 只看文件结构**（路径、宽高），不看图片内容：不用先上传，快
- 标准结构：`MAIN` 主图（方图，第一张当封面）· `DETAIL` 详情（长图，按阅读顺序）· `TEXT` 文案 txt · `IGNORE` 不导入（资质、缩略图、系统文件、无关）
- **校验**（`ZipPlanning#resolve`，纯函数）：每个输入文件恰好一条；模型编的路径丢掉；`target` 不在枚举里、或把非 txt 标成 `TEXT` → 该文件用 `ruleHint`；同一 `target` 内按模型的 `order` 排、兜底的排后面，再重排成 1..n；模型标的封面挪到主图第一张
- `source`：全部来自模型 = `LLM`，全部兜底 = `RULE`，混合 = `MIXED`
- 端上：接口失败（网络、500）也直接用 `ruleHint`，导入不因为模型挂掉而失败
- 累加：页面上还没有封面时，导入的第一张主图当封面（与现在一致）；200 个文件以上直接提示，不调接口

### 清空

| 位置 | 按钮 | 清什么 |
|---|---|---|
| 商品图字段头 | 清空 | 封面 + 轮播 |
| 详情图字段头 | 清空 | 详情图 |
| 快速录入卡 · 图片那一行 | 清空图片 | 两者 |

- 有图才出现；点了先 `confirm`「清空主图？· 5 张」，确认后清。改的是表单，**保存后才生效**
- 清空 + 再导入 = 换一批图

### 契约

| 项 | 内容 |
|---|---|
| 端点 | `POST /biz/goods/zip-plan`，权限同保存商品（`BizPerms.GOODS`）。登记：`BizEndpointPermTest`、端上四处、`gen-openapi` 两张表 |
| 类型 | `ZipTarget`（MAIN/DETAIL/TEXT/IGNORE）、`ZipPlanSource`（LLM/RULE/MIXED）—— 枚举登记表 |
| Port | `GoodsVisionPort#mapZip` 默认返回 `null`（= 全部兜底） |
| i18n | 清空的按钮与确认、导入结果 toast，三语 |

### 模块

| 动作 | 路径 |
|---|---|
| 新增 | `shop-core/.../product/dto/ZipPlanning.java`（校验与兜底，纯函数） |
| 修改 | `BizGoodsController`（端点）· `GoodsVisionPort`（`mapZip`）· `GoodsVisionGateway`（prompt + 解析） |
| 修改 | `packages/shared/src/ports/zip-media.ts`（`ZipTarget`、`ruleHint`、`listsOf`） |
| 修改 | `b-app/src/ports/zip-import.ts`（带回文件宽高）· `goods-edit/photos.ts`（导入走 zip-plan、清空）· `goods-edit/index.vue`（三个清空按钮） |
| 修改 | `b-app/src/api/{endpoints,requests,contract,http}.ts` · `mocks/product.ts` |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 结果 | 消融 |
|---|---|---|---|
| AC11/12 | `ZipPlanningTest`（6）：模型结果被采用 · 模型为空 = RULE · 编的路径丢、缺的按规则 · 非法去向/图片当文案/txt 进主图按规则 · 封面挪第一 · 序号重排 | 6 绿 | 校验短路成「模型给了就用」→ `invalidPickFallsBack` 红；还原 6 绿 |
| AC11/12 | `BizGoodsZipPlanTest`（4）：port 的分法被采用 · port 为空按 ruleHint · 空清单不调模型 · 超 200 拒 | 4 绿 | — |
| AC12/13 | `zip-media.test.ts` 新增 4 条：垃圾文件滤掉 · 规则分法每文件一条 · 按分法取图 · 根目录 txt 要用相对路径判 | 13 绿（含原有） | — |
| AC14/15 | H5（mock）：商品图 3 张、详情 2 张 → 点商品图「清空」→ 取消不动 → 确认后商品图 0、封面空、详情 2；「清空图片」弹「商品图 2 张 · 详情图 1 张，保存后生效」，确认后两者都 0、三个清空按钮消失；确定键是红色危险档 | ✓ | — |
| AC11/13 | 真机：导入压缩包两次（累加）、放不下提示 | 待做 | — |

## §6 不做的

认包与「同一个包即更新」、逐文件比对、导入留档、导入前确认弹框（上一版草稿，2026-10-07 用户改为「清空 + 累加」）；
看图片内容判断是不是这件货；规格图。

## §7 对账二 · 设计 → 实现

与 §2「模块」逐行对得上，另有三处：

- `BizGoodsController#categoryPath`：describe 里内联的类目名查找抽出来，zip-plan 共用（行为逐字不变）
- `b-app/scripts/gen-openapi.mjs` 两张表、`BizEndpointPermTest` 判权表、`enum-registry.ts` 两条（登记清单要求）
- 顺带：`zip-import.ts` 改用相对路径后，规则的「根目录 txt」才判得出来 —— 此前给的是绝对路径，
  按「路径里没有 /」认根目录，永远不成立（新增测试第 4 条钉住）
