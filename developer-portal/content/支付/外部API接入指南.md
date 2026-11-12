---
Sort: 20
title: "Near Pay 外部 API 接入指南"
description: "从应用与密钥申请，到请求签名、支付、退款和异步通知的服务端接入说明。"
version: "1.1"
last_updated: "2026-11-12"
---

# Near Pay 外部 API 接入指南

本指南面向使用Near Pay收款的第三方开发者。业务服务器调用开放API，Near Pay负责收款路由、交易状态及退款处理。AppSecret、EncryptKey和签名运算均放在你的服务器。

阅读顺序：**申请应用 → 配置回调与授权 → 领取并启用密钥 → 验证签名 → 建单支付 → 接收通知/查单 → 退款 → 生产验收。**

当前协议为`/open/pay/v1`、HMAC-SHA256签名及AES-256-GCM字段/通知加密。支付方式、币种和退款能力以平台为应用及门店实际开通的范围为准。

## 1. 接入前准备

准备以下信息提交给平台指定的申请入口或对接人员。当前开放API不提供匿名注册、申请密钥、审批或自行授权门店的接口；由平台办理这些操作后，再使用本指南的交易API。

| 申请信息 | 需要提供的内容 |
|---|---|
| 接入方与应用 | 企业/平台名称、应用名称、用途、技术联系人及故障联系渠道 |
| 业务归属 | 收款主体、拟接入门店、经营地区与币种 |
| 环境 | 沙箱或生产；分别申请，凭证不能混用 |
| 所需能力 | 查询、收款、退款；只申请需要的能力 |
| 来源IP | 业务服务器固定出口IP或CIDR网段；容器内部IP通常不是平台看到的出口IP |
| 回调 | 公网HTTPS通知URL、允许的回调域名、接收服务负责人 |
| 容量与额度 | 预计峰值QPS、退款需求、申请每日退款额度 |
| 支付方式 | 希望接入的终端/浏览器环境及支付方式，由平台确认可用产品 |

回调服务必须能被平台访问，证书有效，域名解析不能指向回环、私网或链路本地地址。回调不是顾客支付后的浏览器跳转页；无需依赖浏览器登录Cookie。

### 1.1 开通节点与完成标志

| 节点 | 平台办理事项 | 你需要确认 |
|---|---|---|
| 1. 登记应用 | 登记归属、用途、环境，分配AppId，应用处于待启用状态 | AppId、应用归属及环境正确 |
| 2. 配置能力 | 配置READ/COLLECT/REFUND、限流、退款额度与出口IP | 查询、收款和退款各自的权限与额度 |
| 3. 配置并核验回调 | 登记通知URL及域名，完成平台侧核验 | URL能接收POST；核验方式按平台提供的联调要求执行 |
| 4. 审批并启用应用 | 平台审核后将应用启用 | 应用ACTIVE；仅拿到AppId尚不能调用 |
| 5. 签发并启用密钥 | 应用启用后生成AppSecret/EncryptKey；密钥经验证和审批启用 | 安全领取一次性明文及序列号；确认可用版本已启用，DRAFT密钥不能调用 |
| 6. 授权门店 | 为应用授予具体门店及动作，由平台/商户授权管理流程办理 | `storeRef`及该门店实际授予的动作 |
| 7. 联调与验收 | 验证签名、业务结果、回调及异常恢复 | 沙箱验收通过，再单独办理生产开通 |

应用启用、密钥启用、门店授权是三个独立条件。平台登记的应用业务动作与接口scope对应如下：

| 业务动作 | 接口scope | 可执行的调用 |
|---|---|---|
| READ | `pay:read` | 查支付、查退款、查门店可用支付方式 |
| COLLECT | `pay:create` | 建单、发起支付、关闭支付单 |
| REFUND | `pay:refund` | 发起退款 |

最终可执行范围是**应用当前权限 ∩ 门店授权动作 ∩ 门店已开通产品及当前业务限制**。应用有退款scope，也需要原支付所属门店的退款授权和足够额度。

### 1.2 领取的接入资料

| 资料 | 用途与处理方式 |
|---|---|
| API_BASE_URL | 平台提供的环境访问地址；以下示例假设为HTTPS origin，无额外路径前缀 |
| AppId | 请求头`X-App-Id`的值；就是平台分配的应用号 |
| AppSecret | 请求签名及响应/通知验签使用；前缀通常为`sk_test_`或`sk_live_` |
| 签名密钥序列号 | 请求`X-Key-Serial`及响应/回调的签名密钥版本选择；作为不透明字符串保存 |
| EncryptKey | 32字节UTF-8密钥，当前签发为32位字母数字；用于敏感字段和通知resource |
| 加密密钥序列号 | 请求`X-Encrypt-Serial`及通知`resource.keySerial`的版本选择 |
| storeRef | 平台提供、且已授权的门店业务号；不是内部数据库ID |
| 回调配置确认 | 已登记URL、核验状态、是否启用加密通知 |
| 限额与能力 | QPS、每日退款额度、币种、支付方式及退款限制 |

密钥明文只在签发时提供一次。丢失后申请新版本；不要依赖再次查询明文。应用密钥、支付渠道密钥、POS设备密钥用途不同，不能相互替换。AppSecret的展示前缀不参与HMAC，EncryptKey不去前缀、不做Base64解码。

## 2. 公共请求与响应

### 2.1 地址与环境

```text
{API_BASE_URL}/open/pay/v1/orders
```

沙箱和生产使用各自的AppId、密钥及授权。域名、环境入口和可用渠道由平台提供，不以示例域名或密钥前缀猜测地址。不要自行增加`/api`前缀或把内部/运营端接口当开放API调用。

示例中的`APP-DEMO`、`STORE-DEMO-001`、`P-DEMO-001`均为占位值。真实请求要替换为平台资料；金额示例不代表任何门店必然支持的限额。

### 2.2 必需请求头

| 请求头 | 必填 | 说明 |
|---|---|---|
| `Content-Type` | JSON请求必填 | `application/json; charset=UTF-8` |
| `X-App-Id` | 是 | 应用AppId |
| `X-Timestamp` | 是 | 当前UTC毫秒的十进制字符串，与平台时间偏差不超过300秒 |
| `X-Nonce` | 是 | 本次HTTP请求独有，8—64字符；推荐32位随机十六进制字符串 |
| `X-Signature` | 是 | 按第3节P1计算的标准Base64签名 |
| `X-Key-Serial` | 建议提供 | 平台分配的签名版本序列号；轮换时显式指定 |
| `X-Encrypt-Serial` | 传加密字段时提供 | 该次敏感字段使用的EncryptKey序列号 |
| `X-Request-Id` | 否 | 排障关联号；不替代业务幂等号 |

每次重试重新生成timestamp、nonce和signature，保留相同业务单号及业务参数。不要把上次已签名的完整HTTP请求原样重放。

### 2.3 成功与失败信封

```json
{
  "code": 0,
  "message": "OK",
  "data": {},
  "requestId": "REQ-DEMO-001"
}
```

`code=0`表示该API操作成功。支付结果还要读取`data.status`；HTTP 200、建单成功和支付参数返回都不代表钱已到账。错误响应同样使用`code/message/data/requestId`，先处理HTTP状态，再处理非零业务code；message供展示/排障，不作为程序分支依据。

### 2.4 数值、时间和字段约定

- JSON字段使用camelCase；JSON请求体用UTF-8。
- 金额字段`amountMinor/paidMinor/refundedMinor`是原币种最小单位的整数，例如12000表示120.00的两位小数币种金额；具体精度按平台提供的币种配置，不能假定所有币种都有两位小数。
- 当前开放API以JSON整数传金额，不能传`120.00`或依赖自动转换字符串。JavaScript应使用安全整数范围内金额；超出安全范围时先与平台确认兼容方案。
- 时间字段为UTC毫秒整数，未发生的时间和不适用字段可为null。
- 查询/响应中的未知新增字段可忽略；未知状态保持待核对，不默认当成功或失败。

## 3. 签名、验签与加密

### 3.1 P1：请求签名

先完成敏感字段加密，再对**最终要发送的原始请求体字节**计算签名。

```text
stringToSign =
METHOD + "\n" +
PATH + "\n" +
CANONICAL_QUERY + "\n" +
APP_ID + "\n" +
TIMESTAMP + "\n" +
NONCE + "\n" +
lowercase_hex(SHA256(rawBody))

X-Signature = Base64(HMAC-SHA256(hmacKey, UTF8(stringToSign)))
```

规则：

1. METHOD大写；PATH只含API路径，不含协议、域名、查询串或fragment。使用平台实际接受的路径，不自行加减尾部`/`。
2. 待签串是7行，行分隔符是LF，没有末尾换行。无query时第3行仍保留为空行。
3. QUERY先按解码后的参数名排序，同名参数按值排序；名和值分别RFC3986编码，再用`=`和`&`连接。空格为`%20`，字面`+`为`%2B`，`~`不编码。
4. GET无请求体时哈希空字节串。空body的SHA-256为`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`；不是`{}`的哈希。
5. `hmacKey`是AppSecret去掉`sk_test_`/`sk_live_`前缀后的UTF-8字节；不再做Base64/Hex解码。
6. 签名采用标准Base64，不是Base64URL，也不是十六进制。
7. body序列化一次，计算摘要后发送同一份字节。空格、中文编码、换行或字段顺序改变都会改变签名。

查询示例：

```text
输入：b=%2B&a=hello+world&a=hello%20world&empty=
规范化：a=hello%20world&a=hello%2Bworld&b=%2B&empty=
```

### 3.2 P2：响应与Webhook验签

响应/通知使用其自带的timestamp、nonce、signature及实际原始body：

```text
responseString = X-Timestamp + "\n" + X-Nonce + "\n" + lowercase_hex(SHA256(rawResponseBody))
expected = Base64(HMAC-SHA256(hmacKey, UTF8(responseString)))
```

先读取原始字节、核验时间窗并按`X-Key-Serial`选择本地保存的AppSecret版本，用常量时间比较验证签名，通过后再解析业务内容。响应验签用P2，不能复用请求P1待签串。

平台无该应用可用签名密钥时，鉴权失败响应可能没有签名；网络代理错误页也可能没有签名。将其作为接入/传输异常处理，不能据此确认支付失败或触发另一笔扣款。更换配置后先查询原业务单。

### 3.3 P3：请求敏感字段加密

当前需要加密的字段：

| 接口 | JSON字段路径 |
|---|---|
| 创建支付单 | `payer.phone`、`payer.email`、`payer.name` |
| 申请退款 | `refundAccount.bankCode`、`refundAccount.accountNo`、`refundAccount.accountName` |

这些对象可不传；传入的敏感字段必须逐字段加密，null可省略。查询只回掩码。

```text
算法：AES-256-GCM
key：EncryptKey的UTF-8字节，长度32
IV：每个字段独立随机12字节
AAD：UTF8(AppId + "|" + 字段完整点路径)
字段值：Base64(IV || ciphertext || tag)
tag长度：16字节
```

例如`refundAccount.accountNo`的AAD为`APP-DEMO|refundAccount.accountNo`。即使字段值相同，也不得跨字段复用IV；重新加密后要重新签名。`payerOpenId/payerAppId`不属于上表字段，按业务要求普通传值，不自行套用这套字段加密。

[Node.js加解密示例](samples/crypto.mjs)提供`encryptField`。示例生成敏感body的顺序：加密字段→序列化JSON一次→P1签名→发送该JSON字节。

### 3.4 P4：通知resource解密

先对整条Webhook原始body按P2验签，再解密`resource`：

```text
算法：AES-256-GCM
key：resource.keySerial指向的EncryptKey版本
IV：Base64Decode(resource.nonce)，12字节
AAD：UTF8(eventType + "|" + eventId)
输入：Base64Decode(resource.ciphertext) = ciphertext || tag
```

`resource.associatedData`必须与本地重建的`eventType|eventId`一致。P4的nonce是独立字段，不包含在ciphertext中；P3把IV放在密文前，两者不能混用。解密后的业务对象按第7节处理。

### 3.5 离线校对

下载[samples目录中的示例](README.md)，保持三个文件在同一目录：

```bash
python3 open_api.py --self-test
node crypto.mjs
```

测试材料是公开向量，包含历史timestamp和固定IV，只能离线校验；线上必须生成当前timestamp、随机nonce和随机IV。向量中的P4小写事件名用于校对已有加密向量，业务通知枚举使用第7节列出的大写名称。

## 4. 第一次API调用

先确认应用、签名密钥、门店READ授权均有效。以下示例使用Python标准库，并对响应先验签再解析。示例不是正式SDK，多版本密钥选择需在实际系统中补齐。

将[open_api.py](samples/open_api.py)保存为模块，在同一目录执行：

```python
import os
import urllib.parse
from open_api import OpenApi

client = OpenApi(
    os.environ["NEARPAY_BASE_URL"],
    os.environ["NEARPAY_APP_ID"],
    os.environ["NEARPAY_APP_SECRET"],
    os.environ.get("NEARPAY_KEY_SERIAL"),
)
store_ref = urllib.parse.quote(os.environ["NEARPAY_STORE_REF"], safe="")
http_status, envelope = client.call(
    "GET", f"/open/pay/v1/stores/{store_ref}/pay-methods"
)
if http_status != 200 or envelope["code"] != 0:
    raise RuntimeError(f"request failed: {http_status}, requestId={envelope.get('requestId')}")
methods = envelope["data"]
```

凭证通过部署密钥管理系统或本地安全配置注入，不写入网页、App包、Git或日志。不要将AppSecret发送给浏览器让其直接签名调用。

## 5. API参考

### 5.1 接口总览

| 方法 | 路径 | scope / 门店动作 | 用途 |
|---|---|---|---|
| GET | `/open/pay/v1/stores/{storeRef}/pay-methods` | pay:read / READ | 获取门店可用支付方式 |
| POST | `/open/pay/v1/orders` | pay:create / COLLECT | 创建支付单 |
| GET | `/open/pay/v1/orders?outOrderNo=...` | pay:read / READ | 按你的订单号查单 |
| GET | `/open/pay/v1/orders/{payOrderNo}` | pay:read / READ | 按Near Pay支付单号查单 |
| POST | `/open/pay/v1/orders/{payOrderNo}/pay` | pay:create / COLLECT | 选择方式并发起支付 |
| POST | `/open/pay/v1/orders/{payOrderNo}/close` | pay:create / COLLECT | 关闭未完成支付 |
| POST | `/open/pay/v1/orders/{payOrderNo}/refunds` | pay:refund / REFUND | 发起退款 |
| GET | `/open/pay/v1/refunds/{payRefundNo}` | pay:read / READ | 查询退款 |

路径参数按单个URI路径段编码。平台会检查当前应用及原单归属；知道别人的支付单号不构成访问权限。

### 5.2 获取门店可用支付方式

`GET /open/pay/v1/stores/{storeRef}/pay-methods`

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| storeRef | path | 是 | 已授权门店号 |
| market | query | 否 | 通常省略，使用门店经营地区 |

无body。`data`为数组；空数组表示当前没有可用方式，不能按固定列表继续发起：

```json
{
  "code": 0,
  "message": "OK",
  "data": [{"payMethod": "LINEPAY", "payChannel": "NEWEBPAY", "accessMode": "MERCHANT_OWN"}],
  "requestId": "REQ-DEMO-001"
}
```

示例只说明字段结构，不保证特定渠道已为你开通。`payMethod`用于发起支付，`payChannel/accessMode`用于展示或选择对应客户端处理方案，不能据此指定或修改收款账户。

### 5.3 创建支付单

`POST /open/pay/v1/orders`

| 字段 | JSON类型 | 必填 | 说明 |
|---|---|---|---|
| outOrderNo | string | 是 | 你的稳定订单号，1—64字符，先持久化再调用 |
| storeRef | string | 是 | 已获COLLECT授权的门店号 |
| amountMinor | integer | 是 | 正整数最小单位金额 |
| currency | string | 是 | 与门店币种一致，例如TWD |
| subject | string | 是 | 非空商品/订单描述，不传敏感身份信息 |
| expireMinutes | integer | 否 | 1—1440，默认30；只影响支付单有效期 |
| bizNo | string | 否 | 业务关联号；省略时使用outOrderNo，不替代幂等号 |
| payer | object | 否 | 可选phone/email/name，传入时逐字段按P3加密 |

```json
{
  "outOrderNo": "DEV202611120001",
  "storeRef": "STORE-DEMO-001",
  "amountMinor": 12000,
  "currency": "TWD",
  "subject": "示例商品",
  "expireMinutes": 30
}
```

本接口不接收每单notifyUrl、returnUrl、渠道密钥或收款账户选择。通知地址来自已登记配置，渠道与收款路径由平台决定。

成功`data`为支付单对象：

```json
{
  "payOrderNo": "P-DEMO-001",
  "outOrderNo": "DEV202611120001",
  "status": "CREATED",
  "amountMinor": 12000,
  "paidMinor": 0,
  "refundedMinor": 0,
  "currency": "TWD",
  "market": "TW",
  "payChannel": null,
  "payMethod": null,
  "expireAt": 1794443400000,
  "succeededAt": null,
  "closedAt": null,
  "checkoutUrl": null,
  "payer": null
}
```

以上为`data`，HTTP响应外层仍有公共信封。保存`payOrderNo ↔ outOrderNo`关系。

| 返回字段 | 类型 | 含义 |
|---|---|---|
| payOrderNo / outOrderNo | string | 平台支付单号 / 你的订单号 |
| status | string | 支付状态，见第6节 |
| amountMinor / paidMinor / refundedMinor | integer | 应付 / 已收 / 已退金额 |
| currency / market | string | 币种 / 经营地区 |
| payChannel / payMethod | string或null | 已选渠道/方式，建单阶段可能为空 |
| expireAt / succeededAt / closedAt | integer或null | UTC毫秒；未发生时为空 |
| checkoutUrl | string或null | 仅建单在已配置托管收银台时返回；API-only场景为空 |
| payer | object或null | 付款人字段掩码；不回明文或密文 |

接下来选择一种收银方式：

- 有`checkoutUrl`时，可使用平台托管收银台让顾客付款；URL带短期令牌，按敏感短链处理。
- 使用自有前端时，由服务端调用5.5获取本次支付参数，再交给已联调的渠道页面/SDK处理。

建单幂等：同一应用范围的outOrderNo同内容返回原支付单，核心业务内容不同返回冲突。重复建单可能签发新的收银台短令牌，不代表新建一张支付单；查询接口不会恢复原checkoutUrl，也不会延长原单有效期。

### 5.4 查询支付单

按Near Pay号：`GET /open/pay/v1/orders/{payOrderNo}`。

按你的订单号：`GET /open/pay/v1/orders?outOrderNo=DEV202611120001`。

均无body，成功`data`与5.3支付单结构相同，`checkoutUrl=null`。优先使用已保存的payOrderNo；同一应用下商户隔离可能使业务号存在歧义，按outOrderNo查询无唯一可见结果时会返回未找到，不应据此新建第二笔交易。

创建请求超时而尚未获得payOrderNo时，先按原outOrderNo查询；暂未查到时以同号、同参数重试创建。不要为了绕过冲突换新订单号。

本接口没有承诺每次都向渠道实时查询；状态仍不确定时持续等待通知、按约定节奏查单或联系平台协助查证。

### 5.5 发起支付

`POST /open/pay/v1/orders/{payOrderNo}/pay`

| 字段 | JSON类型 | 必填 | 说明 |
|---|---|---|---|
| payMethod | string | 是 | 使用该门店当前可用列表中的方式，并符合当前客户端接入场景 |
| payerOpenId | string | 条件必填 | 例如微信JSAPI付款人标识，由你的合法用户授权链路获得 |
| payerAppId | string | 条件必填 | 与上述付款人及渠道应用匹配；按平台给出的渠道接入要求传入 |

```json
{"payMethod": "LINEPAY"}
```

成功`data`结构：

```json
{
  "payOrderNo": "P-DEMO-001",
  "outTradeNo": "TX-DEMO-001",
  "payChannel": "NEWEBPAY",
  "payParams": {}
}
```

`payParams`为渠道专属字符串映射，其具体字段由已开通的支付方式决定；示例空对象只表示类型，不是可付款结果。按平台提供的渠道接入说明解析，不假定永远存在`url`，也不将任意返回字符串直接注入HTML。

本接口不接受银行卡号、磁道、POS刷卡数据或手机付款码作为通用请求字段。需要POS等能力时使用其独立接入合同。

同一支付单可能产生不同交易尝试。发起超时后先查询当前支付单，不能把`/pay`当作任意时刻可重复调用的幂等重试接口，也不能换方式立即再扣一次。是否可重新发起，应由原单状态及平台处理规则决定。

### 5.6 关闭支付单

`POST /open/pay/v1/orders/{payOrderNo}/close`

body可省略，或传：

```json
{"reason": "顾客取消"}
```

返回支付单对象。平台先检查渠道状态；发现已支付会保留/推进真实支付结果，渠道结果不确定时也可能返回当前状态。因此只有返回的实际状态确认关闭后，才能在业务上标记关闭；HTTP 200不保证CLOSED。已支付交易的撤销业务应按退款能力处理。

### 5.7 申请退款

`POST /open/pay/v1/orders/{payOrderNo}/refunds`

| 字段 | JSON类型 | 必填 | 说明 |
|---|---|---|---|
| outRefundNo | string | 是 | 你的稳定退款号，1—64字符；不同退款申请使用不同号 |
| amountMinor | integer | 是 | 正整数，不超过当前可退金额、应用额度和渠道退款能力 |
| reason | string | 否 | 退款原因 |
| refundAccount | object | 条件选填 | 仅平台明确开通并要求收款账户资料的场景使用；各字段按P3加密，不能据此更换原交易退款路由 |

```json
{"outRefundNo": "REF202611120001", "amountMinor": 2000, "reason": "部分退货"}
```

成功`data`结构：

```json
{
  "payRefundNo": "R-DEMO-001",
  "outRefundNo": "REF202611120001",
  "status": "PENDING_REVIEW",
  "amountMinor": 2000,
  "currency": "TWD",
  "succeededAt": null,
  "refundAccount": null
}
```

| 返回字段 | 类型 | 含义 |
|---|---|---|
| payRefundNo / outRefundNo | string | 平台退款号 / 你的退款号 |
| status | string | 退款状态，见第6节 |
| amountMinor / currency | integer / string | 本次退款金额与原交易币种 |
| succeededAt | integer或null | 退款成功UTC毫秒 |
| refundAccount | object或null | 收款账户掩码，不回明文/密文 |

返回待审核或执行中均不代表退款完成。申请可能进入一/二人复核，接入方无需也无权调用运营审批接口。

同一应用范围的outRefundNo不能用于另一张原支付单或不同金额。保持原业务参数；新的部分退款必须使用新的outRefundNo。理由或账户资料变化不能作为修改已受理退款的手段。

每日退款额度当前按UTC自然日核算，额度为0表示不能退款；计入范围由平台处理，退款处理中不应视为额度已释放。应用额度检查可能先于退款重放识别，已受理但响应丢失的重试也可能被当前额度或状态拒绝；此时联系平台按原号核对，不能换退款号重复申请。

### 5.8 查询退款

`GET /open/pay/v1/refunds/{payRefundNo}`，无body。

返回5.7中的退款对象。当前没有开放按outRefundNo直接查询的独立端点；首次响应丢失时，可在业务条件允许下按原outRefundNo、原支付号及相同业务参数重试申请，以恢复平台退款号；仍不能恢复时提交这些坐标给平台核对。已取得payRefundNo后优先查原退款，不重新申请。

## 6. 状态与异常恢复

### 6.1 支付状态

| 状态 | 业务处理 |
|---|---|
| CREATED | 已建单，尚未确认收款 |
| AWAITING_PAYMENT | 等待付款/结果，继续等待或查询 |
| AUTHORIZED / AWAITING_CONFIRM | 授权/确认中，不作为最终收款完成 |
| UNKNOWN或未识别状态 | 待查证，保留原单，不重扣、不发货 |
| SUCCEEDED | 已确认支付成功；校验归属、金额、币种后按业务处理 |
| PARTIALLY_REFUNDED | 支付成功后发生部分退款；仍需核对累计已退金额 |
| REFUNDED | 支付成功后已全额退款，不作为新的收款成功触发履约 |
| CLOSED / FAILED | 当前交易关闭/失败；如收到冲突通知，查询原单并交平台核对 |

### 6.2 退款状态

| 状态 | 业务处理 |
|---|---|
| REQUESTED / PENDING_REVIEW / PENDING_SECOND_REVIEW | 已申请或待审核，尚未退款成功 |
| APPROVED | 审核已通过，尚未确认退款完成 |
| DISPATCHED | 已进入渠道执行，等待结果 |
| OFFLINE_PENDING_PROOF | 等待凭证/处理，仅对平台明确支持的业务场景有意义 |
| UNKNOWN或未识别状态 | 待核对，不能重复退钱 |
| SUCCEEDED | 本次退款成功 |
| FAILED / REJECTED | 当前退款失败/被驳回，保留原记录并核对原因 |

### 6.3 超时与幂等策略

| 场景 | 正确恢复 |
|---|---|
| 创建支付超时 | 查原outOrderNo；必要时同号同参数重试创建 |
| 发起支付超时 | 查原payOrderNo，等待通知；不盲目重发pay或换单 |
| 关闭支付超时 | 查原payOrderNo；以实际状态为准 |
| 退款申请超时 | 查已知payRefundNo；未知时使用原退款号恢复或提交平台核对 |
| 回调重复 | eventId幂等确认，不重复发货/退款 |
| 回调迟到或矛盾 | 查询原单，禁止把更旧事件覆盖当前业务状态 |
| 429限流 | 退避并加入随机抖动；新nonce重新签名，业务号不变 |
| 5xx/连接中断 | 当作结果不确定；先查业务结果，再决定是否安全重试 |

当前业务幂等主要依赖outOrderNo/outRefundNo，不以自造的`Idempotency-Key`请求头代替。不要在客户端为所有POST设置无差别自动重试。

### 6.4 服务端调用串联示例

下面沿用第4节的`client`。调用前将本地订单及稳定的outOrderNo持久化；从实际响应中读取平台单号，不使用文档中的占位号。请求超时按6.3恢复，不在这个示例外包无限重试循环。

```python
# 1. 本地已保存的订单。不要因网络重试而重新生成outOrderNo。
order_request = {
    "outOrderNo": local_order_no,
    "storeRef": os.environ["NEARPAY_STORE_REF"],
    "amountMinor": 12000,
    "currency": "TWD",  # 替换为平台为该门店确认的币种。
    "subject": "示例商品",
}
http_status, response = client.call("POST", "/open/pay/v1/orders", order_request)
if http_status != 200 or response["code"] != 0:
    raise RuntimeError(f"create failed, requestId={response.get('requestId')}")
order = response["data"]
pay_order_no = order["payOrderNo"]
# 持久化pay_order_no与本地订单关系。

# 2. 托管收银场景使用order.get("checkoutUrl")。
# 自有收银场景才由服务端按已选方式调用/pay；不要同时启动两条收银流程。
selected_method = selected_from_available_methods
http_status, started = client.call(
    "POST", f"/open/pay/v1/orders/{pay_order_no}/pay",
    {"payMethod": selected_method},
)
# 检查code、保存交易号，再按已联调的渠道方案把payParams交给前端。
# 此时不发货，等待可信通知/查单结果。

# 3. 查询原单，校验金额/币种/归属后更新本地状态。
http_status, queried = client.call("GET", f"/open/pay/v1/orders/{pay_order_no}")

# 4. 退款由独立售后动作触发；先保存稳定退款号，不在支付流程中自动退款。
# http_status, refunded = client.call(
#     "POST", f"/open/pay/v1/orders/{pay_order_no}/refunds",
#     {"outRefundNo": local_refund_no, "amountMinor": 2000, "reason": "部分退货"},
# )
```

上述`local_order_no/selected_from_available_methods/local_refund_no`来自你的业务状态，不是示例库自动生成。需要payerOpenId/payerAppId的方式，还要按5.5补充合法付款人上下文。

## 7. 异步通知接入

### 7.1 通知入口与请求头

平台向应用登记的通知URL发送HTTPS POST。通知地址在应用配置中管理，不由每次建单传入。

通知头包括`X-App-Id/X-Timestamp/X-Nonce/X-Signature/X-Event-Id`，版本化签名另带`X-Key-Serial`。使用P2验证；通知头nonce与resource.nonce分别用于签名和AES加密，不能混用。

### 7.2 通知信封

```json
{
  "eventId": "EVT-DEMO-001",
  "eventType": "PAYMENT_SUCCEEDED",
  "stateVersion": 2,
  "createdAt": 1794441601000,
  "resource": {
    "algorithm": "AEAD_AES_256_GCM",
    "keySerial": "ENC-SERIAL-DEMO",
    "nonce": "<Base64编码的12字节随机值>",
    "associatedData": "PAYMENT_SUCCEEDED|EVT-DEMO-001",
    "ciphertext": "<Base64编码的密文与Tag>"
  }
}
```

以上加密字段为占位，不可作为解密测试；可运行向量见samples。

| 字段 | 说明 |
|---|---|
| eventId | 稳定事件ID，重投保持不变，格式不透明；不要解析其内部结构 |
| eventType | 当前事件枚举见下表 |
| stateVersion | 事件内容的状态版本信息，不能当作全局序号或不同事件的先后顺序 |
| createdAt | 事件生成UTC毫秒，不是每次投递时间 |
| resource | 加密业务数据，按P4解密 |
| data | 只有平台明确启用的未配置EncryptKey过渡模式才可能直接出现；仍须P2验签 |

当前事件：

| eventType | 业务含义 |
|---|---|
| PAYMENT_SUCCEEDED | 支付成功 |
| PAYMENT_CLOSED | 支付关闭 |
| REFUND_SUCCEEDED | 退款成功 |
| REFUND_FAILED | 退款失败 |

解密后的支付业务对象示例：

```json
{
  "payOrderNo": "P-DEMO-001",
  "outOrderNo": "DEV202611120001",
  "status": "SUCCEEDED",
  "amountMinor": 12000,
  "paidMinor": 12000,
  "refundedMinor": 0,
  "currency": "TWD",
  "payChannel": "NEWEBPAY",
  "payMethod": "LINEPAY",
  "succeededAt": 1794441600000,
  "closedAt": null
}
```

退款事件使用相同原支付字段，并增加`payRefundNo/outRefundNo/refundAmountMinor/refundStatus`。退款事件中的顶层业务`status/amountMinor`描述原支付；判断本次退款必须读取`refundStatus/refundAmountMinor`，不能当成本次退款的结果或金额。

### 7.3 接收处理步骤

1. 在解析、改写JSON前保留原始body，按AppId与签名序列号定位密钥；检查timestamp时间窗，P2验签。
2. 比较`X-Event-Id`与body.eventId、确认X-App-Id是本服务受理的应用；处理未知事件类型时保留记录，不执行未知动作。
3. 有resource时按对应EncryptKey版本解密并核对AAD；若应用约定加密通知，却收到仅data的报文，按异常处理，不自行降级。
4. 持久化接收记录，以`AppId + eventId`建唯一键；同一事务更新本地业务或保存可靠处理任务。重复事件直接确认已收到。
5. 校验原订单/退款归属、币种和金额；金额矛盾、状态冲突或结果不确定时查单核对，再触发后续履约。
6. 持久化成功后快速返回2xx，例如HTTP 200及`{"received":true}`。响应体不要求固定字符串，不要求对ACK再签名。
7. 持久化失败返回非2xx，让平台重试。耗时处理交给你自己的可靠任务，不能先ACK再只放进内存队列。

无需用HTTP nonce作为业务去重键：每次重投的nonce/签名/加密IV都可能变化，但eventId不变。

### 7.4 重投与主动核对

平台默认使用5秒投递超时，失败后退避重试；当前默认间隔从30秒开始倍增、上限2小时，约24小时后进入待人工处理。实际投递时刻受调度及平台配置影响，具体承诺以接入资料为准，不按此推算精确次数或SLA。

乱序、重复、延迟均可能发生。`stateVersion`不是“每条通知加1”的流水号，不要只按它过滤同一订单的不同支付/退款事件。对每个eventId去重，对业务对象校验状态和累计金额；缺失通知通过原单查询与平台核对恢复。

## 8. 密钥轮换与应用维护

1. 向平台申请新密钥版本，安全领取新的明文和序列号；先在服务器加入密钥版本表，不立即删除旧版。
2. 平台完成新版本验证/启用，并告知重叠有效期。
3. 新请求使用新`X-Key-Serial`及对应AppSecret；传敏感字段时独立选择EncryptKey及`X-Encrypt-Serial`。
4. 响应和回调按实际serial选密钥，不能强制用“最新那把”。历史通知重投可能继续使用首投冻结的旧签名/加密版本。
5. 保留旧版本到双方确认的历史通知/重投窗口结束，再按平台安排退役；不能自行延长已吊销版本的有效性。

应用暂停、吊销、门店撤销授权、密钥停用可能影响后续调用。密钥泄露时联系平台立即处理并保留异常业务坐标，不能只删除本地配置。生产与沙箱的密钥不能互相轮换或共享。

## 9. 错误处理与排障

| HTTP / code | 含义 | 建议处理 |
|---|---|---|
| 401 / 10401 | AppId/状态/环境/签名/密钥等鉴权失败 | 核对环境、前缀处理、实际rawBody、路径、密钥启用状态 |
| 401 / 70095 | 时间窗过期 | 校时，使用UTC毫秒，不是秒 |
| 401 / 70096 | nonce已使用 | 新nonce、timestamp、签名，保留原业务号 |
| 403 / 10403或70082 | IP、scope、门店/资源权限不满足 | 核对出口IP及应用/门店动作交集，不反复换资源探测 |
| 400 / 70097 | 敏感字段未按要求加密 | 检查字段是否仍是明文及P3拼接 |
| 400 / 70098 | 敏感字段解密失败 | 检查EncryptKey、serial、IV、Tag及完整字段AAD |
| 503 / 70099 | 密钥服务暂不可用 | 退避并先核对原业务结果 |
| 429 / 10429 | 应用限流 | 降低并发、退避加抖动，不假定响应带Retry-After |
| 非零 / 70083 | 原单不存在或当前调用不可见/无法唯一定位 | 核对AppId、原号与归属；不能据此立即重扣 |
| 非零 / 70084 | 业务单号冲突 | 检查同号是否换了原支付、金额或业务内容；恢复原参数 |
| 非零 / 70085 | 当前状态不允许该动作 | 查单，按实际状态处理 |
| 非零 / 70086 | 超过可退金额 | 核对已退与进行中退款，不重复提交 |
| 非零 / 70092 | 应用退款额度不满足 | 核对已受理退款和UTC日额度，必要时联系平台 |

业务错误的HTTP映射以实际响应为准；不只通过HTTP状态判定交易结果。生产鉴权失败可能合并原因，排障时提供：环境、AppId、requestId、请求方法/路径、时间、outOrderNo/payOrderNo或outRefundNo/payRefundNo；不要提供AppSecret、EncryptKey或敏感明文。

常见问题：

- **签名一直不对**：检查是否把AppSecret前缀参与HMAC、是否把密钥再Base64解码、body签完后是否又被HTTP库序列化、是否把响应也按P1验签。
- **应用有收款权限仍403**：检查门店是否授权COLLECT、当前应用/门店是否有效、出口IP是否一致。
- **支付接口200但顾客未付款**：200表示本次API处理完成；支付状态仍需查询或通知确认。
- **退款接口成功却尚未到账**：APPROVED/DISPATCHED均非SUCCEEDED，可能等待复核或渠道处理。
- **查单没有checkoutUrl**：这是预期行为；仅建单且托管收银台已配置时返回短期链接。
- **回调验签通过但解密失败**：签名密钥和EncryptKey独立，确认resource.keySerial，并保留旧加密版本处理重投。

## 10. 生产接入验收清单

- [ ] 沙箱/生产资料分别保存，确认AppId、密钥、门店号和币种。
- [ ] 应用、签名/加密密钥已启用，所需动作与门店授权均已生效。
- [ ] 回调HTTPS、域名及平台核验完成；接收服务不依赖浏览器会话。
- [ ] P1/P2/P3/P4离线向量通过；修改body、错误密钥、错误AAD和过期报文会被拒绝。
- [ ] 创建成功、同号重试、同号异内容冲突均验证。
- [ ] 已开通支付方式真实联调，能处理付款成功、取消和结果未知。
- [ ] 支付/退款超时先恢复原号；没有POST无差别重试或换号重扣。
- [ ] 退款额度、部分退款能力及审核流程已确认；实际成功以SUCCEEDED为准。
- [ ] 通知可验签解密、可靠落库、重复ACK；重投旧密钥、乱序及状态冲突可恢复。
- [ ] 关闭支付会读取实际返回状态，不把关闭请求200直接认作CLOSED。
- [ ] 密钥轮换、出口IP/回调变更及异常联系流程已确认。

完成开发并不自动开通全部渠道。生产切换以平台对该应用、门店和支付方式的验收结果为准。
