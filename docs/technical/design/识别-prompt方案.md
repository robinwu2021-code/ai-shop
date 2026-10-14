# 识别 prompt 方案（ZIP 图位匹配 · 自然语言商品参数抽取）

状态：待 review（未接线）
关联：TDD-商品快速录入（AC1/AC11/AC14）、TDD-商品录入优化5项（AC4）、设计-发布与销售地区优化（#3）
创建：2026-10-06

**统一约定**：模型输出一律是 **JSON，键名为英文，且与系统字段一一对应**（`GoodsDraft` /
`SkuDraft` / `SpecGroupDraft`，见 `b-app/src/api/contract.ts`）。后端只做校验与单位换算，
不再做「中文名 → 字段」的二次猜测。输出**只是草稿**，一律进确认页由商家逐项确认后才落表单。

---

## Prompt 1 · ZIP 目录结构 → 系统图位

### 系统图位（目标字段）

| 输出键 | 对应系统字段 | 含义 |
|---|---|---|
| `cover` | `GoodsDraft.cover` | 封面 = 主图第 1 张 |
| `images` | `GoodsDraft.images` | 主图（轮播），按位置 1..N 排好，**含封面**（images[0] == cover） |
| `detailImages` | `GoodsDraft.detailImages` | 详情长图，按展示顺序 1..N |
| `textFile` | （读出后送 Prompt 2） | 商品文字文件 |
| `ignored` | — | 不认的文件（附原因） |

上限：`images` ≤ 9（`PHOTO_LIMIT`）、`detailImages` ≤ 10（`DETAIL_IMAGE_LIMIT`），超出的进 `ignored`，原因 `over_limit`。

> **先规则、后模型**：`packages/shared/src/ports/zip-media.ts#classifyZip` 已覆盖「主图/详情/main/detail 目录 + 数字排序 + 根目录 txt」这类标准包。
> **只有规则判不出来时**（目录名不认识、无目录但文件名有暗示、多个商品混在一包）才调这个 prompt。图片内容不看，只看路径。

### Prompt

```text
You are a file-structure mapper for an e-commerce product importer.
Input: the list of file paths extracted from ONE product's zip package.
Task: assign every image to a system image slot. Output ONE JSON object only.
No explanation, no code fence, do not invent paths — every path you output must appear in the input.

Target schema:
{
  "cover": string|null,          // = images[0]
  "images": string[],            // main/carousel images, ordered by position 1..N, max 9
  "detailImages": string[],      // detail long images, ordered by display position 1..N, max 10
  "textFile": string|null,       // the product description text file
  "ignored": [{"path": string, "reason": "not_image"|"system_file"|"over_limit"|"unknown_slot"}],
  "confidence": number           // 0..1
}

Slot rules (apply in order):
1. Folder name decides the slot (case-insensitive, substring match, any language):
   - main slot  : 主图, 主图片, 首图, 轮播, 封面, 头图, 橱窗, main, cover, banner, gallery, thumb
   - detail slot: 详情, 详情页, 详情图, 长图, 描述, 介绍, 图文, detail, desc, description, content, long
   - If both a main-like and a detail-like folder exist, everything else (root images, unknown folders)
     goes to "images" unless its file name says otherwise (rule 2).
2. File name hints (when the folder gives no answer):
   - 主/封面/cover/main/首 → images ; 详情/detail/desc/长图 → detailImages
   - A file named like "主图1", "main_01", "cover" is position 1 of images.
3. Ordering inside a slot:
   - Sort by the FIRST number in the file name as an integer (2 < 10; "01" == 1).
   - Files without numbers go after numbered ones, then by name.
   - Explicit cover markers (封面/cover/首图) always become images[0].
4. No recognizable folder at all → all images go to "images" (merchant didn't bother to sort).
5. Text file: a .txt/.md/.doc at the root (or named 文案/描述/商品信息/info/readme).
   Several → pick the shortest path; others → ignored "unknown_slot".
6. Ignore: __MACOSX/, .DS_Store, Thumbs.db, ._* files → "system_file";
   non-image, non-text files → "not_image". Images = .jpg .jpeg .png .webp .gif .bmp .heic.
7. Over the limit (9 main / 10 detail): keep the first ones by order, rest → "over_limit".

If a path is ambiguous, prefer "images" over "detailImages" (a wrong carousel image is easier to
spot than a misplaced detail page), and lower "confidence".

File paths:
{{paths}}
```

### 例

输入：
```
商品A/封面.jpg
商品A/主图片/2.jpg
商品A/主图片/10.jpg
商品A/主图片/1.jpg
商品A/图文介绍/详情1.png
商品A/图文介绍/详情2.png
商品A/文案.txt
__MACOSX/商品A/._封面.jpg
```
输出：
```json
{
  "cover": "商品A/封面.jpg",
  "images": ["商品A/封面.jpg", "商品A/主图片/1.jpg", "商品A/主图片/2.jpg", "商品A/主图片/10.jpg"],
  "detailImages": ["商品A/图文介绍/详情1.png", "商品A/图文介绍/详情2.png"],
  "textFile": "商品A/文案.txt",
  "ignored": [{"path": "__MACOSX/商品A/._封面.jpg", "reason": "system_file"}],
  "confidence": 0.9
}
```

### 后端校验（不信模型的部分）
- 输出路径必须 ∈ 输入，否则丢弃该条；`images[0]` 强制等于 `cover`。
- 同一路径不得出现在两个槽；上限二次截断。
- `confidence < 0.6` → 确认页把图位标成「待确认」，让商家拖拽调整（详情图拖排已上线）。

---

## Prompt 2 · 自然语言（可多次输入）→ 商品参数清单（确认页）

### 输出字段 → 系统字段对照

| `field`（输出键） | 系统字段 | 值类型 / 单位 |
|---|---|---|
| `title` | `GoodsDraft.title` | string，≤ 30 字 |
| `subtitle` | `GoodsDraft.subtitle` | string，一句卖点 |
| `categoryHint` | （辅助选 `categoryNo`） | string，类目名，后端匹配编号 |
| `specGroups` | `GoodsDraft.specGroups[].name/options` | 规格维度与档位（进 SKU 笛卡尔积） |
| `skus.price` | `SkuDraft.price` | **分**（整数）。按 `optionValues` 指到具体 SKU |
| `skus.originPrice` | `SkuDraft.originPrice` | 分（划线价，必须 > price） |
| `skus.stock` | `SkuDraft.stock` | 整数 |
| `skus.nominalGram` | `SkuDraft.nominalGram` | 克（标称重） |
| `skus.saleUnit` | `SkuDraft.saleUnit` | 销售单位（袋/箱/斤） |
| `params` | `GoodsDraft.params[]` | `{dimNo, label}`，dimNo 取自标准维度清单 |
| `extraParams` | `GoodsDraft.params[]`（自由参数） | `{name, label}`，清单里没有的属性 |
| `fulfillments` | `GoodsDraft.fulfillments` | `EXPRESS` / `MERCHANT_DELIVERY` / `STORE_PICKUP` / `NEIGHBOR_PICKUP` |
| `courier` | （提示，配运费模板） | 快递公司名 |
| `restrictedRegions` | `GoodsDraft.restrictedRegions` | 省级 regionCode 数组 |
| `limitPerUser` | `GoodsDraft.limitPerUser` | 整数，0 = 不限 |
| `fresh.origin` | `GoodsDraft.fresh.origin` | 产地文本（生鲜） |
| `fresh.arrivalDesc` | `GoodsDraft.fresh.arrivalDesc` | 到货说明 |
| `detail` | `GoodsDraft.detail` | 图文详情正文（仅当用户明确给了一段介绍） |

### 多次输入的合并规则
每次调用都把**上一轮已确认/待确认的清单**（`{{current}}`）和**本次新输入**（`{{message}}`）一起送进去，模型只输出**变更**：
- `op: "add"` 新出现的字段；`op: "update"` 用户改口（「改成 12 元」「不对，是 5 斤装」）；
- `op: "remove"` 用户撤回（「不要限购了」「海南也能发」）；
- 没提到的字段**不输出**（不动）。后端按 `field + key` 把变更合进清单，再整体渲染确认页。

### Prompt

```text
You are a product-listing assistant for a Chinese community group-buying app.
The merchant describes a product in free-form Chinese, possibly over several messages.
Your job: extract structured product fields that map 1:1 to the system schema below,
and return ONLY the CHANGES relative to the current draft list.

Output ONE JSON object only. No explanation, no code fence. Never invent facts that are not in the text.
Leaving a field out is always acceptable; guessing is not.

Output schema:
{
  "changes": [
    {
      "op": "add" | "update" | "remove",
      "field": <one of the field names below>,
      "key": string|null,          // identifies WHICH item for list fields (see below); null for scalar fields
      "value": <typed value, see below>|null,   // null when op = "remove"
      "evidence": string,          // the exact fragment of the user's text this came from
      "confidence": number         // 0..1
    }
  ],
  "questions": string[]            // at most 2 short questions for genuinely ambiguous points; else []
}

Fields (name → value type → rules):
- title            → string. Product name incl. brand + key spec, ≤ 30 chars. Only if the text names the product.
- subtitle         → string. One selling point, ≤ 20 chars. Only if stated.
- categoryHint     → string. A category word the text implies (e.g. "水果", "粮油"). key = null.
- specGroups       → {"name": string, "options": string[]}. key = spec name.
                     Only for choices the BUYER picks between (e.g. 规格: 5斤装 / 10斤装).
                     A single fixed size is NOT a spec group — put it in params instead.
- skus.price       → integer, in FEN (1 yuan = 100). key = the option value it belongs to
                     (e.g. "5斤装"), or "default" for a single-SKU product.
                     Prices are often glued to sizes: "4.5斤装10元" = size 4.5斤, price 1000.
- skus.originPrice → integer FEN, the crossed-out price (原价/划线价/市场价). Must be > skus.price.
- skus.stock       → integer. Only if a stock number is stated (库存/现货 N).
- skus.nominalGram → integer grams of ONE sku. 1斤 = 500g, 1kg = 1000g, 1两 = 50g. key as for price.
- skus.saleUnit    → string unit the buyer orders in (袋/箱/盒/斤/个). key as for price.
- params           → {"dimNo": string, "label": string}. key = dimNo.
                     dimNo MUST come from STANDARD DIMENSIONS below.
                     ENUM dims: label MUST be one of the listed candidates, else do not output it.
                     QUANT dims: label = number + unit as written (e.g. "4.5斤"); add "grams": integer if it is a weight.
                     TEXT dims: label = the text as written.
- extraParams      → {"name": string, "label": string}. key = name.
                     Attributes stated in the text that have NO standard dimension (e.g. 单果重量 140g+).
- fulfillments     → string[], subset of ["EXPRESS","MERCHANT_DELIVERY","STORE_PICKUP","NEIGHBOR_PICKUP"].
                     快递/发货/包邮/顺丰/圆通/中通/韵达/申通/邮政/京东 → EXPRESS;
                     送货上门/配送/同城送 → MERCHANT_DELIVERY; 到店自提/门店取 → STORE_PICKUP;
                     团长/自提点/小区取货 → NEIGHBOR_PICKUP. key = null.
- courier          → string, express company name. key = null.
- restrictedRegions→ string[] of province codes the product is NOT sold/shipped to
                     (不发货/不包邮/不卖/偏远除外/除…外). Split glued names: "新疆西藏海南" = 3 provinces.
                     Use ONLY codes from PROVINCE CODES below. key = null.
                     "包邮/全国" alone does NOT mean anything is restricted.
- limitPerUser     → integer. 限购N件/每人限N份. 0 if the text says 不限购. key = null.
- fresh.origin     → string. Place of origin (产地/原产地/来自…). key = null.
- fresh.arrivalDesc→ string. Delivery-time promise (次日达/下单后3天发货). key = null.
- detail           → string. Only when the user explicitly writes a product introduction paragraph.

Disambiguation rules (most frequent mistakes — follow strictly):
1. Per-unit weight vs package weight: 单果/单个/每颗/每只 = per piece → extraParams "单果重量";
   净重/净含量/装/整箱/每份 = per package → skus.nominalGram (+ params 净含量 if that dim exists).
   Never merge the two.
2. Size + price glued together ("4.5斤装10元", "10元4.5斤", "5斤/12.8"): split into size and price.
3. Several sizes with several prices ("5斤18元，10斤32元") → one specGroups entry
   {"name":"规格","options":["5斤","10斤"]} + one skus.price per option.
4. A single size with a price → NO spec group; skus.price key "default", size goes to skus.nominalGram.
5. Corrections in later messages ("改成…", "不对是…", "再加…", "不要…") → op update/add/remove
   on the matching field+key. Do not re-emit unchanged fields.
6. Currency words: 元/块/¥/￥ all mean yuan. "9块9" = 990 fen. "两块五" = 250 fen.
7. Never output a price you are not sure belongs to this product (e.g. shipping fee 运费8元 is NOT skus.price).

STANDARD DIMENSIONS (dimNo | name | type | candidates or unit):
{{dims}}

PROVINCE CODES:
11北京 12天津 13河北 14山西 15内蒙古 21辽宁 22吉林 23黑龙江 31上海 32江苏 33浙江 34安徽
35福建 36江西 37山东 41河南 42湖北 43湖南 44广东 45广西 46海南 50重庆 51四川 52贵州
53云南 54西藏 61陕西 62甘肃 63青海 64宁夏 65新疆 71台湾 81香港 82澳门

CURRENT DRAFT (already extracted earlier, may be empty):
{{current}}

NEW MESSAGE:
{{message}}
```

`{{dims}}` 由后端按 `categoryNo` 调 `specLibrary.propsForCategory` 生成，一行一个，例：
```
SD_BRAND | 品牌 | ENUM | (open: any brand)
SD_ORIGIN | 产地 | ENUM | 本地, 国产, 进口
SD_ORIGIN_DETAIL | 原产地 | TEXT |
SD_NET_CONTENT | 净含量 | TEXT |
SD_SHELF_LIFE | 保质期 | ENUM | 12个月, 18个月, 24个月
SD_STORE_COND | 储存条件 | ENUM | 常温, 阴凉干燥, 冷藏 0~5℃, 冷冻 -18℃
SD_TASTE | 口感风味 | ENUM | 清甜, 酸甜, 脆爽, 多汁, 软糯, 鲜嫩
SD_GRADE | 等级 | ENUM | 普通, 精选, 特级, 礼品级
```
没选类目时传全部平台 PROP 维度（universal + 常用）。

### 例 1（首次输入）

`{{current}}` = `[]`，`{{message}}`：
```
规格：
单果140g+
净重4.5斤装10元
圆通快递，新疆西藏海南不发货
```
输出：
```json
{
  "changes": [
    {"op":"add","field":"skus.price","key":"default","value":1000,"evidence":"净重4.5斤装10元","confidence":0.95},
    {"op":"add","field":"skus.nominalGram","key":"default","value":2250,"evidence":"净重4.5斤","confidence":0.9},
    {"op":"add","field":"params","key":"SD_NET_CONTENT","value":{"dimNo":"SD_NET_CONTENT","label":"4.5斤"},"evidence":"净重4.5斤","confidence":0.9},
    {"op":"add","field":"extraParams","key":"单果重量","value":{"name":"单果重量","label":"140g+"},"evidence":"单果140g+","confidence":0.9},
    {"op":"add","field":"fulfillments","key":null,"value":["EXPRESS"],"evidence":"圆通快递","confidence":0.95},
    {"op":"add","field":"courier","key":null,"value":"圆通","evidence":"圆通快递","confidence":0.95},
    {"op":"add","field":"restrictedRegions","key":null,"value":["65","54","46"],"evidence":"新疆西藏海南不发货","confidence":0.95}
  ],
  "questions": ["商品名是什么？（例如：XX苹果）"]
}
```

### 例 2（追加输入，带改口）

`{{current}}` = 例 1 的结果，`{{message}}`：
```
是阿克苏冰糖心苹果，改成12.8元，海南也能发，每人限购2箱
```
输出：
```json
{
  "changes": [
    {"op":"add","field":"title","key":null,"value":"阿克苏冰糖心苹果 4.5斤装","evidence":"阿克苏冰糖心苹果","confidence":0.85},
    {"op":"add","field":"fresh.origin","key":null,"value":"新疆阿克苏","evidence":"阿克苏","confidence":0.7},
    {"op":"update","field":"skus.price","key":"default","value":1280,"evidence":"改成12.8元","confidence":0.95},
    {"op":"update","field":"restrictedRegions","key":null,"value":["65","54"],"evidence":"海南也能发","confidence":0.9},
    {"op":"add","field":"limitPerUser","key":null,"value":2,"evidence":"每人限购2箱","confidence":0.9},
    {"op":"add","field":"skus.saleUnit","key":"default","value":"箱","evidence":"限购2箱","confidence":0.6}
  ],
  "questions": []
}
```

### 后端校验（不信模型的部分）
- `field` 不在白名单 → 丢弃；`params.dimNo` 不在 `{{dims}}` → 降为 `extraParams`；ENUM label 不在候选 → 丢弃。
- `skus.price` 与规则解析器（`GoodsTextRuleParser`）冲突 → **以规则为准**，确认页标「已按原文修正」。
- `skus.originPrice ≤ skus.price` → 丢弃；`restrictedRegions` 码不在 34 省表 → 丢弃。
- `evidence` 必须是输入原文的子串，否则该条 `confidence` 降到 ≤ 0.5（防编造）。
- 金额只接受整数分；`nominalGram` > 100000 视为单位错误，丢弃。

### 确认页展示（给 #5a 确认层）
- 每条变更一行：`字段中文名 · 值 · 原文片段(evidence)`，勾选框默认：`confidence ≥ 0.8` 勾选，价格类 `< 0.9` 不勾。
- 顶部「全选 / 全不选」，底部「确认所选」；`update` 行显示「旧值 → 新值」，`remove` 行显示删除线。
- `questions` 显示在清单上方，商家回答后作为下一条 `{{message}}` 再调一次（多次输入闭环）。

---

## 待 review 的决策点
1. **单果重量**没有标准维度：先走 `extraParams`（自由参数，不聚合），还是新建 `SD_FRUIT_WEIGHT`？
2. **价格单位**：模型直接出「分」（本方案）还是出「元」由后端换算？出分可直接落 `SkuDraft.price`，但模型偶发少乘 100——已有规则价兜底与 `evidence` 校验。
3. **ZIP 何时调模型**：本方案是「规则判不出才调」；也可每包都调、与规则结果比对，分歧时提示。
4. **多次输入的上下文**：每轮带 `{{current}}` 全量清单（token 随轮数涨）；清单超过 30 项时只带字段名+值，不带 evidence。
