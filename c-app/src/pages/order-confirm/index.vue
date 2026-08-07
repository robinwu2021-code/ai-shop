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
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api, idempotencyKey } from "@/api";
import { useCartStore } from "@/stores/cart";
import { useCommunityStore } from "@/stores/community";
import { FEATURES, FULFILLMENT, POINTS, ROUTES } from "@shared/utils/constants";
import { datetime, money } from "@shared/utils/format";
import { earnPointsFor, pricingFor } from "@shared/strategies/pricing";
import { currentCurrency } from "@shared/utils/money";
import type { Address, CartItem, Coupon, FulfillmentType, OrderItem } from "@shared/types";

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

/** 需要收货地址的履约方式 */
const needAddress = computed(
  () =>
    fulfillment.value === FULFILLMENT.DELIVERY ||
    fulfillment.value === FULFILLMENT.EXPRESS,
);
const needPickup = computed(() => fulfillment.value === FULFILLMENT.PICKUP);

const address = computed(() => addresses.value.find((a) => a.addressId === addressId.value));
const coupon = computed(() => coupons.value.find((c) => c.couponNo === couponNo.value));

/** 可用券：已领取、未过期、且达到门槛 */
const usableCoupons = computed(() =>
  coupons.value.filter(
    (c) => c.received && c.expireAt > Date.now() && goodsMinor.value >= c.thresholdMinor,
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
 * 金额用**和下单同一套策略**算，不在页面里另写一份公式 ——
 * 页面自己算一份、后端算一份，两边迟早对不上（「价格不符」类问题的经典来源）。
 */
const amount = computed(() => {
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

const canSubmit = computed(
  () => !!items.value.length && !submitting.value && (!needAddress.value || !!address.value),
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
    ...usableCoupons.value.map((c) => `${c.name} -${money(c.discountMinor)}`),
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

      <!-- 到店核销 / 预约 / 即时发放 -->
      <view v-else class="recv">
        <text class="recv__title">{{ $t(`fulfillmentDesc.${fulfillment}`) }}</text>
        <text v-if="appointmentAt" class="recv__sub sh-num">
          {{ $t("confirm.appointmentAt", { t: datetime(appointmentAt) }) }}
        </text>
      </view>
    </view>

    <!-- 商品 -->
    <view class="sh-card block">
      <biz-sku-row
        v-for="it in items"
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
            ? `${coupon.name} -${money(coupon.discountMinor)}`
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
  color: var(--sh-primary);
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
  color: var(--sh-primary);
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
</style>
