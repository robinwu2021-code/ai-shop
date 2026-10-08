# TDD-经营范围文字录入

状态：已实现（P1 + P2；P3 大模型兜底与语音另议）
关联需求：`docs/requirements/PRD-位置与经营范围.md`（框定服务范围）
前置：`TDD-经营范围改门店级.md`、`TDD-经营范围排除地区.md`
创建：2026-10-08

## §0 对账一 · 需求 → 设计

需求原文（店主，2026-10-08）：「b 端的经营范围增加文字录入，根据文字选择」。
方案经确认：默认**追加**到现有范围（「替换」要手动开）；先做 P1 + P2，不接大模型、不做语音。

| AC | 一句话 | 落点 |
|---|---|---|
| AC1 | 写「全国发货，新疆、西藏不发」→ 识别为「不限」+ 新疆、西藏两条省级排除 | `ScopeTextParser` 拆句判向 + 省名分词 |
| AC2 | 写「龙华区、南山区」→ 两条区级纳入；同名多处（朝阳区）→ 列候选让店主点选，不替他猜 | `POST /biz/regions/parse` 的 `ambiguous` |
| AC3 | 写「阳光花园 3 栋不送」→ 小区下的楼栋排除；小区名也能直接纳入 | P2：聚落名 + 楼栋号 |
| AC4 | 认不出的短语原样列出，不丢、不猜 | `unmatched` |
| AC5 | 识别结果先进确认表，店主勾选后写进现有清单（未保存态），走原来的预览与保存 | b-app `biz-scope-text` 弹层 |
| AC6 | 识别出「不限」时：清掉已框的纳入项（保留排除），并明说清掉了几条；只做自提的店提示「不限」不生效 | 合并逻辑 + 确认表提示 |

**孤立项**：无。

## §1 现状

- 选择器已有跨级搜索 `GET /biz/regions/search`（区划四级 + 已开通聚落 + 村，按门店坐标排同名）。本端点复用同一个
  `RegionService.search` 与 `CommunityService.all()`，**区划码一律来自我们的库**。
- 商品快速录入的「限购地区」已有「省名 → 两位码」（`Provinces.codeOfName`），但它的前缀匹配对单字会误命中（「山」→ 山西），
  这里另做省简称表、只认完整简称。
- 保存、预览、可见性都不动：识别只产出「建议的范围项」，写进端上清单后与手勾的一模一样。

## §2 方案

### 契约变更
- 端点：`POST /biz/regions/parse`，入参 `{ text, latE6?, lngE6? }`，**只读**。与 `/biz/regions/search` 同性质（只读主数据、不含任何店的数据），
  进 `BizEndpointPermTest.PUBLIC`。
- 返回 `ScopeParseResult { unlimited, items[], ambiguous[], unmatched[] }`：
  - `items`：`{ mode: INCLUDE|EXCLUDE, level, refCode, name（整条路径，与选择器一致）, phrase }`
  - `ambiguous`：`{ phrase, mode, candidates[{ level, refCode, name }] }`
  - `unmatched`：原短语
- i18n：b-app `store.text.*` 约 14 条（三语）。
- 库表 / 权限码 / 配置：无。

### 识别规则（P1 + P2，纯规则）
1. **拆句**：按 `，,；;。\n` 切成分句；**方向按分句判**——含「不送/不发/不做/不卖/不配送/不包括/不含/除了/除/排除/以外/之外/除外」即排除。
   分句内再按 `、/和/与/及/以及` 切成地名。（「新疆、西藏不发」是一个分句 → 两个地名都排除。）
2. **不限**：分句含「全国 / 不限 / 所有地区 / 全部地区」→ `unlimited=true`，该词本身不当地名。
3. **去虚词**：发货、配送、送货、包邮、都、均、可以、只、仅、全部、整个、地区、范围、的 等。
4. **认地名**（逐个）：
   - 省简称表（北京…澳门 34 个，由全称去后缀得出）精确命中 → PROVINCE；
   - 粘连的多个省（「新疆西藏」）按省简称表贪心切分；
   - 否则 `RegionService.search`：**名字相同或「短语 + 行政后缀（省/市/区/县/旗/镇/乡/街道）」相同**才算命中；
     唯一 → 命中；多个 → `ambiguous`（按坐标近排序，取前 6）；
   - 区划没有 → 聚落名（归一化后相同）：唯一 → COMMUNITY；多个 → `ambiguous`；
   - P2 楼栋：「X小区3栋 / 3幢 / 3号楼」→ 先认 X，再在它的子聚落里找同号楼栋；
   - 都没有 → `unmatched`。**不做「包含」式模糊命中**：宁可让店主手动选，也不替他猜。

### 端上（b-app）
| 动作 | 路径 | 说明 |
|---|---|---|
| 加 | `components/biz/biz-scope-text.vue` | sh-sheet：输入框 → 识别 → 确认表（逐条勾选、同名点选、认不出的列出）→ 填入 |
| 加 | `shared/scope-merge.ts` | 合并：同键去重、纳入上级收掉下级（与选择器 addArea 同口径）、不限 → 清纳入；「替换」= 只留识别结果 |
| 改 | `pages/store-scope/index.vue` | 卡头加「用文字填」入口；填入后进未保存态（预览与保存条照旧出现） |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | `ScopeTextParserTest#parses`（13 行表格）+ `ScopeTextParseFlowTest#unlimitedMinusProvinces` / `#gluedProvinces` | ✅ | 删掉「除…外」判向 → 表格红 ✅ |
| 店主原话 | 「全国发货，排除新疆西藏」「除了新疆西藏的其他区域」「深圳，山西运城，广东等」→ `ScopeTextParseFlowTest#ownerSentence1-3`（真实码 44/4403/14/1408，只补缺、只删自己补的）+ 表格 4 行 | ✅ | 去掉「其他区域」与「等」→ 表格 3 行红 ✅ |
| AC2 | `ScopeTextParseFlowTest#uniqueDistrict` / `#sameNameGivesCandidatesUnlessQualified` | ✅ | 同名改成取第一条 → 红「同名时不能挑一个塞进去」✅ |
| AC3 | `ScopeTextParseFlowTest#buildingUnderEstate` | ✅ | — |
| AC4 | `ScopeTextParseFlowTest#unmatchedKeptVerbatim`（含「单字不模糊命中」） | ✅ | — |
| AC5/AC6 | `b-app/tests/scope-merge.test.ts`（6 条：追加、同键后来者赢、父收子、不限清纳入留排除并计数、替换、不改入参） | ✅ | — |
| AC5 端上 | b-app-mock H5 实点：「全国发货，新疆、西藏不发；西湖区、外环」→ 确认表正确（不限、两条省排除、西湖区带路径、「外环」没认出、只自提警告、清掉 2 个）；填入后清单两条排除、保存条出现 | ✅ | — |

## §6 对账二 · 设计 → 实现

| 与设计的出入 | 为什么 |
|---|---|
| 「不限」+ 点名纳入同时出现时，纳入项默认**不勾** | H5 实点撞出：合并后清单里还有一条纳入，就不再是「不限」，两句话互相抵消。让店主自己决定要不要勾回 |
| 前缀拆分的上级**不递归** | 递归拆的话查询次数随长度指数涨；上级只认「省或区划同名」 |
| 「和/与/及」只在行政后缀之后才算分隔、单字虚词只从尾巴剥、「除」只剥头「外」只剥尾 | 「和平区」「发展大道」「外环街道」这类地名不能被切坏（表格里各有一行） |
| `ScopeParsedArea.mode` 用 `AreaMode` | 枚举登记闸（§D5 不许内联字面量联合） |
