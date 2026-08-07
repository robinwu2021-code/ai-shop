# ai-shop-ops-web

ai-shop 平台运营端（PC Web）。技术栈：**Next.js 16 + React 19 + Tailwind 4 + shadcn 风格自持组件 + TanStack Query**。
方案见 [TDD-ops-web](../docs/technical/TDD-ops-web.md)，需求锚点是 [需求矩阵-三端 §六](../docs/requirements/需求矩阵-三端.md)。

> UI 地基（token 三层、31 个组件、外壳、请求层）**提取自 `powerbank/ops-web`**（已试错四轮、实机验证），
> 业务层（导航 / 权限 / 类型 / mock / 页面）按 ai-shop 需求矩阵重写。哪些搬、哪些重写见 TDD §2。

## 运行

```bash
npm install
cp ops-web/.env.local.example ops-web/.env.local
npm run dev:p
```

默认 `NEXT_PUBLIC_USE_MOCK=1`，无后端即可跑全站。登录页选任意角色进入（mock 登录，用于验证 RBAC 与数据域裁剪）。
接后端时把 `.env.local` 改成 `NEXT_PUBLIC_USE_MOCK=0` + `NEXT_PUBLIC_API_BASE=http://localhost:8080`，**页面零改动**。

## 当前状态（脚手架）

| 模块 | 路径 | 状态 |
|---|---|---|
| 登录（11 角色 + 数据域） | `/login` | ✅ |
| 经营看板（P-16.1） | `/` | ✅ KPI + 趋势 + 获客漏斗 |
| 商家治理（P-11.1） | `/merchants` | ✅ **样板页 ①**：列表 / 筛选 / 审核状态机 / 归档 / 权限降级 |
| 订单管理（P-4.1） | `/orders` | ✅ **样板页 ②**：列表 / 筛选 / 详情抽屉 / 兄弟单 / 导出 |
| 组件总览（dev-only） | `/dev/ui` | ✅ 全组件 × 明暗 × 四皮肤 × RTL × 密度 |
| 角色×权限对照（dev-only） | `/dev/perms` | ✅ 11 角色 × 导航涉及的全部权限码，高危码单列 |
| 社区与网点（P-2） | `/communities` | ✅ 社区网格 / 自提点 / 临时点监控 |
| 商品与类目（P-3） | `/products` | ✅ 三级类目树 / 商品池与审核 / 库存与预售 |
| 履约调度（P-5.1） | `/fulfillment` | ✅ 批次 / 分拣 / 核销监控 / 逾期规则 |
| 售后治理（P-6.1） | `/after-sales` | ✅ 工单池 / 平台介入裁决 / 极速退规则 |
| 营销活动（P-7） | `/marketing` | ✅ 券模板 / 发放记录 / 活动 / 内容位 |
| 团购与求团（P-8） | `/groups` | ✅ 商家团 / 求团需求 / 报价与信用 |
| 增长与归因（P-9） | `/growth` | ✅ 归因规则 / 归因链路 / 裂变活动 |
| 门店主页（P-10.1） | `/stores` | ✅ 合规审核 / 店铺码 / 获客效果 |
| 结算与资金（P-12.1） | `/finance` | ✅ 结算单 / 分账明细 / 退款回退 / 费率 |
| 评价治理（P-13.1） | `/reviews` | ✅ 评价审核 / 申诉裁决 / 评分参数 |
| 消息与客服（P-14） | `/messages` | ✅ 模板与推送 / 客服工单 / 帮助中心 |
| 素材与内容（P-15.1） | `/contents` | ✅ 素材库与分发范围 |
| 风控（P-16.2） | `/risk` | ✅ 风险事件 / 黑名单与申诉 / 拦截规则 |
| 员工与权限（P-1.1） | `/iam` | ✅ 员工与数据域 / 角色 RBAC / 操作审计 |
| 系统配置（P-17.1） | `/system` | ✅ 外观与规则文案 / 市场货币 / 开关灰度 |

导航共 18 个 L1（矩阵 §六 的 18 个平台端业务域），**全部已交付**。
个别二期功能（提现与税 P-12.2、内容审核 P-15.2、会员 P-7.4 等）的叶子仍标 `soon` 灰显 —— **不产生 404 入口**。

## 结构

```
app/            页面（Next App Router，静态导出）
  globals.css   ★ 设计 token 三层（原始 / 语义 / 组件）+ 四皮肤 + 暗色 + 密度
components/ui/  原语 + 组合件（31 个，无业务语义）
components/     业务件：status / archive / read-only-notice
lib/api/        契约 + mock/http 两实现 + 一开关
lib/mock/db/    mock 数据集（写操作真落库）
lib/{nav,permissions,auth,phase,types,constants}
```

分层规矩见 [components/README.md](./components/README.md)——**`ui/` 里不许出现业务词**，破了这条 `ui/` 就不再可复用。

## API 架构（一键切换 mock ↔ 真实后端）

```
lib/api/
  contract.ts     interface Api            ← 唯一契约（页面依赖它）
  contracts/*.ts  按域切片的方法签名
  mock.ts         mockApi: Api             ← 走 lib/mock/db 内存数据
  http.ts         httpApi: Api             ← 走真实后端，端点前缀 /ops/**
  http-client.ts  fetch 封装：{code,msg,data} 拆包 + 身份头 + ApiError
  index.ts        export const api = USE_MOCK ? mockApi : httpApi   ← 唯一开关
```

契约口径与 C/B 端一致：响应包 `{code,msg,data}`、分页 `{records,total,page,size}`、金额为最小货币单位整数、
时间 `xxxAt`、单号 `xxxNo`、**禁止 `delete*`**（软删除走 `archive*`/`unarchive*`，由 `contract.test.ts` 拦截）。

## 权限与数据域

`RBAC + 数据域`（矩阵 §2.3）：`can(role, code)` 管按钮，`canModule` 管导航，`scopeOf(auth)` 给出
`{merchantNo, communityNo, pickupNo}` 由请求层带上。**前端只做展示裁剪，越权拦截以后端为准**——
mock 层同样实现 scope 过滤，避免"数据域"这一维在开发期是隐形的。

## 导出契约给后端

```bash
npm run gen:api -w ai-shop-ops-web
```

产出 [docs/api/openapi-ops.yaml](../docs/api/openapi-ops.yaml)（131 端点 / 117 路径 / 102 类型）。
后端可直接 `openapi-generator` 出 Spring 接口层，不用照着前端代码手抄。

生成器在**契约与 http 实现对不上时会失败退出**（而不是少生成几个）；同一判据也在 `npm run check` 里。

## 检查

```bash
npm run check -w ai-shop-ops-web
```

`tsc --noEmit` + vitest：导航结构与矩阵覆盖率、权限矩阵、契约一致性、mock 状态机与落库、设计 token 守卫（基线 0）。
