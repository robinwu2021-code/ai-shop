# 交接 · OpsKit 审批上线到 .80 并联调（2026-11-23）

> 写给接手部署的会话。来源会话：「Nearpay 和 AI-Shop 审批方案」。
> **用户已定：用审批版本直接替换 .80 上的现网服务（方案 B），在正式库上联调。** 动钱的业务每项启用前要单独问用户。
> 部署完成后按 **[测试方案](./测试方案.md)** 联调（测试目的、分阶段用例、停止条件、结果记录）。

## 一句话

代码已经合进两个仓库的 main，上线包已打好，生产库要执行的 root SQL 已写好并核对过。**生产服务和数据库到现在一点没动。**
剩下：① 用户 / DBA 执行两份 SQL → ② 替换 ai-shop → ③ 替换 near-pay → ④ OpsKit 加回调 → ⑤ 逐项启用、联调第一批。

## 一、已就绪的东西

| 项 | 内容 |
|---|---|
| ai-shop 代码 | `main` = `07b3abab8`（PR robinwu2021-code/ai-shop#43 squash 合并）。现网包 `58e5af6bf` 在它的历史里，新 main 只多 4 个提交 |
| near-pay 代码 | `main` = `f85962e3`：审批（第一批 + 第二批平台侧）+ openapi 会话的 `feat/open-api-access`（现网 near-pay 就是从它构建的，已全部合入） |
| ai-shop 上线包 | `/Users/neargo2026/work/ai/ai-shop/.claude/worktrees/release-opskit/backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar`<br>sha256 `33b72b720b056033a4a3b5f1c30b7ac245b6da56af690cf8a1105ad5525f2c18`（`-P opskit`，从 `07b3abab8` 构建） |
| near-pay 上线包 | `/Users/neargo2026/work/ai/nearpay/.claude/worktrees/release-opskit/backend/pay/pay-svc/target/pay-svc-0.1.0-SNAPSHOT.jar`<br>sha256 `090112eccf29868cef3851690837109f25ded1ae1c7cb356ecaf3fcc1561e758`（`-P opskit`，从 `f85962e3` 构建） |
| 生产库 SQL | 本目录 [`ai_shop_ume-V690-V691.sql`](./ai_shop_ume-V690-V691.sql)、[`nearpay_prod-V136-V138.sql`](./nearpay_prod-V136-V138.sql) |
| 测试 | ai-shop：后端单测 / 场景、架构、i18n、ops-web 全套（163 文件）通过；near-pay：合并后默认构建全绿（channel 596 · domain 3786 · guard 130 · portal 290 · svc 954）+ `-P opskit` 审批测试全绿 |
| 本机端到端 | 本机 OpsKit + ai-shop 跑通：内省与角色映射、流程登记、T1 通过 / 驳回 / 重提、T1 旧入口被拒、T10 入驻（见 `docs/technical/方案-第一批审批工作流.md` §7） |

上线包是用开发机 `~/.m2` 里本地安装的 `opskit-approval-client:0.1.0-SNAPSHOT` 打的（它不在 Nexus）。要重打：在两个仓库的 main 上 `mvn -o -B -P opskit -DskipTests -pl <shop-app|pay/pay-svc> -am package`。

## 二、.80 现网现状（只读核对过）

`.80` = `ume-els1`，2 核 7.6G，可用约 3.2G。**这是台湾正式环境**，在线门店 ume-breakfast 在这里。

| 服务 | 怎么跑 | 库 | 端口 |
|---|---|---|---|
| ai-shop-v2 | `aishop-docker` 隔离 docker（`runtime/d`），`/data0/app/ai-shop-v2/run.sh all <start\|restart\|logs\|status>`；包挂载 `releases/shop-app-main-58e5af6bf.jar`；环境文件 `ai-shop-all-v2.env`；`-Xmx1024m`、`--memory 1536m` | `.61:3307/ai_shop_ume`（迁移在 V689） | 18180（单实例，api+ops） |
| nearpay-prod | 同一 docker；起法见 `/data0/app/nearpay-prod/cutover-80.sh` 的 `docker run`（`--env-file nearpay.env`、`-v $APP:/app:ro`、`--memory 1g`、`-Xmx512m`）；包 `/data0/app/nearpay-prod/pay-svc.jar`（2026-11-22 08:34 构建） | `.61:3307/nearpay_prod`（迁移在 V135） | 18093 |
| aishop-loopback | 把 `127.0.0.1:18182` 转到 `192.168.3.80:18180`（near-pay 调 ai-shop 用） | — | 18182 |
| opskit | systemd `opskit.service`，`/data0/app/opskit/`，配置 `config/opskit.env`（600，仅 opskit 可读）+ `config/bootstrap.yml` | `.61:3307/opskit` | 8090 |

连法（主机公钥已核对：`SHA256:dWSBuVIe25T0kxIVv8tPgF2ZJ7IbE3+WN74g6juW1kc`）：

```bash
ssh -o IdentitiesOnly=yes -i ~/work/ume/ume-id_rsa -o "ProxyCommand=ssh -F ~/work/env/ume/ssh.config ume-jump -W %h:%p" ume@192.168.3.80
```

docker 命令：`/data0/app/ai-shop-v2/runtime/docker/docker -H unix:///run/aishop-docker.sock …`

## 三、步骤

### ① 生产库迁移（用户 / DBA 以 root 执行，**不要由会话代做**）

两个库的应用账号都只有 SELECT/INSERT/UPDATE/DELETE，Flyway 建不了表；不先执行，新包启动时会试图建表而失败、服务起不来。
两份 SQL 都是「备份 → 前置核对 → DDL → 登记 `flyway_schema_history` → 核对」，进入方式写在文件头。

- 校验和按 Flyway CRC32 算，已用 near-pay 正式库已登记的 V66 / V67 / V68 / V134 核对算法一致。
- near-pay 三条 DDL 已在本机 MySQL 9.7 实际跑通；ai-shop 的 V690 / V691 在本机联调时由应用在 MySQL 9.7 上执行过。
- 执行后核对：`ai_shop_ume` 最高 691；`nearpay_prod` 最高 138。

### ② 替换 ai-shop（全端中断约 85 秒）

1. 上传包为 `/data0/app/ai-shop-v2/releases/shop-app-main-07b3abab8.jar`，核对 sha256。
2. 备份：`cp run.sh run.sh.before-opskit-07b3abab8`、`cp ai-shop-all-v2.env ai-shop-all-v2.env.before-opskit-20261123`（目录里已有大量同类备份，照惯例）。
3. `run.sh` 里把 `main-58e5af6bf` 两处（label 与 jar 路径）改成 `main-07b3abab8`。
4. `ai-shop-all-v2.env` 追加（**凭证在服务器上用 `sudo grep '^<变量>=' /data0/app/opskit/config/opskit.env` 取，直接写进文件，不要打印或贴进聊天**）：

   ```
   SHOP_OPSKIT_ENABLED=true
   SHOP_OPSKIT_BASE_URL=http://192.168.3.80:8090/api/opskit/v1
   SHOP_OPSKIT_WORKSPACE=ext:PLATFORM
   SHOP_OPSKIT_CREDENTIAL=<opskit.env 的 OPSKIT_APP_AISHOP_CREDENTIAL>
   SHOP_OPSKIT_SIGNING_SECRET=<opskit.env 的 OPSKIT_APP_AISHOP_SIGNING_SECRET>
   SHOP_OPSKIT_ENABLED_SUBJECTS=
   SHOP_OPSKIT_INTROSPECT_CLIENT_ID=<opskit.env 的 OPSKIT_NEARGO_INTROSPECT_CLIENT_ID>
   SHOP_OPSKIT_INTROSPECT_CLIENT_SECRET=<opskit.env 的 OPSKIT_NEARGO_INTROSPECT_CLIENT_SECRET>
   SHOP_GATEWAY_OPS_OPSKITPROXY_ENABLED=true
   ```

   - `ENABLED_SUBJECTS` 先留空：审批出口在，但**没有任何业务切过去**，行为与现网相同（旧入口照常可用）。
   - 透传找 OpsKit 走 Nacos 服务名 `opskit`（与找 near-pay 相同）。若 `/ops/opskit/v1/...` 返回 503，加 `SPRING_APPLICATION_JSON={"shop":{"services":{"targets":{"OPSKIT":"http://192.168.3.80:8090"}}}}`（map 的键要保持大写，环境变量写法会变小写）。
5. `run.sh all restart`；等 `Started ShopApplication`（约 85 秒）。
6. 验证：
   - 日志：`Successfully validated … migrations`、`[opskit] 流程 shop_… 已创建` 共 9–10 条（套餐付款要商城开关开着才登记）。
   - `curl -s http://127.0.0.1:18180/actuator/health` → UP。
   - 运营端令牌调 `GET /ops/opskit/v1/workspaces/ext:PLATFORM/me` → 返回角色与 `approval:*` 权限（内省 + 角色映射通了）。

### ③ 替换 near-pay（门店 ume-breakfast 中断约 40 秒）

openapi 会话已同意重启（它没在改配置，`feat/open-api-access` 已全部合入 main）。它的要求，**照做**：

- `cloud.properties` 里 `near-pay.{ops,biz}.ai-shop.base-url` **保持** `http://127.0.0.1:18182`（不能改成 `192.168.3.80:18180`，否则启动报 "identity origin must use HTTPS or loopback HTTP"）；保留 `shop.pay.open.enabled=true`、`shop.pay.open.public-prefix=/openapi/v1`。
- 启动参数沿用 `cutover-80.sh` 的 `docker run`（aishop dockerd、host 网络、用户 65534、`--memory 1g`、`-Xmx512m`、`--env-file nearpay.env`）。**不要直接跑 `cutover-80.sh rollback`**：它会启动旧的 `nearpay-fwd`（转发回 .166）。
- 重启后冒烟：`https://tpay.neargo.ai/openapi/v1/ping` 与 `/oms-api/pay-ops/v2/*` 应为 **401**，`/h5/s/ume-breakfast` 应为 **200**；结果告诉 openapi 会话。

步骤：

1. `sudo cp /data0/app/nearpay-prod/pay-svc.jar /data0/app/nearpay-prod/pay-svc.jar.before-opskit-20261123`；`sudo cp nearpay.env nearpay.env.before-opskit-20261123`。
2. 上传新包覆盖 `pay-svc.jar`（属主 nfsnobody、644，与原文件一致），核对 sha256。
3. `nearpay.env` 追加（凭证同样在服务器上从 `opskit.env` 取）：

   ```
   NEARPAY_OPSKIT_ENABLED=true
   NEARPAY_OPSKIT_BASE_URL=http://192.168.3.80:8090/api/opskit/v1
   NEARPAY_OPSKIT_WORKSPACE=ext:PLATFORM
   NEARPAY_OPSKIT_CREDENTIAL=<opskit.env 的 OPSKIT_APP_NEARPAY_CREDENTIAL>
   NEARPAY_OPSKIT_SIGNING_SECRET=<opskit.env 的 OPSKIT_APP_NEARPAY_SIGNING_SECRET>
   NEARPAY_OPSKIT_ENABLED_SUBJECTS=
   ```

   环境隔离保持关闭（`NEARPAY_ENVIRONMENT_ISOLATION_ENABLED` 不设）。
4. `docker rm -f nearpay-prod` 后按 `cutover-80.sh` 的参数 `docker run`；等 `Started PayApplication` 与 `[schema] database at V138 matches bundled pay migrations`。
5. 冒烟（上面三条）+ 日志里 `[opskit] 已切到 OpsKit 审批的业务：[]`。

### ④ OpsKit 加回调地址并重启（OpsKit 不是现网服务）

1. `sudo -u opskit cp config/bootstrap.yml config/bootstrap.yml.before-webhooks-20261123`。
2. 在 NEARGO 账户下 `operators:` 之前加（写法见 opskit 仓库 `deploy/prod/bootstrap.yml.example`）：

   ```yaml
        webhooks:
          - app: nearpay
            url: http://192.168.3.80:18093/internal/opskit/webhook
            events: [instance.*, approval.completed]
          - app: aishop
            url: http://192.168.3.80:18180/internal/opskit/webhook
            events: [instance.*, approval.completed, task.created]
   ```

3. 核对 `opskit.env` 里 `OPSKIT_NEARGO_INTROSPECT_URL=http://127.0.0.1:18180/internal/opskit/introspect`、`OPSKIT_NEARGO_TOKEN_PREFIX=otk_`。
4. `sudo systemctl restart opskit`；日志 `启动引导完成 … webhooks=2`、健康 UP。
5. 防火墙：18180 只放行 .166 与本机、18093 只放行本机 / .80 / .166 —— OpsKit 与两个服务同机，回调走 `192.168.3.80`，已在放行范围内。

### ⑤ 逐项启用并联调第一批

详细用例、停止条件与记录表见 [测试方案](./测试方案.md)；下表是摘要。

改 `SHOP_OPSKIT_ENABLED_SUBJECTS` / `NEARPAY_OPSKIT_ENABLED_SUBJECTS`（逗号分隔）后重启对应服务。**一次加一项**，测完再加下一项。

| 顺序 | 项 | 类型（启用名） | 风险 | 怎么测 |
|---|---|---|---|---|
| 1 | T1 门店开通 | `shop.store_opening` | 低 | 找一家可提交的门店（ACTIVE 且 `operational_review` 为空），运营「提交开通」→ 审批中心本人通过 → 门店营业；再驳回一家看回到可重提；旧 `operational-review/decide` 应返回 82101 |
| 2 | T10 入驻审核 | `shop.merchant_entry` | 低 | 代填一张测试申请 → 入驻页「通过」只送审（申请单 REVIEWING、无主体）→ 审批通过 → 建主体、审核人 OPSKIT |
| 3 | T11 进件审核 / 资料修订、T12 门店关联 / 账户转移 / 换绑 | `np.onboarding_review` 等 | 中 | 只在测试商户上做 |
| 4 | T5 凭据启用、T6 平台密钥启用 | `np.credential_enable`、`shop.platform_key_enable` | **密钥，先问用户** | — |
| 5 | T2 退款复核、T4 佣金规则、T7 结算放行 / 放款、T8 积分、T9 商城退款 / 核款 / 套餐付款 | `np.refund_review`、`np.commission_rule`、`shop.settle_*`、`shop.points_*`、`shop.mall_*`、`shop.plan_offline_payment` | **动钱，逐项先问用户** | T2 上线前先发布退款复核规则（没有 ACTIVE 规则 = 全部复核），且只和 `shop.pay.refund.review` 一起开 |
| — | T3 提现 | `np.withdrawal` | — | near-pay 还没有提现申请入口，**不启用** |

每项记录：送审是否进 OpsKit、申请内容（改前 → 改后）、本人审批、执行节点结果、业务状态、驳回回退、旧入口是否被拒。结果写回 `docs/technical/方案-第一批审批工作流.md` §7。

**运营端页面**：审批中心 `/approvals`、审批流程 `/approval-flows` 与支付中心「待审批」提示在 ops-web 里，静态页挂在 .166（`/data/www/ai-shop-ops-v2/releases/<版本>/ops-shop-v2/`，`current` 指向当前版本）。要在界面上联调，需从 ai-shop main（`07b3abab8`）构建 ops-web 发布到那里；只做后端联调可以先不发。

## 四、回滚

| 对象 | 怎么回 |
|---|---|
| ai-shop | `run.sh.before-opskit-07b3abab8` 与 `ai-shop-all-v2.env.before-opskit-20261123` 换回 → `run.sh all restart`（旧包 `releases/shop-app-main-58e5af6bf.jar` 原样保留） |
| near-pay | `pay-svc.jar.before-opskit-20261123`、`nearpay.env.before-opskit-20261123` 换回 → 按 `cutover-80.sh` 参数重起 `nearpay-prod` |
| OpsKit | `bootstrap.yml.before-webhooks-20261123` 换回 → `systemctl restart opskit` |
| 只想关审批 | 把对应 `*_ENABLED_SUBJECTS` 去掉那一项重启即可，业务回到原流程 |
| 数据库 | 新增的表与列不影响旧包（旧包认不到的迁移，Flyway 视为「将来的迁移」忽略）；**不要删迁移历史里的新行**（删了新包会试图重新建表）。真要撤销用执行前的备份 |

## 五、已知限制与风险

- **内存**：.80 可用约 3.2G，两个服务换包不增加进程数，但 OpsKit 开始收回调后负载略增，留意。
- **T9c 套餐付款**：流程只在商城相关开关全开时登记；**T6 平台密钥**依赖密钥服务（本机没接上未测）。
- **T9a / T9b 商城退款 / 核款**是「审批通过 → 运营再点执行」：OpsKit 上显示「已生效」时业务还没执行，要运营再点一次。
- **角色**：`pay:fee-plan:approve`（佣金规则审批人）默认只授超管；要让财务审批，在角色里勾上。
- **near-pay 测试**：整套跑时 pay-svc 偶发 404（`PayBySgsCheckoutLoopTest` / `PaymentPortLocalRemoteParityTest`，单独重跑通过），与审批无关。
- **第二批**代码也在包里（ai-shop T17 / T18 / T24；near-pay B1、C1、B3、A9、G3、G8、F11、B5），按用户要求**搁置、不启用**。
- 本机联调用过的临时环境：库 `ai_shop_opskit_e2e`（本机 3308）、会话 scratchpad 里的 OpsKit 包与脚本；与 .80 无关，可删。
