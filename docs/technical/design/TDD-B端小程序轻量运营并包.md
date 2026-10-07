# TDD-B 端小程序轻量运营并包

状态：已实现（并包脚本 + 入口 + 构建验证；端到端商家登录待真机/真后端）
关联需求：本会话口头需求（2026-10-07 确认），AC 见 §0；无既有 PRD，轻量运营范围属 B 端功能子集
创建：2026-10-07 · 最后更新：2026-10-07

> **一句话**：用 c-app 同一个小程序 appid，把 B 端**四屏轻量运营**（订单 / 核销 / 上下架 / 售后）
> 以分包形式并进虹选好店小程序，入口挂在「我的」已有门店相关入口旁，仅对已绑定商家身份的用户可见。
> 重量级经营（建品、压缩包导入、盘点、活动、会员、报表、员工、门店注册）**不进小程序**，留给 b-app 原生应用。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 小程序端有「商家运营」入口，挂在 c-app「我的」已有门店/商家入口旁 | `c-app/src/pages/me/index.vue`（openShop 卡附近） |
| AC2 | 入口仅当当前用户已绑定商家身份时可见 | 由商家令牌存在与否判定（见 §2 鉴权） |
| AC3 | 运营只收四屏：订单列表 + 详情、核销、商品上下架、售后处理 | `pkg-biz/pages/{orders,order,verify,goods-list,after-sale}` |
| AC4 | 一个手机号分别绑定 C 端与商家两套身份，令牌存储互不覆盖 | pkg-biz 强制 `shb` 命名空间（见 §2 鉴权） |
| AC5 | 并包由脚本完成：构建前拷 + 改写、构建后还原，仓库不留 pkg-biz 源码 | `c-app/scripts/with-biz.mjs`（仿 `with-elec.mjs`） |
| AC6 | pkg-biz 页面 i18n 对 b-app 自己的词条解析，不与 c-app 同名词条互相覆盖 | 每页 setup 注入 vue-i18n local scope + b-app 全量 messages |
| AC7 | 发布走 c-app 现有 `release-mp.sh`，同一 appid，不新增 appid | 复用 c-app 发布链，脚本只多一步并包 |
| AC8 | App 原生能力（压缩包导入等）在小程序端自动无入口 | 现有 `ZIP_IMPORT_SUPPORTED` 已按端分叉，四屏不含建品 |

**孤立项**：
- 没落点的 AC：无。
- 挂不上 AC 的设计：无。
- **明确排除**（不是缺口，是本次范围外）：建品 / 压缩包导入服务端解压（原定方案撤回，因建品不进四屏）、
  tabBar 壳（四屏走 navigateTo，不搬 b-app 的 `switchTab` 导航壳）。

---

## §1 现状与影响面

- **b-app 是完整的第二个 App**（不是叶子页面）：86 页、2502 处 `$t`、自有 shell/i18n/store/tabBar。
  这是本方案一切复杂度的根因；四屏范围把它压到可控。
- **四屏依赖闭包很干净**（实测）：33 个 TS 文件（含 14 个 mock，生产不走）+ 仅 `sh-*` 共享组件，
  **零 `biz-*` 自定义组件**；四屏合计 188 个 i18n 调用、19 个动态键（全为前缀静态 / 裸变量型）。
- **两端命名空间本就隔离**：`VITE_APP_NS` b-app=`shb` / c-app=`shc`，故 `STORAGE.token` 天然不同
  —— 但**并包后同一构建里 `import.meta.env.VITE_APP_NS` 只有一个值**（`shc`），
  拷进去的 b-app 代码会算出 `shc_token` 和 C 端令牌撞。这是鉴权隔离要处理的点。
- 可复用：`c-app/scripts/with-elec.mjs`（拷 + 改写 + 还原三段式）、`c-app/scripts/release-mp.sh`、
  `packages/ui` 的 `sh-*` 组件（两端已共用）、`b-app/src/api/*`（真实后端 `/biz/*`）。
- 会被改到的已跑功能：**无运行时代码被改**。c-app `me/index.vue` 只**新增**一个入口卡，
  不动既有 openShop/入驻流程。`packages/ui` 不改（local-scope 注入写在 pkg-biz 拷贝里，不碰共享库）。
- 明确不受影响：b-app 原生应用（APK/iOS）、c-app 既有页面、后端（零改动）、UI 清单
  （pkg-biz 是 gitignore 的构建期产物，四屏本就在 b-app/pages.json 里已登记，c-app/pages.json 的改动构建后还原）。

---

## §2 方案

### 契约变更
- 端点：**无**（复用 b-app 既有 `/biz/*`）。
- 库表 / 字段 / 迁移号：**无**。
- 权限码：**无**（沿用 b-app 的 `biz:*` 权限码，后端已有）。
- i18n 词条：pkg-biz 侧**无新增**（b-app 既有词条整树挂进 local scope）；c-app 侧**+1**：
  `merchant.bizOps`（「商家运营」入口文案，三语齐全）。
- 配置项：并包脚本注入两个构建期 env：`VITE_WITH_BIZ=1`（「我的」出入口）、
  `VITE_BIZ_ROUTE_BASE=/pkg-biz`（分包路由前缀）。与 with-elec 同形。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `c-app/scripts/with-biz.mjs` | 并包脚本：拷四屏闭包 → 改写 → 改 pages.json → 构建 → 还原 |
| 新增 | `c-app/src/pkg-biz/`（gitignore） | 构建期产物，脚本生成，不进版本库 |
| 修改 | `c-app/package.json` | 加 `build:mp:with-biz` / `release:mp:with-biz` 脚本（仅共享文件加行） |
| 修改 | `c-app/.gitignore` | 忽略 `src/pkg-biz/` 与 `pages.json.with-biz.bak` |
| 修改 | `c-app/src/pages/me/index.vue` | `VITE_WITH_BIZ` 下新增「商家运营」入口卡，`navigateTo` 到运营首页 |
| 新增 | `c-app/src/pkg-biz/_entry/index.vue`（脚本生成） | 运营首页 + 商家登录闸：无商家令牌先走手机号 OTP 登录，再 navigateTo 四屏 |

### 并包脚本改写规则（与 with-elec 的差异）
1. 拷贝 b-app 的四屏闭包（`pages/{orders,order,verify,goods-list,after-sale}` + 其 import 闭包：
   `api/`、`stores/merchant.ts`、`stores/messages.ts`、`shared/{nav,handoff,flags,quick-dates}.ts`、
   `utils/{image,order-copy}.ts` 等）到 `src/pkg-biz/`。
2. `@/` → `@/pkg-biz/`（同 with-elec，避免解析到 c-app 同名模块）。
3. **i18n local scope 注入**：每个拷进来的**页面** `.vue` 的 `<script setup>` 顶部插一行
   `useI18n({ messages: BIZ_MESSAGES, useScope: "local" })`（`BIZ_MESSAGES` 由脚本从
   `b-app/src/i18n/locale/*` 生成一份 `pkg-biz/_i18n.ts`）。整页对 b-app 词条解析，
   子 `sh-*` 组件走全局 c-app scope（它们只用通用词条，不冲突）。**不改写任何 `$t` 键**
   —— 动态键 / 三元 / 裸变量全部原样可用。
4. **鉴权命名空间隔离**：把 pkg-biz 内对 `@shared/utils/constants` 的 `STORAGE` 引用，
   改指脚本生成的 `pkg-biz/_shared/constants.ts`，其中 `NS` 强制 `shb`（而非构建期 `shc`）
   —— 商家令牌落 `shbr_token`，与 C 端 `shcr_token` 天然分开，满足「一手机号两身份」。
5. `src/pages.json` 临时加分包 `{ root: "pkg-biz", pages: [...] }`，构建完从 `.bak` 还原。

### 关键接口 / 数据流
```
c-app「我的」(VITE_WITH_BIZ) ──navigateTo──▶ /pkg-biz/_entry
  _entry: 读 shb 命名空间令牌？
    无 → 手机号 + OTP 登录（b-app loginWithTicket / login，令牌落 shb NS）
    有 → 列四屏入口 ──navigateTo──▶ /pkg-biz/pages/{orders|verify|goods-list|after-sale}
四屏内部跳转：原有 navigateTo（order 详情等），分包内可用；无 switchTab。
```

## §3 选型（2 档）

| 维度 | 方案 A | 方案 B | 结论 |
|---|---|---|---|
| 发布形态 | **并进 c-app 同 appid 作分包** | 独立 appid 独立发布 | 采用 A（用户定：一 appid、轻量运营）；重量留原生 app |
| i18n 隔离 | **per-page local scope + b-app 全量 messages** | 全量词条加 `biz.` 前缀 + 机械改写 | 采用 A：零键改写，动态键 / 裸变量天然成立；B 对 19 个动态 / 裸变量键不可靠 |
| 令牌隔离 | **pkg-biz 强制 shb NS 常量** | 运行时切换 NS | 采用 A：NS 是构建期常量，一包一值；给 pkg-biz 独立常量最简、无运行时分支 |

不可逆决策（同 appid 并包 vs 独立 appid）另记：本会话已拍板 A，元器件 2026-10-03 走的是独立 appid，
本端反向选择的理由是「轻量运营 + 复用 C 端会话入口」，范围锁死四屏以控住单运行时双 App 的代价。

## §4 风险（2 档）

| 风险 | 影响 | 缓解 |
|---|---|---|
| local scope 下 `sh-*` 子组件用到 b-app 专属词条 | 子组件内词条显示键名 | 四屏只用通用 `sh-*`，实测无 `biz-*`；构建后抽查页面渲染 |
| 并包主包体积超限（c 256K + b 612K 压缩） | 上传被拒 | 四屏闭包远小于 86 页；pkg-biz 走**分包**不计主包；构建后核分包体积 |
| 共享工作区：脚本从当前目录构建会带入他人未提交改动 | 体验版混入脏改动 | 同 with-elec：release 只在干净 HEAD 副本跑 |
| 商家登录态与 C 端并存导致 UI 串味 | 两端身份混淆 | NS 隔离令牌 + 入口仅在有商家令牌时亮；登录闸独立 |
| 真实 `/biz/*` 鉴权需真商家账号 | 本机验不到端到端 | 登录闸 + 四屏渲染本机可验；端到端留部署后用运营/店主账号点（见记忆 ops-endpoints-need-ops-account） |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 / 验证 | 跑过 | 消融 |
|---|---|---|---|
| AC3/AC5 | `node scripts/with-biz.mjs build` 成功；`app.json` 含 `pkg-biz` 分包 8 页，分包 648K（独立于主包 2M 限额） | ✅ exit 0 · Build complete | 修 bug 前传字符串 → vite 配置阶段 path 报错（已证分包确实被读） |
| AC6 | 6 个 pkg-biz 页 `.vue` 均注入 `useScope: "local"` + `__BIZ_MESSAGES`；产物 `_i18n.js` 打包 b-app 全量词条 | ✅ grep 6/6 | 去掉 rewrite 的那段 → 注入计数 0 |
| AC4 | `_shared/constants.ts` NS `shc→shb`；`_shared/http-client.ts` 与 `api/http.ts` 都指向影子 | ✅ grep 命中 | 不加 `@shared/utils/constants` 改写 → 仍指 shc（与 C 端同 key）|
| AC1/AC2 | `me/index.vue` 在 `VITE_WITH_BIZ` 下出「商家运营」卡，跳 `/pkg-biz/_entry/index`；产物 `pages/me/index.js` 引用 `merchant.bizOps` + 该路由；身份闸在 `_entry` | ✅ grep 命中；vue-tsc 绿 | `withBiz = VITE_WITH_BIZ==="1"`，普通构建 env 缺省 → 卡不渲染 |
| AC6(撞词) | c-app `merchant.bizOps` 三语齐全；`check-i18n-orphan` 0 缺失、无新增孤儿 | ✅ exit 0 | — |
| AC8 | 四屏不含建品；`ZIP_IMPORT_SUPPORTED` 非 APP 恒 false，小程序端无压缩包入口 | ✅ 四屏不含 goods-edit | — |

**本机验不到（如实记）**：pkg-biz 页面的**运行时渲染**（local scope 是否真的让整页对 b-app 词条解析）、
**商家手机号 OTP 登录 + `/biz/*` 真实鉴权**。两者要真机预览 + 真后端 + 真商家账号
（见记忆 `ops-endpoints-need-ops-account`、`mp-preview-on-device`）。本机验到的是：改写正确、构建通过、产物接线正确。

## §6 对账二 · 设计 → 实现（实现完填）

```
 c-app/.gitignore               |  3 +++   # 忽略 pkg-biz 构建中间产物
 c-app/package.json             |  2 ++    # build:mp:with-biz / release:mp:with-biz
 c-app/src/env.d.ts             |  4 ++++  # VITE_WITH_BIZ / VITE_BIZ_ROUTE_BASE 声明
 c-app/src/i18n/locale/{zh-CN,en,ar}.ts |  3 +++  # merchant.bizOps 三语
 c-app/src/pages/me/index.vue   | 19 +++++  # 商家运营入口卡 + gotoBizOps
 c-app/scripts/with-biz.mjs     | 299 行（新增）  # 并包脚本
 docs/.../TDD-B端小程序轻量运营并包.md       | 131 行（新增）
```

| 差异 | 说明 |
|---|---|
| TDD 列了、实际没动的：`packages/ui` | 本来担心要改 `sh-tabbar`（switchTab）；四屏不走 tab 壳，`switchTab→reLaunch` 在拷贝里改，共享库零改动 ✅ |
| TDD 没列、实际加了：`with-biz.mjs` 的 `prepare` 调试子命令 | 只 prepare 不构建不还原，方便手工看编译报错；文档注明「跑完记得 restore」 |
| 范围内但本次未做：`_entry` 的商家登录 UI 直接复用 b-app `login` 页 | `_entry` 无令牌时 `reLaunch` 到 pkg-biz 的 login 页，不另造登录表单 |
