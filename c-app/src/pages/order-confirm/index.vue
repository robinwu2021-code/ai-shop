<script setup lang="ts">
// 结算页。
//
// 五条履约线在这一页收敛，但**收货信息区**各不相同：
//   PICKUP       → 自提点（用已绑定的，可临时改点）
//   DELIVERY     → 地址簿选地址（社区内配送）
//   EXPRESS      → 地址簿选地址（跨区快递）
//   STORE_VERIFY → 无收货，展示核销门店
//   APPOINTMENT  → 无收货，展示已选预约时段
//   INSTANT      → 无收货，发码到订单
// 差异只在这一块，金额与提交是共用的 —— 与 strategies 的分层保持一致。
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { checkoutKey, checkoutKeyBoundTo } from "@/shared/checkout-key";
import { segmentByMerchant, useCartStore } from "@/stores/cart";
import { useCommunityStore } from "@/stores/community";
import { useLocationStore } from "@/stores/location";
import { useUserStore } from "@/stores/user";
import PhoneGate from "@/components/phone-gate.vue";
import { FEATURES, FULFILLMENT, PAY_MODE, PICKUP_FAR_M, POINTS, ROUTES, TRADE_RULES } from "@shared/utils/constants";
import { datetime, distance as fmtDistance, money } from "@shared/utils/format";
import { earnPointsFor, pricingFor } from "@shared/strategies/pricing";
// 券能减多少与后端同一套算法算 —— 两处各写一遍就会出现「页面说减 8，付完只减 5」
import { couponDiscount } from "@shared/strategies/pricing/types";
import { currentCurrency } from "@shared/utils/money";
import type { Address, CartItem, CheckoutCapability, FulfillmentType, OrderItem, OrderAmount, PointsDeductible, UserCoupon } from "@shared/types";
import { confirm, pick } from "@ai-shop/ui/prompt";
import { metersBetweenE6, withinDeliveryRange } from "@shared/utils/geo";
import { pickedAddress } from "@/shared/address-pick";

const { t } = useI18n();
const cart = useCartStore();
const community = useCommunityStore();
const user = useUserStore();
/** 「先留个手机号」弹层。绑完自动继续提交，不用他再点一次 */
const phoneGate = ref(false);

const fulfillment = ref<FulfillmentType>(FULFILLMENT.PICKUP);
const items = ref<CartItem[]>([]);
const addresses = ref<Address[]>([]);
const addressId = ref("");
/** 我领到的券（`/mp/coupon/mine`）。不是领券中心那批 —— 见 onMounted */
const coupons = ref<UserCoupon[]>([]);
const couponNo = ref("");
const remark = ref("");
const usePoints = ref(false);
const pointBalance = ref(0);
const submitting = ref(false);
/** 预约时段：从商品详情带过来 */
const appointmentAt = ref<number | undefined>(undefined);

/**
 * 需要收货地址的履约方式。
 *
 * ⚠️ **上门预约也要**：师傅要知道去哪。这里此前只有快递与自送，
 * 而后端的 `SHIPPED_FULFILLMENTS` 已经把 APPOINTMENT 算进去 ——
 * 两边不一致的表现是：端上放行、后端拒收，用户点提交拿到一个说不清的错误。
 */
const needAddress = computed(
  () =>
    fulfillment.value === FULFILLMENT.DELIVERY ||
    fulfillment.value === FULFILLMENT.EXPRESS ||
    fulfillment.value === FULFILLMENT.APPOINTMENT,
);
const needPickup = computed(() => fulfillment.value === FULFILLMENT.PICKUP);
/** 要选时段的履约方式。**没有时间的预约单商家不知道该几点去** */
const needAppointment = computed(() => fulfillment.value === FULFILLMENT.APPOINTMENT);

/**
 * 可选时段：今天起 `appointmentWindowDays` 天，每天几个整点。
 *
 * **不做真正的排期**（师傅有没有空）—— 那是另一个量级，一期明确不做。
 * 这里只解决「买家说个时间、商家知道几点去」，商家线下确认。
 */
const SLOT_HOURS = [9, 11, 14, 16, 18];
const slots = computed(() => {
  const out: { at: number; label: string }[] = [];
  const now = Date.now();
  for (let d = 0; d < TRADE_RULES.appointmentWindowDays; d += 1) {
    const day = new Date();
    day.setDate(day.getDate() + d);
    for (const h of SLOT_HOURS) {
      day.setHours(h, 0, 0, 0);
      const at = day.getTime();
      // 过去的时段不给选 —— 后端那道闸也会拒，早点拦住比让他撞一次好
      if (at <= now) continue;
      out.push({ at, label: datetime(at) });
    }
  }
  return out;
});

async function pickSlot() {
  const list = slots.value.slice(0, 12);
  if (!list.length) return;
  const idx = await pick({
    title: String(t("confirm.pickSlot")),
    items: list.map((s) => s.label),
    // 系统面板做不到的一件事：把当前已选的那一档打上勾
    selected: list.findIndex((s) => s.at === appointmentAt.value),
  });
  if (idx === null) return;
  const picked = list[idx];
  if (picked) appointmentAt.value = picked.at;
}

const location = useLocationStore();

/**
 * 把「当前位置」存成收货地址 —— **下单时才问，且问了才存**。
 *
 * <p>不自动存：地址簿上限 20 条，每次「用一下现在这儿」都存一条会很快塞满；
 * 而且送到这儿要姓名电话门牌，那些他还没填。带着坐标跳去新建地址页，
 * 让他自己补完 —— 存下来的那条才是一条**能送到的**地址。
 */
function saveHereAsAddress() {
  // 唯一的一份在 store 里 —— 这一页与收货地址页此前各写一份、走两条路
  location.gotoSaveHere({ address: ROUTES.address });
}

const address = computed(() => addresses.value.find((a) => a.addressId === addressId.value));

/** 这一单的履约方式认不认聚落。快递不认 —— 它能送到任何地方 */
const boundToCommunity = computed(
  () => fulfillment.value === FULFILLMENT.PICKUP
    || fulfillment.value === FULFILLMENT.DELIVERY,
);

/**
 * 收货地址与「这一屏的货所属聚落」对不对得上。
 *
 * <p>只在**认聚落的履约方式**下判，且两边都要有坐标 ——
 * 没坐标推不出聚落，那时拦下来只会挡住一批本来就没问题的人。
 *
 * @returns true = 可以继续（对得上，或者判不了，或者他确认了）
 */
async function confirmPlaceMismatch(): Promise<boolean> {
  if (!boundToCommunity.value) return true;
  const a = address.value;
  const c = community.community;
  if (!a || a.latE6 == null || a.lngE6 == null) return true;
  if (!c || c.latE6 == null || c.lngE6 == null) return true;
  const m = metersBetweenE6(a.latE6, a.lngE6, c.latE6, c.lngE6);
  if (m <= COMMUNITY_REACH_M) return true;
  // ⚠️ 说明文字的字段名是 `hint` 不是 `content` —— ConfirmOptions 没有 content，
  // 写成 content 的话说明文字被静默丢掉，弹框只剩一个标题（vue-tsc 会拦）
  return confirm({
    title: String(t("confirm.placeMismatchTitle")),
    hint: String(t("confirm.placeMismatchBody", { place: c.name, km: (m / 1000).toFixed(0) })),
  });
}

/**
 * 地址离这一屏的聚落多远就算「不是同一个地方」。
 *
 * <p>两公里：比围栏（1000 米量级）宽一档 —— 围栏是「算不算这个小区的人」，
 * 而这里问的是「这一单会不会送不到」，后者该松一些，
 * 否则住在小区斜对面的人每次下单都被问一遍。
 */
const COMMUNITY_REACH_M = 2000;
const coupon = computed(
  () => coupons.value.find((u) => u.coupon.couponNo === couponNo.value)?.coupon,
);

/**
 * 可用券。**「还能不能用」以服务端的 `usableNow` 为准**（用没用过、在不在有效期，
 * 只有它知道）；端上只再判这一单自己的事：金额够不够门槛、当面付能不能用平台券。
 */
const usableCoupons = computed(() =>
  coupons.value
    .filter(
      (u) => u.usableNow && goodsMinor.value >= u.coupon.thresholdMinor
        // 当面付下平台券用不了 —— 列表里就不该出现，否则他选完才被摘掉
        && !(payMode.value === PAY_MODE.OFFLINE && u.coupon.funder === "PLATFORM"),
    )
    .map((u) => u.coupon),
);

const goodsMinor = computed(() =>
  items.value.reduce((s, it) => s + it.price * it.qty, 0),
);

const orderItems = computed(
  () =>
    items.value.map((it) => ({
      goodsNo: it.goodsNo,
      merchantNo: "",
      skuNo: it.skuNo,
      title: it.title,
      cover: it.cover,
      spec: it.spec,
      price: it.price,
      qty: it.qty,
      type: it.type,
    })) as OrderItem[],
);

/** 赠品行：不计价，只展示 */
const gifts = computed(() => items.value.filter((it) => (it.giftQty ?? 0) > 0));

/**
 * 按商家分段。**段数就是提交后会生成的子订单数**。
 *
 * 与购物车页用的是同一个函数：两页显示的段必须一致 ——
 * 各写一份的话，购物车说两家、确认页说一家，而用户只会记住后一个。
 */
const merchantSegments = computed(() => segmentByMerchant(items.value));

/**
 * 金额**以后端预览为准**，端上不自己算。
 *
 * ⚠️ 这里此前调的是共享定价策略 `pricingFor(type).estimate(...)`，
 * 注释写着「不在页面里另写一份公式，两边迟早对不上」——
 * 共享策略确实解决了「C 端与 B 端公式不一致」，但解决不了另一件事：
 * **端上不知道服务端有什么优惠**。活动（店铺满减）、券的可用性、积分规则
 * 全在服务端，端上只能算出一个乐观的近似值。
 *
 * 接通店铺满减那天这个差就现形了：页面显示 ¥298.80，提交后实付 ¥290.80 ——
 * 同一笔单两个金额。所以改成调 `/mp/order/preview`（后端一直有，端上从没接过）。
 *
 * 本地估算保留为**首屏兜底**：预览回来之前先给个数，否则会闪一下空白；
 * 但一旦服务端的数到了就以它为准。
 */
const serverAmount = ref<OrderAmount | null>(null);
/** 距离文案。-1（没标坐标）与空都给空串 —— 宁可少一行，不要编一个数 */
function distanceOf(m?: number | null): string {
  return m == null || m < 0 ? "" : fmtDistance(m);
}

/** 这个点算不算远。**只影响提不提醒**，不拦提交 */
function isFar(m?: number | null): boolean {
  return m != null && m > PICKUP_FAR_M;
}

/**
 * 后端为这一单配好的自提点，**按取货点分组**。
 *
 * 买家不再挑点（TDD-C端位置选择-地址取代自提点）：地址决定他在哪，
 * 点由后端按「这家商家承接哪些 ∩ 归属链上 ∩ 离他最近」配出来。
 * 属于多个就是多个 —— 子单本来就按商家拆，各落各的。
 *
 * <b>两家配到同一个点要合并成一组</b>：按商家分会让人以为要跑两趟。
 */
const pickupGroups = ref<Array<{
  pickupNo: string;
  pickupName: string;
  merchants: string[];
  /** 离买家多远（米）。-1 = 这个点没标坐标，排不出远近；null = 这一次没算 */
  distanceM?: number | null;
}>>([]);
/** 后端没给点的那几家 —— 它们在这一带没有可用的取货点，付款前就要说 */
const pickupMissing = ref<string[]>([]);
/**
 * 试算是不是**在途**，以及上一次有没有失败。
 *
 * 金额会随地址、券、积分、支付方式一起变，每变一次就要重问一次后端。
 * 中间那段时间屏幕上挂着的是上一次的数 —— 不说一声的话，用户会以为
 * 「改完了，就是这个价」，然后在提交后看到另一个数。
 */
const amountPending = ref(false);
/** 服务端试算失败，屏幕上是本地估算。**要说出来**，两个数长得一模一样 */
const amountStale = ref(false);

/**
 * 结算页能力提示：这一车货能不能开票、能用哪些支付方式、额度够不够。
 *
 * 三件事的共同后果都是**付款那一刻才炸** —— 小微没有 H5/App 支付方式、
 * 小微不能开票、额度用尽通道直接拒收。后端一直拦得住，但买家是在
 * 点了支付之后才知道，而那时候平台既解释不清也补救不了。
 */
const capability = ref<CheckoutCapability | null>(null);

/**
 * 这一单里**送不到这个地址**的商家。空数组 = 都送得到。
 *
 * <p>此前端上完全不知道这件事：用户挑地址、填完、点提交，才撞上后端的
 * `OUT_OF_DELIVERY_RANGE` —— 而那时他既不知道是哪家送不到，也不知道该换哪个地址。
 *
 * <p>口径与后端 `requireWithinDeliveryRadius` 共用一份实现（`withinDeliveryRange`），
 * **三条放行一字不差**。只在自送单上判：快递、自提都不受自送半径约束。
 */
const outOfRange = computed(() => {
  if (fulfillment.value !== FULFILLMENT.DELIVERY) return [];
  const a = address.value;
  if (!a) return [];
  return (capability.value?.merchants ?? []).filter((m) => !withinDeliveryRange(m, a));
});

/** 开不了票的商家。买完才发现开不了票，平台补救不了 —— 必须在付款前说。 */
const noInvoiceMerchants = computed(
  () => capability.value?.merchants.filter((m) => !m.invoiceCapable) ?? [],
);

/**
 * 这一车货一种支付方式都用不了。
 *
 * **拦在结算页**，别让他点下去：让他点下去只会得到一个说不清原因的「支付失败」。
 * 注意判据是「后端给了 capability 且交集为空」——
 * 拿不到 capability（接口挂了）时不拦，那是我们的问题，不该变成他不能下单。
 */
const noPayMethod = computed(
  () => !!capability.value
    // null = 未配置（进件还没走完），不是「一种都不支持」—— 混为一谈会把正常订单拦死
    && capability.value.usablePayMethods !== null
    && capability.value.usablePayMethods.length === 0,
);

/**
 * 支付方式：线上 / 当面付。
 *
 * ⚠️ **与 usablePayMethods 是两根轴**：那个是通道（微信/支付宝），
 * 这个是「线上付还是当面付」。一笔订单要同时确定两者。
 *
 * `ONLINE` 永远在后端给的集合里（四层判定的约定），所以这里不需要「没配过」那一档。
 * 拿不到 capability 时按只支持线上处理 —— 那是最保守的一档：
 * 用户照常线上付，而不是看到一个可能下不了单的选项。
 */
const payModes = computed<string[]>(
  () => capability.value?.usablePayModes ?? [PAY_MODE.ONLINE],
);
const canPayOffline = computed(() => payModes.value.includes(PAY_MODE.OFFLINE));
const payMode = ref<string>(PAY_MODE.ONLINE);
/*
 * 后端不再给线下时**当场退回线上**。
 * 不退的话，用户先选了当面付、再把履约改成快递，选项已经消失而 payMode 还是 OFFLINE ——
 * 下单被 80011 拒，而屏幕上看不出他选了什么。
 */
watch(canPayOffline, (ok) => {
  if (!ok) payMode.value = PAY_MODE.ONLINE;
});

/**
 * 平台券在当面付下用不了。
 *
 * **不是「不想给」，是没有资金流可补**：平台券的钱最终要由平台补给商家，
 * 而当面付这笔钱从没进过平台。硬发就是白送且无处对账。
 * 商家券不受影响 —— 那是商家自己少收，与平台无关。
 */
const platformCouponBlocked = computed(
  () => payMode.value === PAY_MODE.OFFLINE && coupon.value?.funder === "PLATFORM",
);
watch(platformCouponBlocked, (blocked) => {
  // 已经选了平台券又改成当面付 → 把券摘掉，别让他带着一张用不了的券去下单
  if (blocked) couponNo.value = "";
});

/**
 * 积分为什么不可用。**判据来自后端**（`deductible.disabledReason`），端上不再造一套 ——
 * 四级开关、端策略、线下开关加起来有五六条，任何一条在端上重写一遍都会走岔，
 * 而走岔的表现是「结算页说能抵、下单没抵」。
 *
 * 空 = 可用。**不可用时也要显示余额**，见模板里那段注释。
 */
const pointsDeductible = ref<PointsDeductible | null>(null);
const pointsBlockedReason = computed(() => pointsDeductible.value?.disabledReason || "");

/** 额度已用尽或本单会超的商家：这家的货现在下不了单。 */
const quotaBlocked = computed(
  () => capability.value?.merchants.filter((m) => m.quotaExhausted || m.quotaWouldExceed) ?? [],
);

const localEstimate = computed(() => {
  const first = items.value[0];
  if (!first) return null;
  return pricingFor(first.type).estimate(orderItems.value, {
    fulfillment: fulfillment.value,
    currency: currentCurrency(),
    coupon: coupon.value,
    usePoints: FEATURES.points && usePoints.value ? pointBalance.value : 0,
    earnPoints: FEATURES.points ? earnPointsFor(orderItems.value) : 0,
  });
});

const amount = computed(() => serverAmount.value ?? localEstimate.value);

/**
 * 影响金额的任何一项变了就重新问后端 —— 少问一次就会显示上一次的价。
 *
 * ⚠️ **每次发请求领一个号，回来时对不上号就整份丢掉。**
 * 没有这道闸的时候，改地址、换券连点两下，先发的那个响应后到就会盖住后发的 ——
 * 屏幕上是**上一次的价**，而页面看起来完全正常（金额有、没报错、也不转圈）。
 * 这种错只在网络慢的那一台手机上出现，本机永远复现不了。
 */
/**
 * 把预览回来的子单按**取货点**归并。
 *
 * 两家配到同一个点时合并成一组 —— 按商家分组会让买家以为要跑两趟，
 * 而他只需要去一个地方。没配到点的那几家单列，付款前就标出来。
 */
function applyPickupGroups(subs: Array<{
  merchantName?: string;
  pickupNo?: string;
  pickupName?: string;
  pickupDistanceM?: number | null;
}>) {
  if (!needPickup.value) {
    pickupGroups.value = [];
    pickupMissing.value = [];
    return;
  }
  const byPoint = new Map<string, {
    pickupNo: string;
    pickupName: string;
    merchants: string[];
    distanceM?: number | null;
  }>();
  const missing: string[] = [];
  for (const sub of subs) {
    const name = sub.merchantName ?? "";
    if (!sub.pickupNo) {
      missing.push(name);
      continue;
    }
    const g = byPoint.get(sub.pickupNo)
      ?? {
        pickupNo: sub.pickupNo,
        pickupName: sub.pickupName ?? sub.pickupNo,
        merchants: [],
        distanceM: sub.pickupDistanceM,
      };
    g.merchants.push(name);
    byPoint.set(sub.pickupNo, g);
  }
  pickupGroups.value = [...byPoint.values()];
  pickupMissing.value = missing;
}

let amountSeq = 0;
async function refreshAmount() {
  if (!items.value.length) {
    serverAmount.value = null;
    amountPending.value = false;
    return;
  }
  const seq = ++amountSeq;
  amountPending.value = true;
  try {
    const p = await api.orderPreview({
      items: items.value.map((it) => ({ goodsNo: it.goodsNo, skuNo: it.skuNo, qty: it.qty })),
      fulfillment: fulfillment.value,
      // **不传 pickupNo**：点由后端配。传一个端上挑的，等于让数组顺序决定佣金归谁
      addressId: needAddress.value ? addressId.value : undefined,
      couponNo: couponNo.value || undefined,
      payMode: payMode.value,
      usePoints: FEATURES.points && usePoints.value ? pointBalance.value : 0,
      appointmentAt: appointmentAt.value,
      groupNo: groupNo.value || undefined,
      openGroup: openGroup.value || undefined,
    });
    if (seq !== amountSeq) return;
    serverAmount.value = p.amount;
    amountStale.value = false;
    applyPickupGroups(p.subOrders ?? []);
  } catch {
    if (seq !== amountSeq) return;
    // 预览失败不挡下单：兜底显示本地估算，真实金额在提交时由后端定 —— 但要说出来
    serverAmount.value = null;
    amountStale.value = true;
  } finally {
    if (seq === amountSeq) amountPending.value = false;
  }
}

/**
 * 能力提示与金额分开问：金额随优惠、地址、履约方式变，能力只随**车里有谁**变。
 * 合成一个请求的话，改一次地址就会把三次能力查询也重跑一遍。
 */
let capabilitySeq = 0;
async function refreshCapability() {
  if (!items.value.length) {
    capability.value = null;
    return;
  }
  const seq = ++capabilitySeq;
  try {
    const cap = await api.orderCapability({
      items: items.value.map((it) => ({ goodsNo: it.goodsNo, skuNo: it.skuNo, qty: it.qty })),
      fulfillment: fulfillment.value,
      // 不传 pickupNo：由后端按地址逐个商家配（与 preview 同一套规则）
    });
    // 同 refreshAmount：过期响应整份丢掉，否则「改成快递」之后
    // 迟到的自提能力会把当面付那个选项又放回屏幕上
    if (seq !== capabilitySeq) return;
    capability.value = cap;
  } catch {
    if (seq !== capabilitySeq) return;
    // 拿不到就不提示，但**不拦下单**：接口挂了是我们的问题，不该变成他买不了
    capability.value = null;
  }
}

/**
 * 积分能不能抵、抵多少、不能抵是为什么 —— **一次问清，判据全在后端**。
 *
 * 与能力提示分开问：这个随支付方式与金额变（线下是否可抵是平台开关，
 * 上限按券后金额算），而能力只随车里有谁变。
 */
let pointsSeq = 0;
async function refreshPoints() {
  const merchantNo = items.value[0]?.merchantNo;
  if (!FEATURES.points || !merchantNo) {
    pointsDeductible.value = null;
    return;
  }
  const seq = ++pointsSeq;
  try {
    const d = await api.pointsDeductible({
      merchantNo,
      payableMinor: goodsMinor.value - (coupon.value ? couponDiscount(coupon.value, goodsMinor.value) : 0),
      payMode: payMode.value,
    });
    if (seq !== pointsSeq) return;
    pointsDeductible.value = d;
  } catch {
    if (seq !== pointsSeq) return;
    // 同上：问不到就不显示原因，但不拦 —— 后端下单时还会再判一次
    pointsDeductible.value = null;
  }
}

watch(
  () => [items.value.length, fulfillment.value, couponNo.value, usePoints.value, addressId.value,
    appointmentAt.value, payMode.value],
  () => void refreshAmount(),
  { immediate: true },
);

/*
 * 跟车里的商品**与履约方式**变。
 *
 * 原先只跟商品 —— 那时能力提示只有开票与额度，改地址换券确实不影响。
 * 现在多了 `usablePayModes`，而它跟履约方式走（快递没有当面收款的那一刻，
 * 自提点自提也不行 —— 自提点不是卖家）。漏掉这个依赖的表现是：
 * 用户从自提改成快递，「当面付」那个选项还留在屏幕上，点下去被 80011 拒。
 * 换券与改地址仍然不影响，所以它们不在这里。
 */
watch(
  () => [items.value.map((it) => it.skuNo).join(","), fulfillment.value],
  () => void refreshCapability(),
  { immediate: true },
);

// 积分试算跟支付方式与券走 —— 线下能否抵是平台开关，上限按券后金额算
watch(
  () => [items.value.map((it) => it.skuNo).join(","), payMode.value, couponNo.value],
  () => void refreshPoints(),
  { immediate: true },
);

/**
 * 提交不了是**为什么**。空串 = 提交得了。
 *
 * ⚠️ **这一条与 `canSubmit` 必须是同一份判据**，不是两套并排的 if ——
 * 灰按钮此前有五种原因而屏幕上一个字都没有：他看得见按钮点不动，
 * 却不知道该改哪儿。分成两份写的话，第六个条件加进 `canSubmit` 时
 * 这里不会跟着变，于是又回到「灰着，不说话」。
 *
 * 顺序按「他能立刻动手改的」在前：地址、时段是他自己能补的；
 * 支付方式与额度得换商品或者等商家。
 */
const submitBlockedReason = computed(() => {
  if (!items.value.length) return String(t("confirm.emptyItems"));
  if (needAddress.value && !address.value) return String(t("confirm.whyNoAddress"));
  if (needAppointment.value && !appointmentAt.value) return String(t("confirm.whyNoSlot"));
  if (outOfRange.value.length) return String(t("confirm.whyOutOfRange"));
  if (noPayMethod.value) return String(t("confirm.whyNoPayMethod"));
  if (quotaBlocked.value.length) return String(t("confirm.whyQuota"));
  return "";
});

const canSubmit = computed(() => !submitBlockedReason.value && !submitting.value);

/** 地址簿这次没取到。**与「一条地址都没存过」是两件事** */
const addressFailed = ref(false);
/** 券没取到。**与「无可用券」是两件事** —— 后者会让顾客直接付全价 */
const couponFailed = ref(false);

async function loadAddresses() {
  // 拉挂了 `addresses` 停在空，下面那个 `v-else` 就渲染成「选择收货地址 · 添加」——
  // 顾客会去新建一条，进去发现自己明明存过几条
  try {
    addresses.value = await api.addressList();
    addressFailed.value = false;
  } catch {
    addressFailed.value = true;
    return;
  }
  if (!addressId.value) {
    addressId.value =
      addresses.value.find((a) => a.isDefault)?.addressId ??
      addresses.value[0]?.addressId ??
      "";
  }
}

/** 「09-30」。券行与选择弹层共用 —— 两处写法不一样会让人以为是两个日期 */
function mmdd(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

async function pickCoupon() {
  // 券没取到时这一行显示「没能加载出来」，点它就是重试 ——
  // 否则那句话说了等于没说：顾客知道出事了，却没有能做的事
  if (couponFailed.value) {
    try {
      coupons.value = await api.myCoupons();
      couponFailed.value = false;
    } catch {
      couponFailed.value = true;
    }
    return;
  }
  if (!usableCoupons.value.length) return;
  /*
   * 每一行要说清**这张券是什么**：名字、抵多少、门槛、到什么时候。
   * 只给「名字 -金额」的话，用户看不出为什么这张能用那张不能，也不知道快过期了。
   */
  const names = [
    String(t("confirm.noCoupon")),
    ...usableCoupons.value.map((c) => {
      const parts = [`${c.title} -${money(couponDiscount(c, goodsMinor.value))}`];
      if (c.thresholdMinor) parts.push(String(t("coupon.threshold", { p: money(c.thresholdMinor) })));
      parts.push(String(t("coupon.until", { d: mmdd(c.endAt) })));
      return parts.join(" · ");
    }),
  ];
  const idx = await pick({
    title: String(t("confirm.pickCoupon")),
    items: names,
    selected: couponNo.value
      ? usableCoupons.value.findIndex((c) => c.couponNo === couponNo.value) + 1 : 0,
  });
  if (idx === null) return;
  couponNo.value = idx === 0 ? "" : usableCoupons.value[idx - 1]!.couponNo;
}

function backToCart() {
  uni.switchTab({ url: ROUTES.cart });
}

function gotoAddress() {
  uni.navigateTo({ url: `${ROUTES.address}?picking=1` });
}

async function submit() {
  if (!canSubmit.value) return;
  /*
   * **自提单必须先有归属。**
   *
   * 附近没开通的用户现在不再被强制推去选社区页（他会留在首页先逛），
   * 于是「没绑归属」会一路走到这里。不拦的话后端回 70025「请先选择自提点」——
   * 一句正确但没有出口的话：他站在结算页，不知道去哪儿选。
   *
   * 这里把他直接送到选社区页，那页有「查看全部已开通社区」的出路。
   */
  /*
   * **不再拦「你还没选自提点」** —— 买家已经不选点了（点由后端按地址匹配）。
   * 真正会挡住他的是「这一带配不出点」，那时后端点名是哪一家，
   * 而这里拦的话只会把他送去一个已经没有选择功能的页面。
   */
  /*
   * **顶栏跟定位，那这里就必须有一道闸**（M9）。
   *
   * 顶栏回答的是「我在看哪一带的货」，而快递能送到任何地方 —— 所以浏览按位置走、
   * 收货按地址走，两件事各管各的，没有冲突。
   *
   * <b>但自提与社区配送仍然与聚落绑死</b>：那两种履约方式下，
   * 「在这儿看得到」不等于「能送到你那条地址」。人在公司逛、货寄回家，
   * 是这个品类最常见的用法 —— 不拦的话他会下一单送不到的，
   * 而界面上一点痕迹都没有（顶栏写的是公司，地址写的是家，两行都对）。
   *
   * 拦但不禁止：说清楚再让他自己定。他可能就是要寄到公司。
   */
  if (!(await confirmPlaceMismatch())) return;
  if (needPickup.value && pickupMissing.value.length) {
    uni.showToast({
      title: String(t("confirm.pickupNoneFor", { names: pickupMissing.value.join("、") })),
      icon: "none",
      duration: 3000,
    });
    return;
  }
  /*
   * **没有手机号就先要一个。**
   *
   * 静默登录建出来的账号没有手机号，而这一单之后要联系他：
   * 自提点到货发通知、配送打电话。等到那时候才发现没号，货已经在路上了。
   *
   * 弹在这一刻而不是启动时：现在他手里有一车东西、正要付钱，
   * 「为什么要我的号」不用解释 —— 而启动时问是最典型的劝退。
   */
  if (!user.user?.phone) {
    phoneGate.value = true;
    return;
  }
  submitting.value = true;
  try {
    const body = {
      items: items.value.map((it) => ({
        goodsNo: it.goodsNo,
        skuNo: it.skuNo,
        qty: it.qty,
      })),
      fulfillment: fulfillment.value,
      // 同上：点由后端配
      addressId: needAddress.value ? addressId.value : undefined,
      couponNo: couponNo.value || undefined,
      payMode: payMode.value,
      usePoints: FEATURES.points && usePoints.value ? pointBalance.value : 0,
      remark: remark.value || undefined,
      appointmentAt: appointmentAt.value,
      groupNo: groupNo.value || undefined,
      openGroup: openGroup.value || undefined,
    };
    /*
     * **幂等键按「这一单的内容」认，不按「这一次点击」认。**
     *
     * 此前这里每次 submit() 现生成一个随机键 —— 注释写着「重复点击提交的是同一个 key」，
     * 实际只在同一次调用里成立。于是绑完手机号自动提交一单、他退回来再点一次，
     * 就是两张一模一样的待付款单（2026-09-18 真机，13 秒内 SO…001389 / SO…005683）。
     * 内容相同 → 同一个键 → 后端回放同一单；内容一变就是新单。寿命与清理见 checkout-key.ts。
     */
    const fingerprint = JSON.stringify(body);
    const order = await api.createOrder({ ...body, idempotencyKey: checkoutKey(fingerprint) });
    checkoutKeyBoundTo(fingerprint, order.payDeadlineAt);
    await cart.load();
    uni.redirectTo({ url: `${ROUTES.pay}?orderNo=${order.orderNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}

/**
 * 拼团：从团页来的带团号（参团），从商品页「开团」来的带 openGroup。
 * 只是把意图带给后端 —— 团价、团还能不能参、开团建团，全在后端判；预览就按团价算。
 */
const groupNo = ref("");
const openGroup = ref(false);
const grouped = computed(() => !!groupNo.value || openGroup.value);

onLoad((q) => {
  groupNo.value = (q?.groupNo as string) || "";
  openGroup.value = q?.openGroup === "1";
  fulfillment.value = (q?.fulfillment as FulfillmentType) || FULFILLMENT.PICKUP;
  appointmentAt.value = q?.appointmentAt ? Number(q.appointmentAt) : undefined;

  // 来源两种：购物车按履约方式分组结算 / 详情页立即购买（skus 参数指定）
  const only = (q?.skus as string) || "";
  const wanted = only ? new Set(only.split(",")) : null;
  items.value = cart.validItems.filter(
    (it) => it.fulfillment === fulfillment.value && (!wanted || wanted.has(it.skuNo)),
  );
});

/**
 * 回到这一页时**重取地址簿并接住选中的那条**。两件事缺一不可：
 *
 * <ul>
 *   <li><b>重取</b> —— 用户可能在地址簿里新增了一条，不重取这里根本没有它；
 *   <li><b>接住</b> —— `addressId` 首次进页就有值了（默认地址），
 *       `loadAddresses` 里那条 `if (!addressId.value)` 回落轮不到，
 *       不显式改就等于他刚才白选了一次。
 * </ul>
 *
 * <p>此前这一页只有 `onMounted`，两件事都没发生：选完返回，页面显示的还是原来那条 ——
 * 而地址簿那边**已经把他的默认地址改掉了**。两个缺陷叠在一起，看起来像
 * 「生效了，只是慢了一步」（下一次进结算页确实会显示新的那条），所以一直没人报。
 *
 * <p>`onShow` 首次进页也会触发，所以地址簿的加载**只留在这里一处**、
 * 从 `onMounted` 里撤掉，进页时不会请求两次。同一种写法在 `pages/order` 已经用着
 * （那一页也是「从别处返回时状态会变，必须重新拉」）。
 */
onShow(async () => {
  const picked = pickedAddress.take();
  await loadAddresses();
  // 放在 load 之后：新增的那条要先进 addresses，`address` 这个 computed 才找得到它
  if (picked) addressId.value = picked;
});

onMounted(async () => {
  /*
   * `allSettled` 而不是 `all`：此前任一挂掉两条都静默没了 ——
   * 券那一行显示「无可用券」，而顾客明明有券；积分抵扣那一行直接不出现。
   * 两件事互不相干，一条挂了不该带走另一条。
   */
  /*
   * **券走「我的券」，不走领券中心**（TDD-C端我的券接真接口）。
   * 领券中心回答的是「现在能领哪些」—— 活动下架 / 抢光 / 过了可领期之后，
   * 用户手里那张就从返回里消失，结算页于是说「无可用券」，而他明明有。
   */
  const [c, a] = await Promise.allSettled([
    api.myCoupons(),
    FEATURES.points ? api.pointAccount() : Promise.resolve(null),
  ]);
  if (c.status === "fulfilled") coupons.value = c.value;
  couponFailed.value = c.status === "rejected";
  if (a.status === "fulfilled" && a.value) pointBalance.value = a.value.balance;
});
</script>

<template>
  <sh-scaffold title-key="confirm.title">
    <!--
      **这一单里一件商品都没有。**
      来源两种：从购物车带过来的 sku 已经被别处删掉/下架了，或者页面被直接打开。
      此前这里是一整页空白 + 一个点不动的灰按钮 —— 看起来像页面没加载出来。
    -->
    <sh-empty v-if="!items.length" :text="String($t('confirm.emptyItems'))">
      <template #action>
        <view class="sh-btn sh-btn--sm" @tap="backToCart">{{ $t("confirm.backToCart") }}</view>
      </template>
    </sh-empty>

    <template v-else>
    <!-- 拼团单：按成团价结算；没凑齐自动全额退款 —— 付款前说清楚 -->
    <view v-if="grouped" class="txt-sub sh-notice cap">
      <text>{{ openGroup ? $t("confirm.groupOpenNote") : $t("confirm.groupJoinNote") }}</text>
    </view>
    <!--
      能力提示：**必须在付款前**说。
      三条的共同后果都是付款那一刻才炸 —— 而那时候平台既解释不清也补救不了。
      放在页首而不是靠近提交按钮：买家的注意力在这一页从上往下走，
      放在底部他会先看完金额再看到「其实付不了」。
    -->
    <view v-if="noPayMethod" class="txt-sub sh-notice sh-notice--danger cap">
      <text>{{ $t("confirm.capNoPayMethod") }}</text>
    </view>
    <view v-if="quotaBlocked.length" class="txt-sub sh-notice sh-notice--danger cap">
      <text>{{ $t("confirm.capQuotaBlocked", { names: quotaBlocked.map((m) => m.merchantName).join("、") }) }}</text>
    </view>
    <view v-if="noInvoiceMerchants.length" class="txt-sub sh-notice sh-notice--warning cap">
      <text>{{ $t("confirm.capNoInvoice", { names: noInvoiceMerchants.map((m) => m.merchantName).join("、") }) }}</text>
    </view>

    <!-- 收货信息：按履约方式变形 -->
    <view class="sh-card">
      <text class="sh-chip sh-chip--primary">{{ $t(`fulfillment.${fulfillment}`) }}</text>

      <!--
        自提：**按取货点分组，且在付款前**。
        两家配到同一个点要合并成一组 —— 按商家分会让人以为要跑两趟，
        而他只需要去一个地方。属于多个点时说清是几个。
      -->
      <view v-if="needPickup" class="recv">
        <text v-if="pickupGroups.length > 1" class="txt-caption recv__multi">
          {{ $t("confirm.pickupGroups", { n: pickupGroups.length }) }}
        </text>
        <view v-for="g in pickupGroups" :key="g.pickupNo" class="recv__group">
          <view class="sh-row sh-row--between">
            <text class="txt-strong">{{ g.pickupName }}</text>
            <!--
              **点是后端按地址配的，买家没得挑** —— 不说距离的话，
              他要到取货那天才知道有多远。没坐标（-1）就不显示，别编一个数。
            -->
            <text v-if="distanceOf(g.distanceM)" class="txt-caption">{{ distanceOf(g.distanceM) }}</text>
          </view>
          <text class="txt-caption recv__sub">{{ g.merchants.join("、") }}</text>
          <!-- 远只是提醒：顺路取两公里外的点很常见，拦下来等于替他做决定 -->
          <text v-if="isFar(g.distanceM)" class="txt-caption recv__warn">
            {{ $t("confirm.pickupFar", { d: distanceOf(g.distanceM) }) }}
          </text>
        </view>
        <!-- 配不出点的那几家：付款前就说，别等他付完钱 -->
        <text v-if="pickupMissing.length" class="txt-caption recv__warn">
          {{ $t("confirm.pickupNoneFor", { names: pickupMissing.join("、") }) }}
        </text>
        <!-- 还没算出来（首屏/改地址中）：别显示一个空块让人以为坏了 -->
        <text v-if="!pickupGroups.length && !pickupMissing.length" class="txt-caption recv__sub">
          {{ $t("confirm.pickupMatching") }}
        </text>
      </view>

      <!-- 送货上门 / 快递 -->
      <view v-else-if="needAddress" class="recv" @tap="gotoAddress">
        <!--
          **与收货地址页是同一件**（bare = 不要卡片外壳，它在这张卡里面）。
          此前两处各画一遍：那边是「列表行 + 五个动作」，这边是「姓名电话一行 +
          地址一行」—— 同一条地址在两屏上的样子不一样，用户要重新认一遍
          哪个是姓名、哪个是门牌。
        -->
        <template v-if="address">
          <biz-address-card
            :address="address"
            bare
            :more="String($t('confirm.change'))"
            @tap="gotoAddress"
            @fix="gotoAddress"
          ></biz-address-card>
          <!--
            送不到要**点名是哪一家**。只说「超出配送范围」的话，
            车里有三家店时他不知道该换地址还是该把某一家的货拿出来。
          -->
          <text v-if="outOfRange.length" class="txt-caption recv__warn">
            {{ $t("confirm.outOfRange", { names: outOfRange.map((m) => m.merchantName).join("、") }) }}
          </text>
        </template>
        <!-- 没取到与「一条地址都没存过」是两件事：后者该去新建，前者该重试 -->
        <sh-empty v-else-if="addressFailed" line failed @retry="loadAddresses"></sh-empty>
        <view v-else class="recv__empty sh-row">
          <text class="txt-body recv__empty-text sh-row">{{ $t("confirm.pickAddress") }}</text>
          <text class="txt-caption recv__more">{{ $t("confirm.add") }}</text>
        </view>
        <!--
          **「当前位置」是上下文，不是收货地址** —— 要送到这儿就得留下姓名电话门牌，
          那是一条资料。所以这里只在**这一单真的要送**、而他又没有可用地址时问一次，
          点进去是带着坐标预填的新建地址页，不是替他偷偷存一条。
          （自提 / 到店核销那几条线走不到这个分支：不留地址也照样下单。）
        -->
        <text v-if="location.isTransient" class="txt-caption recv__here" @tap.stop="saveHereAsAddress">
          {{ $t("confirm.saveHere", { s: location.label }) }}
        </text>
      </view>

      <!-- 到店核销 / 即时发放 -->
      <view v-else class="recv">
        <text class="txt-strong">{{ $t(`fulfillmentDesc.${fulfillment}`) }}</text>
      </view>
    </view>

    <!--
      预约时段。**与地址并列而不是塞进地址块** —— 它们回答两个不同的问题：
      「去哪」和「几点」，缺任何一个这单都履约不了。
    -->
    <view v-if="needAppointment" class="sh-card block recv" @tap="pickSlot">
      <view class="sh-row sh-row--baseline">
        <text class="txt-strong">{{ $t("confirm.appointmentSlot") }}</text>
        <text class="txt-caption recv__more">{{ appointmentAt ? $t("confirm.change") : $t("confirm.pick") }}</text>
      </view>
      <text v-if="appointmentAt" class="txt-caption recv__sub sh-num">
        {{ $t("confirm.appointmentAt", { t: datetime(appointmentAt) }) }}
      </text>
      <text v-else class="txt-body recv__empty-text sh-row">{{ $t("confirm.pickSlotHint") }}</text>
    </view>

    <!-- 商品 -->
    <view class="sh-card block">
      <!--
        **这一页才是拆单真正发生的地方**：提交后按商家生成 N 笔 ord_sub_order。
        此前这里只列一份平铺清单，用户看到「一单」、拿到两单。
      -->
      <template v-for="m in merchantSegments" :key="m.merchantNo">
        <view v-if="merchantSegments.length > 1" class="seg">
          <text class="txt-strong">{{ m.merchantName || $t("cart.unknownMerchant") }}</text>
        </view>

        <biz-sku-row
          v-for="it in m.items"
          :key="it.skuNo"
          :cover="it.cover"
          :title="it.title"
          :spec="it.spec"
          size="lg"
        >
          <view class="row__foot sh-row sh-row--between">
            <text class="txt-price sh-num">{{ money(it.price) }}</text>
            <text class="txt-caption sh-num">×{{ it.qty }}</text>
          </view>
        </biz-sku-row>
      </template>

      <text v-if="merchantSegments.length > 1" class="txt-caption splitnote">
        {{ $t("confirm.splitNote", { n: merchantSegments.length }) }}
      </text>

      <!-- 赠品：单独列出来，让用户在付款前就看见 -->
      <view v-for="g in gifts" :key="`gift-${g.skuNo}`" class="sh-notice sh-notice--danger giftrow sh-row">
        <text class="txt-caption giftrow__tag">{{ $t("promo.gift") }}</text>
        <text class="txt-caption giftrow__text sh-num">
          {{ $t("promo.giftItem", { title: g.title, n: g.giftQty }) }}
        </text>
      </view>
    </view>

    <!--
      支付方式。**只在真的有得选时才画** —— 只支持线上时多一行「在线支付」
      是纯噪声，而结算页每多一行就少一分「一眼看清要付多少」。
    -->
    <view v-if="canPayOffline" class="sh-card block">
      <text class="block__t">{{ $t("confirm.payMode") }}</text>
      <view class="modes">
        <view
          v-for="m in payModes"
          :key="m"
          class="mode"
          :class="{ 'is-on': payMode === m }"
          @tap="payMode = m"
        >
          <text class="txt-body mode__t">{{ $t(`payMode.${m}`) }}</text>
          <text class="txt-caption mode__d">{{ $t(`payModeDesc.${m}`) }}</text>
        </view>
      </view>
      <!--
        当面付的两句话都不能省：
          · 平台不代收 —— 出纠纷时双方对这一点没有分歧
          · 平台券用不了 —— 而且要说**为什么**，否则看着像故障
      -->
      <text v-if="payMode === PAY_MODE.OFFLINE" class="txt-caption mode__note">
        {{ $t("confirm.offlineNoPlatformCoupon") }}
      </text>
    </view>

    <!-- 券 + 备注 -->
    <view class="sh-card block">
      <!--
        **没券可选时压暗**：`pickCoupon` 在无可用券时第一行就 return，
        而这一行原先看上去和能点的时候一模一样 —— 用户点下去毫无反应，
        分不清是「没券」还是「坏了」。线上券表此刻是空的，所以这是常态不是边角。
        （取不到券时不压暗：那时点它就是重试，是有动作的。）
      -->
      <view
        class="cell sh-row sh-row--between"
        :class="{ 'is-disabled': !couponFailed && !usableCoupons.length }"
        @tap="pickCoupon"
      >
        <text class="txt-sub cell__k">{{ $t("confirm.coupon") }}</text>
        <text class="txt-bold txt-sub cell__v" :class="{ 'is-danger': !!coupon }">
          {{ coupon
            ? `${coupon.title} -${money(couponDiscount(coupon, goodsMinor))}`
            : couponFailed
              ? $t("common.loadFailed")
              : usableCoupons.length
                ? $t("confirm.couponAvailable", { n: usableCoupons.length })
                : $t("confirm.noCouponAvailable") }}
        </text>
      </view>
      <!-- 积分抵扣：上限是「券后金额」的固定比例，说清楚为什么抵不满 -->
      <view
        v-if="FEATURES.points && pointBalance > 0"
        class="cell sh-row sh-row--between"
        @tap="pointsBlockedReason ? undefined : (usePoints = !usePoints)"
      >
        <text class="txt-sub cell__k">{{ $t("confirm.points") }}</text>
        <view class="pointsline sh-row">
          <!--
            ⚠️ **不可用时也要显示余额，并说明原因** —— 不能静默把入口藏起来。
            用户知道自己有 500 分，界面上却没有抵扣入口，他会当成 bug 来投诉，
            而客服看不出是哪一条策略关掉的。
          -->
          <text v-if="pointsBlockedReason" class="txt-bold txt-caption cell__v">
            {{ $t("confirm.pointsHave", { n: pointBalance }) }}　{{ pointsBlockedReason }}
          </text>
          <text v-else class="txt-bold txt-caption cell__v" :class="{ 'is-danger': usePoints && !!amount?.pointsUsed }">
            {{ usePoints && amount?.pointsUsed
              ? $t("confirm.pointsUsed", { n: amount.pointsUsed, p: money(amount.pointsDeductMinor) })
              : $t("confirm.pointsHave", { n: pointBalance }) }}
          </text>
          <sh-switch v-if="!pointsBlockedReason" :model-value="usePoints"></sh-switch>
        </view>
      </view>

      <view class="cell sh-row sh-row--between">
        <text class="txt-sub cell__k">{{ $t("confirm.remark") }}</text>
        <input maxlength="255" v-model="remark" class="txt-sub cell__input" :placeholder="$t('confirm.remarkPh')" />
      </view>
    </view>

    <!-- 金额明细 -->
    <view v-if="amount" class="sh-card block">
      <view class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("confirm.goods") }}</text>
        <text class="txt-caption amt__v sh-num txt-ink">{{ money(amount.goodsMinor) }}</text>
      </view>
      <view class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("confirm.freight") }}</text>
        <text class="txt-caption amt__v sh-num txt-ink">
          {{ amount.freightMinor ? money(amount.freightMinor) : $t("confirm.free") }}
        </text>
      </view>
      <view v-if="amount.discountMinor" class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("confirm.discount") }}</text>
        <text class="txt-caption amt__v sh-num is-danger">-{{ money(amount.discountMinor) }}</text>
      </view>
      <view v-if="amount.pointsDeductMinor" class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption sh-num">{{ $t("confirm.pointsDeduct", { n: amount.pointsUsed }) }}</text>
        <text class="txt-caption amt__v sh-num is-danger">-{{ money(amount.pointsDeductMinor) }}</text>
      </view>
      <view v-if="amount.pointsEarn" class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("confirm.pointsEarn") }}</text>
        <text class="txt-caption amt__v amt__v--earn sh-num txt-ink txt-primary">+{{ amount.pointsEarn }}</text>
      </view>
      <!--
        服务端试算失败，上面这些数是**端上估算的**。
        端上不知道服务端有哪些活动，估出来的通常偏高一点点 ——
        不说这句的话，两个数长得一模一样，他会以为提交后被多扣了钱。
      -->
      <text v-if="amountStale" class="txt-caption amt__stale">{{ $t("confirm.estimateOnly") }}</text>
    </view>

    <!--
      **提交不了，要说是为什么。** 五个否决条件此前共用同一个灰按钮，
      屏幕上一个字都没有 —— 他看得见按钮点不动，却不知道该改哪儿。
      贴着提交条放：他往下滚就是为了按那个按钮，话要落在他视线的终点。
    -->
    <view v-if="submitBlockedReason" class="txt-caption sh-notice sh-notice--warning why">
      <text>{{ submitBlockedReason }}</text>
    </view>

    <sh-actionbar pill="lead" :pad="200">
      <view class="sh-fill">
        <text class="sh-muted">{{ amountPending ? $t("confirm.calculating") : $t("confirm.payable") }}</text>
        <text
          class="txt-price actionbar__total sh-num"
          :class="{ 'is-pending': amountPending }"
        >{{ money(amount?.payableMinor ?? 0) }}</text>
      </view>
      <view
        class="txt-body sh-btn actionbar__btn"
        :class="{ 'is-disabled': !canSubmit }"
        @tap="submit"
      >
        {{ submitting ? $t("confirm.submitting") : $t("confirm.submit") }}
      </view>
    </sh-actionbar>
    </template>

    <!--
      **必须留在 sh-scaffold 里面。** 这套 `--sh-*` 变量声明在 `:root, .sh-root` 上，
      而**小程序里没有 `:root`** —— 根节点叫 `page`，那条选择器一个节点都不匹配，
      全靠 scaffold 根节点上的 `.sh-root`。挂到 scaffold 外面就一个变量都继承不到：
      遮罩和卡片背景 `var(--sh-scrim)` / `var(--sh-surface)` 双双落空变透明，
      弹层文字直接浮在商品列表上，**看起来像页面串了行，而不像弹窗坏了**。
      H5 上不会露：浏览器里 `:root` 是匹配的。见 shared/tests/scaffold-scope.test.ts
    -->
    <!--
      绑完手机号**自动继续提交**，不让他再点一次「提交订单」——
      多那一次点击，人会以为刚才那下没生效。
    -->
    <phone-gate
      :visible="phoneGate"
      @done="((phoneGate = false), submit())"
      @close="phoneGate = false"
    />
  </sh-scaffold>
</template>

<style scoped>
.modes {
  display: flex;
  gap: 16rpx;
  margin-top: 16rpx;
}
.mode {
  flex: 1;
  padding: 20rpx;
  border-radius: 16rpx;
  border: 2rpx solid var(--sh-line);
  background: var(--sh-bg);
}
.mode.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-faint);
}
.mode__t {
  display: block;
}
.mode__d {
  display: block;
  margin-top: 8rpx;
}
.mode__note {
  display: block;
  margin-top: 16rpx;
}
.recv {
  margin-top: 24rpx;
}

/* 「把当前位置存成地址」：低调一行，它是提议不是待办 —— 不留地址也照样能自提下单 */
.recv__here {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-primary-text);
}
.recv__more {
  margin-inline-start: auto;
  color: var(--sh-primary-text);
}
.recv__sub {
  display: block;
  margin-top: 8rpx;
}
.recv__warn {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-danger);
}
.recv__empty-text {
  flex: 1;
  color: var(--sh-sub);
}
.seg {
  display: flex;
  align-items: center;
  margin: 24rpx 0 8rpx;
}

.splitnote {
  display: block;
  margin-top: 16rpx;
}
.row__foot {
  margin-top: 16rpx;
}

.giftrow {
  gap: 12rpx;
  margin-top: 16rpx;
}
.giftrow__tag {
  color: var(--sh-danger);
  flex-shrink: 0;
}
.giftrow__text {
  color: var(--sh-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell {
  gap: 24rpx;
  padding: 20rpx 0;
}
.cell__k {
  color: var(--sh-ink);
  flex-shrink: 0;
}
.cell__v {
  text-align: end;
}
.cell__input {
  flex: 1;
  color: var(--sh-ink);
  text-align: end;
}
.amt {
  padding: 12rpx 0;
}


.actionbar__total {
  display: block;
}
.actionbar__btn {
  flex: 0 0 auto;
  padding-inline: 52rpx;
}


/* 提交不了的原因。用 warning 不用 danger：**它不是故障，是还差一步** */
.why {
  margin: 0 24rpx;
}

/* 试算在途：金额压暗而不是换成骨架 —— 上一次的数仍然是最好的猜测 */
.actionbar__total.is-pending {
  opacity: 0.45;
}
.amt__stale {
  display: block;
  padding-top: 12rpx;
  color: var(--sh-warning);
}

/* 能力提示：拦下的用醒目色，只是提醒的用弱一档 —— 两者的用户动作不同 */
/* 形态归 .sh-notice（拦下的用 danger、只是提醒的用 warning —— 两者要求的动作不同）；
   这里只留它相对页面边距的位置 */
.cap {
  margin: 16rpx 24rpx;
}
</style>
