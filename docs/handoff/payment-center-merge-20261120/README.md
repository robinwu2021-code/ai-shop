# 交接：支付中心分支合并与测试（给 Robin，2026-11-20）

> 这份文档请 Robin 照着做：把两个分支合并、跑测试、推送。两个分支**都还没推到 GitHub**，推送在 pre-push 的后端测试那一步中断了，原因见 §4。
> 线上现状、今天动过的生产、回退方法写在发布线的 `docs/handoff/payment-center-v2-20261120/README.md`（分支 `release/v2-paycenter-20261120`），这里不再重复。

## 1. 要合的两个分支

| 分支 | 工作树（本机） | HEAD | 内容 |
|---|---|---|---|
| `feat/ops-pay-center-menu` | `~/work/ai-shop-paycenter` | `984000d2b` 加本文档 | 支付中心开发线，从 `origin/main` 拉出，已包含 main；与 `origin/main` 试合并无冲突 |
| `release/v2-paycenter-20261120` | `~/work/ai-shop-v2rel` | `b01265391` | v2 发布线：网关 `feat/gateway-mp-index` 加上支付中心；正式环境 v2 跑的就是这条线（后端 `b4335120a`，前端 `e86b5ddc0`） |

### 开发线上的提交（旧 → 新）

| 提交 | 内容 |
|---|---|
| `bd35f1058` | P0/P1：支付中心原生页面（表格 + 操作骨架） |
| `8765add55` | P2：退款、终端、应用、追踪、告警、配置 |
| `0a6fbec0a` | 菜单：「商户管理」改为「支付中心」，V684 功能点 |
| `baecf5033` | P3a：资金、对账、储值、费率（V685） |
| `c15a55aab` | P4：支付中心成为独立模块，商户档案与渠道进件工作区原生化 |
| `1cc0c2b70` | 保留 V671 的菜单地址，由前端映射到支付中心（原 V686 移到 `docs/technical/pending-sql/ops_payment_center_own_module.sql`） |
| `34f660411` | FormDrawer 多选字段改用 MultiSelect |
| `43234bab6` | 重新生成 `docs/technical/design/规范-运营端.md` |
| `1ec41ef08` | **为推送修守卫**（见下表） |
| `984000d2b` | 重新生成词条表（`docs/technical/reference/glossary.json`、`中英文对照-词条.md`） |

`1ec41ef08` 修了哪些守卫：

| 守卫 | 改动 |
|---|---|
| `ops-ui-discipline` 焦点环 / 控件高度 | 支付中心的 select、input、链接加上 `focus-ring`，`h-9` 改为 `h-[var(--ctl-h)]`（`merchant-workspace.tsx`、`onboarding-workspace.tsx`、`_kit/pay-kit.tsx`） |
| `env-consumed` | `ops-web/.env.local.example` 声明 `NEXT_PUBLIC_PAY_OPS_API_BASE`（留空表示 `/oms-api/pay-ops/v2`） |
| `copy-slots` 短文案 | 把 `actionKit.draftUnavailable` 缩短到 16 个汉字以内，简体和繁体都改了 |
| `nav-function-point` | 守卫读取 `module.ts` 的 `LEGACY_TAB_REDIRECT`，把库里 5 个旧支付菜单地址按前端的映射换算后再比较。待执行的 SQL 跑完、映射删掉之后，这一步解析结果为空，守卫自动回到只看迁移 |
| `doc-standard` | main 上已有的 `docs/handoff/ume-sync-2026-11-19/渠道密钥接口调用方案.md` 里有两个跨仓库的 md 链接，改成纯文本路径（near-pay 仓库）。**这一条不是本分支引入的**，是 main 自带的 |

## 2. 合并顺序（建议）

1. **发布线补上开发线的最后三个提交**。发布线还缺 `43234bab6`、`1ec41ef08`、`984000d2b`（以及本文档），其余提交已经在里面：
   ```bash
   cd ~/work/ai-shop-v2rel && git merge --no-ff feat/ops-pay-center-menu
   ```
   如果嫌合并面太大，也可以只挑这三个提交：`git cherry-pick 43234bab6 1ec41ef08 984000d2b`。
2. **先推开发线，再推发布线**（两条都需要过 pre-push，见 §3）。
3. 合进 main 的 PR 从 `feat/ops-pay-center-menu` 开。发布线带着网关分支 `feat/gateway-mp-index`，网关有自己的合并节奏，不要随这条线一起进 main。
4. **另一方在 v2 上发的 collection 补丁 `6ee1a6c98` 不在这两个分支里。** 下次发 v2 之前要先把它合进发布线，否则会丢。

## 3. 测试怎么跑

前端（本机实测：开发线 HEAD 全绿）：

```bash
cd packages/shared && npx vitest run      # 721 条全过
```

```bash
cd ops-web && npx vitest run && npx tsc --noEmit    # 1377 条全过，tsc 无输出
```

```bash
cd b-app && npx vue-tsc --noEmit          # c-app 同理；两端都通过
```

后端：直接 `git push` 就会触发 pre-push，它在干净的 HEAD 副本上跑整套后端测试，日志路径会打印出来。

支付中心的手工回归（v2 正式环境 `https://tw.neargo.ai/ops-shop-v2/payment-center/`，**只在测试数据上做写操作**）：
- 31 个页签逐个打开，确认无报错；
- 商户档案：Oscar 门店 `STUME12494685770267754496` 对应测试商户 `PM0E0F6CC8F7454D75`，改收款策略后要复原；
- 渠道进件：新建 LINE Pay 进件后立即撤销；
- 旧书签 `/merchants?tab=payment`、`/orders?tab=payments` 应跳到支付中心。

## 4. 推送卡在哪、本机环境怎么准备

推送前后撞了几道 pre-push 闸门。属于代码的问题都已修复并提交（§1），剩下的都是工作树环境问题：

1. **工作树依赖不全**：b-app、c-app 的 `node_modules` 几乎是空的，vue-tsc 报找不到 `@vue/tsconfig`。另外只在单个子包里执行 `npm install`，会把根目录共享的 `opencc-js` 删掉，ops-web 守卫随之变红。正确做法是在仓库根目录跑一次：
   ```bash
   npm ci --no-audit --no-fund
   ```
2. **commerce 的真库测试缺环境变量**：`backend/commerce` 下大约 60 条 `*MysqlTest` 会报 `Real MySQL required: set COMMERCE_MYSQL_URL`。本分支没有动 commerce，推送前需要先导出这三个变量：
   - `COMMERCE_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8`
   - `COMMERCE_MYSQL_USER=commerce_test`
   - `COMMERCE_MYSQL_PASSWORD=<本机测试口令>`

   本机 MySQL 用 `~/work/tools/mysql-local start` 启动。`commerce_test` 账号只在本机，权限限于 `commerce\_test\_%` 库。口令今天在本机重设过，写在当前会话的临时目录里；如果找不到，用本机 root 给它重设一个口令就行，不需要任何生产口令。
3. 上一次推送（已设好上面的变量）是在跑后端测试时被中断的，**没有结果**。GitHub 上两个分支都还不存在。

## 5. 合并之后的待办

| # | 事项 |
|---|---|
| 1 | 执行 `docs/technical/pending-sql/ops_payment_center_own_module.sql`（原 V686，v1 已停，现在可以执行），然后删掉 `module.ts` 的 `LEGACY_MENU_HREFS` 映射；守卫的换算会自动失效 |
| 2 | near-pay：推送 `59c575bd`（V132），合入 `develop/s1`（含修复二 `fa708146c`），整包发布后才能代建支付商户 |
| 3 | 支付中心各页按岗位授权（现在只有超管能用） |
| 4 | v2 遇到不带签名的付款凭证图返回 404 时，报 `ClassCastException ApiResult → Resource` |
| 5 | 带 `?action=create` 直接打开页面时，新建抽屉不弹出 |
| 6 | 补测蓝新进件：草稿编辑、附件上传、证照识别、提交审核到审批 |
