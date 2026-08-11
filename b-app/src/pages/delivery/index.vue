<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 商家自送（B-11.4.6 / 4.7）。
//
// ⚠️ **不做骑手系统**（ADR-005 §5）：小店老板骑电动车送两条街，他要的是「点一下已送达」，
// 不是位置回传和轨迹回放。这一条做重了店主不会用，送货上门这条线就是废的。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money, toMajor, toMinor } from "@shared/utils/money";
import { FULFILLMENT } from "@shared/utils/constants";
import type { DeliveryRule, Order } from "@shared/types";

const { t } = useI18n();

const rule = ref<DeliveryRule>({
  radius: 3000,
  minOrderMinor: 0,
  feeMinor: 0,
  freeThresholdMinor: 0,
});
/** 表单用主单位（元），保存时换回最小单位 —— 店主输 20，存 2000 */
const form = ref({ radius: "3000", minOrder: "0", fee: "0", freeThreshold: "0" });
const orders = ref<Order[]>([]);
const busy = ref("");

const pending = computed(() =>
  orders.value.filter((o) => o.fulfillment === FULFILLMENT.DELIVERY && o.status === "PAID"),
);

async function load() {
  const [r, res] = await Promise.all([api.mDeliveryRule(), api.mOrderList({ size: 100 })]);
  rule.value = r;
  form.value = {
    radius: String(r.radius),
    minOrder: toMajor(r.minOrderMinor),
    fee: toMajor(r.feeMinor),
    freeThreshold: toMajor(r.freeThresholdMinor),
  };
  orders.value = res.records;
}

async function saveRule() {
  rule.value = await api.mSaveDeliveryRule({
    radius: Number(form.value.radius) || 0,
    minOrderMinor: toMinor(form.value.minOrder),
    feeMinor: toMinor(form.value.fee),
    freeThresholdMinor: toMinor(form.value.freeThreshold),
  });
  uni.showToast({ title: t("common.saved"), icon: "none" });
}

async function delivered(o: Order) {
  if (busy.value) return;
  busy.value = o.orderNo;
  try {
    await api.mDelivered(o.orderNo);
    uni.showToast({ title: t("order.deliveredDone"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = "";
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="delivery.title" :denied="!merchant.can('biz:ship')">
    <text class="sh-h1">{{ $t("delivery.title") }}</text>

    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("delivery.rule") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("delivery.radius") }}</text>
        <input v-model="form.radius" class="field__input sh-num" type="number" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("delivery.minOrder") }}</text>
        <input v-model="form.minOrder" class="field__input sh-num" type="digit" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("delivery.fee") }}</text>
        <input v-model="form.fee" class="field__input sh-num" type="digit" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("delivery.freeThreshold") }}</text>
        <input v-model="form.freeThreshold" class="field__input sh-num" type="digit" />
        <text class="hint">{{ $t("delivery.freeHint") }}</text>
      </view>

      <view class="sh-btn sh-btn--soft save" @tap="saveRule">{{ $t("common.save") }}</view>
    </view>

    <view class="list-head">
      <text class="sh-h2">{{ $t("delivery.pending") }}</text>
      <text class="sh-muted sh-num">{{ pending.length }}</text>
    </view>

    <sh-empty v-if="!pending.length" :text='$t("delivery.empty")'></sh-empty>

    <view v-for="o in pending" :key="o.orderNo" class="sh-card row">
      <view class="row__main">
        <text class="row__buyer">{{ o.buyerNickname || "—" }}</text>
        <text class="sh-muted sh-num">{{ o.orderNo }}</text>
      </view>
      <text class="row__amount sh-num">
        {{ money(o.amount.payableMinor, o.amount.currency) }}
      </text>
      <text class="btn" @tap="delivered(o)">{{ $t("order.delivered") }}</text>
    </view>

    <text class="tip">{{ $t("delivery.noRiderHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  margin-top: 24rpx;
}
.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.save {
  margin-top: 32rpx;
}
.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 32rpx 8rpx 16rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-bottom: 16rpx;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__buyer {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__amount {
  font-size: 30rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.btn {
  padding: 18rpx 28rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 24rpx;
  font-weight: 600;
}
.tip {
  display: block;
  margin: 32rpx 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
