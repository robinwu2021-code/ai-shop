# TDD-商品快速录入（压缩包导图 + 文字识别）

状态：草稿
关联需求：docs/requirements/PRD-商品快速录入-压缩包与文字识别.md
创建：2026-10-05 · 最后更新：2026-10-05（补 AC11–14：确认交互/物流落参/图像识别单批量）
档位：2（新端点 + 后端 LLM/规则 + 端上原生解压能力 + 契约）

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 | 期 |
|---|---|---|---|
| AC1 | 压缩包主图/详情按文件名数字各就各位 | `ports/zip-import`（端）+ `sortByNumber`/`classify` 纯函数 | P1 |
| AC2 | 只有图无 txt → 只导图 | `photos.ts#importFromZip` | P1 |
| AC3 | txt 回填输入框，非空弹覆盖/追加/取消 | `goods-edit` 文字区 + `confirm` | P1 |
| AC4 | 图逐张走现有上传，非图挡下，超限截断 | 复用 `mUploadImage`（`MediaUploadService`） | P1 |
| AC5 | 价格/快递/区域由规则稳定抽（不依赖 LLM） | `GoodsTextRuleParser`（shop-core） | P1 |
| AC6 | LLM 挂了仍返回规则结果 | `BizGoodsController#parseText` 合并逻辑 | P2 |
| AC7 | 识别结果弹框逐项确认，默认不落 | `goods-edit` 的 `sh-sheet` 确认层 | P1 |
| AC8 | 价格可改、低置信默认不勾 | 同上 | P1 |
| AC9 | 不发货区域只跳转不写字段 | 确认层 → 跳运费模板 | P1 |
| AC10 | 文字输入框不入库 | 端上 ref，不进 `GoodsDraft` | P1 |
| AC11 | 识别出的参数：可逐项点选确认 · 一键确认全部 · 只落勾选项（部分确认） | 确认层（`sh-sheet`）每项带勾选 + 顶部「全选/全不选」+ 底部「确认所选」→ `applyParamPicks` 只落勾选项 | P1 |
| AC12 | 已落参数可在编辑/设置里再调整（改值、删项），不是识别一次定死 | `goods-edit` 参数区对已落项可编辑；识别只是来源之一，落库前后都可改 | P1 |
| AC13 | 识别到的物流信息确认后**写入真实物流参数**（发货方式/默认快递/重量/运费模板）；**经营范围 / 不发货区域仍只跳转不自动改**（守 AC9） | 确认层按类分流：发货方式/重量→`mSaveStoreFulfillment`+`mSaveStore`(ship_*)；运费→运费模板；区域类→跳转页由人改 | P1/P2 |
| AC14 | 从图识别名称/描述/参数：**单图即时识别并更新** · **压缩包多商品批量识别并逐个更新** | 单：复用 `/biz/goods/recognize`+`/describe`（已有）；批量：对压缩包每个商品目录分别识别，结果各进确认层，支持「逐个确认」或「批量一键确认」 | P2 |

**孤立项**：无 AC 没落点；无落点挂不上 AC。

> **识别要覆盖的参数集**（接《商品展示字段对标淘宝/拼多多》缺口分析）：AC11 识别出的 `params`
> 应尽量覆盖——**预包装食品合规组**：品牌 · 净含量 · 配料 · 厂名/厂址 · 生产许可证(SC) · 执行标准(GB) · 保质期(`SD_SHELF_LIFE`) · 储存条件(`SD_STORE_COND`)；
> **生鲜体验组**：产地(`SD_ORIGIN`) · 口感/甜度(`SD_TASTE`) · 等级/规格 · 净重/件装 · 产季。
> 其中品牌/净含量/配料/厂名厂址/SC/执行标准/等级/净重/产季**当前无 PROP 维度，需另开维度任务新建并绑类目**；
> 本 TDD 只负责「识别→确认→落到已有/新建维度」，维度本身的新建是前置依赖。

## §1 现状与影响面

- **图上传**：`photos.ts` 的 `addImages`/`addDetailImages` 逐张 `pickImages`→`api.mUploadImage`。导入复用这一条，只多一个「来源=解压的临时路径」。
- **AI 识别**：`BizGoodsController`（`/biz/goods/recognize`、`/biz/goods/describe`）→ `GoodsVisionPort`（spi，实现 `GoodsVisionGateway`）。文字解析在同一个 controller 加 `/biz/goods/parse-text`。
- **参数落表单**：`goods-edit#applyParamPicks` 已有，确认层复用它。
- **会被改到**：`BizGoodsController`（加端点）、`photos.ts`（加导入）、`goods-edit`（加文字区+确认层）、契约四处 + requests。
- **明确不受影响**：`/biz/upload/image` 与 `MediaUploadService`（零改动）；运费模板（只跳转不写）；商品表结构（不加字段）。

## §2 方案

### 契约变更
- 端点：`POST /biz/goods/parse-text`（body `{text, categoryNo?}` → `GoodsTextParseVO`）。/biz 七处登记 + `RESPONSE_TYPES` + 生成产物。
- 权限码：复用 `BizPerms.GOODS`（与 recognize/describe 同）。
- 库表：**无**。
- i18n：导入入口、确认层、覆盖/追加提示的词条（三语）。
- 配置：无。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-core/.../product/service/GoodsTextRuleParser.java` | **纯逻辑**正则：价格/快递/区域/重量。不碰 AI、不碰库，可单测 |
| 新增 | `shop-core/.../product/api/biz/dto`（或并入 controller 的 record） | `GoodsTextParseVO` |
| 修改 | `shop-core/.../product/api/biz/BizGoodsController.java` | `+parseText`：P1 只调规则；P2 合并 LLM |
| 修改 | `shop-base/.../spi/product/GoodsVisionPort.java` | `+parseText(...)`（P2 才接实现） |
| 新增 | `b-app/src/ports/zip-import.ts` | `#ifdef APP-PLUS` 的 `plus.zip` 解压；纯函数 `sortByNumber`/`classify` 独立导出可测 |
| 修改 | `b-app/src/pages/goods-edit/photos.ts` | `+importFromZip()` |
| 修改 | `b-app/src/pages/goods-edit/index.vue` | 导入入口 + 文字输入区 + `sh-sheet` 确认层 |
| 修改 | `b-app/src/api/{contract,http,endpoints,requests}.ts` | 契约四处 |
| 修改 | `b-app/.../goods-edit` 确认层 | AC11：每项勾选 + 全选/全不选 + 「确认所选」；只落勾选项 |
| 修改 | `b-app/.../goods-edit` 参数区 | AC12：已落参数可改值/删项（识别只是来源之一） |
| 修改 | 确认层「物流」分组 | AC13：发货方式/重量 → `mSaveStoreFulfillment`+`mSaveStore(ship_*)`；运费 → 运费模板；**区域类仍跳转不写** |
| 新增 | 批量识别编排（端上） | AC14：压缩包每个商品目录分别调识别，结果进各自确认层 + 「批量一键确认」 |

### 关键接口
```ts
// zip-import.ts — 纯函数单独导出（可测），解压本身要真机
export function classify(paths: string[]): { main: string[]; detail: string[]; txt?: string };
export function sortByNumber(names: string[]): string[];   // 2 < 10

// 契约
interface GoodsTextParseVO {
  params?:  { dimNo; name; label; source: "llm"|"rule"; confidence: number }[];
  specs?:   { name; options: string[] }[];
  skus?:    { optionValues: string[]; priceYuan: number; source: string }[];
  fulfillment?: string[];          // "EXPRESS" …
  excludeRegionText?: string;      // 只回文本
  confidence: number;
}
```
```java
// GoodsTextRuleParser — 确定性字段，永不丢
record RuleHit(List<SkuPrice> skus, List<String> fulfillment,
               String excludeRegionText, List<WeightSpec> weights) {}
RuleHit parse(String text);
```

## §3 选型（档位 2）

| 决策 | 选 | 弃 | 理由 |
|---|---|---|---|
| 解压在哪 | **端上**（App `plus.zip`） | 后端解压 | 图本就在端上选、逐张传；上传到后端再解压要先把整个 zip 传上去，白费一次大流量 |
| 规则在哪 | **后端** | 端上 | 与 LLM 同次返回合并，端上不维护两套正则；且后端能单测、能上棘轮 |
| 规则 vs LLM | 规则抽确定字段 + LLM 归类，**冲突规则压 LLM** | 全交 LLM | 价格=真金白银，不交给概率；LLM 挂了确定字段也不丢 |
| 落表单时机 | **商家确认后** | 自动填 | 识别是草稿，错填比不填更糟（商家不易察觉） |

不可逆决策（解压放端上、规则放后端）记入 ADR 视需要；一期先在本 §3 定。

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| `plus.zip` 只在真机 App 可跑 | 本机/模拟器验不了解压 | 纯函数（排序/分类/正则）单测覆盖；解压集成真机 adb 验 |
| LLM 把价格读错 | 商家按错价卖 | 价格以规则正则为准，LLM 不得改写；确认层强制过目 |
| 「不发货区域」误伤整店 | 一句话改运费模板波及所有商品 | 只回文本 + 跳转，绝不自动写 |
| 大文本 body 调 LLM | HTTP/2 大包挂起 | 锁 HTTP/1.1（见记忆 java-httpclient-http2-large-body） |

## §5 对账三 · 实现 → 需求（测试，实现时填）

| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | `ZipImportTest#sortByNumber` `#classify`（vitest） | | 排序改字符串序 → 红 |
| AC4 | 复用 `MediaUploadFlowTest` + 超限截断用例 | | |
| AC5 | `GoodsTextRuleParserTest#价格` `#快递` `#不发货区域` `#重量` | | 去掉某条正则 → 对应红 |
| AC6 | `BizGoodsParseTextTest#LLM不可用退回规则` | | LLM 桩抛异常，断言规则字段仍在 |
| AC9 | `BizGoodsParseTextTest#不发货区域不落字段` + 端上确认层用例 | | |
| AC11 | 端上确认层用例：全选/部分/全不选 → 只落勾选项（断言 `applyParamPicks` 入参） | | 去掉「只落勾选」改成全落 → 红 |
| AC13 | 端上：物流项确认 → 调 `mSaveStoreFulfillment`/`mSaveStore`；**区域类不调写接口只跳转** | | 把区域类接上写接口 → 红（守 AC9 的分流） |
| AC14 | 批量：多商品目录 → 多次识别 + 批量确认落各自草稿 | | 单商品/多商品路径分别覆盖 |

消融每条必做：撤实现 → 对应测试变红。

## §6 对账二 · 设计 → 实现

（实现完贴 `git diff --stat`，与 §2 逐行比）

## §7 偏差说明

（实现中与本设计不一致处写这里，先改文档再改代码）
