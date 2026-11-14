// 商城（commerce）后端原因码 → 人话（中 / 英）。
//
// 商城接口的错误 msg 是**机器原因码**（`VERSION_CONFLICT`、`OFFLINE_RECEIVER_MISMATCH`…），
// 后端不做本地化（可移植 starter 不认识宿主的 i18n）。原样弹给运营等于让他去猜 ——
// 而这批错误恰恰是「先别动、先核对」的那一类：收款主体不符、流水号已用过、金额不符。
//
// 只翻**已登记**的码：toast 全局入口拿它兜一层，命中才替换，其余错误原样显示。
// mock（lib/mock/db/commerce.ts）抛的是同一批码，两边同构。
import { ApiError } from "@/lib/api/error";
import { useLocaleStore } from "@/lib/stores/locale";

const REASONS: Record<string, [zh: string, en: string]> = {
  FORBIDDEN: ["没有这项操作的权限（权限可能刚被调整，刷新后再看）", "You no longer have permission for this action — refresh and check again"],
  NOT_FOUND: ["找不到这条记录（门店号或单号是否填对？）", "Record not found — check the store or order number"],
  INVALID_REQUEST: ["请求被拒绝：字段不完整或超出允许范围", "Rejected: a field is missing or out of the allowed range"],
  INVALID_ARGUMENT: ["目录校验未通过：常见原因是高档未包含低档权益、FREE 缺少必需端、版本不属于本类型或币种不一致", "Catalog validation failed — e.g. a higher tier misses lower-tier entitlements, FREE lacks a required end, or currencies differ"],
  VERSION_CONFLICT: ["数据已被别人改过（版本不一致），刷新后再操作", "Someone changed this in the meantime (version conflict) — refresh and retry"],
  IMMUTABLE_VERSION: ["已发布的版本只读，请新建一个草稿版本", "Published versions are read-only — create a new draft version"],
  IDEMPOTENCY_CONFLICT: ["同一个操作号已用于另一组参数，请关闭重开表单", "This operation number was already used with different values — reopen the form"],
  QUOTE_EXPIRED: ["报价已过期（15 分钟），请重新报价", "The quote expired (15 minutes) — quote again"],
  QUOTE_USED: ["这张报价已经下过单", "This quote has already been used for an order"],
  DIGITAL_CHANGE_PENDING: ["这家门店还有一笔数字权益订单未完成，不能同时再下", "This store already has an open digital order"],
  CHECKOUT_UNAVAILABLE: ["所选 SKU 当前不可售（未发布、已停售或币种不符）", "The selected SKU is not purchasable (unpublished, off sale, or wrong currency)"],
  DEPLOYMENT_UNAVAILABLE: ["已验收状态必须选部署验收清单里的端；该部署不可用", "VERIFIED requires an entry from the accepted deployment list; that deployment is unavailable"],
  INVALID_REFUND_POLICY: ["退款政策不合法：未使用部分只能选按原分摊全退或仅人工，已使用部分只能人工", "Invalid refund policy: unused = full allocation or manual only; used = manual only"],
  FULFILLMENT_UNAVAILABLE: ["当前不能发货（未付款或发货能力未开启）", "Shipment is unavailable (unpaid order or fulfillment disabled)"],
  REFUND_UNAVAILABLE: ["这笔退款当前不能执行该操作", "This refund cannot take that action now"],
  SOURCE_MANAGEMENT_UNAVAILABLE: ["权益来源管理未开启", "Entitlement source management is not enabled"],
  STORE_UNAVAILABLE: ["门店不可用（不存在或不是新商城模型）", "Store unavailable (missing or not on the mall model)"],
  CHECK_UNAVAILABLE: ["权限实时校验暂不可用，请稍后重试", "Live permission check unavailable — retry shortly"],
  SALES_REFERENCE_CONFLICT: ["这张销售收款凭据已用于核款，不能重复用于其他订单", "This sales receipt was already recorded and cannot fund another order"],
  SALES_COLLECTOR_UNAVAILABLE: ["收款销售已停用或无本店代购职责，请核对员工资料", "The collector is inactive or not authorized for this store"],
  OFFLINE_METHOD_INVALID: ["不支持此收款方式", "Unsupported collection method"],
  OFFLINE_METHOD_MISMATCH: ["收款资料与订单冻结方式不一致，请重新核对", "The payment details do not match the order's frozen collection method"],
  OFFLINE_UNAVAILABLE: ["线下收款未开启，或该币种的收款档案未启用", "Offline collection is off, or the profile for this currency is disabled"],
  OFFLINE_INSTRUCTIONS_NOT_ISSUED: ["还没有发出付款指引", "Payment instructions have not been issued yet"],
  OFFLINE_INTENT_REQUIRED: ["这不是线下付款订单", "This is not an offline-payment order"],
  OFFLINE_PROFILE_INVALID: ["收款档案不合法：字段缺失，或付款窗口不在 1 秒 ~ 90 天之间", "Invalid profile: missing fields, or the payment window is outside 1 s – 90 days"],
  OFFLINE_PROFILE_MERCHANT_MISMATCH: ["收款主体与部署配置的平台主体不一致", "The receiving entity does not match the deployment's platform merchant"],
  EVIDENCE_FORBIDDEN: ["这个凭证键不属于本订单，或你无权读取", "That evidence key does not belong to this order, or you cannot read it"],
  EVIDENCE_UNAVAILABLE: ["凭证还没上传完成", "The evidence file has not been uploaded"],
  EVIDENCE_ALREADY_STORED: ["这个凭证键已上传过，每个键只能传一次", "This evidence key was already uploaded — each key is single-use"],
  CLAIM_ALREADY_REVIEWED: ["这份申报已经处理过了", "This claim has already been reviewed"],
  OFFLINE_RECEIVER_MISMATCH: ["实际收款主体与指引里的平台主体不一致 —— 先核对是不是打到了别的账户", "The actual receiver differs from the instructed platform entity — check whether funds went elsewhere"],
  OFFLINE_BANK_REFERENCE_CONFLICT: ["这个银行流水号已经被核销过（同一笔钱不能核两张单）", "This bank reference was already used — one transfer cannot settle two orders"],
  OFFLINE_HISTORICAL_MODE_REQUIRED: ["到账时间早于下单时间：请勾选「历史补录」后再提交", "Paid before the order was created — tick “historical entry” to record it"],
  OFFLINE_ALREADY_PAID: ["订单已确认到账，不能再核款或作废", "The order is already paid — it cannot be confirmed again or voided"],
  OFFLINE_UNMATCHED_DISPOSITION_REQUIRED: ["作废前必须为每一笔金额不符的实收（MALL_UNMATCHED）写处置说明，一笔不漏", "Every unmatched receipt (MALL_UNMATCHED) needs a disposition note before voiding"],
  OFFLINE_RECONCILIATION_CONFLICT: ["这张单已经作废过，或作废参数与上次不一致", "Already voided, or the void details differ from the earlier attempt"],
  OFFLINE_PAYOUT_AMOUNT_MISMATCH: ["实际出款金额必须等于批准的退款金额", "The payout amount must equal the approved refund amount"],
  CATEGORY_UNAVAILABLE: ["分类不存在或已停用（商品只能挂启用中的分类；新建下级时上级也必须启用）", "Category missing or disabled — products and new children need an enabled category"],
  CATEGORY_TOO_DEEP: ["分类最多 3 级（移动时整棵子树一起算）", "Categories are limited to 3 levels (a moved subtree counts as a whole)"],
  CATEGORY_CYCLE: ["上级不能是自己或自己的下级", "A category cannot be moved under itself or its own descendant"],
  CATEGORY_HAS_CHILDREN: ["还有启用中的下级分类，先停用下级", "Disable its enabled child categories first"],
  CATEGORY_IN_USE: ["还有在售商品挂在这个分类上，先改商品分类或停售", "On-sale products still use this category — move or stop them first"],
  PAYMENT_UNAVAILABLE: ["支付协调未开启", "Payment coordination is not enabled"],
};

/** 已登记的原因码 → 当前语言的说明；未登记返回 null */
export function mallReason(code: string, locale: string = useLocaleStore.getState().locale): string | null {
  const hit = REASONS[code];
  return hit ? (locale === "en" ? hit[1] : hit[0]) : null;
}

const withCode = (human: string, code: string) =>
  useLocaleStore.getState().locale === "en" ? `${human} (${code})` : `${human}（${code}）`;

/** 给 toast / 错误态用：商城原因码翻成人话并附原码（便于对日志），其余错误原样 */
export function describeError(e: unknown): string {
  const msg = e instanceof Error ? e.message : String(e);
  const human = mallReason(msg);
  return human ? withCode(human, msg) : msg;
}

/** 把查询错误换成带人话的 ApiError，喂给 DataTable / ErrorState */
export function humanError(e: unknown): unknown {
  if (!(e instanceof Error)) return e;
  const human = mallReason(e.message);
  return human ? new ApiError(e instanceof ApiError ? e.code : 0, withCode(human, e.message)) : e;
}

/** 测试与审查用：登记了哪些码 */
export const MALL_REASON_CODES = Object.keys(REASONS);
