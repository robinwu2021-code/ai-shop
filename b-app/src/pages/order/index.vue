<script setup lang="ts">
// 订单详情 + 履约动作（B-11.4.2/4.3/4.7）。
//
// 一个页面只给一个主动作：快递单给「填运单号发货」，自送单给「已送达」，
// 自提单什么都不给 —— 自提的动作在履约台（核销），不在这里。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { datetime } from "@shared/utils/datetime";
import { FULFILLMENT } from "@shared/utils/constants";
import type { Order } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const order = ref<Order | null>(null);
const expressNo = ref("");
const busy = ref(false);

/** 快递单且已付款 → 该发货 */
/*
 * **状态对 + 有权限，两个都要**。原先只判状态：客服有 `biz:order:view` 能进详情页，
 * 于是「发货」按钮画给了他，点下去 70006 —— 而他既不该发货，也没有任何办法拿到这个权限。
 */
const canShip = computed(
  () => order.value?.fulfillment === FULFILLMENT.EXPRESS && order.value?.status === "PAID"
    && merchant.can("biz:ship"),
);
/** 自送单且已付款 → 该送。同样两个都要判（见 {@link canShip}） */
const canDeliver = computed(
  () => order.value?.fulfillment === FULFILLMENT.DELIVERY && order.value?.status === "PAID"
    && merchant.can("biz:ship"),
);

async function load(orderNo: string) {
  order.value = await api.mOrderDetail(orderNo);
}

async function ship() {
  if (!order.value || !expressNo.value || busy.value) return;
  busy.value = true;
  try {
    order.value = await api.mShip(order.value.orderNo, expressNo.value);
    uni.showToast({ title: t("order.shipped"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function delivered() {
  if (!order.value || busy.value) return;
  busy.value = true;
  try {
    order.value = await api.mDelivered(order.value.orderNo);
    uni.showToast({ title: t("order.deliveredDone"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

onLoad((q) => {
  if (q?.orderNo) void load(q.orderNo);
});
</script>

<template>
  <!-- 正常入口（订单列表）已判过一次，这里是给刷新与深链兜底 -->
  <sh-scaffold title-key="order.detail" :denied="!merchant.can('biz:order:view')">
    <template v-if="order">
      <view class="sh-card">
        <view class="line">
          <text class="sh-muted">{{ $t("order.no") }}</text>
          <text class="sh-num">{{ order.orderNo }}</text>
        </view>
        <view class="line">
          <text class="sh-muted">{{ $t("order.createdAt") }}</text>
          <text class="sh-num">{{ datetime(order.createdAt) }}</text>
        </view>
        <view class="line">
          <text class="sh-muted">{{ $t("order.fulfillment") }}</text>
          <text>{{ order.fulfillment }}</text>
        </view>
        <view class="line">
          <text class="sh-muted">{{ $t("order.buyer") }}</text>
          <text>{{ order.buyerNickname || "—" }}</text>
        </view>
        <view v-if="order.trafficSource" class="line">
          <text class="sh-muted">{{ $t("home.ownedTraffic") }}</text>
          <text class="sh-chip sh-chip--primary">
            {{ $t(`order.traffic${order.trafficSource}`) }}
          </text>
        </view>
        <!--
          收件人。自提单没有这一段（货在自提点，不送）。
          手机号的脱敏程度后端已经按履约方式定好了，这里原样显示 ——
          端上再判一次就是第二套规则。
        -->
        <view v-if="order.receiver?.address" class="line line--wrap">
          <text class="sh-muted">{{ $t("order.receiver") }}</text>
          <view class="recv">
            <text class="recv__who">
              {{ order.receiver.name || "—" }}
              <text v-if="order.receiver.phone" class="sh-num">　{{ order.receiver.phone }}</text>
            </text>
            <text class="recv__addr">{{ order.receiver.address }}</text>
          </view>
        </view>
      </view>

      <view class="sh-card mt">
        <text class="sh-h2">{{ $t("order.items") }}</text>
        <view v-for="it in order.items" :key="it.skuNo" class="item">
          <text class="item__cover">{{ it.cover }}</text>
          <view class="item__main">
            <text class="item__title">{{ it.title }}</text>
            <text class="sh-muted">{{ it.spec }} × {{ it.qty }}</text>
          </view>
          <text class="sh-num">{{ money(it.price, order.amount.currency) }}</text>
        </view>
        <view class="line total">
          <text class="sh-muted">{{ $t("order.amount") }}</text>
          <text class="total__v sh-num">
            {{ money(order.amount.payableMinor, order.amount.currency) }}
          </text>
        </view>
      </view>

      <!-- 快递发货：运单号回填（B-11.4.3） -->
      <view v-if="canShip" class="sh-card mt">
        <text class="sh-h2">{{ $t("order.ship") }}</text>
        <input
          v-model="expressNo"
          class="field__input mt-s"
          :placeholder="$t('order.expressNo')"
        />
        <view class="sh-btn mt-s" :class="{ 'sh-btn--muted': !expressNo }" @tap="ship">
          {{ $t("order.ship") }}
        </view>
      </view>

      <!-- 商家自送：老板点一下就是送到了，不做骑手轨迹（ADR-005 §5） -->
      <view v-if="canDeliver" class="sh-card mt">
        <text class="sh-h2">{{ $t("order.delivered") }}</text>
        <view class="sh-btn mt-s" @tap="delivered">{{ $t("order.delivered") }}</view>
      </view>

      <view v-if="order.expressNo" class="sh-card mt">
        <view class="line">
          <text class="sh-muted">{{ $t("order.expressNo") }}</text>
          <text class="sh-num">{{ order.expressNo }}</text>
        </view>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  margin-top: 24rpx;
}
.mt-s {
  margin-top: 20rpx;
}
.line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10rpx 0;
}
/* 地址是长文本，跟着基线对齐会把标签顶歪 */
.line--wrap {
  align-items: flex-start;
  gap: 32rpx;
}
.recv {
  flex: 1;
  min-width: 0;
  text-align: right;
}
.recv__who {
  display: block;
  font-size: 28rpx;
  color: var(--sh-ink);
}
.recv__addr {
  display: block;
  margin-top: 4rpx;
  font-size: 26rpx;
  color: var(--sh-sub);
  line-height: 1.4;
}
.item {
  display: flex;
  gap: 20rpx;
  align-items: center;
  margin-top: 20rpx;
}
.item__cover {
  font-size: 48rpx;
  width: 76rpx;
  height: 76rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  text-align: center;
  line-height: 76rpx;
}
.item__main {
  flex: 1;
  min-width: 0;
}
.item__title {
  display: block;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.total {
  margin-top: 24rpx;
}
.total__v {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
</style>
