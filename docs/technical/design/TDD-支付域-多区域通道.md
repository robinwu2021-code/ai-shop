# TDD-支付域 · 多区域通道

> 状态：**方案待评审** · 创建 2026-08-31
> 上游：[TDD-支付域 · 架构与拆分路径](./TDD-支付域-架构与拆分路径.md) ·
> [ADR-021 支付域独立为服务与独立库](../ADR/ADR-021-支付域独立为服务与独立库.md)
> 配套：[TDD-支付域 · 数据库设计](./TDD-支付域-数据库设计.md) · [TDD-支付域 · API 设计](./TDD-支付域-API设计.md)
> 图：[多区域通道的三轴与筛选管线](../diagrams/pay-region-channels.svg)

---

## L1 · 定位

平台要在**中国大陆、中国台湾、阿联酋、沙特、东南亚**开展业务。
这些地区的支付**不是同一套东西**：不只是「多一个通道」，而是
钱由不同的机构清算、账单由不同的机构出、币种不同、结算周期不同、
连「一个支付方式算不算一个通道」的答案都不同。

这份文档回答三个问题：

1. **模型**：区域、通道、支付方式、币种，四者是什么关系（**不是四根平行的轴**）；
2. **展示**：商家看到哪些渠道可进件、买家看到哪些按钮可付，判据分别是什么；
3. **内置**：系统预置哪些渠道，以什么状态预置。

---

## L2 · 一、盘点：三件已有的、三件缺的

### 已经有了（不用新建）

| 已有 | 在哪 | 说明 |
|---|---|---|
| 通道的市场维度 | `sys_pay_channel.markets` | JSON 数组文本，如 `["CN"]`。**空 = 全市场可用** |
| 按市场筛通道 | `MasterDataService.enabledChannels(market)` | 已实现，且按 token 比而不是 `contains`（H2 与 MariaDB 对 `\"` 的转义不同，`contains` 会在生产成立、测试永不成立） |
| 市场清单 | `sys_setting` 的 `platform.markets` | `MarketVO(code, name, currency, timezone, rate, enabled)`，**今天只有 `CN` 一条** |

**筛选能力是现成的**，这一点很重要 —— 多区域的第一步不是建表。

### 缺的三件

**① `enabledChannels(null)` —— 两处调用都传 `null`**

```
backend/.../MerchantPaymentServiceImpl.java:95    availableChannels()  →  enabledChannels(null)
backend/.../MerchantPaymentServiceImpl.java:371   resolveChannel()     →  enabledChannels(null)
```

按市场筛的能力做好了，**而进件路径根本没把市场传进去**，等于没筛。
`null` 会落到 `DEFAULT_MARKET`（`CN`），于是今天的表现刚好是对的 ——
**这正是它一直没被发现的原因**。加一个 `TW` 的通道进去，
台湾商家和大陆商家会看到同一份列表。

> 多区域要做的第一个动作是把这两个 `null` 换掉，不是写迁移。

**② 商家没有「经营区域」这个字段**

`mch_entity` 上与地域有关的只有 `serviceCityCode`（`scope=CITY` 时有意义）、
`serviceScope`、`fulfillmentReach` —— 而 `sys_region` 是**中国行政区划树**
（PROVINCE / CITY / DISTRICT / VILLAGE，V31 从 `regions.csv.gz` 灌进去的），
**没有国家层**。

所以「按商家的经营区域展示不同渠道」这句话，今天连主语都没有。

**③ `stl_bill` 没有币种 —— 这是本文最要紧的一条**

| 表 | 有 `currency` |
|---|---|
| `ord_order` | ✅ |
| `stl_payment` | ✅ |
| `pts_user_ledger` / `stl_points_pool` | ✅ |
| **`stl_bill`** | ❌ |
| **`stl_settle_batch`**（本周新加的） | ❌ |

结算单是**按金额聚合的那张表**：收入汇总、账期批次、放款合计、对账三道门
全部建在它上面。多币种一上来，`SUM(net_minor)` 会把 12000 TWD 和 12000 CNY
加成 24000 —— **不报错，不告警，报表上就是一个大数**。

它比缺一个字段严重，因为**错误一旦落库就追不回来**：
批次总额、放款指令、对账差异都是照那个数生成的。

> 这条必须在**第一个非 CNY 的市场上线之前**做完，
> 而不是「等真的有多币种再说」——「真的有」的那一刻就是错账开始的那一刻。

---

## L2 · 二、模型：四者不是四根平行的轴

### 2.1 通道 ≠ 支付方式 —— 判据是「谁出账单」

这是整份文档最容易做错的一处。

> **通道（channel）= 谁给我出账单、谁跟我清算。**
> **支付方式（method）= 买家在收银台看到的那个按钮。**

海湾与东南亚的现实是：真正对接的多半是**聚合网关**，
一个网关下面挂着十几个本地钱包与卡组织。

举例：印尼的 GoPay / OVO / DANA / QRIS —— 如果把它们各建成一个 channel，
会得到四份进件、四份费率、四份对账 —— **而账单只有一份**，是 Xendit 出的。
对账三道门当场对不上，因为我方的四条流水在对面是同一份结算报表里的四行。

所以：

```
channel  = XENDIT          ← 进件在这里、费率在这里、账单在这里、结算周期在这里
methods  = [QRIS, GOPAY, OVO, DANA, VA_BCA, ...]   ← 收银台上的按钮
```

现有模型**已经支持**这个形状：`sys_pay_channel.pay_methods` 就是一列 JSON 数组。
今天存的是 `["JSAPI","APP","H5","NATIVE"]`（微信的接入方式），
多区域后它要能同时表达「本地钱包」这一类值 —— 见 §2.3。

### 2.2 四者的关系

```
市场 market（CN / TW / AE / SA / SG / MY / TH / ID / VN / PH）
  ├── 币种 currency   一个市场一个结算币种（MarketVO.currency，已有）
  └── 通道 channel    多对多：一个通道可覆盖多个市场（Stripe / Checkout）
        ├── 费率版本  按 (market × legal_form) 分档 —— 沙特的费率不等于泰国的
        ├── 结算周期  T+1 / T+2 / T+7，随通道也随市场
        └── 支付方式  收银台按钮，随市场收窄（Xendit 在 ID 给 QRIS，在 PH 给 GCash）
```

**币种挂在市场上，不挂在通道上。** 一个通道跨市场时它的结算币种是随市场变的，
把 currency 只放在 `sys_pay_channel` 上（今天就是这样）会在跨市场通道上给出错的答案。

### 2.3 要改的表

| 表 | 改动 | 为什么 |
|---|---|---|
| `mch_entity` | `+ market_code VARCHAR(8) NOT NULL DEFAULT 'CN'` | 商家的经营区域。**给 DEFAULT 且回填**，否则存量全是 null，筛出来是空列表 |
| `stl_bill` | `+ currency CHAR(3) NOT NULL DEFAULT 'CNY'` | 见 §一③ |
| `stl_settle_batch` | `+ currency CHAR(3) NOT NULL DEFAULT 'CNY'` | **批次不能跨币种合批** —— 唯一键要带上它 |
| `sys_pay_channel` | `+ channel_type VARCHAR(16)`（`DIRECT` / `AGGREGATOR`） | 聚合网关的对账口径与直连不同（§2.1） |
| `sys_pay_channel_rate` | 费率版本键补 `market` | 今天是 `(pay_channel, pay_method, legal_form)`，跨市场通道要按市场分档 |

> ⚠️ **加了列要补实体**（记忆里撞过一次）：`market_code` / `currency` 这两列
> 只写迁移不改 `MchEntity` / `StlBill` / `StlSettleBatch`，那一列**永远读出 null**，
> 而库里其实有值。跑 `entity-alignment` 守卫。

---

## L2 · 三、展示：两条不同的筛选链

「按经营区域展示不同渠道」在两个地方发生，**判据不一样，不要合并**。

### 3.1 B 端 · 商家能进件哪些渠道

```
sys_pay_channel.enabled = 1
  ∩ markets ∋ mch_entity.market_code          ← 今天传的是 null，要换掉
  ∩ 该主体形态（legal_form）在这个通道有费率档
  = 商家在「收款设置」里看到的渠道列表
```

**返回空列表是合法结果**，调用方必须自己处理 ——
`MasterDataService` 的注释里已经写明了这条，不要在这里兜一个默认通道：
兜底等于「没有可用通道时静默走微信」，而那是把钱发到一个可能根本没开户的通道。

### 3.2 C 端 · 买家能看到哪些按钮

```
商家已进件且状态 = OPENED 的通道
  ∩ 该通道在本市场开放的 pay_methods
  ∩ 端上能力（iOS 才有 Apple Pay、微信内才有 JSAPI）
  = 收银台上的按钮
```

**买家所在地 ≠ 商家经营地。** 跨境时两者可能不同 ——
本方案的口径是**以商家的经营区域为准**（钱结给商家，账期与币种都随他），
端上能力只做最后一层收窄。

> 反过来（按买家 IP / 手机号归属地选渠道）在合规上要谨慎得多，
> 且会让同一笔订单在不同人手里走不同通道，对账时无法解释。**本期不做。**

---

## L3 · 四、内置渠道：预置什么、以什么状态预置

### 4.1 预置状态：**全部 `enabled = 0`**

内置不等于开启。理由有两条，第二条更实际：

1. 渠道要**签约**才能用。内置一堆开着的渠道，商家在收款设置里会看到
   一整屏他根本签不下来的通道，而每一个都能点进去提交进件。
2. 记忆里那条「默认关闭的那一半没人测」在这里要**反过来读**：
   默认关闭是对的，但**开启路径必须有测试** —— 否则真到签下某个通道那天，
   打开开关才发现装不起来。

所以：种子写进迁移，`enabled=0`；运营在通道设置页逐个开。

### 4.2 预置清单

**中国大陆 · CN · CNY**

| 通道 | 类型 | 支付方式 |
|---|---|---|
| `WECHAT` 微信支付 | DIRECT | JSAPI · APP · H5 · NATIVE |
| `ALIPAY` 支付宝 | DIRECT | APP · H5 · NATIVE |

（这两个是今天就有的，不属于新增。）

**中国台湾 · TW · TWD**

| 通道 | 类型 | 支付方式 |
|---|---|---|
| `LINEPAY` LINE Pay | DIRECT | 钱包 |
| `JKOPAY` 街口支付 | DIRECT | 钱包 |
| `ECPAY` 綠界 | AGGREGATOR | 信用卡 · ATM 轉帳 · 超商代碼 · LINE Pay |
| `NEWEBPAY` 藍新 | AGGREGATOR | 信用卡 · ATM · 超商 |

**阿联酋 · AE · AED**

| 通道 | 类型 | 支付方式 |
|---|---|---|
| `NGENIUS` Network International | DIRECT | 卡 · Apple Pay · Google Pay |
| `TAP` Tap Payments | AGGREGATOR | 卡 · Apple Pay · benefit · KNET |
| `PAYTABS` | AGGREGATOR | 卡 · Apple Pay |
| `TABBY` / `TAMARA` | DIRECT（BNPL） | 分期 |

**沙特 · SA · SAR**

| 通道 | 类型 | 支付方式 |
|---|---|---|
| `HYPERPAY` | AGGREGATOR | mada · 卡 · Apple Pay · STC Pay |
| `MOYASAR` | AGGREGATOR | mada · 卡 · Apple Pay |
| `PAYTABS` / `TAP` | AGGREGATOR | 同上（跨市场通道） |
| `TAMARA` / `TABBY` | DIRECT（BNPL） | 分期 |

> **`mada` 是沙特的本地卡网，不是一个通道** —— 它是网关下面的一个
> `pay_method`。把它建成 channel 会得到一份永远不会有账单的进件。
> 这正是 §2.1 那条判据的用处。

**东南亚**

| 市场 | 币种 | 通道 | 类型 | 支付方式 |
|---|---|---|---|---|
| SG 新加坡 | SGD | `STRIPE` · `ADYEN` | AGGREGATOR | PayNow · 卡 · GrabPay |
| MY 马来西亚 | MYR | `IPAY88` · `RAZER` | AGGREGATOR | FPX 网银 · TNG eWallet · DuitNow QR |
| TH 泰国 | THB | `OMISE` · `2C2P` | AGGREGATOR | PromptPay · TrueMoney · 卡 |
| ID 印尼 | IDR | `XENDIT` · `MIDTRANS` | AGGREGATOR | QRIS · GoPay · OVO · DANA · VA |
| VN 越南 | VND | `VNPAY` · `MOMO` | DIRECT | QR · 钱包 |
| PH 菲律宾 | PHP | `XENDIT` · `PAYMONGO` | AGGREGATOR | GCash · Maya · QR Ph |

**跨市场 · 国际通用**

| 通道 | 类型 | 覆盖 |
|---|---|---|
| `STRIPE` · `ADYEN` · `CHECKOUT` · `AIRWALLEX` | AGGREGATOR | 多市场，`markets` 列多个码 |
| `PAYPAL` | DIRECT | 多市场 |

> **清单是「预置什么」，不是「已经能用什么」。** 每一个都要走
> 签约 → 密钥配置 → 沙箱联调 → 开关打开。
> 文档里列出来只是让运营在通道设置页看得到、且不用先提需求才能加一行。

### 4.3 显示名以运营配置为准

`sys_pay_channel.name` 与 `platform.markets` 里的 `MarketVO.name` 都是**可配的**。
迁移里给的是初值，不是硬编码 —— 地区与品牌的中文写法各地有各地的惯例，
写死在代码里会变成一个要发版才能改的字。

---

## L3 · 五、分步（每步单独可验）

| 步 | 动作 | 闸门 |
|---:|---|---|
| 1 | `stl_bill` / `stl_settle_batch` 加 `currency`，回填 `CNY`；实体补字段 | `entity-alignment` 守卫；**批次唯一键带上 currency** |
| 2 | 所有金额聚合处按 currency 分组（收入汇总、批次合计、对账三道门） | 造一条 TWD 的单，断言它**没有**被算进 CNY 的合计 |
| 3 | `mch_entity` 加 `market_code`，回填 `CN` | 同上守卫 |
| 4 | 把两处 `enabledChannels(null)` 换成商家的 `market_code` | 造一个 `TW` 商家，断言他**看不到**只在 CN 的通道 |
| 5 | `sys_pay_channel` 加 `channel_type`；费率版本键补 `market` | 现有费率取值不变（回归） |
| 6 | 内置渠道种子（全部 `enabled=0`） | 断言开启任意一条后 `enabledChannels` 能返回它 |
| 7 | C 端收银台按 method 展示 | 等 C 端支付从桩变成真实现（今天只有一个桩端点） |

**第 1–2 步与多区域无关，是今天就该补的欠账** —— 它们在单币种下也是对的，
只是没有第二个币种时看不出错。

---

## L4 · 六、待确认

1. **首发市场**：先做哪一个？建议 **TW 或 SG**（币种简单、通道成熟、法规负担轻），
   把整条链走通之后再进海湾 —— 沙特与阿联酋的进件材料与合规要求重得多。
2. **`market_code` 挂在主体还是门店**：本文按**主体**（`mch_entity`）。
   一个主体在两个市场经营的话要开两个主体 —— 这与「收款进件按主体」是同一条口径。
3. **汇率**：`MarketVO.rate` 已经有了（相对 CNY）。它今天只用于**展示折算**，
   **不参与结算** —— 结算按各自币种独立成批。这条要写死，否则汇率一动历史账就变。
4. **BNPL（Tabby / Tamara）算不算支付通道**：它的资金流是「机构先垫付、后向买家收」，
   对商家来说仍是一笔到账，但**退款与追偿链路不同**。本文暂按普通通道预置，
   真接的时候要单独评审 —— 它会碰到 [账期与对账放款方案](./账期与对账放款-方案.md) 的 Z4 追偿。
