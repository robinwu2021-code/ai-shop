# TDD-元器件 · 小程序独立工程与目录结构

> 2026-09-30 · 状态：**已实现**（小程序 16 页；运营端菜单是下一批，见 §2.3）
> 档位：2（新前端工程 · 跨端 · 取代既有方案的一处决定）
> 依据：[原型 elec-rfq](../../prototypes/elec-rfq.html) e01–e18 ·
> [TDD-元器件-前端独立与通知矩阵](./TDD-元器件-前端独立与通知矩阵.md) §1 / §2.2 / §6 ·
> [TDD-元器件-运营端接口](./TDD-元器件-运营端接口.md)（运营端后端，另一批，已实现）
> **取代**：「前端独立与通知矩阵」§1.2「测试阶段并进 c-app 打包」—— 这次定为**小程序也独立**，不走软链并包

---

## §0 对账一 · 需求 → 设计

需求原话：元器件用独立的服务、独立的目录（类似 pay）；小程序也独立；与 ai-shop 共用同一个 ops-web，
整个挂在「电子元器件」根菜单下。先整理目录结构（含运营端菜单项），再开发小程序页面。

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 后端独立服务、独立目录，与 pay 同构 | `backend/elec/{elec-api,elec-core,elec-svc}`（**已有**，本批不动）|
| AC2 | 小程序独立：自己的工程、自己的 appid、自己的构建 | `elec-app/`（uni-app + Vue3 + TS，与 c-app 同栈）· `elec-app/src/manifest.json` |
| AC3 | 与商城共用的只有组件库与类型 | `packages/ui`（sh-*）· `packages/shared/src/types/elec.ts` · 登录借主系统 `/mp/user/*` |
| AC4 | 运营端同一个 ops-web，「元器件」一个根菜单、四个子项 | §2.3 菜单树 · 权限码沿用运营端接口 TDD 的六个 —— **下一批落地** |
| AC5 | 买家：搜料号 / 相近 / 详情 / 批量查 / 询价 / 我的询价 / 询价详情 / 接受报价 | `pages/{home,search,part,lookup,rfq-create,rfqs,rfq}`（e01–e11）|
| AC6 | 买家按行选供应商的报价（匿名 A/B/C） | `pages/rfq` 的「选这条」→ `POST /elec/c/rfq/{no}/line/{n}/accept` |
| AC7 | 供应商：成为供应商 / 工作台两态 / 上传 · 列映射 / 上架前确认 / 我的库存 / 资料 | `pages/{supplier-join,supplier,stock-upload,stock-preview,stocks,supplier-profile}`（e12–e18）|
| AC8 | 供应商：看派来的求购、报价或说没货 | `pages/{dispatches,dispatch}`（**原型里没有这两屏**，后端 9af209fc9 已有）|
| AC9 | 登录与手机号：询价、成为供应商都要手机号 | `pages/login`（两段：微信登录 → 绑手机号）· `shared/auth.ts#ensurePhone` |
| AC10 | 订阅授权放在产生结果的动作上（一次只够一条） | `shared/subscribe.ts`：提交询价 / 提交报价 / 打开求购 |
| AC11 | 界面清单、原型登记、闸门都认得这一端 | `gen-ui-catalog.py` · `gen-proto-index.py` · `prototypes/registry.json` · `.githooks/pre-push` |

**孤立项**：
- 没落点的 AC：AC4 只到设计（§2.3），代码在下一批 —— 用户这次要的开发对象是小程序
- 挂不上 AC 的设计：无

---

## §1 现状与影响面

- **后端**：`backend/elec` 早已按 pay 的方式独立（聚合 pom、三模块、8085、自己的库 `ai_shop_elec`、
  nginx `location ^~ /elec/ → 8085`）。买家 `/elec/c/**`、供应商 `/elec/b/**` 的接口齐全；
  运营端 `/elec/ops/**` 由另一个会话在本批同时补齐（eff0f9c84）。**本批一行后端都不改。**
- **前端**：此前一行都没有。原型登记表把这 13 屏挂在 `c-app` 的 `pkg-elec/*` 下（按旧方案的分包路线）。
- **可直接复用**：`@shared/net/http-client`（请求、上传、401 处理）· `@shared/ports/{auth,push,persist}` ·
  `@ai-shop/ui` 的 sh-* 与 i18n 引擎 · c-app 的登录态写法（`stores/user.ts`）
- **会被改到的**：`scripts/link-ui.mjs`（多链一端）· `scripts/gen-ui-catalog.py` / `gen-proto-index.py`（多认一端）·
  `.githooks/pre-push`（vue-tsc 与单测各多一端）· `prototypes/registry.json`（13 屏的端与路由）
- **明确不受影响的**：c-app / b-app 的任何页面与构建；根 `package.json` 与 lockfile（elec-app **不进** npm workspaces）

---

## §2 方案

### 2.1 目录结构

```
backend/elec/                      独立服务（已有）
  elec-api/                        与主系统的内部契约，零依赖
  elec-core/                       实体、Mapper、服务、控制器（/elec/c · /elec/b · /elec/ops）、迁移
  elec-svc/                        独立进程 8085
elec-app/                          独立小程序（本批新建）
  src/pages/                       16 页，见 2.2
  src/components/el-part-card.vue  料号卡片（搜索、批量查、详情共用）
  src/api/index.ts                 全部接口：/elec/** 与主系统 /mp/user/*
  src/shared/                      routes · format（E6 金额与取值域文案）· auth · subscribe · draft · batch · file · rfq · dispatch · recent
  src/stores/user.ts               登录态（主系统令牌）
  src/i18n/                        只有 zh-CN（组件库要 i18n 实例；页面标题走 title-key）
  tests/format.test.ts             金额换算单测
  vite.config.mts                  代理 /elec → 8085、/mp → 8081（.env.local 可改）
packages/shared/src/types/elec.ts  前后端类型（本批补齐派单 / 报价 / 按行选 / 货况包装）
ops-web/app/elec/                  运营端「元器件」（下一批，见 2.3）
```

### 2.2 小程序页面

| 路由 | 原型 | 接口 | 要身份 |
|---|---|---|---|
| `pages/home/index` 元器件 | e01 | `GET /elec/c/rfq` · `GET /elec/b/supplier`（只为提示） | 否 |
| `pages/search/index` 搜索结果 | e02 e03 | `GET /elec/c/part` | 否 |
| `pages/part/index` 料号详情 | e04 | `GET /elec/c/part/{partNo}` | 否 |
| `pages/lookup/index` 批量查 | e06 | `GET /elec/c/part/lookup` | 否 |
| `pages/rfq-create/index` 询价 | e05 | `POST /elec/c/rfq` | 手机号 |
| `pages/rfqs/index` 我的询价 | e07 | `GET /elec/c/rfq` | 登录 |
| `pages/rfq/index` 询价详情 | e08 e10 e11 | `GET /elec/c/rfq/{no}` · `POST …/accept` · `POST …/line/{n}/accept` | 登录 |
| `pages/supplier-join/index` 成为供应商 | e12 | `POST /elec/b/supplier` | 手机号 |
| `pages/supplier/index` 供应商工作台 | e13 e14 | `GET /elec/b/supplier` · `POST /elec/b/stock/renew` | 登录 |
| `pages/stock-upload/index` 上传库存 | e15 | `POST /elec/b/stock/upload` · `…/batch/{no}/remap` | 登录 |
| `pages/stock-preview/index` 上架前确认 | e16 | `POST /elec/b/stock/batch/{no}/apply` | 登录 |
| `pages/stocks/index` 我的库存 | e17 | `GET /elec/b/stock` · `POST /elec/b/stock/renew` | 登录 |
| `pages/supplier-profile/index` 供应商资料 | e18 | `PUT /elec/b/supplier` | 登录 |
| `pages/dispatches/index` 求购 | — | `GET /elec/b/rfq?status=` | 登录 |
| `pages/dispatch/index` 求购详情 | — | `GET /elec/b/rfq/{no}` · `…/quote` · `…/decline` | 登录 |
| `pages/login/index` 登录 | — | 主系统 `/mp/user/{login,profile,otp/send,phone/bind,phone/wx,phone/capable}` | — |

**登录模型**（查代码得出，改了原先的设想）：主系统 C 端**没有匿名的验证码登录** ——
`/mp/user/otp/send` 要已有会话（`MpUserController#sendOtp` 的注释写了为什么）。所以：
小程序打开即静默登录（`WX_MINI`）→ 要手机号的那一步再绑号（能一键就一键，否则验证码）。
H5 只是调试形态，在那里登不进来是预期的。

### 2.3 运营端菜单（下一批）

同一个 ops-web，一个根菜单、四个子项，子页走 `?tab=` 深链（静态导出，没有动态路由）：

```
元器件            key: elec · icon: Cpu · href: /elec · modules: ["elec"]
  ├ 询报价        /elec?tab=rfq       elec:rfq:read · 录入报价/关单/指派 elec:rfq:quote
  ├ 供应商        /elec?tab=supplier  elec:supplier:read · 暂停/恢复/改资料 elec:supplier:manage
  ├ 料号与库存    /elec?tab=part      elec:part:read
  └ 基础数据      /elec?tab=base      elec:base:manage（厂牌 · 别名 · 认不出的厂牌）
```

目录：`ops-web/app/elec/{page.tsx, rfq-tab.tsx, supplier-tab.tsx, part-tab.tsx, base-tab.tsx, copy.ts}` ·
`ops-web/lib/api/{contracts,https,mocks}/elec.ts`。

要做的登记（运营端的既定流程，漏一处要么看不见、要么 pre-push 挡所有人）：
1. `ops-web/lib/nav.ts` 加 section（`soon` 到页面落地为止）· `lib/i18n/nav-labels.ts` 英文名
2. 主系统迁移：`sys_function` 一行 + `sys_function_point` 四行 + `sys_role_point`（**菜单在库里**，只改 nav.ts 接真后端看不到）
3. 六个码进 `Perms.java` · `ops-web/lib/permissions.ts` · `lib/perm-map.ts` 的 `UI_PERM_MAP`
4. 端点登记：contracts / https / mocks 三件 · `gen-openapi.mjs` 的 `RESPONSE_TYPES` · `perm-endpoint-map.mjs`
5. `point-codes.ts` **手工追加**（别跑 `--emit-point-codes`，它会改冻结码）
6. 生成物：`gen-ui-catalog.py` · `gen-ops-feature-list.py` · `gen-openapi.mjs` · `gen-perm-endpoint-matrix.mjs`

**要先定的一件事**：ops-web 调 elec-svc 的基址。生产同域（nginx `/elec/` → 8085），留空即可；
开发期 ops-web 没有代理（`output: "export"`），而 `NEXT_PUBLIC_API_BASE` 指着 8082 ——
要么加 `NEXT_PUBLIC_ELEC_BASE`，要么 elec-svc 为 3100 开 CORS。建议前者：CORS 是会带进生产配置的口子。

### 2.4 测试期：并进虹选好店（2026-09-30 追加）

元器件自己的小程序号还没有，主系统也只认虹选好店的 appid（§4 第一行）。用户定的测试方式：
**借虹选好店的登录与域名，在「我的」里给一个临时入口**，测完再独立发布。

| 做法 | 落点 |
|---|---|
| 构建时把 elec-app 的页面拷成 c-app 的分包 `pkg-elec`，`@/` 改指 `@/pkg-elec/`，去掉 `title-key` | `c-app/scripts/with-elec.mjs` |
| `pages.json` 临时加分包与 `el-*` easycom，**构建完还原** —— 仓库里的 c-app 一个字节不变 | 同上 |
| 元器件的路由前缀可配：独立为空，并包为 `/pkg-elec` | `elec-app/src/shared/routes.ts`（`VITE_ELEC_ROUTE_BASE`）|
| 「我的」出一行「电子元器件（测试）」，只在 `VITE_WITH_ELEC=1` 的包里渲染 | `c-app/src/pages/me/index.vue` · 词条 `me.elecEntry`（三语）· `c-app/.env` 默认 0 |
| 元器件登录态的持久化键与 c-app 分开（同进程两个 store 写同一个键会互相覆盖）；令牌仍共用 | `elec-app/src/stores/user.ts` |

发体验版**在干净的 HEAD 副本里跑**（发版脚本从当前目录构建，共享工作区里会带上别人没提交的改动）：

```bash
git worktree add --detach <tmp>/c-rel HEAD && cd <tmp>/c-rel
ln -s <repo>/node_modules node_modules && mkdir -p c-app/node_modules/@ai-shop elec-app/node_modules/@ai-shop
ln -s ../../../packages/ui c-app/node_modules/@ai-shop/ui
cd c-app && node scripts/with-elec.mjs release <版本> "<备注>"
```

**独立发布那天要删的**：`me/index.vue` 那一行与 `gotoElec`、词条 `me.elecEntry`、`c-app/.env` 的 `VITE_WITH_ELEC`、
`with-elec.mjs` 与 `.gitignore` 里那两行。

### 2.5 生产部署 elec-svc（2026-09-30 追加）

照「独立服务与第一步」§3.8 的清单：

| 项 | 做了什么 |
|---|---|
| 库 | MySQL 9.7（3307）建 `ai_shop_elec`（utf8mb4_0900_ai_ci）与专用账号 `ai_shop_elec@127.0.0.1/localhost`，只授这一个库 |
| 配置 | `/data/app/ai-shop/elec-svc/elec.env`（deploy:deploy 600）：库连接、`ELEC_MAIN_URL`，内部令牌与企业微信群地址从主系统配置**按行拷**，密码在服务器上生成，全程不落终端 |
| 进程 | `deploy/tencent/systemd/ai-shop-elec.service` 装到 `/etc/systemd/system/` 并 enable；日志 `/data/log/ai-shop/elec-svc/` |
| 发包 | `scripts/deploy-backend.sh elec-svc`（本次加的分支）：健康检查用游客可查的 `GET /elec/c/part`（200 = 容器、过滤链、库都通）；版本回读走 pay-svc 那条「软链 + 进程启动时间」 |
| nginx | `www.hxmall.top` 用仓库版替换（与线上逐字比过，只差 `/elec/` 那一段），先备份、`nginx -t` 再 reload |

**主系统没有一起发**：线上停在 `02259c97a`，比「派单给供应商」（9af209fc9）还早，所以
`/internal/elec/notify/supplier`（通知供应商有新求购 / 被选中）线上还没有 —— 调不通只记日志、
不影响操作本身（`RemoteSupplierNotifier` 返回 false）。认令牌与取手机号两个接口的契约没变，照常可用。
带上它要连同别的会话这两天的全部主系统改动一起上线，不在这次范围里。

### 契约变更

- 端点：**无**（全部是已有端点）
- 库表 / 迁移 / 权限码 / 配置项：**无**
- 类型：`packages/shared/src/types/elec.ts` 补齐已有后端 record 里前端没有的字段与类型
  （`ElecOffer` · `ElecDispatch` · `ElecSupplierQuote(Req)` · `ElecDeclineReq` · `ElecRemapReq` · `ElecPriceTier`，
  `ElecMarket` / `ElecRfq` / `ElecRfqLine` / `ElecStock` / `ElecLineQuote` / `ElecRfqReq` 补字段），
  十个新取值域登记进 `enum-registry.ts`。**逐字对着 `RfqDtos` / `SupplierDtos` / `PartDtos` 抄，没有自拟字段**
- i18n：elec-app 自己的 `zh-CN.ts`（不进三语闸门，见 §3）

---

## §3 选型

| 决定 | 采用 | 没采用 | 理由 |
|---|---|---|---|
| 小程序形态 | 独立工程 `elec-app/` | c-app 分包 `pkg-elec`（旧 §1.2 的并包路线） | 用户明确要独立；分包要从 pages.json / i18n / contract / mocks 四处拆，搬走那天就是一次重构 |
| 依赖 | 根 `node_modules`（全部已提升）+ `link-ui.mjs` 链 `@ai-shop/ui` | 进 npm workspaces 并 `npm i` | 共享工作区里 install 会改 lockfile、重排 node_modules、打断别人的小程序构建。独立发布前再单独 install |
| mock | **不做** | 照 c-app 做 mock 层 | 运营端吃过「默认 mock 盖住缺接口」的亏；后端接口本来就齐 |
| 语言 | 只有 zh-CN，正文直接写中文 | 三语 | 元器件只做国内（省份匿名、专票、人民币含税）；组件库要 i18n 实例所以保留引擎，页面标题走 title-key 以便界面清单取名 |
| 金额 | 百万分之一元（E6），端上按字符串拼 | 分制 `@shared/utils/money` | 0402 电阻 ¥0.0015，分制存不下；浮点直接乘会错（8.2 × 1e6 = 8199999.999999999）|

---

## §4 风险与待拍板

| 风险 | 影响 | 缓解 / 谁来定 |
|---|---|---|
| **独立小程序的 appid 没申请、主系统也不认它** | 静默登录换不出 openid（code 属于另一个 appid）→ 只能靠手机号，而手机号绑定又要先有会话 → **真机上登不进来** | 申请 appid 后，主系统 `shop.wx.appid/secret` 要支持第二个小程序（按来源 appid 选 secret）。**这是上线前的硬前置** |
| 订阅消息模板没选、额度没处上报 | 三处授权调用点已放好，但模板号空 → 不弹窗；主系统 `/mp/message/subscribe` 记的是另一个 appid 的额度 | 模板在新小程序后台选；额度上报接口随 appid 一起做 |
| 买家选完报价看不出选的是哪条 | `Offer` 没有「已选中」标记，刷新后「已选」消失（本会话内靠端上记着） | 后端 `Offer` 加 `picked` 布尔（一行映射 `ElcQuote.status == ACCEPTED`）|
| 平台那条报价不能按行选 | `acceptOffer` 只查供应商报价表，传 `P1` 会 404 | 端上已按 `from` 分流：平台报价走整单「接受报价」。要按行接受平台价需后端支持 |
| 上传后后端记下的文件名是临时路径名 | 预览与批次记录里认不出是哪张表 | 端上显示选中的名字；后端要记原名需加一个表单字段 `fileName` |
| 批量查把「0805 10K 1%」切出「1%」当料号 | 照它询价就是一张废单 | 端上规则：同行读出了数量才用切出来的料号，否则按原文询（`lookup/index.vue#askText`）|

---

## §5 对账三 · 实现 → 需求

小程序页面没有组件测试；验收是**在真后端上走 UI**：本机从 HEAD 编 shop-app 与 elec-svc 两个 jar，
各起一个临时实例（18091 / 18095，临时库 `test_elecui_*`），elec-app H5 经代理连它们。
登录用真的 `/mp/user/login`（`WX_MINI`，本机 `shop.wx.login.stub` 默认是桩）与真的发码、绑号。

| AC | 怎么验的 | 结果 |
|---|---|---|
| AC9 | 登录页：已登录无手机号 → 显示绑号段 → 发码（倒计时插值正常）→ 绑定 → 回首页 | ✅ |
| AC7 | 成为供应商 → 工作台 e13 态（编号 S-HVCT）→ 选表（`uni.chooseFile` 换成返回 CSV Blob，其余全走页面）→ 表头在第 2 行也认出、备注列不导入 → 预览 4 新增 1 认不了 → 确认上架 → 工作台 e14 态（4 在售）→ 我的库存 4 行 → 资料保存后接口回读一致 | ✅ |
| AC5 | 首页搜 `f103c8` → 中段一致命中 STM32F103C8T6 → 详情三格与参考价 → 询价（数量、目标价、城市）→ 询价详情「已派给 1 家供应商」 | ✅ |
| AC5 | 批量查 5 行：2 库里有 · 1 要确认（CH340 → 只有开头一致的 CH340N）· 2 库里没有 → 一起询价 → 草稿 5 行带到询价页 → 缺数量的第 4 行被拦（「第 4 行：数量没填」）| ✅ |
| AC8 | 求购列表只显示「发往广东」→ 详情报价 ¥6.30 → 「我的报价」回显 | ✅（见 §6 偏差 ①）|
| AC6 | 买家详情出现「报价 A ¥6.804」（6.30 加价后、无任何供应商字段）→ 选这条 → 「已选」；供应商侧接口回读该报价 `ACCEPTED` | ✅ |
| AC5 | 平台报价（e10）与整单接受（e11） | ⚠️ **没验**：要运营令牌录入平台报价，本机没起 ops 实例 |
| AC2 | `uni build`（H5）与 `uni build -p mp-weixin` 都通过；库件产物落在包内 `node-modules/@ai-shop/ui/…`，没越出根目录 | ✅ |
| AC11 | `gen-ui-catalog.py --check` ✓（283 个界面，元器件小程序 16）· `gen-proto-index.py --check` ✓ | ✅ |
| — | `elec-app/tests/format.test.ts` 7 条；**消融**：`e6Of` 换成 `Number(s) * 1e6` → 红（8199999.999999999 ≠ 8200000），还原 → 绿。第一版用例消融**没变红**（0.0015 恰好不出浮点误差），补了 8.2 / 1.005 才红 | ✅ |
| — | `vue-tsc --noEmit`：放一个 `const x: number = "x"` 进 `.vue` → 报错，撤掉 → 空输出（确认它在查 `.vue`）| ✅ |

走 UI 时顺手修掉的（都在页面上看得见）：`sh-tabs` 外的类名撞了库件根类 `.tabs` 被压成竖排 ·
`.sheet` 撞 `sh-sheet` 根 · 上架确认后 reLaunch 清空页面栈 · 预览丢了冷启动无出口 ·
交期没说被标成「期货」· 预填值盖住占位符看不出字段名。

---

## §6 对账二 · 设计 → 实现

```
 .claude/launch.json                               |   9 +
 .githooks/pre-push                                |   9 +-
 CLAUDE.md                                         |   4 +-
 docs/api/openapi-b.yaml                           | 459 +++++++++++++++++++++++++++++++++++++++++-
 docs/api/openapi.yaml                             | 459 +++++++++++++++++++++++++++++++++++++++++-
 docs/technical/README.md                          |   1 +
 docs/technical/design/ui-catalog.json             | 335 +++++++++++++++++-------------
 docs/technical/design/原型清单.md                 |   2 +-
 docs/technical/reference/glossary.json            | 104 ++++++++++
 docs/technical/reference/中英文对照-实体与字典.md |  14 +-
 packages/shared/src/contract/enum-registry.ts     |  32 +++
 packages/shared/src/types/elec.ts                 | 222 +++++++++++++++++++-
 prototypes/index.html                             |   2 +-
 prototypes/registry.json                          |  54 ++---
 scripts/gen-proto-index.py                        |   2 +-
 scripts/gen-ui-catalog.py                         |  19 +-
 scripts/link-ui.mjs                               |   4 +-
 17 files changed, 1538 insertions(+), 193 deletions(-)
 elec-app/  （新增 47 个文件，3999 行）
 docs/technical/TDD-元器件-小程序独立工程.md （新增，本文）
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的：`CLAUDE.md` · `.claude/launch.json` | 前者把「改了页面要重跑清单」「vue-tsc」两条扩到 elec-app；后者加 `elec-app-h5`（5177）这一个 dev server 配置 |
| 生成物：openapi 两份 · glossary 两份 · README · ui-catalog · 原型清单 · prototypes/index.html | 源头改了（共享类型、原型登记、新 TDD），按生成器重新生成；在 HEAD 副本里只放本批改动跑的，没读进别的会话的未提交改动 |
| TDD 列了、实际没动的 | 无 |

### 偏差说明

1. **求购三页在 HEAD 4b6d40e43 上是 500**：`DispatchMapper#mine` 用了 `<if>` 而没包 `<script>`，
   求购列表 / 详情 / 报价后的返回全挂。另一个会话同时发现并修了（eff0f9c84 已含修复）。
   本批验证时是在自己的 HEAD 副本里给这条 SQL 包上 `<script>` 后编的 jar —— **结论依赖那个修复**。
2. **登录页不是「手机号 + 验证码登录」**：原先照 c-app 的样子写了，真跑发现主系统不给匿名发码。
   改成两段式（§2.2 登录模型）。
3. **原型里没有的两屏**（求购、求购详情）按后端 9af209fc9 已有的接口做了；原型待补。
4. **运营端只到设计**：§2.3。另一个会话的运营端后端 TDD 也明确把 ops-web 页面与菜单登记留给「下一批」。

---

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-30 | 方案给出并开始实现（用户要求「给出计划后直接执行」）|
| 2026-09-30 | 小程序 16 页已实现，真后端走通 §5；闸门见提交说明 |
