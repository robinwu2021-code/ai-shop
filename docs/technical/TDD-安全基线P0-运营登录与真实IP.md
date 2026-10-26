# TDD-安全基线P0-运营登录与真实IP

状态：已实现（服务器侧 nginx 待运维照做，见 §6）
档位：1（新端点 `POST /ops/auth/login/verify`、登录响应加字段、新配置项 `shop.ops.auth.second-factor`、去掉 `SHOP_DB_PASS` 默认值）· 依据：[支付服务 · 安全规范](./支付服务-安全规范.md) §0 G1 / G2 / G6 / G9、§2.7、§3.2、§4 · [完成度复核与剩余开发计划](./支付服务-完成度复核与剩余开发计划.md) §4.1 P0
创建：2026-10-26 · 分支 `codex/pay-i5-access`

> 开放 API 与收银台上线之前必须先关掉的四个口子：运营后台对全网开放（G1）、客户端 IP 可伪造（G2）、数据库口令有默认值（G6）、运营登录可撞库（G9）。
> 每项先写会变红的测试 → 修 → 消融。决定：第二因子用**邮箱验证码**（运营账号即邮箱、无手机号）；运营白名单网段放**部署期文件**、不进仓库、默认拒绝。

## §0 对账一 · 需求 → 设计

| AC | 验收 | 落点 |
|---|---|---|
| AC1 | 只认可信代理覆写的 `X-Real-IP`，且只在请求来自本机 / 私网时才认；`X-Forwarded-For` 一律不读；直连应用伪造任何头都无效 | `shop-base-auth` `ClientMeta#clientIp`（改为 public） |
| AC2 | 全仓只有一处读转发头 | `ClientIpSingleSourceTest`；`MpStoreController`、`ChannelPayCallbackController` 改用 `ClientMeta` |
| AC3 | 生产配置里密钥类键不得有默认值或字面量 | `NoSecretDefaultInConfigTest`；`application.yml` `password: ${SHOP_DB_PASS}` |
| AC4 | 运营登录：同账号 15 分钟 5 次失败即锁（锁定期内密码对也不放）；同 IP 15 分钟 20 次失败即限；成功清该账号计数 | `OpsLoginGuard`（只数失败）· `OpsServiceImpl#login` |
| AC5 | 第二因子：密码对只回挑战（无令牌）；6 位码发到登录邮箱；5 分钟、最多试 5 次、一次有效；凭码换会话 | `OpsLoginChallenges` · `OpsServiceImpl#verifySecondFactor` · `POST /ops/auth/login/verify` · `LoginResultVO.challenge/challengeTo` |
| AC6 | `prod` profile 下第二因子关不掉（配 `off` 启动失败） | `OpsSecondFactorGuard` |
| AC7 | 运营前端支持第二步；登录响应只有挑战时不当成已登录 | ops-web `app/login/page.tsx` · `lib/api/https/dashboard.ts#toLoginResp / verifyLogin` · `LoginResp.challenge` |
| AC8 | nginx：转发头覆写不追加；`/ops`、`/ops-web`、`/pay-ops` 只对白名单、其余 deny；每个反代到应用的 server 都拒 `/internal/` | `deploy/tencent/nginx/www.hxmall.top.conf` · `ops-allow.conf.example` · `NginxEdgeRulesTest` |

## §1 现状（修之前，2026-10-26 核对）

- `ClientMeta` 取 XFF 最左值（注释承认可伪造）；`MpStoreController`、`ChannelPayCallbackController` 各有一份自己的 XFF 读取（后者大小写不敏感，第一版守卫没扫到，见 §5 记录）。
- `application.yml` `password: ${SHOP_DB_PASS:shop}`；测试经 `h2db` profile 自带空口令，`e2e` profile 在测试资源里另有一份。
- `OpsServiceImpl#login` 无锁定、无限流；运营账号 `username` 即邮箱、无手机号；忘记密码已走邮件（`MailTemplatePort`）。59 个测试类经 `/ops/auth/login` 取令牌。
- nginx 快照：`/ops` 在公共正则 location 里对全网开放；6 处 `$proxy_add_x_forwarded_for`；80 端口 server 反代 `/callback/logistics/` 却没拒 `/internal/`。

## §2 方案

- **G2**：`ClientMeta#clientIp(HttpServletRequest)` 公开；TCP 对端是回环 / 私网（10/8、172.16/12、192.168/16）/ IPv6 ULA 时采信 `X-Real-IP`，否则用对端本身；不读 XFF。两处旧副本改为同一来源：C 端门店直接调它；回调控制器在领域包里、不许碰 servlet（`ArchitectureTest#domainsMustNotTouchWebRuntime`），所以给 `/pay/callback/**` 加一条显式安全链 `channelCallbackChain`（无会话、放行、不开 CORS），链上的 `RequestMetaFilter` 经 `ClientMeta` 设 `RequestMetaContext`，控制器只读它 —— 顺带关掉 G11 的回调一半（此前回调不进任何链）。
- **G6**：去掉默认值；守卫扫全部模块 `src/main/resources` 的 yml / properties，键名最后一段含 password / secret / token / api-key / private-key / credential / pepper / signing-key / hmac / cipher-key / master-key（排除 -path / -store / -header / -ttl 等），值只能是无默认的 `${ENV}`。
- **G9**：`OpsLoginGuard`（15 分钟滑窗，账号 5、IP 20，只数失败，成功清账号计数）在验密码之前拦；`OpsLoginChallenges`（挑战号 32 字节随机、只存哈希，码 6 位 `SecureRandom`、只存哈希、常量时间比较）；
  `shop.ops.auth.second-factor`（`email` 默认 / `off`）；`testcfg` 配 `off`（与 `password-delivery: response` 同一取舍），`OpsLoginProtectionFlowTest` 在共用上下文里打开；
  邮件走 `MailTemplatePort`，模板号 `TPL_MAIL_OPS_LOGIN_CODE`（暂无库内模板，用调用方内置的中英双语文案）、业务类型 `OTP`；第一步成功不记「登录成功」，第二步才记。
- **G1**：运营三段各自 `include /etc/nginx/ai-shop/ops-allow.conf; deny all;`；公共正则去掉 `ops`；80 端口补 `/internal/` deny。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融（改回去必须红） |
|---|---|---|---|
| AC1 | `ClientMetaTest`（4 条） | ✅ 4/4 | ① 谁的 `X-Real-IP` 都信 → `directClientCannotForgeHeaders` 红 ② 没有 `X-Real-IP` 时退回读 XFF → 2 条红 ✅ |
| AC2 | `ClientIpSingleSourceTest` · `CallbackTemplateTest`（6 条，改为发 `X-Real-IP` + 伪造 XFF，断言记录的是真 IP）· `ArchitectureTest`（20 条） | ✅ | 修之前红：守卫列出 `MpStoreController`；**第一版守卫区分大小写，漏了回调控制器的 `"x-forwarded-for"`** —— 改成不区分大小写后才红出来；回调记录了伪造的 `6.6.6.6`；③ 回调链去掉 `RequestMetaFilter` → `CallbackTemplateTest#badSignature` 红（记录里没有来源 IP）✅ |
| AC3 | `NoSecretDefaultInConfigTest` | ✅ | 修之前红，且只红 `application.yml:66` 一处 ✅ |
| AC4 | `OpsLoginProtectionFlowTest#accountLocksAfterFiveFailures` · `#successClearsFailures` · `#ipThrottledAcrossAccounts` | ✅ | ① 锁定期内照样验密码 → 2 条红 ② 不记 IP 失败 → `ipThrottled…` 红 ✅ |
| AC5 | `#secondFactorByEmail` · `#challengeDiesAfterFiveWrongCodes` | ✅ | ③ 第二因子开着也直接发会话 → 2 条红 ④ 猜码不限次数 → `challengeDies…` 红 ✅ |
| AC6 | `#prodRefusesSecondFactorOff` | ✅ | ⑤ 生产也能关 → 红 ✅ |
| AC7 | ops-web `lib/api/https/login-mapping.test.ts` 新增 2 条（挑战不当成已登录、第二步同样摊平） | ⏸ **未跑**：本工作区无 ops-web `node_modules`（`vitest` / `tsc` 不在），见偏差 4 | — |
| AC8 | `NginxEdgeRulesTest`（3 条） | ✅ 3/3 | 修之前三条全红（含 80 端口 `/internal/` 那条意外发现）✅ |
| 回归 | 全量 `mvn -o -pl shop-app -am test`（共享分支已把 shop-app 拆成 8 批 surefire 执行，堆不再溢出） | 第一轮 4171 条只红 2 条，**都是本片引起、已修**：`ArchitectureTest#domainsMustNotTouchWebRuntime`（回调控制器第一版用了 `RequestContextHolder` → 改为回调链 + `RequestMetaFilter`）、`OpsEndpointPermTest#everyOpsEndpointHasADecision`（新端点 `/ops/auth/login/verify` 进免权白名单并写明理由）；**第二轮：6409 条（含上游各模块）0 失败 0 错误、无 OOM** | — |

## §6 对账二 · 设计 → 实现

| 设计 | 文件 |
|---|---|
| G2 | `shop-base-auth` `ClientMeta`（+ `ClientMetaTest`）· `RequestMetaFilter`（新）· `shop-app` `MpStoreController` · `SecurityConfig#channelCallbackChain`（新）· `shop-core` `ChannelPayCallbackController` · `CallbackTemplateTest` · `ClientIpSingleSourceTest` · `OpsEndpointPermTest`（白名单 +1） |
| G6 | `shop-app` `application.yml` · `NoSecretDefaultInConfigTest` |
| G9 | `shop-core` `platform.impl.{OpsLoginGuard, OpsLoginChallenges, OpsSecondFactorGuard, OpsServiceImpl}` · `platform.OpsService` · `platform.dto.OpsVOs.LoginResultVO` · `platform.api.ops.OpsPlatformController` · `shop-base` `MailTemplatePort.TPL_OPS_LOGIN_CODE` · `shop-app` `SecurityConfig`（permit `/ops/auth/login/verify`）· `application.yml` / `application-testcfg.yml` · `OpsLoginProtectionFlowTest` |
| 运营前端 | `ops-web` `app/login/page.tsx` · `lib/api/https/dashboard.ts` · `lib/api/contracts/dashboard.ts` · `lib/api/mocks/dashboard.ts` · `lib/types/dashboard.ts` · `lib/i18n/messages/{zh,en}.ts` · `login-mapping.test.ts` |
| G1 | `deploy/tencent/nginx/{www.hxmall.top.conf, ops-allow.conf.example, README.md}` · `NginxEdgeRulesTest` |

**服务器侧（运维照做，`deploy/tencent/nginx/README.md` 有步骤）**：写 `/etc/nginx/ai-shop/ops-allow.conf` → 合入快照改动 → `nginx -t` → reload → 办公网 / 外网各验一次。部署时还要给每个环境 `export SHOP_DB_PASS`（不再有默认值，缺了启动即失败）。

### 偏差说明

1. **计数器是单实例内存**（`OpsLoginGuard` / `OpsLoginChallenges`），与现有 `PasswordResetTokens` 同一前提；多实例部署时要换共享存储（Redis），否则锁定按实例分摊。
2. **锁定可被拿来锁别人的账号**（输错 5 次让真运营 15 分钟进不来）：撞库防护的固有代价；锁定只 15 分钟、且同 IP 20 次后连这个也做不了。
3. **登录验证码邮件没有库内模板**（`notify_template` 无 `TPL_MAIL_OPS_LOGIN_CODE` 行），走调用方内置的中英双语文案；要多语言模板时补一条平台迁移。
4. **ops-web 的类型检查与单测本地未跑**：本工作区与主工作区都没有可用的 `node_modules`；需要 `npm ci`（下载依赖）后跑 `npm run check`，pre-push 的同一道闸门在有 `node_modules` 的机器上会跑。
5. **`api` 与 `ops` 仍同进程**（G1 的另一半）：nginx 层已隔离入口，进程拆分属部署形态，另排。
6. **`useSSL=false`**（G6 的另一半）：数据库与应用同机，暂不处理；跨主机前改。
