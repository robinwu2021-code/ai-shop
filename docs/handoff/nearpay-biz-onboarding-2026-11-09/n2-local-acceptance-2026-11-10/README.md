# N2 本地联调证据（2026-11-10）

结果：真实 App → ai-shop → 独立 near-pay → OPS HTTP 客户端联调通过。N1 认领及恢复证据见[同批 N1](../n1-local-acceptance-2026-11-10/README.md)。TDD、ADR 修订先于实现；旧 host BIND/QUAL 方案已替代。

## 已验证的旅程

1. 真实商户 USR 密码登录、进入身份、选择门店，读取实际 typed PAY 成员；新认领带当前门店、主体和真实支付商户头。
2. 不支持的市场渠道创建请求产生固定 REJECTED 原键回执，不落 DRAFT；新意图再创建 NEWEBPAY 草稿。
3. 私有证明经实际 multipart、AES-GCM 和数据库 SecretVault 封存。上传成功回包丢失、原命令查询暂时 503 后，App 保存原 key/version，不重复上传，也不把 503 当作不存在。
4. 两个 JVM 在原数据库、同主密钥和会话下重启；新 App controller、文件 journal、关闭新建的 coordinator 仅用 GET 恢复原上传，证明内容一致，原日志结束。随后提交 PENDING_VERIFY 并 pin 证明。
5. 真实 OPS 身份/权限/商户数据范围检查、私有证明读取及未登录读取拒绝；沿既有登记 API 创建实际账户。错误账户版本与正确关联两次均丢失回包，生产 OPS 客户端分别恢复 REJECTED 和 SUCCEEDED 原键回执。
6. 最终 claim=LINKED；实际账户仍 UNVERIFIED/PENDING、版本未因认领改变、门店绑定数为 0。host 门店仍归 M，影子 payment/qualification 行均为 0；PAY 有一份已 pin 的加密证明。

`result.json` 包含跨库断言与故障计数；`dart-result.json` 包含实际 App 请求路径及公开标识；`ops-result.json` 包含 OPS 回执/账户断言；`source-hashes.json` 为采集时关键源码摘要。以上不含令牌、口令、证明字节或渠道号明文，也不将其他会话改动归为本批作者。

运行：`python3 scripts/verify-channel-account-claim-local.py`。仅 loopback MySQL9 与独立临时 schema；本次 runner 已终止两进程、删除 ready 凭据并清理自有数据库。无 166 访问/部署、无供应商渠道调用、无 git 提交/推送。

## 验证边界与剩余工作

- BIZ 是真实密码旅程；OPS 使用受控持久 DB 会话，非 OPS 密码登录。fixture 通过真实 DataScopeService、不同录入/启用人配置本次 TW 商户范围，不代表数据范围审批 HTTP 旅程验收。实际每次请求仍运行生产鉴权及 scope 检查，没有 wildcard 或 ApprovalGate 绕过。
- PAY 为单 SANDBOX 实例；不宣称此旅程覆盖混环境生产部署。N1 环境历史门闩已有独立真实隔离包装器定向验证。
- host 轻量装配沿 N1 的实际认证、DB 会话、mapper、业务服务和必要测试 stub；非全应用浏览器验收。OPS 使用真实前端 HTTP/recovery 实现，PDF 链接逻辑经静态审查，浏览器实际打开效果未验收。
- 定向验证：PAY 19 domain + 2 portal + 3 guard；host 35 项（首轮 33 过、修正两处测试夹具/旧预检断言后仅复跑失败两项均过）；App N2/TW 16 + 新增长度边界 1、经营配置文案 1；OPS recovery 6、实际 HTTP 旅程 1。相关 App analyze 和 OPS tsc 通过。未运行无关全量检查。N1 旧商品流程的 H2 FORCE INDEX 不兼容限制见 N1 证据。
- 后续优先：独立库存归属事实/预留协议；其余渠道的真实目录与运行时合同；实际渠道核验、启用、显式门店绑定/readiness 和真实收款验收。N2 LINKED 本身不改变这些状态。

## 发布准备，尚未部署

- 迁移：host V647；独立 PAY V77 与 V79。本批保留并消费已有 V78，不抢占或改写该迁移；发布前按真实库迁移历史核对顺序。
- N1 host `shop.merchant.store-claim.enabled`、App `STORE_ENTITY_RELINK_ENABLED` 默认关闭；已有业务单恢复不因新建关闭而丢失。非 RETAIL、已有商品/经营配置及独立库存启用但缺归属协议的实例按 blocker 拒绝。
- N2 host/PAY `NEARPAY_CHANNEL_ACCOUNT_CLAIMS_ENABLED` 默认关闭；host 仍需既有 `NEARPAY_BIZ_ONBOARDING_ENABLED` 与固定 `NEARPAY_BIZ_ORIGIN` 支持身份及成员路径。
- App 需同时启用 `NEARPAY_BIZ_ONBOARDING_ENABLED`（共享会话/成员基础）与 `CHANNEL_ACCOUNT_CLAIMS_ENABLED`；当前界面首批仅开放 NEWEBPAY。关闭新建仍允许查询恢复原单。
- 正式放开前需核对实际 PAY typed 委托、OPS 数据范围、私有 Vault 配置及私有文件权限。166 继续保持不部署。
