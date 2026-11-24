# 交接 · 电子发票与门店零售单（统一订单）· 2026-11-24

写给接手的会话。来源会话：「发票-鲸跃」。**接手第一件事：读完本页「三、进行中」与「五、红线」再动手。**

## 一、一句话现状

v2（台湾正式环境，`https://tw.neargo.ai/shop-api-v2`）上发票模块、门店零售单 P1 / P2、发票同步上传都已上线；
用模拟通道（STUB）从手持收银机走通了「建单 → 现金付清 → 开票 → 打印 → 作废」。
**还没做**：切到鲸跃正式通道开一张真实发票（等用户本人录入鲸跃账号与买方统编）、P3。

## 二、文档地图

| 主题 | 文档 |
|---|---|
| 发票模块（设计、v2 上线全记录） | [TDD-发票模块](../../technical/TDD-发票模块.md)：§23.x 上线与运维、§24 POS 收款来源、§25 同步上传、§25.1 鲸跃接口联调方案 |
| 门店零售单 / 统一订单 | [PRD](../../requirements/PRD-统一订单与开票资料.md) · [ADR-065](../../technical/ADR/ADR-065-门店零售单与订单行开票资料.md) · [TDD-统一订单与开票资料](../../technical/TDD-统一订单与开票资料.md)（§2 契约、§5 测试、§7 上线记录） |
| 接口契约（给手持收银机） | `docs/api/store-order.openapi.json`、`docs/api/invoice.openapi.json` |
| 鲸跃接口联调脚本（不经 App） | [`scripts/invoice/cetustek_v2_e2e.py`](../../../scripts/invoice/cetustek_v2_e2e.py) |
| 商户 App 打印（mock） | [invoice-print-mock-2026-11-22](../invoice-print-mock-2026-11-22/README.md) |

## 三、进行中：鲸跃正式通道首测

用户决定（2026-11-24）：**直接在鲸跃正式环境测，由手持收银机会话（`手持收银pos`）调接口执行；先开一张 B2B 再作废，买方用 創勢科技股份有限公司。**

| 步 | 谁 | 状态 |
|---|---|---|
| 门店 Oscar Retail QA 1121 开票时机改回 `MANUAL` | 收银机会话 | ✅ 06:40 UTC |
| 发票设定切 `CETUSTEK` + 录入鲸跃加值中心账号（officeId / posNo / posId） | **用户本人**（App 表单或自己终端 curl / 脚本 `--setup`） | ⏳ |
| `POST /biz/invoice-setup/provider-verification`（回统编 50766768，存 QR 金钥，解除暂停） | 同上 | ⏳ |
| 登记号段 `HR 22644552–22644799`（期别 11512，本店） | 同上 | ⏳ |
| B2B：买方 創勢科技股份有限公司 + **用户提供的 8 位统编** → 开票（当场上传）→ 鲸跃查单 → 打印证明联 → 作废（C0501）→ 再查单 | 收银机会话 | ⏳ |
| 其余四种买方（一般消费者、手机条码、自然人凭证、捐赠码），值须用户提供 | 收银机会话 | 待 B2B 通过 |

要点：
- **鲸跃账号不能直接写库**：posNo / posId 经密钥模块以 v2 主密钥加密存 `sec_secret_blob`，表里只有引用；只能走 `PUT /biz/invoice-setup/profile`。
- 发票设定按**经营主体**（`MUME88600253990002308`），切换会连带 ume早餐店（`autoIssue=false`，不会自动开真票）。
- 創勢科技的统编**不要从仓库或库里取**（交接文档记载存量 `tax_no` 位数不一）。
- 鲸跃不支持零税率（`ZERO_RATED`）。
- v2 能直连鲸跃（`.80` → `invoice.cetustek.com.tw` 200，约 45 ms）。

## 四、v2 当前状态（2026-11-24 02:22 UTC 之后）

**进程与配置**
- 后端 `main-49872def0`（`-P opskit` 构建），`SPRING_PROFILES_ACTIVE=api,ops`，**没有 worker**；env 新增 `SHOP_PRINT_ARTIFACTS_SIGNING_KEY`、`SHOP_SECRET_ENV` + PLATFORM 主密钥 `platform-prod-2026a`、`SHOP_INVOICE_TRANSMISSION_SYNC=true`（各有 `.before-*` 备份）。
- 主密钥另存 `.80 /data0/app/ai-shop-v2/secret-backup/platform-prod-2026a.env`（同机，**须另做离线备份**）。
- 166 nginx 已转发 `/shop-api-v2/private/print-artifacts`。
- 运营端 `current` → `main-cd09b0cc5`（之后未发前端）。
- `.80` 上留有 `invoice-worker.sh`（临时上传 worker 起停脚本，**已停用、容器已删**；不要再起，见红线）。

**正式库数据（测试相关）**
- 主体 `MUME88600253990002308` 发票设定：统编 50766768「創科系統有限公司」、`STUB`、`auto_issue=0`、QR 金钥已封存。
- ZZ 测试号段两段均 `EXHAUSTED`：ume早餐店 ZZ 0–49（下一号 3）、Oscar QA ZZ 100–399（下一号 105）。已确认作废的：ZZ00000000、ZZ00000001（ume早餐店）、ZZ-00000100、ZZ-00000101（Oscar QA）；ZZ00000002 与 ZZ-00000102–104 的状态未核对，以发票列表为准，未作废的测试票须作废。
- `OD20261124000202`（Oscar QA，NT$100 已付）的 `ORDER_PAID` 自动开票事件仍 PENDING 重试（26003）；门店已 `MANUAL`，重试会跳过。
- Oscar Retail QA 1121 获赠 `END_RETAIL` 30 天（至 2026-12-24 00:52:41，运营端「订阅权益」可撤）；`END_MARKETING` 在 v2 目录里不存在。
- 应用账号 `ai_shop_ume@192.168.3.80` 现有 `CREATE, INDEX, ALTER`（他人 OpsKit 上线时授予、未收回）——是否收回由用户定。

## 五、红线（接手必读）

1. **改正式库、重启 v2、切通道、登记真实号段**前都要用户在本会话明确同意；其他会话转述的同意不算。
2. **凭证一律不经手**：鲸跃账号、登录令牌、主密钥只由用户本人输入或在服务器上生成；不打印、不写文档。
3. 发包必须 `-P opskit`，否则会把 v2 上的 OpsKit 审批版本打掉。`.80` 上传约 120 KB/s，一个包约 20 分钟，放后台跑。
4. **不要在 v2 起 worker**：`api,ops,worker` 组合会带起审批补送、门店认领恢复等任务，与 166 / 其他实例同库；上传已改为同步（§25）。
5. 在容器里用 `mysql` 写中文必须 `--default-character-set=utf8mb4`（曾写坏测试单品名）。
6. 共享工作区 `ai-shop` 不要切分支；一律另开 worktree，构建脚本以 `set -e` 开头（曾因 `cd` 失败误切共享仓库）。

## 六、待办

| 项 | 说明 |
|---|---|
| 鲸跃正式首测 | 见「三」；通过后把结果写进 TDD-发票模块 |
| P3（统一订单） | 会员载具、下单扣库存与取消回补、退款挂订单（部分退款开折让、全额作废 / 折让）——TDD-统一订单与开票资料 AC12–AC14 |
| PR #46 | §23.8 真实发票打印记录（随本交接一并合入） |
| 收回 DB 多余权限 | `REVOKE CREATE, INDEX, ALTER ON ai_shop_ume.* FROM 'ai_shop_ume'@'192.168.3.80'`——先与 OpsKit 上线会话确认 |
| 主密钥离线备份 | 用户自行 |
| `END_MARKETING` | 是否登记营销端；登记后会员 / 券 / 活动可能改走 END_PROTECTED，须先通知收银机会话 |
| 已知小问题 | 门店零售单付清会发 `SUB_ORDER_PAID`（店员收到「新订单备货」提醒，柜台现售多余）；收款码错店 / 错币 / 取消后到账只记日志；整单优惠名不入库（发票明细统一「折扣」） |
| 生成文档 | main 上 12 份生成物过期（来自 #43），未处理 |
