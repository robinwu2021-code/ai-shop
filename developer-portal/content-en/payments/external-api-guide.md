---
Title: Near Pay External API Integration Guide
Description: Server-side integration from application and key provisioning to signing, payments, refunds, and asynchronous notifications.
Version: "1.1"
Last_updated: "2026-11-12"
Sort: 20
---
# Near Pay External API Integration Guide

This guide is for third-party developers collecting payments with Near Pay. Your business server calls the open API; Near Pay handles collection routing, transaction states, and refunds. Keep AppSecret, EncryptKey, and signature operations on your server.

Read in this order: **Apply for an application → configure notifications and authorization → obtain and activate keys → verify signatures → create and pay an order → receive notifications/query status → refund → production acceptance.**

The current protocol uses `/open/pay/v1`, HMAC-SHA256 signing, and AES-256-GCM field/notification encryption. Available payment methods, currencies, and refund capabilities depend on what the platform has enabled for your application and store.

## 1. Before you integrate

Submit the following information through the platform's designated application channel or integration contact. The current open API has no anonymous registration, key application, approval, or self-service store authorization endpoints. The platform provisions these before you call the transaction APIs.

| Information | What to provide |
| --- | --- |
| Integrator and application | Company/platform name, application name, purpose, technical contact, incident contact channel |
| Business ownership | Collecting entity, target stores, operating market, currency |
| Environment | Sandbox or production; apply separately and keep credentials separate |
| Capabilities | Read, collect, refund; request only what you need |
| Source IP | Fixed server egress IP or CIDR; a container's internal IP is usually not the address seen by the platform |
| Notifications | Public HTTPS notification URL, allowed callback domains, receiving-service contact |
| Capacity and limits | Expected peak QPS, refund requirements, requested daily refund limit |
| Payment methods | Target device/browser environment and desired methods; the platform confirms available products |

The platform must be able to reach your notification service. Its certificate must be valid and DNS must not resolve to loopback, private, or link-local addresses. Notifications are separate from the customer's browser return page and must not depend on browser login cookies.

### 1.1 Provisioning steps and readiness criteria

| Step | Platform action | What to confirm |
| --- | --- | --- |
| 1. Register application | Record ownership, purpose, environment; allocate AppId; application awaits activation | Correct AppId, ownership, environment |
| 2. Configure capabilities | Configure READ/COLLECT/REFUND, rate limit, refund limit, egress IP | Separate permissions and limits for read, collect, refund |
| 3. Configure and verify notifications | Register URL/domain and complete platform verification | URL accepts POST; follow the supplied verification procedure |
| 4. Approve and activate application | Review and activate the application | Application is ACTIVE; AppId alone is insufficient |
| 5. Issue and activate keys | Generate AppSecret/EncryptKey after application activation; verify, approve, activate keys | Securely receive one-time plaintext and serials; the usable version is active; DRAFT keys cannot call APIs |
| 6. Authorize stores | Grant specific stores and actions through platform/merchant authorization management | Correct `storeRef` and granted actions |
| 7. Test and accept | Verify signatures, business outcomes, notifications, recovery | Pass sandbox acceptance, then provision production separately |

Application activation, key activation, and store authorization are three independent requirements. Application actions map to API scopes as follows:

| Action | API scope | Allowed calls |
| --- | --- | --- |
| READ | `pay:read` | Query payments/refunds and available store payment methods |
| COLLECT | `pay:create` | Create orders, initiate payments, close orders |
| REFUND | `pay:refund` | Request refunds |

Effective access is **current application permissions ∩ authorized store actions ∩ enabled store products and current business restrictions**. Refund scope also requires REFUND authorization on the original payment's store and sufficient limits.

### 1.2 Connection details you receive

| Item | Purpose and handling |
| --- | --- |
| API_BASE_URL | Platform-provided environment address; examples assume an HTTPS origin with no extra path prefix |
| AppId | Application number assigned by the platform; sent as `X-App-Id` |
| AppSecret | Request signing and response/notification verification; normally prefixed `sk_test_` or `sk_live_` |
| Signing key serial | Request `X-Key-Serial` and response/notification signing-version lookup; store as an opaque string |
| EncryptKey | 32 UTF-8 bytes, currently issued as 32 alphanumeric characters; encrypts sensitive fields and notification resource |
| Encryption key serial | Request `X-Encrypt-Serial` and notification `resource.keySerial` lookup |
| storeRef | Platform-provided authorized store business number, not an internal database ID |
| Notification confirmation | Registered URL, verification status, whether encrypted notifications are enabled |
| Limits and capabilities | QPS, daily refund limit, currencies, payment methods, refund restrictions |

Key plaintext is provided only once at issuance. Request a new version if lost; do not rely on retrieving plaintext later. Application, payment-channel, and POS-device keys are different and cannot substitute for one another. The AppSecret display prefix does not participate in HMAC. Do not strip a prefix from EncryptKey or Base64-decode it.

## 2. Common requests and responses

### 2.1 Addresses and environments

```text
{API_BASE_URL}/open/pay/v1/orders
```

Sandbox and production each use separate AppIds, keys, and authorization. The platform provides domains, entry points, and available channels. Do not infer an address from an example domain or key prefix. Do not add `/api` or use internal/operations endpoints as open APIs.

`APP-DEMO`, `STORE-DEMO-001`, and `P-DEMO-001` are placeholders. Replace them with provisioned values. Example amounts do not imply that a store supports those limits.

### 2.2 Required request headers

| Header | Required | Description |
| --- | --- | --- |
| `Content-Type` | For JSON | `application/json; charset=UTF-8` |
| `X-App-Id` | Yes | Application AppId |
| `X-Timestamp` | Yes | Current UTC milliseconds as a decimal string; within 300 seconds of platform time |
| `X-Nonce` | Yes | Unique per HTTP request, 8–64 characters; 32 random hexadecimal characters recommended |
| `X-Signature` | Yes | Standard Base64 signature calculated using P1 in section 3 |
| `X-Key-Serial` | Recommended | Platform-issued signing-version serial; specify explicitly during rotation |
| `X-Encrypt-Serial` | With encrypted fields | EncryptKey serial used for these sensitive fields |
| `X-Request-Id` | No | Troubleshooting correlation ID; not a business idempotency number |

For each retry, generate a new timestamp, nonce, and signature while preserving the business number and business parameters. Do not replay the previous signed HTTP request unchanged.

### 2.3 Success and error envelopes

```json
{
  "code": 0,
  "message": "OK",
  "data": {},
  "requestId": "REQ-DEMO-001"
}
```

`code=0` means the API operation succeeded. Read `data.status` for the payment outcome. HTTP 200, successful order creation, and returned payment parameters do not prove receipt of funds. Error responses also use `code/message/data/requestId`. Handle HTTP status first, then nonzero business codes. `message` is for display/troubleshooting, not program branching.

### 2.4 Numbers, times, and fields

- JSON uses camelCase and UTF-8 request bodies.
- `amountMinor/paidMinor/refundedMinor` are integers in the currency's smallest unit. For a two-decimal currency, 12000 means 120.00. Use the platform's currency precision configuration; not all currencies have two decimals.
- Send amounts as JSON integers, not `120.00` or strings relying on coercion. JavaScript amounts must be within the safe integer range; discuss compatibility with the platform before exceeding it.
- Time fields are UTC millisecond integers. Unoccurred or inapplicable fields may be null.
- Ignore unknown new response fields. Treat unknown states as requiring reconciliation, not automatic success or failure.

## 3. Signing, verification, and encryption

### 3.1 P1: Request signing

Encrypt sensitive fields first, then sign the **final raw request-body bytes you will send**.

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

1. Uppercase METHOD. PATH contains only the API path, without scheme, host, query, or fragment. Use the actual accepted path; do not independently add/remove a trailing `/`.
2. The signing string has seven lines separated by LF and no trailing newline. Preserve the empty third line when there is no query.
3. Sort QUERY by decoded parameter name, then value for repeated names. RFC3986-encode names and values separately; join with `=` and `&`. A space is `%20`, a literal `+` is `%2B`, and `~` remains unencoded.
4. For GET without a body, hash an empty byte sequence, not `{}`. Its SHA-256 is `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
5. `hmacKey` is the UTF-8 bytes of AppSecret after removing `sk_test_`/`sk_live_`. Do not Base64/Hex-decode it again.
6. Use standard Base64, not Base64URL or hexadecimal.
7. Serialize the body once and send the exact bytes you hashed. Whitespace, Unicode encoding, newlines, and field order affect signatures.

Query example:

```text
Input: b=%2B&a=hello+world&a=hello%20world&empty=
Canonical: a=hello%20world&a=hello%2Bworld&b=%2B&empty=
```

### 3.2 P2: Response and webhook verification

Use the response/notification's own timestamp, nonce, signature, and actual raw body:

```text
responseString = X-Timestamp + "\n" + X-Nonce + "\n" + lowercase_hex(SHA256(rawResponseBody))
expected = Base64(HMAC-SHA256(hmacKey, UTF8(responseString)))
```

Read raw bytes first, check the time window, select your stored AppSecret version using `X-Key-Serial`, and compare signatures in constant time. Parse business content only after verification. P2 is different from the P1 request signing string.

Authentication failures may be unsigned when the platform has no usable signing key for the application. Network/proxy error pages may also be unsigned. Treat them as integration/transport errors; they do not confirm payment failure and must not trigger another charge. After correcting configuration, query the original business order first.

### 3.3 P3: Encrypting sensitive request fields

| API | JSON field paths |
| --- | --- |
| Create payment order | `payer.phone`, `payer.email`, `payer.name` |
| Request refund | `refundAccount.bankCode`, `refundAccount.accountNo`, `refundAccount.accountName` |

These objects may be omitted. Encrypt every supplied sensitive field independently; null fields may be omitted. Queries return masked values only.

```text
Algorithm: AES-256-GCM
key: UTF8(EncryptKey), 32 bytes
IV: 12 random bytes per field
AAD：UTF8(AppId + "|" + 字段完整点路径)
Field value: Base64(IV || ciphertext || tag)
tag length: 16 bytes
```

The AAD for `refundAccount.accountNo` is, for example, `APP-DEMO|refundAccount.accountNo`. Never reuse an IV across fields, even for identical values. Sign again after re-encryption. `payerOpenId/payerAppId` are not in the sensitive-field table; pass them normally according to business requirements.

The [Node.js example](downloads/crypto.mjs) provides `encryptField`. Prepare a sensitive body in this order: encrypt fields → serialize JSON once → P1-sign → send those JSON bytes.

### 3.4 P4: Decrypting notification resource

Verify the entire raw webhook body using P2 before decrypting `resource`:

```text
Algorithm: AES-256-GCM
key: EncryptKey version identified by resource.keySerial
IV: Base64Decode(resource.nonce), 12 bytes
AAD：UTF8(eventType + "|" + eventId)
Input: Base64Decode(resource.ciphertext) = ciphertext || tag
```

`resource.associatedData` must equal your locally reconstructed `eventType|eventId`. P4's nonce is a separate field, not part of ciphertext. P3 prefixes the IV to its ciphertext; the formats are not interchangeable. Process the decrypted object as described in section 7.

### 3.5 Offline checks

Download the [examples and test vectors](samples.md) and save all three files together:

```bash
python3 open_api.py --self-test
node crypto.mjs
```

Public vectors include historical timestamps and fixed IVs for offline verification only. Online requests require current timestamps and random nonces/IVs. The lowercase P4 event name validates an existing encryption vector; business notification enums are the uppercase names in section 7.

## 4. Your first API call

Confirm the application, signing key, and store READ authorization are active. This Python standard-library example verifies responses before parsing. It is not a production SDK; implement multi-version key lookup in your system.

Save [open_api.py](downloads/open_api.py) as a module and run from the same directory:

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

Inject credentials through deployment secret management or secure local configuration. Never put them in a webpage, app package, Git, or logs. Do not send AppSecret to a browser for direct signing.

## 5. API reference

### 5.1 Endpoint overview

| Method | Path | Scope / store action | Purpose |
| --- | --- | --- | --- |
| GET | `/open/pay/v1/stores/{storeRef}/pay-methods` | pay:read / READ | Available store payment methods |
| POST | `/open/pay/v1/orders` | pay:create / COLLECT | Create payment order |
| GET | `/open/pay/v1/orders?outOrderNo=...` | pay:read / READ | Query by your order number |
| GET | `/open/pay/v1/orders/{payOrderNo}` | pay:read / READ | Query by Near Pay payment number |
| POST | `/open/pay/v1/orders/{payOrderNo}/pay` | pay:create / COLLECT | Select method and initiate payment |
| POST | `/open/pay/v1/orders/{payOrderNo}/close` | pay:create / COLLECT | Close incomplete payment |
| POST | `/open/pay/v1/orders/{payOrderNo}/refunds` | pay:refund / REFUND | Request refund |
| GET | `/open/pay/v1/refunds/{payRefundNo}` | pay:read / READ | Query refund |

Encode each path parameter as a single URI path segment. The platform verifies application and original-order ownership; knowing another party's payment number does not grant access.

### 5.2 Get available store payment methods

`GET /open/pay/v1/stores/{storeRef}/pay-methods`

| Parameter | Location | Required | Description |
| --- | --- | --- | --- |
| storeRef | path | Yes | Authorized store number |
| market | query | No | Usually omitted; defaults to the store's operating market |

No body. `data` is an array. An empty array means no methods are currently available; do not proceed with a hardcoded list.

```json
{
  "code": 0,
  "message": "OK",
  "data": [{"payMethod": "LINEPAY", "payChannel": "NEWEBPAY", "accessMode": "MERCHANT_OWN"}],
  "requestId": "REQ-DEMO-001"
}
```

This example describes the shape, not channel availability. Use `payMethod` to initiate payment. `payChannel/accessMode` support display or the corresponding client integration; they do not let you specify/change a collection account.

### 5.3 Create payment order

`POST /open/pay/v1/orders`

| Field | JSON type | Required | Description |
| --- | --- | --- | --- |
| outOrderNo | string | Yes | Your stable order number, 1–64 characters; persist before calling |
| storeRef | string | Yes | Store with COLLECT authorization |
| amountMinor | integer | Yes | Positive integer in smallest currency units |
| currency | string | Yes | Matches store currency, e.g. TWD |
| subject | string | Yes | Nonempty goods/order description without sensitive identity data |
| expireMinutes | integer | No | 1–1440, default 30; affects order validity only |
| bizNo | string | No | Business reference; defaults to outOrderNo; not a substitute idempotency number |
| payer | object | No | Optional phone/email/name; P3-encrypt supplied fields individually |

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

The endpoint does not accept per-order notifyUrl/returnUrl, channel keys, or collection-account selection. Notification addresses come from registered configuration. The platform determines the channel and collection route.

Successful `data` is a payment order:

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

This is only `data`; the HTTP response retains the common envelope. Save the `payOrderNo ↔ outOrderNo` relationship.

| Response field | Type | Meaning |
| --- | --- | --- |
| payOrderNo / outOrderNo | string | Platform payment number / your order number |
| status | string | Payment state; section 6 |
| amountMinor / paidMinor / refundedMinor | integer | Due / collected / refunded amount |
| currency / market | string | Currency / operating market |
| payChannel / payMethod | string or null | Selected channel/method; may be empty at creation |
| expireAt / succeededAt / closedAt | integer or null | UTC milliseconds; null before the event |
| checkoutUrl | string or null | Returned at creation only when hosted checkout is configured; null for API-only integrations |
| payer | object or null | Masked payer fields, neither plaintext nor ciphertext |

Choose a checkout approach:

- If `checkoutUrl` is present, use hosted checkout for the customer. It contains a short-lived token; treat it as a sensitive short link.
- For your own frontend, your server calls section 5.5 and passes the returned parameters to the integrated channel page/SDK.

Creation idempotency: within the same application, identical outOrderNo and content return the original order; conflicting core content produces a conflict. Repeated creation may issue a new short-lived checkout token without creating a new order. Queries neither recover the original checkoutUrl nor extend validity.

### 5.4 Query payment order

By Near Pay number: `GET /open/pay/v1/orders/{payOrderNo}`.

By your order number: `GET /open/pay/v1/orders?outOrderNo=DEV202611120001`.

Both have no body. Successful `data` matches section 5.3 with `checkoutUrl=null`. Prefer the saved payOrderNo. Merchant isolation within one application can make a business number ambiguous; lookup by outOrderNo returns not found when no unique visible result exists. Do not create another transaction on that basis.

If creation timed out before you obtained payOrderNo, query the original outOrderNo first. If still not found, retry creation with the same number and parameters. Do not change the number to bypass a conflict.

The endpoint does not promise a real-time channel query on every call. For uncertain states, wait for notifications, query at the agreed cadence, or contact the platform for reconciliation.

### 5.5 Initiate payment

`POST /open/pay/v1/orders/{payOrderNo}/pay`

| Field | JSON type | Required | Description |
| --- | --- | --- | --- |
| payMethod | string | Yes | From the store's current available list and suitable for the client integration |
| payerOpenId | string | Conditional | E.g. a WeChat JSAPI payer identifier from your legitimate user authorization flow |
| payerAppId | string | Conditional | Matches the payer and channel application; follow platform integration requirements |

```json
{"payMethod": "LINEPAY"}
```

Successful `data`:

```json
{
  "payOrderNo": "P-DEMO-001",
  "outTradeNo": "TX-DEMO-001",
  "payChannel": "NEWEBPAY",
  "payParams": {}
}
```

`payParams` is a channel-specific string map determined by the enabled method. The empty object illustrates the type, not a usable payment result. Follow the channel instructions. Do not assume a `url` field always exists or inject arbitrary strings into HTML.

This endpoint does not accept card numbers, track data, POS card data, or mobile payment codes as generic fields. POS capabilities use their own integration contract.

An order may have multiple transaction attempts. After an initiation timeout, query the current order first. `/pay` is not an idempotent retry endpoint callable at any time; do not immediately switch methods and charge again. The original order state and platform rules determine whether another attempt is allowed.

### 5.6 Close payment order

`POST /open/pay/v1/orders/{payOrderNo}/close`

Omit the body, or send:

```json
{"reason": "顾客取消"}
```

Returns a payment order. The platform first checks channel status. If already paid, it preserves/advances the actual payment result. An uncertain channel result may also return the current state. Mark the order closed only when the actual returned state confirms closure; HTTP 200 does not guarantee CLOSED. Reverse an already paid transaction through refund capabilities.

### 5.7 Request refund

`POST /open/pay/v1/orders/{payOrderNo}/refunds`

| Field | JSON type | Required | Description |
| --- | --- | --- | --- |
| outRefundNo | string | Yes | Your stable refund number, 1–64 characters; a different number for each refund request |
| amountMinor | integer | Yes | Positive integer within the refundable amount, application limits, and channel capabilities |
| reason | string | No | Refund reason |
| refundAccount | object | Conditional/optional | Only when explicitly enabled and required by the platform; P3-encrypt fields; does not change the original refund route |

```json
{"outRefundNo": "REF202611120001", "amountMinor": 2000, "reason": "部分退货"}
```

Successful `data`:

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

| Response field | Type | Meaning |
| --- | --- | --- |
| payRefundNo / outRefundNo | string | Platform refund number / your refund number |
| status | string | Refund state; section 6 |
| amountMinor / currency | integer / string | This refund's amount / original currency |
| succeededAt | integer or null | Refund-success time in UTC milliseconds |
| refundAccount | object or null | Masked account, neither plaintext nor ciphertext |

Pending review or execution does not mean completed. Requests may require one- or two-person review; integrators neither need nor have permission to call operations approval endpoints.

Within one application, outRefundNo cannot be reused for a different original payment or amount. Preserve the original parameters. A new partial refund requires a new outRefundNo. Changing reasons/account details is not a way to modify an accepted refund.

Daily refund limits currently use UTC calendar days. A limit of zero disables refunds. The platform determines counted usage; an in-progress refund does not imply released capacity. Limit checks may occur before replay recognition, so retrying an accepted request whose response was lost can still be rejected by current limits/states. Contact the platform using original identifiers; do not change the refund number and duplicate the refund.

### 5.8 Query refund

`GET /open/pay/v1/refunds/{payRefundNo}`, no body.

Returns the section 5.7 refund object. There is currently no separate open endpoint to query by outRefundNo. If the first response was lost, business conditions permitting, retry the original outRefundNo/payment number and identical parameters to recover the platform number. If recovery still fails, submit those identifiers to the platform. Once payRefundNo is known, query it instead of requesting again.

## 6. States and recovery

### 6.1 Payment states

| State | Business handling |
| --- | --- |
| CREATED | Order created; collection unconfirmed |
| AWAITING_PAYMENT | Awaiting payment/result; wait or query |
| AUTHORIZED / AWAITING_CONFIRM | Authorization/confirmation in progress; not final collection success |
| UNKNOWN or unrecognized | Reconcile; retain original order; do not charge again or fulfill |
| SUCCEEDED | Confirmed paid; validate ownership, amount, currency before business processing |
| PARTIALLY_REFUNDED | Partial refund after payment; reconcile cumulative refunded amount |
| REFUNDED | Fully refunded after payment; do not trigger fulfillment as a new payment success |
| CLOSED / FAILED | Current transaction closed/failed; query original order and contact platform on conflicting notifications |

### 6.2 Refund states

| State | Business handling |
| --- | --- |
| REQUESTED / PENDING_REVIEW / PENDING_SECOND_REVIEW | Requested/awaiting review, not successful yet |
| APPROVED | Approved, but completion is unconfirmed |
| DISPATCHED | Dispatched to channel; awaiting result |
| OFFLINE_PENDING_PROOF | Awaiting proof/processing; meaningful only for explicitly supported scenarios |
| UNKNOWN or unrecognized | Reconcile; do not refund twice |
| SUCCEEDED | This refund succeeded |
| FAILED / REJECTED | Current refund failed/rejected; retain record and reconcile the cause |

### 6.3 Timeouts and idempotency

| Scenario | Correct recovery |
| --- | --- |
| Create timeout | Query original outOrderNo; if needed, recreate with the same number/parameters |
| Initiate timeout | Query original payOrderNo; await notification; do not blindly repeat pay or change orders |
| Close timeout | Query original payOrderNo; follow actual state |
| Refund-request timeout | Query known payRefundNo; if unknown, recover with original number or contact platform |
| Duplicate notification | Deduplicate eventId; do not repeat fulfillment/refund |
| Late/conflicting notification | Query original order; never overwrite current business state with an older event |
| 429 rate limit | Back off with jitter; re-sign with new nonce while retaining business number |
| 5xx/disconnection | Outcome is uncertain; query business result before deciding if retry is safe |

Business idempotency primarily uses outOrderNo/outRefundNo, not a self-invented `Idempotency-Key` header. Do not configure indiscriminate automatic retries for every POST.

### 6.4 Server-side workflow example

Reuse `client` from section 4. Persist the local order and stable outOrderNo first; read platform numbers from actual responses rather than placeholders. Recover timeouts under section 6.3 instead of wrapping this example in unlimited retries.

```python
# 1. Persisted local order. Never regenerate outOrderNo for a network retry.
order_request = {
    "outOrderNo": local_order_no,
    "storeRef": os.environ["NEARPAY_STORE_REF"],
    "amountMinor": 12000,
    "currency": "TWD",  # Use the currency confirmed by the platform for this store.
    "subject": "示例商品",
}
http_status, response = client.call("POST", "/open/pay/v1/orders", order_request)
if http_status != 200 or response["code"] != 0:
    raise RuntimeError(f"create failed, requestId={response.get('requestId')}")
order = response["data"]
pay_order_no = order["payOrderNo"]
# Persist the relationship between pay_order_no and the local order.

# 2. Hosted checkout uses order.get("checkoutUrl").
# Only custom checkout calls /pay from the server; do not start both flows.
selected_method = selected_from_available_methods
http_status, started = client.call(
    "POST", f"/open/pay/v1/orders/{pay_order_no}/pay",
    {"payMethod": selected_method},
)
# Check code, save the transaction number, then pass payParams to the integrated frontend.
# Do not fulfill yet; wait for verified notifications or query results.

# 3. Query the original order; validate amount/currency/ownership before updating local state.
http_status, queried = client.call("GET", f"/open/pay/v1/orders/{pay_order_no}")

# 4. A separate after-sales action triggers refunds. Persist a stable refund number first.
# http_status, refunded = client.call(
#     "POST", f"/open/pay/v1/orders/{pay_order_no}/refunds",
#     {"outRefundNo": local_refund_no, "amountMinor": 2000, "reason": "部分退货"},
# )
```

`local_order_no/selected_from_available_methods/local_refund_no` come from your business state, not automatic generation by the example library. Methods needing payerOpenId/payerAppId also require the legitimate payer context described in section 5.5.

## 7. Asynchronous notifications

### 7.1 Endpoint and request headers

The platform sends HTTPS POST to the application's registered notification URL. Configure it at application level, not per order.

Headers include `X-App-Id/X-Timestamp/X-Nonce/X-Signature/X-Event-Id`, plus `X-Key-Serial` for versioned signing. Verify using P2. The header nonce is for signing; resource.nonce is for AES encryption. Do not confuse them.

### 7.2 Notification envelope

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

Encrypted fields above are placeholders, not decryption test data. Use the downloadable vectors for runnable examples.

| Field | Description |
| --- | --- |
| eventId | Stable across retries; opaque format; do not parse its internal structure |
| eventType | Current event enums below |
| stateVersion | State-version information for event content, not a global sequence or ordering across events |
| createdAt | Event creation UTC milliseconds, not each delivery time |
| resource | P4-encrypted business data |
| data | May appear only in an explicitly enabled transitional mode without EncryptKey; still requires P2 verification |

Current events:

| eventType | Meaning |
| --- | --- |
| PAYMENT_SUCCEEDED | Payment succeeded |
| PAYMENT_CLOSED | Payment closed |
| REFUND_SUCCEEDED | Refund succeeded |
| REFUND_FAILED | Refund failed |

Example decrypted payment object:

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

Refund events include the same original payment fields and add `payRefundNo/outRefundNo/refundAmountMinor/refundStatus`. Top-level business `status/amountMinor` describe the original payment. Read `refundStatus/refundAmountMinor` for this refund's outcome/amount.

### 7.3 Receiving and processing

1. Preserve raw body before parsing/rewriting JSON. Find the key by AppId/signing serial, check timestamp window, and verify P2.
2. Compare `X-Event-Id` to body.eventId and confirm X-App-Id belongs to an application your service accepts. Retain unknown event types without executing unknown actions.
3. Decrypt resource with the matching EncryptKey version and verify AAD. If encryption is required but only data arrives, treat it as an error rather than downgrading.
4. Persist receipt with a unique key on `AppId + eventId`. Update local business state or save a reliable processing task in the same transaction. Acknowledge duplicates already received.
5. Verify original payment/refund ownership, currency, amount. Reconcile amount mismatches, conflicting states, or uncertain outcomes before fulfillment.
6. After successful persistence, quickly return 2xx, e.g. HTTP 200 and `{"received":true}`. No fixed response string or ACK signature is required.
7. Return non-2xx on persistence failure so the platform retries. Use your own reliable tasks for slow processing; do not ACK first and then retain work only in memory.

HTTP nonce is not the business deduplication key. Nonce/signature/encryption IV can change on each redelivery while eventId stays the same.

### 7.4 Redelivery and reconciliation

Default delivery timeout is five seconds. Failed deliveries retry with exponential backoff, initially 30 seconds and capped at two hours, moving to manual handling after roughly 24 hours. Scheduling and configuration affect actual timing. Formal commitments come from provisioned integration information; do not derive precise attempt counts or an SLA from these defaults.

Out-of-order, duplicate, and delayed events are possible. `stateVersion` is not a counter incremented for every notification. Do not use it alone to filter different payment/refund events on one order. Deduplicate each eventId and validate business state/cumulative amounts. Recover missing notifications through original-order queries and platform reconciliation.

## 8. Key rotation and application maintenance

1. Request new key versions and securely receive plaintext/serials. Add them to server-side version lookup before deleting any old version.
2. The platform verifies/activates the new version and confirms the overlap window.
3. New requests use the new `X-Key-Serial`/AppSecret. Select EncryptKey and `X-Encrypt-Serial` separately for sensitive fields.
4. Verify responses and notifications using the actual serial, not always the latest key. Historical redeliveries may retain signing/encryption versions frozen at first delivery.
5. Retain old versions until both sides confirm the historical delivery/retry window has ended, then retire them under the platform's instructions. Do not independently extend revoked-key validity.

Application suspension/revocation, withdrawn store authorization, or disabled keys can affect calls. On a leak, contact the platform immediately and retain affected business identifiers; deleting local configuration alone is insufficient. Production/sandbox keys cannot be shared or rotated into each other's environments.

## 9. Errors and troubleshooting

| HTTP / code | Meaning | Recommended handling |
| --- | --- | --- |
| 401 / 10401 | AppId/state/environment/signature/key authentication failure | Check environment, prefix removal, actual rawBody, path, key activation |
| 401 / 70095 | Timestamp expired | Synchronize time; use UTC milliseconds, not seconds |
| 401 / 70096 | Nonce reused | New nonce/timestamp/signature, same business number |
| 403 / 10403 or 70082 | IP/scope/store/resource permission failure | Check egress IP and application/store action intersection; do not repeatedly probe other resources |
| 400 / 70097 | Sensitive field not encrypted as required | Check plaintext fields and P3 packing |
| 400 / 70098 | Sensitive field decryption failed | Check EncryptKey, serial, IV, Tag, full-field AAD |
| 503 / 70099 | Key service temporarily unavailable | Back off and reconcile original business result first |
| 429 / 10429 | Application rate limit | Reduce concurrency, back off with jitter; do not assume Retry-After |
| Nonzero / 70083 | Original order absent, invisible, or not uniquely identifiable | Check AppId, original number, ownership; do not immediately charge again |
| Nonzero / 70084 | Business number conflict | Check changed original payment/amount/content; restore original parameters |
| Nonzero / 70085 | Action not allowed in current state | Query and follow actual state |
| Nonzero / 70086 | Refund exceeds available amount | Reconcile completed/in-progress refunds; do not resubmit |
| Nonzero / 70092 | Application refund limit exceeded | Check accepted refunds and UTC daily limit; contact platform if needed |

Actual responses determine business-error HTTP mappings. Do not determine transaction results from HTTP status alone. Production authentication failures may combine causes. For troubleshooting, provide environment, AppId, requestId, method/path, time, and outOrderNo/payOrderNo or outRefundNo/payRefundNo, never AppSecret, EncryptKey, or sensitive plaintext.

Common issues:

- **Signatures never match:** check AppSecret prefix inclusion, extra Base64 key decoding, body reserialization after signing, or P1 used to verify P2 responses.
- **Collect scope but still 403:** check store COLLECT authorization, application/store validity, and egress IP.
- **HTTP 200 but customer has not paid:** it indicates API handling; confirm payment through queries/notifications.
- **Refund accepted but funds not returned:** APPROVED/DISPATCHED are not SUCCEEDED; review or channel processing may be pending.
- **Query has no checkoutUrl:** expected; only creation with hosted checkout configured returns a short-lived URL.
- **Notification signature passes but decryption fails:** signing key and EncryptKey are independent. Check resource.keySerial and retain old encryption versions for redeliveries.

## 10. Production acceptance checklist

- [ ] Sandbox/production details are separate; AppId, keys, store number, currency confirmed.
- [ ] Application and signing/encryption keys are active; required actions and store authorization are effective.
- [ ] Notification HTTPS/domain/platform verification complete; receiver does not depend on browser sessions.
- [ ] P1/P2/P3/P4 offline vectors pass; modified bodies, wrong keys/AAD, expired messages are rejected.
- [ ] Creation, identical-number retries, and conflicting content with the same number tested.
- [ ] Enabled payment methods genuinely integrated; success, cancellation, unknown outcomes handled.
- [ ] Payment/refund timeouts recover original numbers; no indiscriminate POST retries or new-number duplicate charges.
- [ ] Refund limits, partial-refund capabilities, review process confirmed; actual success requires SUCCEEDED.
- [ ] Notifications verify/decrypt, persist reliably, ACK duplicates; old-key redelivery, reordering, state conflicts recoverable.
- [ ] Closing reads the actual returned state rather than treating HTTP 200 as CLOSED.
- [ ] Key rotation, egress IP/notification changes, incident contact process confirmed.

Completing development does not enable every channel. Production rollout depends on platform acceptance for the particular application, store, and payment method.
