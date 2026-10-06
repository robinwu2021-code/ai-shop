# TDD-商品编辑页：录入落点 · 文案收敛 · 发布历史

状态：草稿（待确认）
关联：
- 原型 [商品编辑页 · 快速录入与发布历史](https://claude.ai/artifact/XtoeDXzLTob2Dc3osQcUBd)（`prototypes/goods-edit-input-first.html`，15 屏）
- [TDD-商品快速录入.md](TDD-商品快速录入.md)（AC1–14 的基座，本文是它的 AC11 落地决定）
- [TDD-商品录入优化5项.md](TDD-商品录入优化5项.md)（#5 在那里记为「协调项，交回快速录入这条线统一决定」——本文就是那个决定）
- [识别-prompt方案.md](识别-prompt方案.md)（ZIP 图位 + 自然语言参数抽取两份 prompt）

创建：2026-10-06

## 档位（三期分别声明）

| 期 | 内容 | 档 | 依据 |
|---|---|---|---|
| **A** | UI 文案收敛 | **0 → 1** | A1 只改 i18n 的**值**、键集不变 = 契约没动，档 0。A2 删 3 个键 = 动 i18n 契约，档 1 |
| **B** | 识别结果补落点 | **1** | 端上行为 + `SaveCommand` 多一个来源标记（对外 JSON 结构），不新增端点 |
| **C** | 发布历史 | **2** | 新表 `prd_goods_revision` + 两个新端点 + 不可逆决策（每次保存写一行快照） |

整体按 2 档交付（PRD 的验收标准一节已在 §0 落为 AC）。**三期可独立上线，顺序不能换**（见 §7）。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 | 期 |
|---|---|---|---|
| AC1 | 文字识别出的**限购地区**要落进商品，不能只亮一个 chip | `applyTextParse` 落 `restrictedRegions` | B |
| AC2 | 识别出的**参数**（净含量 / 单果重量…）文字路径也要自动填 | `applyTextParse` → 复用 `applyParamPicks` | B |
| AC3 | 识别出的**规格维度**要进规格卡 | `applyTextParse` → `applySpecPicks`（新，与 `applyParamPicks` 同形） | B |
| AC4 | 识别出的**重量**要落进标称重量 | `applyTextParse` → `rows[*].nominalGram` | B |
| AC5 | 自动填必须**可撤销**，且能看清这次改了哪几项 | 卡内一行「已更新 N 项 · 撤销」+ 展开复核面 | B |
| AC6 | 多规格时价格**不得**只填第一行就算完 | 单规格直落；多规格落进「统一价格」输入框不直接 apply | B |
| AC7 | 「一键识别」要说清它只认图 | 改文案 + 没有封面图时按钮本就提示，不改逻辑 | A |
| AC8 | 解释机制的句子删掉，只留后果与下一步 | §3 文案审计表 18 条 | A |
| AC9 | 文案标点全角统一，短提示句末不加句号 | §3 规则 3 | A |
| AC10 | 文案里写「去某处某处」的路径，换成按钮 | `noStoreCategory` 一条 | A |
| AC11 | 发布历史能看到：每一版何时存、谁存、怎么录的、改了哪几项、何时发布 | 新表 `prd_goods_revision` + `/biz/goods/{no}/revisions` | C |
| AC12 | 某一版能看字段级变更，并能取回 | `/biz/goods/{no}/revisions/{v}` 回两份 diff + 「以这一版建草稿」 | C |
| AC13 | 发布冲突时能看到**是谁**改了什么、哪一项会被覆盖 | 发布预览加 `overwrites[]`（依赖 AC11 的表） | C |
| AC14 | 以上三期**不得改动页面骨架** | §6 保护措施（可失败的回归清单） | A B C |

> 没有挂不上 AC 的设计条目；AC14 是约束型 AC，它的「测试」是 §6 的回归清单。

---

## §1 现状核查（在 HEAD 上实测，不凭记忆）

### 1.1 两条识别链路是分开的，叫法却混在一起

| | 触发 | 调的接口 | 填什么 |
|---|---|---|---|
| **图片识别** | 点「一键识别」按钮（`runRecognize`） | `mRecognizeGoods` + `mDescribeGoods` | 名称 · 副标题 · 类目 · 详情 · **参数**（走 `applyParamPicks`） |
| **文字识别** | **边输边识别**，停手 800ms 自动跑（`applyTextParse`） | `mParseText` | **只有** 快递 + 第一个 SKU 的售价 |

两件事都叫「识别」，按钮只管图片（没有封面图时直接提示 `recognizeNeedImg`），文字根本不用点按钮。
**文案 `quickHint` 写的「然后一键识别名称·描述·参数·价格」在教一个不存在的步骤。**

### 1.2 `parse-text` 回七项，端上只落两项

`GoodsTextParse`（`b-app/src/api/requests.ts:244`）回的字段 vs `applyTextParse` 实际用的：

| 字段 | 后端回 | 端上现在 | 缺口 |
|---|---|---|---|
| `pricesMinor` | ✓ | 落 `rows[0].priceMajor` | 多规格时只落第一行，静默 |
| `fulfillment` | ✓ | 加进 `fulfillments` | — |
| `carriers` | ✓ | 只亮 chip | 商品上没有承运商字段，**本就不该落**（文案要说清） |
| `weights` | ✓ | 只亮 chip | **不落** |
| `specs` | ✓（LLM） | — | **不落** |
| `params` | ✓（LLM） | — | **不落**（图片路径落，文字路径不落 —— 同一个后端结果，两条路两种待遇） |
| `restrictedRegions` | ✓ | — | **不落**。契约注释写着「确认层确认后落进商品 `restrictedRegions`」，而确认层没建，于是这条路断在端上 |
| `excludeRegionText` | ✓ | 亮 chip | — |

> **AC1 就是这一行。**「文字里已经有限购地区，没有更新到商品页」这个报障，根因不在后端、不在识别，
> 在于端上少写一行赋值 —— 而 chip 亮着，让它看起来像是已经生效了。

### 1.3 发布侧已经做对了，缺的只是存储

`goods-publish` 现状：服务端 `mPublishPreview` 烘焙后算差异、一行一个字段、stale 不禁用发布只换语义
（`publishStaleBtn`「已核对差异，仍要发布」）、带 `baseVersion` 发布、80018 冲突就地刷新、第三条路「放弃这份修改」。
**这些一处都不用改。** `prd_goods_draft` 只存当前一份草稿，发布不留痕 —— 这是 C 期唯一要动的东西。

---

## §2 关键决策：**不换交互**

原型 06 屏画的是「识别 → 前置确认页 → 勾选 → 填进表单」。**这版方案不采用它作为主路径**，理由：

| | 前置确认页 | 自动填 + 可撤销（采用） |
|---|---|---|
| 与 HEAD 的关系 | **推翻**已上线的「边输边识别·持续自动填」（commit 48565fc5a，用户当时明确要的交互） | 保留，只补落点 |
| 边输边识别 | 不可能共存 —— 每打几个字弹一次确认页 | 天然共存 |
| 改动面 | 新页面 + 新路由 + 新状态机，骨架必动 | 一个函数体 + 一行提示 |
| 回答「会不会改坏我填的」 | 事前问 | **事后可撤** |

**采用：自动填照旧，把「怎么知道它改了什么」和「改错了怎么退回」补上。**
原型 06 屏的确认清单不丢 —— 它降级为**可选的复核面**：点卡内那行「已更新 N 项」展开，
同一份清单、同样写明「落到哪张卡的哪一行」，但**不挡路**。原型 08/09 两屏（只给变更的弹层、
长按单字段重认）原样保留，它们本来就是事后的。

> 这一条直接回答「保留目前界面 UI，防止改坏」：**改动面从「新增一个页面」压到「一个函数 + 一行」。**

---

## §3 A 期 · UI 文案收敛

### 规则（五条，写下来是为了以后能上闸门）

1. **不解释机制，只说后果与下一步。** 商家不需要知道系统为什么这样做。
2. **不聊天腔。** 去掉「刚刚」「才会发现」「可能在别处」「继续？」这类叙述与反问。
3. **标点全角统一，短提示句末不加句号。** 分隔用 `·`，两件事之间用 `，` 或 `。`，不混半角 `,;:`。
4. **空态和 placeholder 已经说了的，正文不再说第二遍。** placeholder 只说字段名、不举例子。
5. **按钮是动词短语。** 文案里出现「去某处 → 某处」的路径，就该是一颗按钮。

### 审计表（18 条 · 省 ≈ 250 字）

| key | 现在（字数） | 改为 | 为什么 |
|---|---|---|---|
| `quickHint` | 粘贴商品文字,或点右上「导入压缩包」带入图片;然后一键识别名称·描述·参数·价格（40） | **删** | 与下方 placeholder 同屏说同一件事；且「然后一键识别」与现行为不符（文字是自动识别的） |
| `parsePh` | 粘贴商品文字，如:规格 单果140g+;净重4.5斤装10元;圆通快递,新疆西藏海南不发货（45） | 粘贴商品文字，自动识别名称、价格、规格、参数、限购地区（26） | placeholder 只说字段名 —— 例子一打字就消失，最该在的那刻看不见 |
| `recognize` | 一键识别（4） | 识别图片（4） | 它只认图（没封面图直接提示）。叫「一键识别」让人以为文字也归它管 |
| `reRecognize` | 重新识别（4） | 重识图片（4） | 同上 |
| `parseNoShipHint` | 只作提示,请到运费模板里设置,不会自动改（20） | 已填入限购地区。运费仍需在运费模板设置（19） | B 期后行为变了，文案必须跟着变；顺手把半角逗号改全角 |
| `fulfillmentRejectedHint` | 刚刚这一路已经不在本店的开通名单里了（可能在别处被关掉）。去开通后再提交，或换一条已开通的。（46） | 本店已关闭这条配送方式。去开通，或改选其他方式（21） | 「刚刚这一路」「可能在别处被关掉」是旁白；商家不需要知道是谁关的 |
| `verifyStoreHint` | 顾客到这家店核销。只能选本主体的门店 —— 手打的名字对不上，核销那天才会发现（39） | 只能选本主体的门店（9） | 真正的约束只有这半句；「核销那天才会发现」是威胁式叙述 |
| `gateMissing` | 本类目需要{s}，当前账号尚未取得该授权。可先保存草稿，补齐资质后再上架（36） | 本类目需要{s}。可先存草稿，补齐后上架（18） | 「当前账号尚未取得该授权」把「需要」说了第二遍 |
| `noStoreCategory` | 本店还没有经营类目。去「工作台 → 经营类目」添加后再建商品（30） | 本店还没有经营类目（9）+ 按钮「去添加」 | 路径写进文案 = 让人手动导航（规则 5） |
| `limitPerUserHint` | 不填为不限。按每位顾客累计计算，已取消、已退款的订单不计入（29） | 按顾客累计计算，不含已取消与已退款（17） | 「不填为不限」由 placeholder「不限」承担 |
| `restrictedHint` | 勾中的省不发货，默认全国可售。下单时按收货地址拦截。（26） | 勾中的省不发货，下单时按收货地址拦截（18） | 「默认全国可售」由空态「全国可售」承担；去掉句末句号 |
| `detailEmptyHint` | 还没写图文详情 —— 买家在详情页看不到任何介绍（24） | 买家在详情页看不到介绍（11） | 字段名就在上一行；破折号从句改直陈后果 |
| `payOfflineHint` | 买家下单时可选择当面付款；门店需已开启线下收款（23） | 买家可选当面付款。需门店已开启线下收款（19） | 两件事确实是两件，只压虚词 |
| `untranslated` | {s} 未填，将显示中文。商品名不做机器翻译（22） | {s} 未填，将显示中文（11） | 后半句解释系统实现 |
| `originPriceInvalid` | 划线价要高于售价，否则会标出「涨价」的折扣（21） | 划线价需高于售价（8） | 「否则会标出涨价的折扣」是机制解释 |
| `genDetailOverwrite` | 已有内容，生成后会被替换。继续？（16） | 已有内容将被替换（8） | 确认框自带按钮，「继续？」是第二个问号 |
| `draftBanner` + `savedDraftHint` + `saveTipOnSale` | 三条分别 14 / 26 / 11 字，说同一件事 | 横幅：`草稿 v{n} · 线上在售 v{m}`（C 期带版本号前先用「草稿未发布 · 线上在售旧版」）；保存后 toast：`已存草稿，发布后生效` | 三处说三遍，商家读完还是不确定要不要再点一次 |
| `freightEstUnweighed` | 未填重量，按首重计：买家快递运费约 {v}（21） | 按首重计，运费约 {v}（11） | 「未填重量」= 上一行那个空着的字段自己在说 |

**不改的（点名，免得下次有人顺手删）**：`invMode.sheetHint`（回答「接入有什么用」，无冗余）、
`channelsFailed`（失败 + 兜底，两件事都必要）、`fulfillmentClosedWarn`（后果句，已经是最短）、
`margin` / `imagesCount` / `restrictedSome`（纯数据格式串）。

### A 期的契约影响

- **A1（17 条改值）**：i18n 的 key 集合**一字不变** → `i18n-parity`、`check-i18n-orphan` 两道闸天然绿。档 0。
- **A2（删 `quickHint` 1 个键 + 新增 2 个键）**：动 i18n 契约 → 档 1，**单独一个提交**，三语 `zh-CN / en / ar` 同步，跑 `node scripts/check-i18n-orphan.mjs --check`。

---

## §4 B 期 · 识别结果补落点

### 4.1 `applyTextParse` 从两项补到七项

```
applyTextParse(text):
  r = mParseText(text, categoryNo)
  if !r.confidence: parsed = null; return

  patch = []                                  // 每项 {field, label, before, after}
  ① fulfillment   EXPRESS 不在列表里才加             （现状，保留）
  ② pricesMinor   单规格 → rows[0].price            （现状，保留）
                  多规格 → 只写进「统一价格」输入框，不 apply   ← AC6
  ③ weights       → rows[*].nominalGram（空着的才填）     ← AC4
  ④ params        → applyParamPicks(r.params)           ← AC2
  ⑤ specs         → applySpecPicks(r.specs)（新）        ← AC3
  ⑥ restrictedRegions → 合并进 restrictedRegions（并集，不覆盖手选） ← AC1
  ⑦ carriers / excludeRegionText → 仍只亮 chip（商品上没有这个字段）

  lastPatch = patch                           // 供撤销与复核面
  卡内显示「已更新 {n} 项 · 撤销」
```

三条贯穿的原则，与现状一致、不要改掉：
- **只填空着的，不覆盖已填的**（`applyParamPicks` 现在就是这个语义，`specs` 照抄）
- **价格永远以规则为准**，不取 LLM 的数（真金白银不交给概率）
- **不动运费模板**（跨整店，识别一件商品不该改它）

### 4.2 撤销与复核面（AC5）

- 卡内一行：`已更新 3 项 · 撤销`。「已更新 3 项」可点 → 展开复核面（原型 06 屏那份清单，
  逐行写「落到哪张卡的哪一行」+ 原文片段），**只读 + 单项撤销**，不是前置闸门。
- `撤销` = 按 `lastPatch` 逐项写回 `before`。只撤最近一次，不做多级 undo（多级 undo 要和
  草稿保存、边输边识别的防抖交织，收益不抵复杂度）。
- 防抖期内连续识别：`lastPatch` 以**最后一次**为准，前面的并入 —— 否则撤销只退回半步。

### 4.3 来源标记

`SaveCommand` 增一个 `fieldSources?: Record<string,"MANUAL"|"QUICK_TEXT"|"ZIP"|"IMAGE">`，
随草稿 payload 存。它喂三处：原型 07 屏的 tint「识别」标、09 屏长按三选一、
C 期历史里「这一版怎么录的」。**对外 JSON 多一个可选字段 → 档 1 的全部来源就是这一条。**

---

## §5 C 期 · 发布历史

### 5.1 新表

```sql
-- V3xx__goods_revision.sql（迁移号在落地当天现取，避免撞车）
CREATE TABLE prd_goods_revision (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  goods_no      VARCHAR(32)  NOT NULL COMMENT '商品号',
  merchant_no   VARCHAR(32)  NOT NULL COMMENT '商家号:带域表',
  version       INT          NOT NULL COMMENT '版本号:同一商品内自增',
  base_version  INT              NULL COMMENT '这一版基于哪一版线上',
  payload       LONGTEXT     NOT NULL COMMENT 'SaveCommand 快照 JSON',
  change_summary VARCHAR(500)    NULL COMMENT '改了哪几项:字段 label 逗号连接',
  entry_source  VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '录入方式:MANUAL/QUICK_TEXT/ZIP/IMAGE',
  status        VARCHAR(16)  NOT NULL COMMENT '状态:DRAFT/ONLINE/SUPERSEDED/REJECTED',
  reject_reason VARCHAR(255)     NULL COMMENT '驳回原因',
  saved_by      VARCHAR(32)  NOT NULL COMMENT '保存人',
  saved_at      DATETIME     NOT NULL COMMENT '保存时间',
  published_by  VARCHAR(32)      NULL COMMENT '发布人',
  published_at  DATETIME         NULL COMMENT '发布时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_goods_version (goods_no, version),
  KEY idx_goods_saved (goods_no, saved_at)
) COMMENT='商品提交历史:一次保存一行快照';
```

写入点：`saveGoods` 成功后写一行 `DRAFT`；`publishDraft` / `swapFromDraft` 成功后把该行翻
`ONLINE`、把上一个 `ONLINE` 翻 `SUPERSEDED`；审核驳回写 `REJECTED` + 原因。

### 5.2 两个新端点

| 端点 | 回 |
|---|---|
| `GET /biz/goods/{goodsNo}/revisions` | 列表：version · status · entry_source · change_summary · saved_by/at · published_by/at |
| `GET /biz/goods/{goodsNo}/revisions/{version}` | 两份 diff（对比前一版 / 对比此刻线上）+ `canFork` |
| `POST /biz/goods/{goodsNo}/revisions/{version}/fork` | 以这一版建草稿（**不直接改线上** —— 回滚也是一次发布） |

差异一律复用现成的 `PublishPreviewVO.DiffRow(field,label,before,after)`，**不另造形状**。
发布预览加 `overwrites: DiffRow[]`（AC13：此刻线上比我的基版多出来、而我这一版会覆盖掉的项）。

### 5.3 登记清单（漏一处 pre-push 才报，且挡所有人）

新表带枚举 → 按既有清单走**八处**；新端点 `/biz` → **七处**；新 ErrorCode → 四处。
迁移号落地当天现取；收尾 `) COMMENT=...;` 必须单行；禁 `uca1400`（生产是 MySQL 9.7）；
加列同时改实体与 `schema-test.sql`。

---

## §6 保护措施：怎么保证没改坏（AC14）

这一节是「保留目前界面 UI」的可执行版本。**都是能失败的检查，不是承诺。**

### 6.1 改动面白名单

| 允许改 | 一律不改 |
|---|---|
| `i18n/locale/*.ts` 的**值** | 卡的顺序与卡内标题 |
| `applyTextParse` 函数体 + 新增 `applySpecPicks` | 字段顺序、`sh-seg` 档位、`sh-notice` 的位置 |
| 快速录入卡内**新增一行**「已更新 N 项 · 撤销」 | `sh-actionbar` 的按钮数与文案结构 |
| 复核面 / 历史页 = **新页面**，不改旧页 | `goods-publish` 现有四块（横幅 / 差异 / 两按钮 / 放弃链） |

> 判据不是「我觉得没动」：改完 `git diff -- b-app/src/pages/goods-edit/index.vue` 自己读一遍，
> 模板部分的增删行**只应出现在那一行提示上**。模板有别的改动 = 越界。

### 6.2 动手前先存基线

共享工作区里「闸门红了」有两个答案。先整套跑一遍存下来，再动手：

```bash
cd /Users/robin/work/ai/ai-shop && git stash list   # 先确认没人在 stash 里
bash .githooks/pre-push </dev/null 2>&1 | tee /tmp/baseline-prepush.txt
cd b-app && npx vue-tsc --noEmit                    # c-app 同理
```

改完再跑一次对差集。**整套跑，不挑着跑**（挑着跑的那几道正好是没被影响的那几道）。

### 6.3 每条 AC 一个消融

AC1–AC6 各指名一个测试方法，撤掉对应那一行实现，**对应测试必须变红**。
AC1 的消融尤其要做：它现在的症状就是「chip 亮着、值没落」—— 一个只断言 chip 的测试会恒绿。

### 6.4 真机验

B 端店主用 App，不用 H5。A 期（文案）与 B 期（落点）各出一组真机截图：
同一页改前改后对比，确认卡序、间距、按钮位置**逐像素没动**。

---

## §7 风险与顺序

| 风险 | 为什么 | 怎么办 |
|---|---|---|
| **顺序反了会白做** | A 期有 2 条文案（`parseNoShipHint`、`parsePh`）描述的是 B 期之后的行为 | A 期先上「只删冗余、不改行为描述」的 15 条；那 2 条随 B 期一起改 |
| 自动填碰上多规格 | 现在只填 `rows[0]`，多规格商家看不出另几行没填 | AC6：多规格改成写进「统一价格」输入框，由人点「统一填入」 |
| 撤销与草稿保存打架 | 撤销只改内存，若此间自动存过草稿，撤销后不保存就退出 = 草稿里留着识别值 | 撤销后标脏，退出时走既有的「未保存」确认 |
| `restrictedRegions` 并集还是覆盖 | 商家手选了 3 个省，识别又给 2 个 | **并集**。识别只会让范围更保守，不会悄悄放开一个省 |
| C 期每次保存写一行 | 高频存草稿会把表撑大 | 同一人同一商品 5 分钟内的连续保存合并进同一 `DRAFT` 行（更新而非插入） |
| 历史保留多久 | 不定会无限长 | **待确认**：建议 ONLINE / SUPERSEDED 全留，DRAFT 只留最近 20 版 |

---

## §8 分期

| 期 | 范围 | 契约 | 可独立上线 |
|---|---|---|---|
| **A1** | 15 条文案改值（不含那 2 条行为描述） | 无 | ✓ |
| **A2** | 删 `quickHint`、新增 2 键、`noStoreCategory` 配按钮 | i18n 键集 | ✓ |
| **B** | AC1–AC6 落点 + 撤销 + 复核面 + `fieldSources` | `SaveCommand` 多一可选字段 | ✓ |
| **C** | 新表 + 三个端点 + 历史页 + `overwrites` | 新表 / 新端点 | ✓ |

**AC1 一行赋值就能修**（限购地区），它是报障的直接原因，建议从 B 期里单独拎出来先上。

---

## §9 对账二 · 设计 → 实现（实现时填）

实现完把 `git diff --stat` 的文件清单贴回这里，与 §4 / §5 / §6.1 的白名单逐行比。

## §10 对账三 · 实现 → 需求（测试，实现时填）

每条 AC 一个测试方法名 + 真实输出 + 消融结果。

## §11 偏差说明

（实现与本文不一致时写在这里，不要悄悄改代码）

- **已记一条**：原型 04 屏画的「识别结果一个字都不落表单」与 HEAD 不符 —— 文字识别实际会自动填
  快递与第一个 SKU 的售价。原型已按本文 §1.2 的落点矩阵更正。
