# TDD-商家工作台入口两列

状态：已实现
关联需求：用户 2026-09-28「首页的[入口]如果太多，考虑减少字体，用两列的方式排列，同时优化文案，限制四个字以内」
（澄清：指 b-app 工作台的「规格管理、公告管理等功能入口菜单」；「所有的标题」四字以内）
创建：2026-09-28

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 功能入口两列排 | `b-app/src/pages/home/index.vue`：七个入口收成一个 `entries` 列表，`.sh-wrap` 两列等宽格 |
| AC2 | 字小一号 | 入口标题 `txt-title` → `txt-strong`；「核销分拣」卡标题同 |
| AC3 | 标题四字以内，去掉说明行 | 三份词条：`home.scopeEntry` 等改短；删 `catalogEntryHint` / `specsEntryHint` / `skuIdentityEntryHint`。公告保留一行现状（挂没挂、哪天到期），那不是说明 |

**孤立项**：待办格子 `.tiles__cell` 补 `box-sizing: border-box` —— 设计是三列，内容盒把内边距加在 33% 之外，一直被挤成两列。同一类问题，顺手修。

## §1 契约变更

i18n（b-app 三语）：

| 键 | zh-CN 旧 → 新 |
|---|---|
| `home.scopeEntry` | 经营范围与送货 → 经营范围 |
| `home.noticeEntry` | 公告 → 店铺公告 |
| `home.specsEntry` | 商品规格与参数 → 商品规格 |
| `home.skuIdentityEntry` | 商品编码导入导出 → 商品编码 |
| `home.fulfillEntry` | 核销与分拣 → 核销分拣 |
| `home.catalogEntryHint` / `specsEntryHint` / `skuIdentityEntryHint` | 删除 |

en / ar 同步改短（英文做不到四字，取一个词）。端点、库表、权限码不动；每格仍按原权限裁剪。

## §2 模块设计

| 动作 | 路径 |
|---|---|
| 修改 | `b-app/src/pages/home/index.vue` |
| 修改 | `b-app/src/i18n/locale/{zh-CN,en,ar}.ts` |

## §5 对账三 · 实现 → 需求

纯界面，无单测。验证：b-app H5 mock（375 宽）截图，入口两列、七格；待办格子三列；最后一格不撑满整行。
`vue-tsc --noEmit` 通过；pre-push 整套（孤儿词条、页面规范）。
