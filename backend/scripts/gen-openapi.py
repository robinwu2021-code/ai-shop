#!/usr/bin/env python3
"""A1 契约冻结：由 API 清单生成 docs/api/openapi.yaml。

为什么用生成而不是手写：437 条端点手写必然与清单漂移，而清单本身是由需求矩阵反推的
—— 手写等于在第三个地方再抄一遍。这里让「清单」保持唯一真源，契约是它的机器产物。

覆盖度分两级（对应任务清单 §二 的模块七步）：
  1. **全部端点**：路径 / 方法 / 鉴权 / 摘要 / 矩阵 ID —— 本脚本自动生成
  2. **已实现模块（M1–M3）的字段级 schema** —— 在 SCHEMAS 里手工维护，随模块 .1 步逐个补齐
未补 schema 的端点会带 x-schema-status: TODO，`api-align.py` 会统计覆盖率。

用法：python3 backend/scripts/gen-openapi.py
"""
import json
import re
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
API_LIST = ROOT / "docs/technical/reference/API清单.md"
OUT = ROOT / "docs/api/openapi.yaml"

# ---------------------------------------------------------------- 端点抽取

ENDPOINT_RE = re.compile(r"`(GET/POST|GET|POST)\s+(/[A-Za-z0-9:_\-{}/.]+)`")


def extract_endpoints():
    rows, seen = [], set()
    for line in API_LIST.read_text().splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        matrix_id = cells[0] if cells else ""
        priority = next((c for c in cells if c.startswith("P0") or c in ("P1", "P2")), "")
        for meth, path in ENDPOINT_RE.findall(line):
            marker = f"`{meth} {path}`"
            tail = line.split(marker, 1)[1] if marker in line else ""
            summary = re.split(r"[·|`]", tail)[0].strip().strip("* ") or path
            for m in (["GET", "POST"] if meth == "GET/POST" else [meth]):
                key = (m, path)
                if key in seen:
                    continue
                seen.add(key)
                rows.append({"method": m.lower(), "path": normalize(path),
                             "matrixId": matrix_id, "summary": summary, "priority": priority})
    return rows


def normalize(path):
    """`:orderNo` → `{orderNo}`（OpenAPI 语法）。端点表用的是 Express 风格。"""
    return re.sub(r":(\w+)", r"{\1}", path)


def tag_of(path):
    parts = path.strip("/").split("/")
    prefix = parts[0]
    domain = parts[1] if len(parts) > 1 else "root"
    return f"{prefix}:{domain}"


def security_of(path):
    """与 SecurityConfig 的三条过滤器链一致。"""
    if path.startswith("/callback") or path.startswith("/common"):
        return []
    if path.startswith("/ops"):
        return [{"operatorBearer": []}]
    if path.startswith("/biz"):
        return [{"consumerBearer": []}]
    # /mp：游客可访问的端点在 GUEST_PATHS 里列出，其余需要登录
    return [] if any(path.startswith(p) for p in GUEST_PATHS) else [{"consumerBearer": []}]


GUEST_PATHS = [
    "/mp/config", "/mp/community", "/mp/goods", "/mp/merchant", "/mp/category",
    "/mp/search", "/mp/home", "/mp/store", "/mp/review", "/mp/coupon",
    "/mp/group-buy", "/mp/group-request", "/mp/seckill", "/mp/user/login", "/mp/user/otp",
]

# ---------------------------------------------------------------- 手工 schema（M1–M3）

REF = lambda name: {"$ref": f"#/components/schemas/{name}"}

SCHEMAS = {
    # (method, path): {"req": schemaName|None, "resp": schemaName}
    ("post", "/mp/user/login"): {"req": "LoginReq", "resp": "LoginResult"},
    ("post", "/mp/user/otp/send"): {"req": "OtpReq", "resp": "Empty"},
    ("get", "/mp/user/profile"): {"req": None, "resp": "User"},
    ("post", "/mp/user/community"): {"req": "BindCommunityReq", "resp": "User"},
    ("get", "/mp/community/nearby"): {"req": None, "resp": "CommunityList"},
    ("get", "/mp/goods"): {"req": None, "resp": "GoodsPage"},
    ("get", "/mp/goods/{goodsNo}"): {"req": None, "resp": "Goods"},
    ("get", "/mp/merchant"): {"req": None, "resp": "MerchantPage"},
    ("get", "/mp/merchant/{merchantNo}"): {"req": None, "resp": "Merchant"},
    ("get", "/mp/cart"): {"req": None, "resp": "CartItemList"},
    ("post", "/mp/cart/add"): {"req": "CartAddReq", "resp": "CartItemList"},
    ("post", "/mp/cart/update"): {"req": "CartUpdateReq", "resp": "CartItemList"},
    ("post", "/mp/cart/remove"): {"req": "CartRemoveReq", "resp": "CartItemList"},
    ("post", "/mp/order/preview"): {"req": "CreateOrderReq", "resp": "Order"},
    ("post", "/mp/order"): {"req": "CreateOrderReq", "resp": "Order"},
    ("post", "/mp/order/{orderNo}/pay"): {"req": None, "resp": "PayResult"},
    ("get", "/mp/order/{orderNo}/pay-result"): {"req": None, "resp": "Order"},
    ("get", "/mp/order"): {"req": None, "resp": "OrderPage"},
    ("get", "/mp/order/{orderNo}"): {"req": None, "resp": "Order"},
    ("post", "/mp/order/{orderNo}/cancel"): {"req": "CancelReq", "resp": "Order"},
    ("get", "/mp/config/bootstrap"): {"req": None, "resp": "BootstrapConfig"},

    # ---- M1 契约冻结（2026-08-06）
    ("post", "/mp/user/token/refresh"): {"req": None, "resp": "LoginResult"},
    ("post", "/mp/user/logout"): {"req": None, "resp": "Empty"},
    ("post", "/mp/user/phone/bind"): {"req": "BindPhoneReq", "resp": "User"},
    ("post", "/mp/user/profile"): {"req": "UpdateProfileReq", "resp": "User"},
    ("get", "/mp/user/address"): {"req": None, "resp": "AddressList"},
    ("post", "/mp/user/address"): {"req": "SaveAddressReq", "resp": "AddressList"},
    ("post", "/mp/user/address/{addressId}/archive"): {"req": None, "resp": "AddressList"},
    ("post", "/mp/user/address/{addressId}/default"): {"req": None, "resp": "AddressList"},
    ("get", "/mp/community/{communityNo}"): {"req": None, "resp": "Community"},
    ("get", "/mp/pickup/{pickupNo}"): {"req": None, "resp": "Pickup"},
    # ---- M2 契约冻结（2026-08-06）
    ("get", "/mp/category/tree"): {"req": None, "resp": "CategoryTree"},
    ("get", "/mp/goods/{goodsNo}/sku-price"): {"req": None, "resp": "SkuPrice"},
    ("get", "/mp/search/suggest"): {"req": None, "resp": "StringList"},
    ("get", "/mp/search/hot"): {"req": None, "resp": "StringList"},
    ("get", "/mp/merchant/visited"): {"req": None, "resp": "VisitedMerchantList"},
    ("get", "/mp/merchant/{merchantNo}/score"): {"req": None, "resp": "MerchantScore"},

    # ---- M4 契约冻结（2026-08-06）
    ("get", "/biz/context"): {"req": None, "resp": "BizContext"},
    ("get", "/biz/pickup/overview"): {"req": None, "resp": "PickupOverview"},
    ("post", "/biz/pickup/verify"): {"req": "VerifyReq", "resp": "VerifyResult"},
    ("get", "/biz/pickup/verify/search"): {"req": None, "resp": "PickupOrderList"},
    ("post", "/biz/pickup/verify/batch"): {"req": "VerifyBatchReq", "resp": "VerifyBatchResult"},
    ("get", "/biz/pickup/orders"): {"req": None, "resp": "PickupOrderList"},
    ("get", "/biz/pickup/picking"): {"req": None, "resp": "PickingList"},
    ("get", "/biz/order"): {"req": None, "resp": "MerchantOrderPage"},
    ("post", "/mp/order/{orderNo}/confirm-receipt"): {"req": None, "resp": "Order"},

    # ---- M5 契约冻结（2026-08-06）
    ("post", "/mp/order/{orderNo}/after-sale"): {"req": "ApplyAfterSaleReq", "resp": "AfterSale"},
    ("get", "/mp/after-sale"): {"req": None, "resp": "AfterSaleList"},
    ("get", "/mp/after-sale/{afterSaleNo}"): {"req": None, "resp": "AfterSale"},
    ("post", "/mp/after-sale/{afterSaleNo}/cancel"): {"req": None, "resp": "AfterSale"},
    ("post", "/mp/after-sale/{afterSaleNo}/ship"): {"req": "ShipBackReq", "resp": "AfterSale"},
    ("post", "/mp/after-sale/{afterSaleNo}/escalate"): {"req": "EscalateReq", "resp": "AfterSale"},
    ("get", "/mp/after-sale/reasons"): {"req": None, "resp": "StringList"},
    ("get", "/biz/after-sale"): {"req": None, "resp": "AfterSaleList"},
    ("post", "/biz/after-sale/{afterSaleNo}/approve"): {"req": None, "resp": "AfterSale"},
    ("post", "/biz/after-sale/{afterSaleNo}/reject"): {"req": "RejectReq", "resp": "AfterSale"},
    ("post", "/biz/after-sale/{afterSaleNo}/receive"): {"req": None, "resp": "AfterSale"},

    # ---- M6a 契约冻结（门店主页 + 归因，2026-08-06）
    ("get", "/mp/store/{merchantNo}"): {"req": None, "resp": "StoreHome"},
    ("get", "/mp/store/by-code"): {"req": None, "resp": "StoreHome"},
    ("post", "/mp/store/{merchantNo}/enter"): {"req": "EnterStoreReq", "resp": "Attribution"},
    ("get", "/mp/store/{merchantNo}/frequent"): {"req": None, "resp": "FrequentItemList"},
    ("post", "/mp/store/{merchantNo}/rebuy"): {"req": None, "resp": "RebuyResult"},
    ("get", "/mp/store/mine"): {"req": None, "resp": "MerchantBriefList"},
    ("post", "/mp/store/{merchantNo}/favorite"): {"req": None, "resp": "MerchantBriefList"},
    ("post", "/mp/attribution/report"): {"req": "AttributionReportReq", "resp": "Attribution"},
    ("get", "/biz/store/qrcode"): {"req": None, "resp": "StoreQrcode"},

    # ---- M6b 契约冻结（券与优惠，2026-08-06）
    ("get", "/mp/coupon"): {"req": None, "resp": "CouponList"},
    ("post", "/mp/coupon/{couponNo}/receive"): {"req": None, "resp": "UserCoupon"},
    ("get", "/mp/coupon/mine"): {"req": None, "resp": "UserCouponList"},
    ("post", "/mp/coupon/best"): {"req": "BestCouponReq", "resp": "BestCouponResult"},

    # ---- M6c 契约冻结（团购与求团，2026-08-06）
    ("get", "/mp/group-buy"): {"req": None, "resp": "GroupBuyList"},
    ("get", "/mp/group-buy/{groupNo}"): {"req": None, "resp": "GroupBuy"},
    ("post", "/mp/group-buy/{groupNo}/join"): {"req": None, "resp": "GroupBuy"},
    ("get", "/mp/group-request"): {"req": None, "resp": "GroupRequestList"},
    ("get", "/mp/group-request/{requestNo}"): {"req": None, "resp": "GroupRequest"},
    ("post", "/mp/group-request"): {"req": "CreateRequestReq", "resp": "GroupRequest"},
    ("post", "/mp/group-request/{requestNo}/interest"): {"req": None, "resp": "GroupRequest"},
    ("get", "/mp/group-request/{requestNo}/quotes"): {"req": None, "resp": "QuoteList"},
    ("post", "/mp/group-request/{requestNo}/choose"): {"req": "ChooseQuoteReq", "resp": "GroupRequest"},
    ("get", "/mp/group-request/{requestNo}/price-history"): {"req": None, "resp": "QuoteRevisionList"},
    ("get", "/biz/group-request/pool"): {"req": None, "resp": "GroupRequestList"},
    ("post", "/biz/group-request/{requestNo}/quote"): {"req": "QuoteReq", "resp": "Quote"},
    ("post", "/biz/quote/{quoteNo}/revise"): {"req": "QuoteReq", "resp": "Quote"},

    # ---- M7 契约冻结（结算与分账，2026-08-06）
    ("get", "/biz/settle/bills"): {"req": None, "resp": "SettleBillList"},
    ("get", "/biz/settle/bills/{settleNo}"): {"req": None, "resp": "SettleBill"},
    ("get", "/biz/settle/rate-card"): {"req": None, "resp": "RateCard"},

    # ---- M8 契约冻结（消息与客服，2026-08-06）
    ("get", "/mp/message"): {"req": None, "resp": "MessageList"},
    ("post", "/mp/message/{messageNo}/read"): {"req": None, "resp": "MessageList"},
    ("post", "/mp/message/read-all"): {"req": None, "resp": "MessageList"},
    ("post", "/mp/message/subscribe"): {"req": "SubscribeReq", "resp": "Empty"},
    ("get", "/mp/ticket"): {"req": None, "resp": "TicketList"},
    ("post", "/mp/ticket"): {"req": "CreateTicketReq", "resp": "Ticket"},
    ("get", "/mp/ticket/{ticketNo}"): {"req": None, "resp": "Ticket"},
    ("get", "/mp/help/faq"): {"req": None, "resp": "FaqList"},

    # ---- M9a 契约冻结（平台端骨架，2026-08-06）
    ("post", "/ops/auth/login"): {"req": "OpsLoginReq", "resp": "OpsLoginResult"},
    ("get", "/ops/auth/me"): {"req": None, "resp": "Staff"},
    ("get", "/ops/staff"): {"req": None, "resp": "StaffList"},
    ("post", "/ops/staff"): {"req": "SaveStaffReq", "resp": "Staff"},
    ("get", "/ops/audit-log"): {"req": None, "resp": "AuditLogList"},
    ("get", "/ops/merchant/apply-queue"): {"req": None, "resp": "MerchantApplyList"},
    ("post", "/ops/merchant/apply/{applyNo}/audit"): {"req": "AuditReq", "resp": "Empty"},
    ("get", "/ops/goods/audit-queue"): {"req": None, "resp": "GoodsPage"},
    ("post", "/ops/goods/{goodsNo}/audit"): {"req": "AuditReq", "resp": "Empty"},
    ("get", "/ops/order"): {"req": None, "resp": "OrderPage"},

    ("post", "/callback/wechat/pay"): {"req": "PayCallback", "resp": "Text"},
}

STR = {"type": "string"}
INT = {"type": "integer", "format": "int64"}
BOOL = {"type": "boolean"}


def obj(props, required=None, desc=None):
    s = {"type": "object", "properties": props}
    if required:
        s["required"] = required
    if desc:
        s["description"] = desc
    return s


def arr(item):
    return {"type": "array", "items": item}


COMPONENT_SCHEMAS = {
    "Empty": {"type": "object", "nullable": True},
    "Text": {"type": "string"},

    "LoginReq": obj({
        "grantType": {"type": "string", "enum": ["WECHAT_MP", "PHONE_OTP", "APPLE"]},
        "principal": dict(STR, description="微信 code / 手机号 / Apple identityToken"),
        "credential": dict(STR, description="短信验证码等"),
        "merchantNo": dict(STR, description="从店铺码进入时带上，用于进店归因与费率分档 C-ST-09"),
        "inviterNo": STR,
        "agreed": dict(BOOL, description="是否勾选协议 —— 注册的合规前置，服务端留痕"),
    }, ["grantType", "principal"]),
    "OtpReq": obj({"phone": STR}, ["phone"]),
    "BindCommunityReq": obj({"communityNo": STR, "pickupNo": STR}, ["communityNo", "pickupNo"]),
    "LoginResult": obj({"token": STR, "user": REF("User")}),

    "User": obj({
        "userNo": dict(STR, description="C1：统一命名后的字段（Q1 决议）"),
        "cUserNo": dict(STR, description="⚠️ deprecated —— 过渡期与 userNo 双写，前端改完即删"),
        "nickname": STR, "avatar": STR,
        "phone": dict(STR, description="脱敏 138****8000"),
        "communityNo": STR, "pickupNo": STR,
        "merchantNo": dict(STR, description="我的常去店 C-ST-10"),
    }),

    "Pickup": obj({
        "pickupNo": STR, "name": STR, "address": STR,
        "distance": dict(INT, description="米；未传定位时为 0"),
        "leaderNo": dict(STR, description="⚠️ ADR-004 遗留命名，实为承接商家 merchantNo"),
        "leaderName": STR, "leaderAvatar": STR, "openHours": STR, "arrivalDesc": STR,
    }),
    "Community": obj({
        "communityNo": STR, "name": STR, "address": STR, "distance": INT,
        "pickups": arr(REF("Pickup")),
    }),
    "CommunityList": arr(REF("Community")),

    "MerchantBrief": obj({
        "merchantNo": STR, "name": STR, "logo": STR,
        "rating": {"type": "number", "description": "0–5，一位小数"},
        "verified": BOOL,
        "breachCount": dict({"type": "integer"}, description=">0 在报价卡公示（ADR-003）"),
    }),
    "Merchant": obj({
        "merchantNo": STR, "name": STR, "logo": STR, "rating": {"type": "number"},
        "verified": BOOL, "breachCount": {"type": "integer"}, "type": STR, "desc": STR,
        "salesCount": {"type": "integer"}, "ratingCount": {"type": "integer"},
        "goodsCount": {"type": "integer"}, "address": STR, "openHours": STR,
        "joinedAt": INT, "tags": arr(STR),
        "scores": obj({"goods": {"type": "number"}, "service": {"type": "number"},
                       "speed": {"type": "number"}}),
    }),

    "Sku": obj({
        "skuNo": STR, "optionValues": arr(STR), "spec": STR,
        "price": dict(INT, description="最小货币单位（分）"),
        "originPrice": INT,
        "stock": dict({"type": "integer"}, description="可售 = 总库存 - 已锁定"),
        "nominalGram": {"type": "integer"},
    }),
    "SpecGroup": obj({"name": STR, "options": arr(STR)}),
    "Goods": obj({
        "goodsNo": STR, "title": STR, "subtitle": STR, "cover": STR, "images": arr(STR),
        "type": {"type": "string", "enum": ["NORMAL", "FRESH", "SERVICE", "VIRTUAL", "CARD"]},
        "categoryNo": STR, "merchant": REF("MerchantBrief"),
        "rating": {"type": "number"}, "ratingCount": {"type": "integer"},
        "price": dict(INT, description="展示价 = 最低 SKU 价"),
        "originPrice": INT, "fulfillments": arr(STR),
        "specGroups": arr(REF("SpecGroup")), "skus": arr(REF("Sku")),
        "sales": {"type": "integer"}, "cutoffAt": INT, "arrivalDesc": STR,
        "weighed": BOOL, "origin": STR, "durationMin": {"type": "integer"},
        "storeName": STR, "limitPerUser": {"type": "integer"}, "onSale": BOOL,
    }),

    "CartItem": obj({
        "goodsNo": STR, "skuNo": STR, "title": STR, "cover": STR, "spec": STR,
        "price": dict(INT, description="实时价，非加购时快照"),
        "qty": {"type": "integer"}, "type": STR, "fulfillment": STR,
        "merchantNo": STR, "merchantName": STR, "selected": BOOL,
        "invalid": dict(BOOL, description="下架/删除 → 端上失效区"),
        "available": {"type": "integer"},
    }),
    "CartItemList": arr(REF("CartItem")),
    "CartAddReq": obj({"goodsNo": STR, "skuNo": STR, "qty": {"type": "integer"}},
                      ["goodsNo", "skuNo"]),
    "CartUpdateReq": obj({"skuNo": STR, "qty": dict({"type": "integer"},
                                                    description="<=0 视为移除")}, ["skuNo"]),
    "CartRemoveReq": obj({"skuNos": arr(STR)}),

    "OrderItem": obj({
        "goodsNo": STR, "merchantNo": STR, "skuNo": STR, "title": STR, "cover": STR, "spec": STR,
        "price": dict(INT, description="成交单价快照"), "qty": {"type": "integer"},
        "amount": INT, "type": STR,
    }),
    "OrderAmount": obj({
        "goodsMinor": INT, "freightMinor": INT, "discountMinor": INT,
        "payableMinor": INT, "paidMinor": INT,
        "pointsDeductMinor": INT, "pointsUsed": {"type": "integer"}, "pointsEarn": {"type": "integer"},
        "currency": STR,
    }, desc="金额收在值对象里（随 c-app 命名）。单位=最小货币单位"),
    "OrderTimelineNode": obj({"status": STR, "label": STR, "at": INT}),
    "Order": obj({
        "orderNo": dict(STR, description="**订单视角=子单号，支付视角=主单号**（Q6）"),
        "payOrderNo": dict(STR, description="支付单号（主单）。子单视角也带上，供收银台跳转"),
        "status": {"type": "string",
                   "enum": ["WAIT_PAY", "PAID", "PREPARING", "ARRIVED", "SHIPPED",
                            "COMPLETED", "CANCELLED", "REFUNDING", "REFUNDED"]},
        "fulfillment": {"type": "string",
                        "enum": ["STORE_PICKUP", "NEIGHBOR_PICKUP", "MERCHANT_DELIVERY", "EXPRESS"],
                        "description": "支付视角为空（跨商家可能不同）"},
        "merchantNo": STR, "merchantName": STR,
        "items": arr(REF("OrderItem")),
        "amount": REF("OrderAmount"),
        "verifyCode": dict(STR, description="自提码/核销码/兑换码三态共用；支付成功后才有"),
        "pickupNo": STR, "pickupName": STR,
        "payDeadlineAt": dict(INT, description="支付截止（原 expireAt，随前端命名）"),
        "createdAt": INT, "paidAt": INT,
        "trafficSource": {"type": "string", "enum": ["MERCHANT_OWNED", "PLATFORM"]},
        "timeline": arr(REF("OrderTimelineNode")),
        "subOrders": dict(arr(REF("Order")),
                          description="**仅支付视角返回**：一次支付覆盖的各商家订单"),
    }, desc="Q6：C 端「订单」= 子单。GET /mp/order/{no} 同时接受主单号与子单号"),
    "CreateOrderReq": obj({
        "items": arr(obj({"goodsNo": STR, "skuNo": STR, "qty": {"type": "integer"}})),
        "fulfillment": STR, "pickupNo": STR, "addressId": STR, "couponNo": STR, "remark": STR,
        "idempotencyKey": dict(STR, description="兼容旧端；优先用 Idempotency-Key 请求头"),
    }),
    "CancelReq": obj({"reason": STR}),
    "PayResult": obj({"orderNo": STR, "payChannel": STR,
                      "payParams": {"type": "object", "additionalProperties": STR}}),
    "PayCallback": obj({"outTradeNo": STR, "transactionId": STR, "sign": STR}),

    "BindPhoneReq": obj({"phone": STR, "code": dict(STR, description="短信验证码")}, ["phone", "code"]),
    "UpdateProfileReq": obj({"nickname": STR, "avatar": STR}),
    "Address": obj({
        "addressId": STR, "name": STR,
        "phone": dict(STR, description="属主可见完整号；他人视角脱敏"),
        "province": STR, "city": STR, "district": STR, "detail": STR,
        "isDefault": dict(BOOL, description="同一用户至多一条为 true"),
        "tag": dict(STR, description="家/公司/其他"),
    }),
    "AddressList": arr(REF("Address")),
    "SaveAddressReq": obj({
        "addressId": dict(STR, description="为空=新增，有值=编辑"),
        "name": STR, "phone": STR, "province": STR, "city": STR, "district": STR,
        "detail": STR, "isDefault": BOOL, "tag": STR,
    }, ["name", "phone", "detail"]),

    "StringList": arr(STR),
    "Category": obj({
        "categoryNo": STR, "parentNo": STR, "level": {"type": "integer"},
        "name": STR, "icon": STR, "sort": {"type": "integer"},
        "children": dict(arr({"$ref": "#/components/schemas/Category"}),
                         description="平台类目树（两级封顶），叶子无 children"),
    }),
    "CategoryTree": arr(REF("Category")),
    "SkuPrice": obj({
        "skuNo": STR, "spec": STR,
        "price": dict(INT, description="实时价，规格选中后重新取 —— 不用列表里那个「起」价"),
        "originPrice": INT, "stock": {"type": "integer"},
    }),
    "MerchantScore": obj({
        "merchantNo": STR, "rating": {"type": "number"}, "ratingCount": {"type": "integer"},
        "scores": obj({"goods": {"type": "number"}, "service": {"type": "number"},
                       "speed": {"type": "number"}}),
        "basis": dict(STR, description="评分依据说明（C-MC-05），端上原样展示"),
    }),
    "VisitedMerchant": obj({
        "merchantNo": STR, "name": STR, "logo": STR, "rating": {"type": "number"},
        "verified": BOOL, "breachCount": {"type": "integer"},
        "orderCount": dict({"type": "integer"}, description="在该商家的下单次数"),
        "lastOrderAt": INT,
    }),
    "VisitedMerchantList": arr(REF("VisitedMerchant")),

    "BizContext": obj({
        "merchantNo": STR, "merchantName": STR,
        "pickupNos": arr(STR), "groupNos": arr(STR),
    }, desc="当前用户在经营侧的三个作用域。空 = 不是商家，所有 /biz 端点 403"),
    "PickupOverview": obj({
        "pickupNo": STR, "pickupName": STR,
        "pendingVerify": dict({"type": "integer"}, description="今日待核销数"),
        "arrivedBatches": {"type": "integer"},
        "serviceFeeMinor": dict(INT, description="本月履约服务费；NEIGHBOR 恒 0"),
    }),
    "PickupOrder": obj({
        "subOrderNo": STR, "verifyCode": STR,
        "buyerNickname": dict(STR, description="**不是完整手机号** —— 承接方只需要认人"),
        "buyerPhoneTail": dict(STR, description="后四位，用于当面核对"),
        "merchantName": STR, "status": STR,
        "items": arr(obj({"title": STR, "spec": STR, "qty": {"type": "integer"}})),
    }, desc="⚠️ 自提点视角：**没有金额字段、没有完整手机号**。别家商家的货也会到本点核销"),
    "PickupOrderList": arr(REF("PickupOrder")),
    "VerifyReq": obj({"verifyCode": STR, "onBehalf": dict(BOOL, description="代核销，强制留痕")},
                     ["verifyCode"]),
    "VerifyResult": obj({
        "success": BOOL, "subOrderNo": STR,
        "reason": dict(STR, description="失败原因：ALREADY_VERIFIED/NOT_THIS_PICKUP/REFUNDED"),
    }),
    "VerifyBatchReq": obj({"verifyCodes": arr(STR)}),
    "VerifyBatchResult": obj({"successCount": {"type": "integer"},
                              "failed": arr(REF("VerifyResult"))}),
    "PickingRow": obj({
        "goodsNo": STR, "title": STR, "spec": STR,
        "totalQty": dict({"type": "integer"}, description="本点该规格合计件数"),
        "buyerCount": {"type": "integer"},
    }, desc="按商品聚合的分拣单：到货当日按它分堆"),
    "PickingList": arr(REF("PickingRow")),
    "MerchantOrderPage": obj({
        "records": arr(REF("Order")), "total": INT, "page": INT, "size": INT,
    }, desc="商家订单：只含本 merchantNo 的子单"),

    "AfterSale": obj({
        "afterSaleNo": STR, "subOrderNo": STR, "orderNo": STR,
        "type": {"type": "string", "enum": ["REFUND_ONLY", "RETURN_REFUND", "EXCHANGE"]},
        "status": {"type": "string",
                   "enum": ["APPLIED", "REFUNDING", "REFUNDED", "REJECTED",
                            "ARBITRATING", "CLOSED"]},
        "reason": STR, "images": arr(STR),
        "refundMinor": INT,
        "instant": dict(BOOL, description="极速退：命中阈值自动通过，商家只可见不可拒"),
        "merchantRemark": STR,
        "expressNo": dict(STR, description="退货物流单号（RETURN_REFUND）"),
        "liability": dict(STR, description="责任方 PLATFORM/MERCHANT/PICKUP，平台裁决后才有（M4 待定）"),
        "createdAt": INT, "timeline": arr(REF("OrderTimelineNode")),
    }),
    "AfterSaleList": arr(REF("AfterSale")),
    "ApplyAfterSaleReq": obj({
        "subOrderNo": dict(STR, description="**售后是子单粒度**（Q6）：一次只针对一个商家"),
        "type": STR, "reason": STR, "images": arr(STR), "refundMinor": INT,
    }, ["type", "reason"]),
    "ShipBackReq": obj({"expressCompany": STR, "expressNo": STR}, ["expressNo"]),
    "EscalateReq": obj({"appeal": STR}),
    "RejectReq": obj({"remark": dict(STR, description="驳回必须写理由 —— 用户要据此决定是否申诉")},
                     ["remark"]),

    "StoreHome": obj({
        "merchant": REF("MerchantBrief"),
        "notice": dict(STR, description="店铺公告"),
        "fulfillmentDesc": dict(STR, description="履约说明：几点到货、怎么取"),
        "favorited": BOOL,
        "hotGoods": arr(REF("Goods")),
    }, desc="门店主页（C-ST-01）。**游客可访问，不经首页与选社区** —— 扫码/分享直达"),
    "MerchantBriefList": arr(REF("MerchantBrief")),
    "FrequentItem": obj({
        "goodsNo": STR, "skuNo": STR, "title": STR, "cover": STR, "spec": STR,
        "price": dict(INT, description="**当前**价，不是上次买的价"),
        "lastPrice": dict(INT, description="上次成交价；与 price 不同则端上标「已涨价/已降价」"),
        "buyCount": {"type": "integer"}, "lastBoughtAt": INT,
        "available": {"type": "integer"},
        "invalid": dict(BOOL, description="已下架 —— 一键复购时跳过并明确告知"),
    }, desc="常买清单（C-ST-02）。粮油副食不是逛出来的，第一屏就是「我买过的」"),
    "FrequentItemList": arr(REF("FrequentItem")),
    "RebuyResult": obj({
        "addedCount": {"type": "integer"},
        "skipped": arr(obj({"title": STR, "reason": STR})),
    }, desc="一键再来一单（C-ST-03）：**失效品与涨价品必须显式标出**，不能悄悄少加"),
    "EnterStoreReq": obj({"storeCode": STR, "inviterNo": STR, "channel": STR}),
    "AttributionReportReq": obj({"merchantNo": STR, "inviterNo": STR, "channel": STR}),
    "Attribution": obj({
        "merchantNo": STR, "inviterNo": STR, "channel": STR,
        "source": {"type": "string", "enum": ["STORE_CODE", "INVITER", "CHANNEL"],
                   "description": "命中的归因来源，优先级 店铺码 > 邀请人 > 渠道"},
        "trafficSource": {"type": "string", "enum": ["MERCHANT_OWNED", "PLATFORM"]},
        "expireAt": dict(INT, description="窗口期结束（默认 30 天）"),
    }),
    "StoreQrcode": obj({
        "merchantNo": STR, "storeCode": STR,
        "url": dict(STR, description="扫码落地页；小程序码由端上生成"),
        "printableHint": STR,
    }),

    "Coupon": obj({
        "couponNo": STR, "title": STR,
        "type": {"type": "string", "enum": ["FULL_CUT", "DISCOUNT"]},
        "faceMinor": dict(INT, description="满减面额；折扣券为 0"),
        "discountRate": dict({"type": "integer"}, description="折扣 ×100，如 88 = 8.8 折"),
        "thresholdMinor": dict(INT, description="使用门槛（商品额）"),
        "maxDiscountMinor": dict(INT, description="折扣券的封顶"),
        "funder": {"type": "string", "enum": ["PLATFORM", "MERCHANT"],
                   "description": "**出资方**：决定分账时扣谁的钱（Q9）"},
        "merchantNo": dict(STR, description="商家券限本店；平台券为空"),
        "startAt": INT, "endAt": INT,
        "remain": {"type": "integer"}, "received": BOOL,
    }),
    "CouponList": arr(REF("Coupon")),
    "UserCoupon": obj({
        "userCouponNo": STR, "coupon": REF("Coupon"),
        "status": {"type": "string", "enum": ["UNUSED", "USED", "EXPIRED"]},
        "usableNow": dict(BOOL, description="对当前购物车是否可用"),
        "receivedAt": INT, "usedAt": INT,
    }),
    "UserCouponList": arr(REF("UserCoupon")),
    "BestCouponReq": obj({
        "items": arr(obj({"goodsNo": STR, "skuNo": STR, "qty": {"type": "integer"}})),
    }),
    "BestCouponResult": obj({
        "bestUserCouponNo": dict(STR, description="最优券；无可用券为空"),
        "discountMinor": INT,
        "usable": arr(REF("UserCoupon")),
        "unusable": arr(obj({"userCouponNo": STR, "reason": STR})),
    }, desc="不可用的券也要返回并**给出原因** —— 「为什么我的券用不了」是券功能最大的客诉来源"),

    "GroupBuy": obj({
        "groupNo": STR, "goodsNo": STR, "title": STR, "cover": STR,
        "merchantNo": STR, "merchantName": STR,
        "groupPriceMinor": INT, "originPriceMinor": INT,
        "minCount": dict({"type": "integer"}, description="起团人数"),
        "joinedCount": {"type": "integer"},
        "status": {"type": "string", "enum": ["OPEN", "FORMED", "FAILED", "CLOSED"]},
        "endAt": INT, "joined": BOOL,
    }),
    "GroupBuyList": arr(REF("GroupBuy")),
    "GroupRequest": obj({
        "requestNo": STR, "title": STR, "description": STR, "images": arr(STR),
        "ownerId": dict(STR, description="**发起人**：只有他能选定报价（不是身份，是团实例上的字段）"),
        "ownerNickname": STR,
        "expectCount": {"type": "integer"},
        "interestCount": dict({"type": "integer"}, description="+1 数 —— **是意向不是订单**"),
        "interested": BOOL,
        "status": {"type": "string",
                   "enum": ["COLLECTING", "QUOTED", "LOCKED", "CONFIRMED", "CLOSED"]},
        "quoteCount": {"type": "integer"},
        "chosenQuote": REF("Quote"),
        "createdAt": INT, "endAt": INT,
    }, desc="邻里求团（C-8.2）。报价**不做事前审核**，靠锁价 + 改价公示 + 毁约记录（ADR-003）"),
    "GroupRequestList": arr(REF("GroupRequest")),
    "Quote": obj({
        "quoteNo": STR, "requestNo": STR,
        "merchantNo": STR, "merchantName": STR,
        "merchantRating": {"type": "number"},
        "breachCount": dict({"type": "integer"}, description="**毁约次数，>0 直接公示在报价卡上**（ADR-003）"),
        "unitPriceMinor": INT,
        "minQty": dict({"type": "integer"}, description="起订量"),
        "note": STR,
        "validUntil": INT,
        "revisionCount": dict({"type": "integer"}, description="改价次数，>0 端上展示改价历史入口"),
        "chosen": BOOL, "createdAt": INT,
    }),
    "QuoteList": arr(REF("Quote")),
    "QuoteRevision": obj({
        "quoteNo": STR, "merchantName": STR,
        "fromPriceMinor": INT, "toPriceMinor": INT,
        "raised": dict(BOOL, description="是否涨价 —— **涨价必须公示**，这是不做事前审核的代价"),
        "at": INT,
    }),
    "QuoteRevisionList": arr(REF("QuoteRevision")),
    "CreateRequestReq": obj({
        "title": STR, "description": STR, "images": arr(STR),
        "expectCount": {"type": "integer"}, "days": {"type": "integer"},
    }, ["title"]),
    "ChooseQuoteReq": obj({"quoteNo": STR}, ["quoteNo"]),
    "QuoteReq": obj({
        "unitPriceMinor": INT, "minQty": {"type": "integer"},
        "note": STR, "validDays": {"type": "integer"},
    }, ["unitPriceMinor"]),

    "SettleBill": obj({
        "settleNo": STR, "subOrderNo": STR, "orderNo": STR, "merchantNo": STR,
        "grossMinor": dict(INT, description="应结基数 = 用户实付 + **平台补贴的优惠**（平台券的钱要给商家）"),
        "commissionMinor": dict(INT, description="平台佣金 = 基数 × 费率档"),
        "serviceFeeMinor": dict(INT, description="自提点履约服务费（R15 口径未定，恒 0）"),
        "netMinor": dict(INT, description="商家实得 = 基数 - 佣金 - 服务费"),
        "trafficSource": {"type": "string", "enum": ["MERCHANT_OWNED", "PLATFORM"]},
        "commissionRate": dict({"type": "integer"}, description="万分比。自带客流一期为 0（R16）"),
        "status": {"type": "string",
                   "enum": ["PENDING", "SPLITTING", "SPLIT", "RETRYING", "MANUAL",
                            "REVERSING", "REVERSED"]},
        "createdAt": INT, "splitAt": INT,
    }, desc="结算单：**按子单**生成（一个子单 = 一次分账）"),
    "SettleBillList": arr(REF("SettleBill")),
    "RateCard": obj({
        "merchantOwnedRate": dict({"type": "integer"}, description="自带客流费率（万分比）"),
        "platformRate": dict({"type": "integer"}, description="平台客流费率（万分比）"),
        "note": STR,
    }, desc="费率说明（B-11.9.5 / R16）：商家要能自己算清楚每单能拿多少"),

    "Message": obj({
        "messageNo": STR,
        "type": {"type": "string", "enum": ["TRADE", "MARKETING", "SYSTEM"],
                 "description": "三类分开是因为**用户对它们的期待完全不同**：交易类必须看到"},
        "title": STR, "body": STR,
        "link": dict(STR, description="点进去跳哪，已是完整页面路径带参"),
        "read": BOOL, "at": INT,
    }),
    "MessageList": arr(REF("Message")),
    "SubscribeReq": obj({
        "templateIds": arr(STR),
        "accepted": dict(BOOL, description="用户是否同意 —— 拒绝也要记，否则会反复弹窗骚扰"),
    }),
    "Ticket": obj({
        "ticketNo": STR, "subject": STR, "content": STR,
        "orderNo": dict(STR, description="关联订单，可空"),
        "status": {"type": "string", "enum": ["OPEN", "REPLIED", "CLOSED"]},
        "reply": STR, "createdAt": INT, "repliedAt": INT,
    }),
    "TicketList": arr(REF("Ticket")),
    "CreateTicketReq": obj({"subject": STR, "content": STR, "orderNo": STR}, ["subject", "content"]),
    "Faq": obj({"question": STR, "answer": STR, "category": STR}),
    "FaqList": arr(REF("Faq")),

    "OpsLoginReq": obj({"username": STR, "password": STR}, ["username", "password"]),
    "OpsLoginResult": obj({"token": STR, "staff": REF("Staff")}),
    "Staff": obj({
        "staffNo": STR, "username": STR, "realName": STR,
        "roles": arr(STR),
        "perms": dict(arr(STR), description="权限码。**前端只用来控制展示**，真正的拦截在后端"),
        "status": STR,
    }),
    "StaffList": arr(REF("Staff")),
    "SaveStaffReq": obj({"username": STR, "realName": STR, "password": STR, "roles": arr(STR)},
                        ["username"]),
    "AuditLogList": arr(obj({
        "staffNo": STR, "staffName": STR, "action": STR,
        "target": STR, "detail": STR, "at": INT,
    })),
    "MerchantApply": obj({
        "applyNo": STR, "merchantNo": STR, "name": STR, "type": STR,
        "contactPhone": STR, "qualifications": arr(STR),
        "status": STR, "rejectReason": STR, "createdAt": INT,
    }),
    "MerchantApplyList": arr(REF("MerchantApply")),
    "AuditReq": obj({
        "approved": BOOL,
        "reason": dict(STR, description="驳回必填 —— 不写理由的驳回等于让对方猜"),
    }, ["approved"]),

    "BootstrapConfig": obj({
        "defaultSkin": STR,
        "features": {"type": "object", "additionalProperties": BOOL},
        "minAppVer": STR, "serviceHours": STR,
    }),
}

# 分页包装：PageData<T>
for name in ("Goods", "Merchant", "Order"):
    COMPONENT_SCHEMAS[f"{name}Page"] = obj({
        "records": arr(REF(name)), "total": INT, "page": INT, "size": INT,
    })

# ---------------------------------------------------------------- 生成

def result_wrap(schema_name):
    """统一响应包 {code,msg,data}。data 指向具体 schema。"""
    return {
        "type": "object",
        "required": ["code", "msg"],
        "properties": {
            "code": {"type": "integer", "description": "0=成功；分段见 API 清单 §1.5"},
            "msg": STR,
            "data": REF(schema_name) if schema_name else {"nullable": True},
        },
    }


def build():
    endpoints = extract_endpoints()
    paths = {}
    todo = 0
    for ep in endpoints:
        key = (ep["method"], ep["path"])
        schema = SCHEMAS.get(key)
        if schema is None:
            todo += 1
        op = {
            "tags": [tag_of(ep["path"])],
            "summary": ep["summary"],
            "operationId": operation_id(ep),
            "x-matrix-id": ep["matrixId"],
            "x-priority": ep["priority"],
            "x-schema-status": "DEFINED" if schema else "TODO",
            "security": security_of(ep["path"]),
            "responses": {"200": {
                "description": "OK",
                "content": {"application/json": {
                    "schema": result_wrap(schema["resp"] if schema else None)}},
            }},
        }
        if schema and schema.get("req"):
            op["requestBody"] = {"required": True, "content": {
                "application/json": {"schema": REF(schema["req"])}}}
        for p in re.findall(r"\{(\w+)\}", ep["path"]):
            op.setdefault("parameters", []).append(
                {"name": p, "in": "path", "required": True, "schema": STR})
        paths.setdefault(ep["path"], {})[ep["method"]] = op

    doc = {
        "openapi": "3.0.3",
        "info": {
            "title": "ai-shop 服务端 API",
            "version": "0.1.0",
            "description": (
                "由 docs/technical/reference/API清单.md 生成（backend/scripts/gen-openapi.py）。\n"
                "响应包统一 {code,msg,data}，分页 {records,total,page,size}。\n"
                f"端点 {len(endpoints)} 条，其中字段级 schema 已定义 {len(endpoints)-todo} 条、"
                f"待定义 {todo} 条（随各模块「契约冻结」步骤补齐）。"),
        },
        "servers": [{"url": "http://localhost:8080"}],
        "tags": sorted({tag_of(e["path"]) for e in endpoints}),
        "components": {
            "securitySchemes": {
                "consumerBearer": {"type": "http", "scheme": "bearer",
                                   "description": "C 池 realm=CONSUMER，/mp 与 /biz 共用"},
                "operatorBearer": {"type": "http", "scheme": "bearer",
                                   "description": "O 池 realm=OPERATOR，/ops 专用"},
            },
            "schemas": COMPONENT_SCHEMAS,
        },
        "paths": dict(sorted(paths.items())),
    }
    return doc, len(endpoints), todo


def operation_id(ep):
    parts = [p for p in ep["path"].strip("/").split("/") if not p.startswith("{")]
    return ep["method"] + "".join(w.capitalize() for p in parts for w in p.split("-"))


def to_yaml(node, indent=0):
    """极简 YAML 序列化：只覆盖本脚本产出的结构，避免引入 PyYAML 依赖。"""
    pad = "  " * indent
    if isinstance(node, dict):
        if not node:
            return "{}"
        out = []
        for k, v in node.items():
            key = k if re.fullmatch(r"[A-Za-z0-9_.\-]+", str(k)) else json.dumps(k, ensure_ascii=False)
            if isinstance(v, (dict, list)) and v:
                out.append(f"{pad}{key}:\n{to_yaml(v, indent + 1)}")
            else:
                out.append(f"{pad}{key}: {scalar(v)}")
        return "\n".join(out)
    if isinstance(node, list):
        if not node:
            return f"{pad}[]"
        out = []
        for item in node:
            if isinstance(item, (dict, list)) and item:
                body = to_yaml(item, indent + 1)
                out.append(f"{pad}-\n{body}")
            else:
                out.append(f"{pad}- {scalar(item)}")
        return "\n".join(out)
    return f"{pad}{scalar(node)}"


def scalar(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, (dict, list)):
        return "{}" if isinstance(v, dict) else "[]"
    return json.dumps(str(v), ensure_ascii=False)


if __name__ == "__main__":
    # ─────────────────────────────────────────────────────────────────────────
    # ⚠️ **这个生成器已被取代，且不能再跑。**
    #
    # `docs/api/openapi.yaml` 现在由 `c-app/scripts/gen-openapi.mjs` 生成
    #（从 C 端端点表 + TS 类型出，文件头第一行就写着 `npm run gen:api`）。
    # 本脚本写的是同一个路径，但内容来自**需求矩阵**——那份矩阵比实现少一截：
    # 2026-08-27 实测，跑一次会**删掉 17 个后端真实存在的端点**
    #（/mp/community/regions、/mp/invoice/*、/mp/push-token、/mp/order/capability…），
    # 而 diff 有两万五千行，删的那 17 条埋在里面根本看不出来。
    #
    # 它不报错、还打一行 `wrote ... 440 endpoints` 的成功日志 ——
    # 一把上了膛、枪口朝上的枪。留着文件是因为里面那张手工 SCHEMAS 表还有参考价值；
    # 要真正复活它，得先让需求矩阵补齐那 17 条，并想清楚两个生成器谁写这个路径。
    # ─────────────────────────────────────────────────────────────────────────
    print("✗ 本脚本已停用：docs/api/openapi.yaml 由 c-app/scripts/gen-openapi.mjs 生成。",
          file=sys.stderr)
    print("  跑它会用需求矩阵覆盖 C 端契约，并静默删掉 17 个真实端点（见上方注释）。",
          file=sys.stderr)
    print("  要生成 C 端契约：cd c-app && npm run gen:api", file=sys.stderr)
    sys.exit(2)

    doc, total, todo = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(to_yaml(doc) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {total} endpoints, "
          f"{total - todo} with field-level schema, {todo} TODO")
    sys.exit(0)
