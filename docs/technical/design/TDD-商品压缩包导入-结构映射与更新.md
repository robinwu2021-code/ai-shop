# TDD-商品压缩包导入：结构映射与更新

状态：草稿（待确认）
关联需求：docs/requirements/PRD-商品快速录入-压缩包与文字识别.md §十二（AC11–AC18，本次新增）
创建：2026-10-07 · 最后更新：2026-10-07

> 档位：1 · 动了契约：新端点 `POST /biz/goods/zip-plan`、新表 `prd_goods_media_import`、
> 保存体加 `mediaImport`、新枚举三个、i18n 词条若干、配置项两个。

用户原话（2026-10-07）：「默认调用 LLM，根据 zip 的文件结构匹配到标准结构，支持新增或者更新，
如果相同的 zip 就是更新」。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC11 | 压缩包的文件结构**默认由 LLM** 映射到标准结构（主图 / 详情 / 文字 / 不导入），目录叫什么都行 | `ZipPlanService#plan` → `GoodsVisionPort#mapZip`（§2.4） |
| AC12 | LLM 不可用、超时、输出不合法时，退回现有目录规则，导入照样能用 | 端上 `classifyZip` 结果随请求带上当兜底（§2.4 ⑤） |
| AC13 | 同一个包再导一次 = **更新**；别的包 = **新增** | `ZipPlanService#matchPackage`（§2.3） |
| AC14 | 更新时逐个文件判：新增 / 更新 / 未变 / 移除；未变的不重传 | `ZipPlanService#diff`（§2.5）+ 端上只传 ADDED/UPDATED |
| AC15 | 更新只动**来自这个包**的图；手动传的图位置、内容都不动 | 端上纯函数 `planZipApply`（§2.6） |
| AC16 | 导入前弹框确认，每张图可改去向、可不导；放不下的列出来，不再悄悄丢 | 结果弹框（§2.7） |
| AC17 | 保存时留档：这件商品的图来自哪个包的哪个文件，下次才认得出「同一个包」 | 保存体 `mediaImport` → 表 `prd_goods_media_import`（§2.8） |
| AC18 | 弹框里改过的去向，在下次更新时沿用（他纠正过的分法不被 LLM 再改回去） | 留档 `items[].target` 是下次的先验（§2.4 ③） |

**孤立项**：无。图片内容识别（「这张是不是这件货」）不在本期，见 §6。

---

## §1 现状与影响面

**现在的导入**（`b-app/src/pages/goods-edit/photos.ts#importFromZip` + `packages/shared/src/ports/zip-media.ts#classifyZip`）：

- 分类只认目录名里含「主图/main」「详情/detail」；没有目录时全当主图
- **只会往后追加**：不判重复、不替换。同一个包导两次，图翻一倍直到上限
- **放不下的直接丢**：已有 5 张主图时，包里 6 张只进前 2 张，其余只弹一句 toast
- 不留任何记录：导完就不知道哪张图来自哪个包

**可以直接复用的**：

| 能力 | 在哪 |
|---|---|
| 解压、列文件、读 txt | `b-app/src/ports/zip-import.ts`（App 原生 ZipPicker + plus.zip） |
| 规则分类与数字排序 | `classifyZip` / `sortByNumber`（已单测）—— 降级成 LLM 的**先验与兜底** |
| LLM 网关 | `shop-channel` 的 `GoodsVisionGateway`（`shop.ai.vision.*`，文字识别已在用） |
| 上传 | `POST /biz/upload/image`（magic-number 校验、`sys_media_asset` 记账） |
| 撤销 | `mergeUndo` 快照（快速录入已在用） |
| 草稿 | `prd_goods_draft.payload` 存的是整份保存体 —— 保存体加字段，草稿自动带上 |
| 录入方式 | `entry_source = ZIP`（V377，0.5.32 起真正发出去） |

**会被改到的**：商品编辑页的「导入压缩包」两个入口（快速录入卡、商品图字段）、保存体与 `http.ts` 映射。

**明确不受影响**：文字识别（压缩包里的 txt 仍然只进 `zipText`，点了才进文字框）、C 端、运营端、
图片识别按钮（看封面填字段，另一件事）。

---

## §2 方案

### 2.1 一次导入的全过程

```
选 zip ─▶ 解压 ─▶ ① 端上列清单 + 指纹 ─▶ ② POST /biz/goods/zip-plan
                                              │
                         ┌────────────────────┤ 服务端
                         │ ③ 认包：新增 / 更新 │
                         │ ④ LLM 结构映射      │（更新时只映射新文件）
                         │ ⑤ 逐文件状态        │
                         └────────────────────┘
                                              ▼
              ⑥ 端上落位（纯函数）─▶ ⑦ 弹框确认 ─▶ ⑧ 只传要传的 ─▶ 写进表单（可撤销）
                                                                    │
                                                         保存时 ⑨ 带 mediaImport 留档
```

**LLM 只做一件事：把文件归到标准结构。** 认包、判变化、排位置都是确定性的规则，可测、可复现。

### 2.2 端上：清单与指纹（①）

解压后对每个文件取：

| 字段 | 怎么来 | 用途 |
|---|---|---|
| `path` | 相对路径，去掉包名那一层顶层目录，统一 `/` | 文件的身份 |
| `bytes` | 文件大小 | 判变化（大小不同 = 一定变了） |
| `width` / `height` | `uni.getImageInfo`（本地路径） | 给 LLM：长图倾向详情、方图倾向主图 |
| `fp` | **抽样指纹**：大小 + 头/中/尾各 16KB 的 MD5 | 判变化（大小相同时） |

- 包指纹 `zipFp` 同样用抽样指纹算 zip 文件本身
- 先在端上滤掉明显的垃圾（`__MACOSX/`、`._*`、`.DS_Store`、`Thumbs.db`），省 token，也不给 LLM 犯错的机会
- 根目录 txt 读前 500 字作 `txtPreview`（帮 LLM 判断这是文案还是别的）
- 同时跑一遍现有 `classifyZip`，结果作为 `ruleHint` 随请求带上（§2.4 ⑤ 兜底用）
- 上限：200 个文件 / 100MB，超了直接提示，不调接口

> 为什么用抽样指纹不算全量哈希：App 端 JS 没有 `crypto.subtle`，十几张几 MB 的图全量算要好几秒。
> JPEG/PNG 只要重新导出过，头部与尾部必然变 —— 抽样漏判的代价是「改过的图被当成未变」，
> 而弹框里每张都看得见，他能手动改成「更新」。

### 2.3 服务端：认包（③）

取这件商品**最近一次**导入留档（`prd_goods_media_import`，新建商品没有），按顺序判：

| 判据 | 结论 |
|---|---|
| 没有留档 | **新增** |
| `zipFp` 完全一致 | **更新**（同一个包原样再导：结果会是「全部未变」） |
| 包名归一后相同（去扩展名、空白、`(1)`、`副本`、`copy`、`_v2`/`-2`、`最终版`、日期串） | **更新** |
| 文件路径集合与上次的重合度（Jaccard，只算图片）≥ 0.6 | **更新** |
| 都不满足 | **新增** |

返回里写明依据（`SAME_FP` / `SAME_NAME` / `SIMILAR_PATHS` + 重合度），弹框顶上显示，并允许他手动切换。

### 2.4 服务端：LLM 结构映射（④）

**① 输入**（纯文字，不传图片 —— 快、便宜，也不用先上传）：

```
商品：脆柿子 · 类目：食品生鲜/水果
标准结构：MAIN 主图（方图，最多 7 张，第一张是封面）/ DETAIL 详情（长图，按阅读顺序，最多 10 张）
          / TEXT 商品文案（txt）/ IGNORE 不导入（资质证照、尺寸表以外的说明图、缩略图、重复、无关）
文件清单（路径 · 宽×高 · 大小）：
  01-首图/白底正面.jpg        800×800    212KB
  01-首图/细节2.jpg           800×800    188KB
  02-长图/详情_01.png         750×2400   1.1MB
  资质/检测报告.jpg           1240×1754  640KB
  文案.txt                    —          2KB   「规格：单果140g+ 净重4.5斤装…」
规则初分（供参考，可以推翻）：01-首图/* → MAIN；其余 → MAIN（无已知目录）
上次的分法（更新时才有，**这些不要动**）：…
```

**② 输出**（JSON，温度 0）：

```json
{ "files": [
  { "path": "01-首图/白底正面.jpg", "target": "MAIN", "order": 1, "cover": true,
    "role": "WHITE_BG", "reason": "首图目录·白底" },
  { "path": "资质/检测报告.jpg", "target": "IGNORE", "order": 0, "cover": false,
    "role": "CERT", "reason": "资质证照" }
] }
```

`role` 只用于弹框上的小字说明（白底 / 场景 / 细节 / 规格 / 尺寸 / 长图 / 资质 / 其他），不进库。

**③ 更新时只映射新文件**：上次留档里有的路径，去向直接沿用（包括他在弹框里手动改过的 —— AC18），
只把新路径交给 LLM，并把上次的分法当上下文。**没有新路径就不调 LLM**。同一个包反复导，分法不漂。

**④ 校验**（任何一条不过，该文件按规则兜底，`source` 标 `MIXED`）：

- 每个输入文件**恰好出现一次**；多出来的路径（模型编的）丢掉
- `target` 在枚举里；`cover` 只能有一张且必须是 MAIN
- `order` 在同一 `target` 内去重后重排成 1..n
- MAIN 超过 7、DETAIL 超过 10：**不在这里截断**，交给端上标「放不下」

**⑤ 兜底**：LLM 关闭（`shop.ai.vision.enabled=false`）、超时（`shop.goods.zip-plan.llm-timeout-seconds`，默认 8）、
JSON 解析失败 → 整包用端上带来的 `ruleHint`，`source=RULE`，弹框顶上一行小字「按目录名分的」。

**⑥ Port**：`GoodsVisionPort` 加一个默认方法 `ZipMapping mapZip(ZipTree tree)`，默认返回 `null`（= 走兜底），
网关实现走与 `extractText` 同一个模型与超时。

### 2.5 服务端：逐文件状态（⑤）

新增模式下一律 `ADDED`（`IGNORE` 的为 `IGNORED`）。更新模式按路径对上次留档：

| 情况 | 状态 | 带回 |
|---|---|---|
| 路径两边都有，`fp` 相同 | `UNCHANGED` | 上次的 `url` |
| 路径两边都有，`fp` 不同 | `UPDATED` | 上次的 `url`（要被换掉的那张） |
| 只在新包里 | `ADDED` | — |
| 只在上次留档里 | `REMOVED` | 上次的 `url` |
| 去向变了（上次 MAIN，这次 DETAIL） | 在上面状态之外加 `moved=true` | 上次的去向 |

改名的文件（`01.jpg` → `1.jpg`）会被判成一删一增 —— 这是有意的：按路径认身份最可预测，
弹框里两张缩略图并排，他看得出来。

### 2.6 端上：落位（⑥，纯函数 `planZipApply`）

服务端不知道他**此刻**页面上的图（可能刚删过、刚手动加过、还没保存），所以落位在端上做：

```ts
planZipApply(
  current: { images: string[]; cover: string; detailImages: string[] },
  plan: ZipPlan,                       // ②的回包
  limits: { main: 7; detail: 10 },
): ZipApplyPreview                     // 每个文件：要不要导、落到第几格、默认勾不勾、说明
```

规则：

1. **只动来自这个包的图**（`url` 在上次留档里的那些）；手动传的图不动、相对顺序不变（AC15）
2. `UPDATED`：原位换成新版本 —— 封面那张被更新，新版本仍是封面
3. `ADDED`：接在本包最后一张图之后；新增模式下接在现有图之后
4. `REMOVED`：腾出位置，后面的前移。被移除的若是封面，第一张顶上，弹框里写明「封面会换成 X」
5. `UNCHANGED`：什么都不做，不重传
6. **他删过的不加回**：上次留档里有、此刻页面上没有、这次又是 `UNCHANGED` → 标「你删过」，默认不导
7. 超过上限的标「放不下」，默认不导 —— 不再悄悄丢
8. 新增模式、页面上还没有封面：用 LLM 标的 `cover`，没有就用主图第一张

### 2.7 弹框（⑦）

与文字识别的参数块同一套样子（`sh-sheet` + 缩略图块，选中描边 + tint 底）：

```
┌ 更新 · 10-05 14:20 导入过的「脆柿子.zip」  改为新增 × ┐
│ 主图（导入后 6/7）                                  │
│ [图·更新] [图·新增] [图·你删过]                      │
│ 详情（导入后 9/10）                                 │
│ [图·新增] [图·移除]                                 │
│ 未变 7 张 ›                    ← 折叠，点开才列       │
│ 文字 · 文案.txt                  导入到文字识别      │
│ 不导入 2 张 ›（资质 1 · 系统文件 1）                  │
│        [取消]      [导入（新增 2 · 更新 1 · 移除 1）] │
└───────────────────────────────────────────────────┘
```

- **点一张图**：导 / 不导切换（移除那张：点了就是「保留，不移除」）
- **点图上的去向小字**：开一个小列表改去向（主图 / 详情 / 不导入），改了会写进留档，下次沿用（AC18）
- **默认勾选**：新增、更新、移除勾上；你删过、放不下、不导入不勾
- **不弹的唯一情况**：更新模式且全部 `UNCHANGED` → toast「与上次导入的相同，没有要更新的」
- 新建商品也弹：LLM 分的可能不对，导入前要能看一眼；正常情况一次点击
- 点「导入」后：只上传勾上的 `ADDED` / `UPDATED`，写进 `images` / `detailImages` / `cover`，
  拍快照进撤销，`markEntry("ZIP")`。保存前都能撤回

### 2.8 保存与留档（⑨）

保存体（以及草稿 payload）加一个可选字段：

```ts
/** 这次保存里来自压缩包的图。只在导入过压缩包的那次编辑里带；不带 = 不动留档 */
mediaImport?: {
  zipName: string;
  zipFp: string;
  items: { path: string; fp: string; target: "MAIN" | "DETAIL" | "TEXT" | "IGNORE"; order: number; url?: string }[];
};
```

- **类型、`http.ts` 映射、`save-goods-body.test.ts` 三处一起加**（0.5.32 刚修过同一个坑）
- 服务端在保存商品的同一个事务里写一行留档；草稿保存时随 payload 走，发布时落表
- `items[].url` 只保留**此刻仍在商品上**的图（他导完又手动删掉的，不进留档）

### 2.9 契约变更

| 项 | 内容 |
|---|---|
| 端点 | `POST /biz/goods/zip-plan`（权限与保存商品相同）。按 /biz 登记清单七处走 |
| 表 | `prd_goods_media_import`：`id` · `goods_no` · `entity_no` · `zip_name` · `zip_name_norm` · `zip_fp` · `items` JSON · `file_count` · `created_by` · `created_at`。迁移号实现时取当时最大号 +1；迁移 + 实体 + `schema-test.sql` 三处；数据域：带 `entity_no`，按覆盖率闸（data-scope-coverage）的要求登记或写明豁免 —— 只在 B 端按已过域的 `goods_no` 回捞，运营端不读它 |
| 保存体 | `SaveGoodsReq.mediaImport`（可选） |
| 枚举 | `ZipTarget`（MAIN/DETAIL/TEXT/IGNORE）、`ZipItemStatus`（ADDED/UPDATED/UNCHANGED/REMOVED/IGNORED）、`ZipImportMode`（NEW/UPDATE）—— 后端常量 + 端上类型 + 枚举登记表 |
| 配置 | `shop.goods.zip-plan.llm-timeout-seconds`（默认 8）、`shop.goods.zip-plan.max-files`（默认 200） |
| i18n | 弹框文案约 20 条，三语 |

### 2.10 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `b-app/src/ports/zip-import.ts` | 解压后返回清单（path/bytes/宽高/fp），垃圾文件预过滤 |
| 新增 | `packages/shared/src/ports/zip-fingerprint.ts` | 抽样指纹、包名归一（纯函数，单测） |
| 新增 | `b-app/src/pages/goods-edit/zip-apply.ts` | `planZipApply`（纯函数，单测） |
| 修改 | `b-app/src/pages/goods-edit/photos.ts` | `importFromZip` 改成：清单 → zip-plan → 弹框 → 只传要传的 |
| 修改 | `b-app/src/pages/goods-edit/index.vue` | 结果弹框 + 去向小列表 |
| 修改 | `b-app/src/api/{endpoints,requests,contract,http}.ts` | 新端点 + `mediaImport` |
| 新增 | `shop-core/.../product/service/ZipPlanService(+Impl)` | 认包 · 调 LLM · 校验兜底 · 逐文件状态 |
| 修改 | `shop-core/.../product/api/biz/BizGoodsController` | `zip-plan` 端点 |
| 修改 | `shop-core/.../product/service/impl/MerchantGoodsServiceImpl` | 保存时写留档 |
| 修改 | `shop-base/.../spi/product/GoodsVisionPort` | `mapZip` 默认方法 |
| 修改 | `shop-channel/.../ai/port/GoodsVisionGateway` | `mapZip` 实现 + prompt |
| 新增 | `V3xx__goods_media_import.sql` + `PrdGoodsMediaImport` | 新表 |

---

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **LLM 只看文件结构（路径 + 尺寸 + 大小）** | 快、便宜；不用先上传；同一清单分法稳定 | 文件名乱起（`IMG_2034.jpg`）时只能靠尺寸 | ✅ 用户定的；名字不可读时尺寸 + 规则先验够用 |
| LLM 看图片内容 | 能判「是不是这件货」 | 要先上传全部图；慢（多次视觉调用）；放不下的图白传 | ❌ 本期不做，见 §6 |
| 规则留在端上当先验与兜底 | 只有一份规则实现（`classifyZip`，已测） | 请求体多一个字段 | ✅ |
| 规则在后端再写一份 Java | 服务端自给自足 | 同一套规则两种语言，迟早分叉 | ❌ |
| 认包靠 LLM | — | 不可复现，「相同的 zip」判错了没人知道为什么 | ❌ 认包是确定性规则 |
| 留档挂在商品上（一行一次导入，items 用 JSON） | 只有「取这件商品最近一次」一种查法；登记面小 | 不能按文件跨商品查 | ✅ 没有那种查询需求 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| Windows 打的 zip 文件名是 GBK，解压后乱码 | LLM 读不懂名字；按路径认身份仍然稳定 | 解压时探测编码，乱码则 `source` 标「文件名不可读」，LLM 只按尺寸与序号分 |
| LLM 分错 | 图进错地方 | 弹框里每张可改去向；改过的写进留档、下次沿用 |
| 抽样指纹漏判 | 改过的图被当成「未变」 | 弹框可手动改成「更新」；实际只在「只改中间像素且不重新编码」时发生 |
| 两个人同时编辑同一件商品 | 留档以后保存的为准 | 沿用现有保存冲突提示（AC13 of 录入落点） |
| 包很大 | 上传慢 | 只传勾上的新增/更新；200 文件 / 100MB 上限 |

## §5 对账三 · 实现 → 需求（测试计划，实现后填输出与消融）

| AC | 测试 | 消融 |
|---|---|---|
| AC11 | `ZipPlanServiceTest#llmMapping_usedWhenValid` | 不调 LLM → 红 |
| AC12 | `#llmTimeout_fallsBackToRuleHint` · `#hallucinatedPath_dropped_missingPath_ruled` | 去掉校验 → 红 |
| AC13 | `#samePackage_byFp_byName_byPaths` · `#differentPackage_isNew` | 改阈值/删归一 → 红 |
| AC14 | `#diff_added_updated_unchanged_removed` · `#update_noNewPaths_llmNotCalled` | 去掉「只映射新路径」→ 红 |
| AC15 | `zip-apply.test.ts`：手动图不动 · 更新原位 · 封面被更新仍是封面 · 你删过不加回 · 放不下不丢 | 逐条撤 → 红 |
| AC16 | 真机：同一包改 1 张、加 1 张、删 1 张再导 → 弹框「更新 1 · 新增 1 · 移除 1 · 未变 N」 | — |
| AC17 | `save-goods-body.test.ts` 加 `mediaImport` · `MerchantGoodsServiceTest#save_writesMediaImport` | 删映射一行 → 红 |
| AC18 | `#update_keepsUserCorrectedTarget` | 不读留档 target → 红 |

## §6 分期与待确认

**本期（P1）**：§2 全部。**下一期（可选）**：看图片内容判「是不是这件货」（视觉模型，要先上传）；
规格图（每个规格一张图）——数据结构里规格没有图片字段，是另一次契约变更。

**三个默认值，我按下面的定了，不同意就改**：

1. 新包里没有了的图（`REMOVED`）**默认移除**：「更新」就是和包对齐；只动来自这个包的图
2. 他手动删过的包内图，更新时**默认不加回**
3. 弹框**总是弹**，唯一例外是「全部未变」

## §7 对账二 · 设计 → 实现（实现完再填）
