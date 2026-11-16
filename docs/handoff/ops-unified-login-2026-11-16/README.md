# 运营端统一登录（ai-shop × near-pay）· 交接

> 状态：**已实现、已合入两边主线，未上线**（2026-11-16）。166 线上为 v46-entity-closure 版本，用户决定 v46 暂不发布，本功能随之暂缓；重新安排发布时按 §四 执行。
> 方案：[TDD-ops-统一登录与系统切换](../../technical/design/ops/TDD-ops-统一登录与系统切换.md)（含现状对照、设计、待决项与实现记录）。

## 一、接手结论

1. 平台运营（`/ops-shop/`）与支付运营（`/oms/`）的统一登录、退出吊销、跨标签页同步、顶栏系统切换都已写完并测过，代码在两边主线上。
2. **一处也没上线。** 166 上 ai-shop 后端 jar、`/oms` 前端都是另一条线（entity-v46）的构建，比主线新；直接从主线发布会回滚那条线，所以停在部署前。
3. 发布前提：后端 jar 带上退出接口；`/ops-shop` 与 `/oms` 两个前端**同一批**切换。

## 二、提交

| 仓库 | 分支 | 提交 | 内容 |
|---|---|---|---|
| ai-shop | `main`（来自 `feat/ops-unified-login`） | `55e1b472a` | 后端 `POST /ops/auth/logout`；前端共用会话、跨标签页同步、退出吊销、登录页去向提示与强制改密、顶栏系统切换；方案 TDD |
| near-pay | `codex/near-pay-migration`（来自 `codex/ops-unified-login`） | `232b6ac2` | `build:live` 改统一登录模式；只读写共用会话；退出经 `/ops-shop/ops/auth/logout` 吊销；顶栏系统切换 |

两个特性分支已推到远端，内容与主线一致，可删。

## 三、实现要点

| 项 | 实现 | 位置 |
|---|---|---|
| 唯一会话 | `localStorage["neargo-ops-auth"] = {token, username, issuedAt}`，两端共读；旧键（`shop-ops-auth` 中的令牌、`neargo-ops-session`、`nearpay-ops-session`）首次加载迁移一次后删除。ai-shop 的 `shop-ops-auth` 只作本页角色与权限缓存，令牌与共用键不一致即作废 | ai-shop `ops-web/lib/auth/ops-bridge.ts`、`shared-session.ts`；near-pay `ops-web/lib/auth/shared-session.ts`、`session.ts` |
| 跨标签页 | 监听 `storage`：共用键被删 → 回统一登录页（带 `returnTo`）；换了人 → 整页重载重新核验 | ai-shop `components/layout/app-shell.tsx`；near-pay `features/auth/login-gate.tsx` |
| 退出 | 先 `POST /ops/auth/logout` 服务端吊销（尽力而为，3 秒超时），再清两端本地。near-pay 走同源 `/ops-shop/ops/auth/logout`，**不需改 Nginx** | ai-shop `components/layout/header.tsx`、`lib/api/https/dashboard.ts`；near-pay `lib/auth/session.ts` |
| 后端接口 | `OpsService.logout(token)` → `TokenStores.of(OPERATOR).revoke`；幂等；SecurityConfig 放行；权限闸门两处登记 | `OpsPlatformController`、`OpsServiceImpl`、`SecurityConfig`、`OpsEndpointPermTest`、`scripts/gen-perm-endpoint-matrix.mjs` |
| 统一登录页 | ai-shop `/ops-shop/login/`：`returnTo` 白名单（只认 `/ops-shop/`、`/oms/`）；去 `/oms` 时副标题「登录后进入 支付运营」；初始密码账号先改密，改完用新密码自动重新登录 | ai-shop `app/login/page.tsx` |
| near-pay 登录 | 生产 `NEXT_PUBLIC_LOGIN_MODE=shared`：无会话整页跳统一登录；不再显示「在此登录」；`local` 模式只留给本地开发 | near-pay `scripts/build-live.mjs`、`features/auth/login-gate.tsx` |
| 系统切换 | ai-shop：顶栏「切换系统」下拉，「支付运营」按 `GET /oms-api/pay-ops/v2/me/access` 有权限才显示。near-pay：同款下拉，「平台运营」名称读 `/ops-shop/brand.json` | ai-shop `components/layout/system-switcher.tsx`；near-pay `features/auth/system-switcher.tsx` |
| 配置 | ai-shop 新增 `NEXT_PUBLIC_NEARPAY_ACCESS_URL`（默认 `/oms-api/pay-ops/v2/me/access`），已写入 `ops-web/.env.local.example` | — |

## 四、上线步骤（待安排）

1. **确认发布线**：v46 的负责人与分支（ai-shop `feat/entity-v46` 远端为 `ac1cea9cb`；`/oms` 的 near-pay 源码分支未知）。把 ai-shop `main`（含 `55e1b472a`）合进去，把 near-pay `232b6ac2` 合进发 `/oms` 的那条线。
2. **后端**：发布带退出接口的 jar。验收：登录后 `POST /ops/auth/logout`，同一令牌访问 `/ops/auth/me` 返回 401。
3. **两个前端同批切换**：
   - `/ops-shop/`：`cd ops-web && NEXT_PUBLIC_API_BASE=/ops-shop NEXT_PUBLIC_BASE_PATH=/ops-shop NEXT_PUBLIC_USE_MOCK=0 npm run build -- --webpack`，上传到 166 `/data/www/ai-shop-ops/releases/<id>/ops-shop/`，`current` 原子切换（打包加 `COPYFILE_DISABLE=1`，避免 macOS `._*` 文件）。
   - `/oms/`：`cd ops-web && npm run build:live`，按 near-pay 现行方式切 `/data/www/oms-root/oms`。
4. **联调验收**（TDD §六）：未登录开 `/oms/trade/payments/?status=FAILED` → 统一登录 → 回原页并保留筛选；两个标签页一边退出另一边立即回登录；换人后原标签页不显示上一个人的数据；无支付权限账号看不到「支付运营」；`returnTo=https://evil.example` 被忽略；两个域名各验一遍。
5. **回退**：两个前端切回上一版目录；退出接口保留无害。

**只切一边的后果**：旧版 `/oms` 不读 `neargo-ops-auth`，会要求重新登录；旧版 `/ops-shop` 不吊销令牌。

## 五、验证（2026-11-16，本地）

| 项 | 结果 |
|---|---|
| ai-shop 后端 | `OpsLogoutFlowTest` 3 项、`OpsEndpointPermTest` 5 项通过 |
| ai-shop ops-web | tsc、eslint 无错；vitest 1268 通过（`gated-endpoints.test.ts` 整个文件跑不起来，改动前即如此） |
| ai-shop 共享检查 | 生成文档闸门 26 个生成器最新；`packages/shared` 失败项与改动前的 `main` 相同，另修好了 `env-consumed`、`ops-ui-discipline`、图示 title/desc 三项 |
| near-pay ops-web | tsc 无错；vitest 442 通过；`build:live` 构建成功 |
| 联调 | **未做**：两端需同域部署才能在浏览器里验证 |

## 六、166 现状（2026-11-16 只读核对）

| 项 | 现状 |
|---|---|
| ai-shop 后端 | 容器 `ai-shop-ops` / `ai-shop-api` 2026-11-15 15:12 UTC 重启，jar sha256 前缀 `4623d27652a30d6b`，与 `/home/ume/v46-entity-closure-20261115/deployment.json` 记录一致；schema shop 675、pay 103。之后 pay jar 还做过两次类文件覆盖（v47-material、v47-identity） |
| `/ops-shop/` | `releases/brand-login-20261116-b88ea3a41`（主线发布） |
| `/oms/` | `/data/www/oms-root/oms → nearpay-oms-releases/v46-entity-closure-20261115` |
| 发布者 | 服务器记录里没有会话标识；会话「进件」确认不是它发的，并转告用户决定 v46 暂不发布 |

## 七、环境备忘

- 共用工作目录 `/Users/neargo2026/work/ai/ai-shop` 停在旧提交 `48cc0490b`，未切到新 `main`（避免影响同目录其他会话）。
- near-pay 工作目录 `/Users/neargo2026/work/ai/nearpay-sso`（分支 `codex/ops-unified-login`，已装依赖）保留，可直接用于构建 `/oms`。
- 推送：ai-shop 用 `~/work/env/git/github-credentials`（`git -c credential.helper= -c "credential.helper=store --file=$HOME/work/env/git/github-credentials" push …`）；near-pay 远端为阿里云 Codeup，本机凭证可直接推。
- 166：`ssh -F ~/work/env/ume/ssh.config ume-app166`，有 sudo。
- `rg` 在本机是 shell 函数，Node 子进程找不到，跑生成文档闸门前要放一个真 ripgrep 或 shim 到 PATH。
- 后端测试用 JDK `/Users/neargo2026/Library/Java/JavaVirtualMachines/jdk-21.0.13+9/Contents/Home`，`mvn -o -pl shop-app -am -Dtest=… test`。
