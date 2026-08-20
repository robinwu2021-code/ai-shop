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
import { onLoad } from "@dcloudio/uni-app";
import { api, idempotencyKey } from "@/api";
import { segmentByMerchant, useCartStore } from "@/stores/cart";
import { useCommunityStore } from "@/stores/community";
import { FEATURES, FULFILLMENT, POINTS, ROUTES, TRADE_RULES } from "@shared/utils/constants";
import { datetime, money } from "@shared/utils/format";
import { earnPointsFor, pricingFor } from "@shared/strategies/pricing";
// 券能减多少与后端同一套算法算 —— 两处各写一遍就会出现「页面说减 8，付完只减 5」
import { couponDiscount } from "@shared/strategies/pricing/types";
import { currentCurrency } from "@shared/utils/money";
import type { Address, CartItem, CheckoutCapability, Coupon, FulfillmentType, OrderItem, OrderAmount } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();
const community = useCommunityStore();

const fulfillment = ref<FulfillmentType>(FULFILLMENT.PICKUP);
const items = ref<CartItem[]>([]);
const addresses = ref<Address[]>([]);
const addressId = ref("");
const coupons = ref<Coupon[]>([]);
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

function pickSlot() {
  const list = slots.value;
  if (!list.length) return;
  uni.showActionSheet({
    itemList: list.slice(0, 12).map((s) => s.label),
    success: (r) => {
      const picked = list[r.tapIndex];
      if (picked) appointmentAt.value = picked.at;
    },
  });
}

const address = computed(() => addresses.value.find((a) => a.addressId === addressId.value));
const coupon = computed(() => coupons.value.find((c) => c.couponNo === couponNo.value));

/** 可用券：已领取、未过期、且达到门槛 */
const usableCoupons = computed(() =>
  coupons.value.filter(
    (c) => c.received && c.endAt > Date.now() && goodsMinor.value >= c.thresholdMinor,
  ),
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

/**
 * 结算页能力提示：这一车货能不能开票、能用哪些支付方式、额度够不够。
 *
 * 三件事的共同后果都是**付款那一刻才炸** —— 小微没有 H5/App 支付方式、
 * 小微不能开票、额度用尽通道直接拒收。后端一直拦得住，但买家是在
 * 点了支付之后才知道，而那时候平台既解释不清也补救不了。
 */
const capability = ref<CheckoutCapability | null>(null);

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

/** 影响金额的任何一项变了就重新问后端 —— 少问一次就会显示上一次的价 */
async function refreshAmount() {
  if (!items.value.length) {
    serverAmount.value = null;
    return;
  }
  try {
    const p = await api.orderPreview({
      items: items.value.map((it) => ({ goodsNo: it.goodsNo, skuNo: it.skuNo, qty: it.qty })),
      fulfillment: fulfillment.value,
      pickupNo: needPickup.value ? community.pickup?.pickupNo : undefined,
      addressId: needAddress.value ? addressId.value : undefined,
      couponNo: couponNo.value || undefined,
      usePoints: FEATURES.points && usePoints.value ? pointBalance.value : 0,
      appointmentAt: appointmentAt.value,
    });
    serverAmount.value = p.amount;
  } catch {
    // 预览失败不挡下单：兜底显示本地估算，真实金额在提交时由后端定
    serverAmount.value = null;
  }
}

/**
 * 能力提示与金额分开问：金额随优惠、地址、履约方式变，能力只随**车里有谁**变。
 * 合成一个请求的话，改一次地址就会把三次能力查询也重跑一遍。
 */
async function refreshCapability() {
  if (!items.value.length) {
    capability.value = null;
    return;
  }
  try {
    capability.value = await api.orderCapability({
      items: items.value.map((it) => ({ goodsNo: it.goodsNo, skuNo: it.skuNo, qty: it.qty })),
      fulfillment: fulfillment.value,
      pickupNo: needPickup.value ? community.pickup?.pickupNo : undefined,
    });
  } catch {
    // 拿不到就不提示，但**不拦下单**：接口挂了是我们的问题，不该变成他买不了
    capability.value = null;
  }
}

watch(
  () => [items.value.length, fulfillment.value, couponNo.value, usePoints.value, addressId.value, appointmentAt.value],
  () => void refreshAmount(),
  { immediate: true },
);

// 只跟车里的商品变化 —— 改地址、换券都不影响「这家能不能开票」
watch(
  () => items.value.map((it) => it.skuNo).join(","),
  () => void refreshCapability(),
  { immediate: true },
);

const canSubmit = computed(
  () => !!items.value.length && !submitting.value
    && (!needAddress.value || !!address.value)
    // 没选时段就提交，后端会拒 —— 在这里灰掉按钮，别让他撞一次
    && (!needAppointment.value || !!appointmentAt.value)
    // 一种支付方式都没有 / 有商家额度过不去：拦在这里，别让他撞一个说不清的「支付失败」
    && !noPayMethod.value && quotaBlocked.value.length === 0,
);

async function loadAddresses() {
  addresses.value = await api.addressList();
  if (!addressId.value) {
    addressId.value =
      addresses.value.find((a) => a.isDefault)?.addressId ??
      addresses.value[0]?.addressId ??
      "";
  }
}

function pickCoupon() {
  if (!usableCoupons.value.length) return;
  const names = [
    String(t("confirm.noCoupon")),
    ...usableCoupons.value.map((c) => `${c.title} -${money(couponDiscount(c, goodsMinor.value))}`),
  ];
  uni.showActionSheet({
    itemList: names,
    success: (r) => {
      couponNo.value = r.tapIndex === 0 ? "" : usableCoupons.value[r.tapIndex - 1]!.couponNo;
    },
  });
}

function gotoAddress() {
  uni.navigateTo({ url: `${ROUTES.address}?picking=1` });
}

async function submit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  try {
    const order = await api.createOrder({
      items: items.value.map((it) => ({
        goodsNo: it.goodsNo,
        skuNo: it.skuNo,
        qty: it.qty,
      })),
      fulfillment: fulfillment.value,
      pickupNo: needPickup.value ? community.pickup?.pickupNo : undefined,
      addressId: needAddress.value ? addressId.value : undefined,
      couponNo: couponNo.value || undefined,
      usePoints: FEATURES.points && usePoints.value ? pointBalance.value : 0,
      remark: remark.value || undefined,
      appointmentAt: appointmentAt.value,
      // 幂等 key 在**提交时**生成一次，重复点击提交的是同一个 key，后端返回同一单
      idempotencyKey: idempotencyKey(),
    });
    await cart.load();
    uni.redirectTo({ url: `${ROUTES.pay}?orderNo=${order.orderNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}

onLoad((q) => {
  fulfillment.value = (q?.fulfillment as FulfillmentType) || FULFILLMENT.PICKUP;
  appointmentAt.value = q?.appointmentAt ? Number(q.appointmentAt) : undefined;

  // 来源两种：购物车按履约方式分组结算 / 详情页立即购买（skus 参数指定）
  const only = (q?.skus as string) || "";
  const wanted = only ? new Set(only.split(",")) : null;
  items.value = cart.validItems.filter(
    (it) => it.fulfillment === fulfillment.value && (!wanted || wanted.has(it.skuNo)),
  );
});

onMounted(async () => {
  await Promise.all([
    loadAddresses(),
    api.couponList().then((c) => (coupons.value = c)),
    FEATURES.points
      ? api.pointAccount().then((a) => (pointBalance.value = a.balance))
      : Promise.resolve(),
  ]);
});
</script>

<template>
  <sh-scaffold title-key="confirm.title">
    <!--
      能力提示：**必须在付款前**说。
      三条的共同后果都是付款那一刻才炸 —— 而那时候平台既解释不清也补救不了。
      放在页首而不是靠近提交按钮：买家的注意力在这一页从上往下走，
      放在底部他会先看完金额再看到「其实付不了」。
    -->
    <view v-if="noPayMethod" class="cap cap--block">
      <text>{{ $t("confirm.capNoPayMethod") }}</text>
    </view>
    <view v-if="quotaBlocked.length" class="cap cap--block">
      <text>{{ $t("confirm.capQuotaBlocked", { names: quotaBlocked.map((m) => m.merchantName).join("、") }) }}</text>
    </view>
    <view v-if="noInvoiceMerchants.length" class="cap cap--warn">
      <text>{{ $t("confirm.capNoInvoice", { names: noInvoiceMerchants.map((m) => m.merchantName).join("、") }) }}</text>
    </view>

    <!-- 收货信息：按履约方式变形 -->
    <view class="sh-card">
      <text class="sh-chip sh-chip--primary">{{ $t(`fulfillment.${fulfillment}`) }}</text>

      <!-- 自提 -->
      <view v-if="needPickup" class="recv">
        <text class="recv__title">{{ community.pickup?.name }}</text>
        <text class="recv__sub">
          {{ community.hostName }} · {{ community.pickup?.arrivalDesc }}
        </text>
        <text class="recv__sub">{{ community.pickup?.address }}</text>
      </view>

      <!-- 送货上门 / 快递 -->
      <view v-else-if="needAddress" class="recv" @tap="gotoAddress">
        <template v-if="address">
          <view class="recv__row">
            <text class="recv__title">{{ address.name }}</text>
            <text class="recv__phone sh-num">{{ address.phone }}</text>
            <text class="recv__more">{{ $t("confirm.change") }}</text>
          </view>
          <text class="recv__sub">{{ address.region }} {{ address.detail }}</text>
        </template>
        <view v-else class="recv__empty">
          <text class="recv__empty-text">{{ $t("confirm.pickAddress") }}</text>
          <text class="recv__more">{{ $t("confirm.add") }}</text>
        </view>
      </view>

      <!-- 到店核销 / 即时发放 -->
      <view v-else class="recv">
        <text class="recv__title">{{ $t(`fulfillmentDesc.${fulfillment}`) }}</text>
      </view>
    </view>

    <!--
      预约时段。**与地址并列而不是塞进地址块** —— 它们回答两个不同的问题：
      「去哪」和「几点」，缺任何一个这单都履约不了。
    -->
    <view v-if="needAppointment" class="sh-card block recv" @tap="pickSlot">
      <view class="recv__row">
        <text class="recv__title">{{ $t("confirm.appointmentSlot") }}</text>
        <text class="recv__more">{{ appointmentAt ? $t("confirm.change") : $t("confirm.pick") }}</text>
      </view>
      <text v-if="appointmentAt" class="recv__sub sh-num">
        {{ $t("confirm.appointmentAt", { t: datetime(appointmentAt) }) }}
      </text>
      <text v-else class="recv__empty-text">{{ $t("confirm.pickSlotHint") }}</text>
    </view>

    <!-- 商品 -->
    <view class="sh-card block">
      <!--
        **这一页才是拆单真正发生的地方**：提交后按商家生成 N 笔 ord_sub_order。
        此前这里只列一份平铺清单，用户看到「一单」、拿到两单。
      -->
      <template v-for="m in merchantSegments" :key="m.merchantNo">
        <view v-if="merchantSegments.length > 1" class="seg">
          <text class="seg__name">{{ m.merchantName || $t("cart.unknownMerchant") }}</text>
        </view>

        <biz-sku-row
          v-for="it in m.items"
          :key="it.skuNo"
          :cover="it.cover"
          :title="it.title"
          :spec="it.spec"
          size="lg"
        >
          <view class="row__foot">
            <text class="row__price sh-num">{{ money(it.price) }}</text>
            <text class="row__qty sh-num">×{{ it.qty }}</text>
          </view>
        </biz-sku-row>
      </template>

      <text v-if="merchantSegments.length > 1" class="splitnote">
        {{ $t("confirm.splitNote", { n: merchantSegments.length }) }}
      </text>

      <!-- 赠品：单独列出来，让用户在付款前就看见 -->
      <view v-for="g in gifts" :key="`gift-${g.skuNo}`" class="giftrow">
        <text class="giftrow__tag">{{ $t("promo.gift") }}</text>
        <text class="giftrow__text sh-num">
          {{ $t("promo.giftItem", { title: g.title, n: g.giftQty }) }}
        </text>
      </view>
    </view>

    <!-- 券 + 备注 -->
    <view class="sh-card block">
      <view class="cell" @tap="pickCoupon">
        <text class="cell__k">{{ $t("confirm.coupon") }}</text>
        <text class="cell__v" :class="{ 'is-on': !!coupon }">
          {{ coupon
            ? `${coupon.title} -${money(couponDiscount(coupon, goodsMinor))}`
            : usableCoupons.length
              ? $t("confirm.couponAvailable", { n: usableCoupons.length })
              : $t("confirm.noCouponAvailable") }}
        </text>
      </view>
      <!-- 积分抵扣：上限是「券后金额」的固定比例，说清楚为什么抵不满 -->
      <view v-if="FEATURES.points && pointBalance > 0" class="cell" @tap="usePoints = !usePoints">
        <text class="cell__k">{{ $t("confirm.points") }}</text>
        <view class="pointsline">
          <text class="cell__v" :class="{ 'is-on': usePoints && !!amount?.pointsUsed }">
            {{ usePoints && amount?.pointsUsed
              ? $t("confirm.pointsUsed", { n: amount.pointsUsed, p: money(amount.pointsDeductMinor) })
              : $t("confirm.pointsHave", { n: pointBalance }) }}
          </text>
          <view class="dot" :class="{ 'is-on': usePoints }" />
        </view>
      </view>

      <view class="cell">
        <text class="cell__k">{{ $t("confirm.remark") }}</text>
        <input v-model="remark" class="cell__input" :placeholder="$t('confirm.remarkPh')" />
      </view>
    </view>

    <!-- 金额明细 -->
    <view v-if="amount" class="sh-card block">
      <view class="amt">
        <text class="amt__k">{{ $t("confirm.goods") }}</text>
        <text class="amt__v sh-num">{{ money(amount.goodsMinor) }}</text>
      </view>
      <view class="amt">
        <text class="amt__k">{{ $t("confirm.freight") }}</text>
        <text class="amt__v sh-num">
          {{ amount.freightMinor ? money(amount.freightMinor) : $t("confirm.free") }}
        </text>
      </view>
      <view v-if="amount.discountMinor" class="amt">
        <text class="amt__k">{{ $t("confirm.discount") }}</text>
        <text class="amt__v amt__v--off sh-num">-{{ money(amount.discountMinor) }}</text>
      </view>
      <view v-if="amount.pointsDeductMinor" class="amt">
        <text class="amt__k sh-num">{{ $t("confirm.pointsDeduct", { n: amount.pointsUsed }) }}</text>
        <text class="amt__v amt__v--off sh-num">-{{ money(amount.pointsDeductMinor) }}</text>
      </view>
      <view v-if="amount.pointsEarn" class="amt">
        <text class="amt__k">{{ $t("confirm.pointsEarn") }}</text>
        <text class="amt__v amt__v--earn sh-num">+{{ amount.pointsEarn }}</text>
      </view>
    </view>

    <view class="actionbar">
      <view class="actionbar__sum">
        <text class="sh-muted">{{ $t("confirm.payable") }}</text>
        <text class="actionbar__total sh-num">{{ money(amount?.payableMinor ?? 0) }}</text>
      </view>
      <view
        class="sh-btn actionbar__btn"
        :class="{ 'is-disabled': !canSubmit }"
        @tap="submit"
      >
        {{ submitting ? $t("confirm.submitting") : $t("confirm.submit") }}
      </view>
    </view>
    <view class="spacer" />
  </sh-scaffold>
</template>

<style scoped>
.recv {
  margin-top: 24rpx;
}
.recv__row {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}
.recv__title {
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.recv__phone {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.recv__more {
  margin-inline-start: auto;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.recv__sub {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 10rpx;
  line-height: 1.5;
}
.recv__empty {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.recv__empty-text {
  flex: 1;
  font-size: 28rpx;
  color: var(--sh-sub);
}
.seg {
  display: flex;
  align-items: center;
  margin: 24rpx 0 8rpx;
}
.seg__name {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.splitnote {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.5;
}
.block {
  margin-top: 20rpx;
}
.row__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18rpx;
}
.row__price {
  font-size: 28rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.row__qty {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.giftrow {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
  background: var(--sh-danger-tint);
  border-radius: 16rpx;
  padding: 12rpx 18rpx;
}
.giftrow__tag {
  font-size: 24rpx;
  font-weight: 400;
  color: var(--sh-danger);
  flex-shrink: 0;
}
.giftrow__text {
  font-size: 24rpx;
  color: var(--sh-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  padding: 20rpx 0;
}
.cell__k {
  font-size: 26rpx;
  color: var(--sh-ink);
  flex-shrink: 0;
}
.cell__v {
  font-size: 24rpx;
  color: var(--sh-sub);
  text-align: end;
}
.cell__v.is-on {
  color: var(--sh-danger);
  font-weight: 600;
}
.cell__input {
  flex: 1;
  font-size: 24rpx;
  color: var(--sh-ink);
  text-align: end;
}
.amt {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 0;
}
.amt__k {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.amt__v {
  font-size: 24rpx;
  color: var(--sh-ink);
}
.amt__v--off {
  color: var(--sh-danger);
}
.amt__v--earn {
  color: var(--sh-primary-text);
}
.pointsline {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.dot {
  width: 40rpx;
  height: 40rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  flex-shrink: 0;
}
.dot.is-on {
  background: var(--sh-primary);
}
.actionbar {
  position: fixed;
  inset-inline: 28rpx;
  bottom: calc(28rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: var(--sh-surface);
  border-radius: 9999px;
  padding: 16rpx 16rpx 16rpx 40rpx;
}
.actionbar__sum {
  flex: 1;
  min-width: 0;
}
.actionbar__total {
  display: block;
  font-size: 34rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.actionbar__btn {
  flex: 0 0 auto;
  padding-left: 52rpx;
  padding-right: 52rpx;
  font-size: 28rpx;
}
.is-disabled {
  opacity: 0.45;
}
.spacer {
  height: 200rpx;
}

/* 能力提示：拦下的用醒目色，只是提醒的用弱一档 —— 两者的用户动作不同 */
.cap {
  margin: 16rpx 24rpx;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  font-size: 26rpx;
  line-height: 1.5;
}
/* 拦下的用 danger，只是提醒的用 warning —— 两者要求的用户动作不同 */
.cap--block {
  background: var(--sh-danger-tint);
  color: var(--sh-danger);
}
.cap--warn {
  background: var(--sh-warning-tint);
  color: var(--sh-warning);
}
</style>
