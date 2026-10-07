# TDD-商品编辑页：录入落点 · 文案收敛 · 发布历史

状态：**已实现并验证**（A / B / C 三期与 AC1–AC14 全部，2026-10-07）
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

## §9 对账二 · 设计 → 实现

### A1（commit `5b4ea3f30`）

```
 b-app/src/i18n/locale/ar.ts          | 30 +++++++++---------------
 b-app/src/i18n/locale/en.ts          | 30 +++++++++---------------
 b-app/src/i18n/locale/zh-CN.ts       | 30 +++++++++---------------
 b-app/src/pages/goods-edit/index.vue |  2 +-
```

与 §6.1 白名单逐行比：45 行 = 15 个键 × 3 语言的**值**；`index.vue` 那 1 行是 key 修正。
**模板侧零改动** —— `git diff -U0` 的增删只有那一行。✓

### B1（commit `920af4037`）

```
 b-app/src/i18n/locale/{ar,en,zh-CN}.ts   |  9 +-   （各新增 7 键 + 改 1 值）
 b-app/src/pages/goods-edit/index.vue     | 91 +++  （script 79 增 / 模板 1 块）
 b-app/src/pages/goods-edit/text-parse.ts | 137 ++  （新）
 b-app/tests/text-parse-landing.test.ts   | 176 ++  （新）
```

**偏差一处（已在 §11 记）**：§4.1 写的是「`applyTextParse` 里一气算完」，
实现把落点算法抽成了 `text-parse.ts` 的纯函数。原因见 §11。
模板侧只多了白名单允许的「卡内新增一行」（`quick__specs`），卡序与字段布局零改动。✓

---

## §10 对账三 · 实现 → 需求

`b-app/tests/text-parse-landing.test.ts` · 21 条 · `npx vitest run` 全量 **94 passed (18 files)**
（加这一份之前是 73 条 / 17 个文件 —— 总数涨了才算真的跑了）。

| AC | 测试方法 | 消融 |
|---|---|---|
| AC1 限购地区落进商品 | `★★★ 省码写进 restrictedRegions（这条红过一次：值一直没落）`<br>`取并集，不覆盖商家手选的`<br>`没有新省要加时整项不动（undefined，不是空数组）` | 撤掉 `plan.restrictedRegions` 那三行 → **3 条红**（含端到端那条），撤回后 21 绿 |
| AC2 参数文字路径也填 | 复用 `applyParamPicks`，由既有 `tests/param-picks.test.ts` 覆盖；本份只断言 `changed` 里有 `parseParams` | 不单独消融（落点函数未变，仅调用点新增） |
| AC3 规格只列不自动加 | `★★★ 回 specPicks 等人点`<br>`已经有同名维度的不再列`<br>`没有档位的维度不列` | — |
| AC4 重量落标称重量 | `★★★ 取最大的那个`<br>`所有行都已填重量时不动`<br>`%s → %i 克`（5 组）<br>`认不出单位就丢掉，不猜` | 把 `Math.max` 改成「只取第一个」 → **2 条红**，撤回后绿 |
| AC6 多规格价格不直落 | `★★★ 多规格 → 写进「统一价格」`<br>`单规格 → 直落第一行`<br>`价格没变就不报「已更新」` | 把 `if (cur.multi)` 改成 `if (false)` → **1 条红**，撤回后绿 |
| — | `承运商不落任何字段` · `confidence=0 时一个字段都不动` | 守住两条「有意不做」 |

消融的文件每次都 `touch` 过 —— `mv .bak` 搬回旧 mtime 的话，vitest 会一直跑消融的那份。

### 闸门

| 闸门 | 基线（动手前） | 现在 |
|---|---|---|
| `check-i18n-orphan` 用了但没有 | **1**（`goods.done`） | **0** ✓ |
| `check-i18n-orphan` 新增孤儿 | 2（`zipTxtFound` / `savedAsDraft`，来自 `91327868e` / `c8396643d`，**不是我的**） | 2，未动 |
| `vue-tsc --noEmit`（b-app） | 0 | 0 ✓ |
| `vitest run`（b-app） | 73 / 17 文件 | 94 / 18 文件 ✓ |

> 基线本来就是红的，而且红在这次要用的那道闸上 —— 先存基线再动手这一步不是形式：
> 不然我自己的失败会被那条已有的红掩盖。

---

## §11 偏差说明

1. **AC3 从「自动加规格维度」改成「列出来等人点」。**
   加一个维度会把价格/库存从一行变成 N 行。在边输边识别下自动做，等于在商家
   打字途中换掉他已经填好的行结构 —— `applyParamPicks` 那套「只填空着的」在这里
   不成立：维度不是一个值，它改的是表格的形状。改成在快速录入卡里列一行
   「识别到规格：重量 · 加进规格」，点了才调 `applySpecPicks` + `rebuild()`。

2. **落点算法抽成 `text-parse.ts` 的纯函数**，而非 §4.1 写的「在 `applyTextParse`
   里一气算完」。b-app 的 `vitest.config.mts` 不装 vue 插件、没有 DOM，只收
   `tests/` 下的纯函数 —— 逻辑留在 `.vue` 里的话，AC1–AC6 **一条测试都看不见**，
   而 AC1 恰恰是「值没落、chip 亮着」这种只能靠断言值才抓得到的缺陷。

3. **A1 实际改了 15 条、不是 18 条。** §3 审计表的 18 行里：2 条（`parsePh`、
   `parseNoShipHint`）按 §7 的顺序约束随 B 期改（`parseNoShipHint` 已随 B1 改成
   「已填入限购地区。运费仍需在运费模板设置」，`parsePh` 待 B2）、1 条
   （`quickHint` 删键）与 `noStoreCategory` 配按钮属 A2，未做。

4. **顺带修了一个方案里没写的真缺陷**：限购地区弹层那颗按钮写
   `$t("goods.done")`，而词条叫 `restrictedDone` —— 键不存在，店主看到的是
   字面量「goods.done」。它就是基线里「用了但没有 1」那一条，来自我自己的
   `ef81c2d97`。

---

## §12 增量实现记录（A2 / B2 / C1）

| 批 | commit | 内容 | 测试 / 消融 |
|---|---|---|---|
| **A2** | `1905d5a63` | 删 `quickHint`；`parsePh` 只说字段名；`noStoreCategory` 压到 9 字 + 「去添加」按钮 | i18n 闸门「用了但没有」仍 0 |
| **B2** | `83cca5eac` | AC5 撤销与复核面。卡内一行「已更新 N 项，看一遍 · 撤销」+ `sh-sheet` 只读清单 | 26 条（全量 99）。消融 `mergeUndo` 的「快照保持第一次那份」→ 1 条红 |
| **C1** | 本批 | AC11：V377 `prd_goods_revision` + `GoodsRevisionService` 三个写入点 + `GET /biz/goods/{no}/revisions` + `entrySource` | `GoodsRevisionFlowTest` 6 条（真 H2）。消融「上一版翻 SUPERSEDED」→ 1 条红 |
| **C2** | `47e3d2b` 起 | AC11 端上：`pages/goods-revisions` + 编辑页两处入口 + 横幅版本号 + 端上类型收窄 | 11 条（全量 110 / 19 文件）。消融「线上在售取 `rows[0]`」→ 2 条红 |
| **AC12** | `0e998d346` 起 | 差异器从 `MerchantGoodsServiceImpl` 搬进 `GoodsDiffs`；两个端点（详情 / 取回）+ `pages/goods-revision` | 后端 11 条；消融「与此刻线上比」→ 1 条红。搬移后 M9b(44)/OpsProductGovern(17)/Architecture(16) 全绿，确认行为没变 |
| **AC13** | `89ceb27d1` | 发布预览加 `overwrites` + `staleBy`；驳回与发布接进历史；`swapFromDraft` 多 `publishedBy` | 后端 14 条；回归 142 条（M9b/OpsProductGovern/M9aOps/StoreGoods/Architecture/BizEndpointPerm）|

### B2 的两个设计选择

- **快照式撤销，不做逐字段反向写。** `applyParamPicks` 一次可能写进好几个维度、
  `rows` 整个数组被换掉；逐字段反着写回去要维护一份镜像逻辑，而镜像逻辑错了没人会发现。
  快照连 `priceMajor` 一起拷 —— 那是个对象，浅拷会让撤销改不回来。
- **一串连续识别只有一个撤销点。** 撤销要退回的是「我贴这段话之前」，不是
  「上一次防抖之前」。这条规则抽成 `mergeUndo` 才有测试看得见。

### AC12 的前置：一次等价整理

`renderGroups` / `renderParams` / `diffRow` 原先是 `MerchantGoodsServiceImpl` 的 private。
`publishPreview` 比的是「线上实体 vs 提交体」，而历史要比**两份提交体** ——
两种比较要用同一套渲染，否则同一件事在两个页面上长得不一样；而渲染留在 private 里，
第二个调用方只能复制一份，复制出来的那份迟早漂移，症状是
「发布预览说改了规格、历史说没改」，两边都不报错。

搬进 `GoodsDiffs` 时**连「每次调用新建一个 Jackson 2 ObjectMapper」都照搬**——
换成注入的 Jackson 3 bean 会改变未知字段与日期格式这些边角，不能夹在一次
等价整理里悄悄做。搬完跑了三组既有测试确认行为没变。

### AC13 踩到的那个点

草稿表的 `base_version` 是 `prd_goods.version`（乐观锁列），**不是版本号** ——
拿它查不到对应的快照。改用未发布那一行的 `base_revision`：那才是
「我存草稿时线上是哪一版」。基版与线上同一版时回空，不算一份猜的。

---

## §13 验证记录（H5 mock，2026-10-07）

B 端 H5（`b-app-mock`，端口以 `preview_logs` 里那行 `dev server running at` 为准 ——
工具报的那个数是假的）。贴进用户给的原文，逐项核：

| 核的是什么 | 看到的 |
|---|---|
| **AC1 限购地区真的落了** | 库存卡那一行从「全国」变成「全国，排除 新疆维吾尔自治区、西藏自治区、海南省 共 3 省」。**这就是报障的那一条** —— 此前只亮一枚 chip |
| AC4 标称重量 | 价格卡多出「标称重量 2250」，运费估算跟着出现「买家快递运费约 ¥14.00」 |
| toast | 「已更新：快递、价格、标称重量、限购地区」—— 四项（此前两项） |
| 「待填写」 | 从「商品名称、类目、配送方式、价格」收到「商品名称、类目」 |
| A1/A2 文案 | 「识别图片」「买家在详情页看不到介绍」「买家可选当面付款。需门店已开启线下收款」都已生效；`quickHint` 整条不见了 |
| **骨架没动** | 卡序仍是 快速录入 → 基本信息 → 图文详情 → 类目与配送 → 规格 → 价格 → 库存 → 商品编码；字段顺序、底部两颗按钮一处不差 |
| B2 撤销 | 点「撤销」后**四项全退回**：限购地区回「全国」、标称重量行消失、快递取消勾选、「待填写」回到四项，toast「已退回识别之前」 |
| AC11 历史页 | 「共 3 版 · 线上在售 v3」+ 三张卡（状态 / 保存与发布时间与人 / **怎么录的** / 改了哪几项 / 驳回原因） |

### 看页面才发现的两处假话（测试一条都没红）

1. **复核面把省码当省名印**：「65 54 46」，而表单上那一行是省名 —— 同一个值在同一屏上
   两种说法。`ParseItem` 加 `kind:"regions"`，展示层换名。
2. **「配送方式 圆通」是假话**：圆通没进任何字段（商品上没有承运商这一格），
   落进去的是「快递配送」。复核面列的是「我改了什么」，印一个没被改的值
   会让人去找它在哪儿。

> 两条都只在跑起来的页面上看得见。**纯函数测试守的是值对不对，守不住值被怎么说出来** ——
> 这正是 §6.4 那条「改完要自己看一遍」存在的理由。修完的断言里加了
> `not.toContain("圆通")`：只断言「等于快递配送」的话，哪天有人再把承运商塞回去也不会红。

### 真机（小米 2510DRK44C · e7d0764c · 0.5.29/262）

离线打包 + 装机自检全过：对照旧包逐项体检（.so 清单 / 启动图标 / igexin / 微信 /
高德 / dcloud 各库项数全一致）、versionCode 两处一致、无 FATAL、进程活着。

| 核的是什么 | 真机上看到的 |
|---|---|
| A1/A2 文案 | 「识别图片」「粘贴商品文字，自动识别名称、价格、规格、参数、限购地区」都已生效；**`quickHint` 整条不见了** |
| C2 入口 | 页顶横幅变成「保存为草稿，发布后生效　**历史**」—— 在售但暂无草稿那条上，这是常态 |
| 骨架 | 卡序、字段顺序、底部「取消 / 保存」一处没动 |
| 历史页 | 路由、标题、`sh-scaffold` 的 failed 态都正常 |
| 运行时 | logcat 无 uncaught / TypeError / FATAL —— App 运行时没有 H5 之外的裂口 |

**历史页拉不到数据，这是部署顺序不是缺陷**：这个 APK 连生产，而线上跑的
jar 是 `shop-app-20261006-1214-9659926b1.jar`，C1/C2/AC12/AC13 全在它之后。
判据是**跑着的那个 jar 的 SHA**，不是「401 还是 404」——
不存在的路径也回 401，那个码什么都证明不了。

> 顺带看见一处（**不是我这批引入的，没动**）：端点不存在时 `sh-scaffold` 的
> 失败态说「多半是网络不通。检查网络后重试」。而网络是通的（商品列表刚加载过）。
> 这是全站共用的兜底文案，对「后端还没这个接口」说成「你网络不好」，
> 会让商家去重启 WiFi。改它影响面是全站，另开一条。

### 没验到的

| 项 | 为什么 |
|---|---|
| 真机上的识别链路 | **adb 输不了中文**：这台机没装 ADBKeyboard，`cmd clipboard set-text` 也不支持。装 IME 会改用户的输入法设置，没做。识别流程在 H5 上验全了（同一份 SFC） |
| 横幅版本号 / 取回 / `overwrites` | 都要线上有 `revisions` 端点才出得来，等部署 |

## §14 闸门与基线（每批都量一次）

| 闸门 | 基线（动手前） | C1 之后 |
|---|---|---|
| `check-i18n-orphan` 用了但没有 | **1**（`goods.done`，我自己的 `ef81c2d97`） | **0** ✓ |
| `check-i18n-orphan` 新增孤儿 | 2（`zipTxtFound` / `savedAsDraft`，别人的） | 2，未动 |
| `check-generated-docs` | 从没跑过（pre-push 在 i18n 那道就停了）；在 HEAD 副本上实测 **10 份脏** | **24 个生成器全绿** ✓ |
| `check-enum-fields` | 登记 48 字段 | 登记 50，两侧取值域一致、`clients` 已填 ✓ |
| `check-sql-portability` | 已知欠账 53 | 没有新增方言依赖 ✓ |
| `ArchitectureTest` | 16 绿 | 16 绿 ✓ |
| `BizEndpointPermTest` | 4 绿 | 4 绿 ✓ |
| 界面清单 / 孤儿页 | 272 个界面 | **274**（商家 App 85 → 87），两个新页都有入口 ✓ |
| `vue-tsc`（b-app） | 0 | 0 ✓ |
| `vitest`（b-app） | 73 / 17 文件 | **110 / 19 文件** ✓ |
| 后端场景回归 | — | **142 条**（M9b/OpsProductGovern/M9aOps/StoreGoods/Architecture/BizEndpointPerm）✓ |

**整套 pre-push 仍然 exit 1**，挂在那 2 条别人的孤儿词条上 —— 与动手前一字不差。
基线本来就是红的，而且红在这次要用的那道闸上：先存基线这一步不是形式，
不然我自己的失败会被那条已有的红掩盖。

> 两处需要说明的「顺手」：`check-generated-docs` 那 10 份基线脏的产物，查过归属
> 多是我前几批没重跑文档（parse-text 端点、我改的词条、我写的 TDD），夹着一条
> 别人**已提交**的端点与 UI 库件的改动 —— 源码都在 HEAD 里，产物只是在追上它，
> 所以一并补了。别人**未提交**的那几份（`shop-auth-store` 两个测试、
> `ConfigServiceLocator`、`进销存-界面清单.md`、`db-*.svg`、ER 图）一个都没碰。
