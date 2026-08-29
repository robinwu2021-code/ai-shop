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

/**
 * 线下收款。**权限是 `biz:receive` 不是 `biz:order:view`** ——
 * 后者是只读权限，配送员（COURIER）也持有；让他能点等于让送货的人替商家宣布已收款。
 */
const canConfirmOffline = computed(
  () => order.value?.status === "WAIT_OFFLINE_PAY" && merchant.can("biz:receive"),
);
const offlineAsking = ref(false);

/**
 * 应收金额 = 抵扣后的实付。
 *
 * ⚠️ **这是整条链路上唯一一处「抵扣」离开系统、落到人当面执行的地方。**
 * 顾客用积分抵掉的那部分，平台没有任何资金动作 —— 商家当面少收即是抵扣。
 * 所以这个数必须大字显示，而且要把「已抵扣多少」摆在旁边：
 * 老板按订单原价收钱的话，顾客的积分就白花了，而系统里查不出这件事。
 */
const dueMinor = computed(() => order.value?.amount.payableMinor ?? 0);
const deductedMinor = computed(() => order.value?.amount.pointsDeductMinor ?? 0);

async function confirmOffline() {
  if (!order.value || busy.value) return;
  busy.value = true;
  try {
    order.value = await api.mConfirmOfflinePay(order.value.orderNo);
    offlineAsking.value = false;
    uni.showToast({ title: t("order.offlinePaid"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

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
        <view class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.no") }}</text>
          <text class="sh-num">{{ order.orderNo }}</text>
        </view>
        <view class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.createdAt") }}</text>
          <text class="sh-num">{{ datetime(order.createdAt) }}</text>
        </view>
        <view class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.fulfillment") }}</text>
          <!-- **不要直接渲染枚举**：店主看到的是「快递配送」，不是 EXPRESS。
               与权限码、角色码同一条规矩，这里是最后一处漏网的 -->
          <text>{{ $t(`fulfillmentLabel.${order.fulfillment}`) }}</text>
        </view>
        <view class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.buyer") }}</text>
          <text>{{ order.buyerNickname || "—" }}</text>
        </view>
        <view v-if="order.trafficSource" class="line sh-row sh-row--between">
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
        <view v-if="order.receiver?.address" class="line line--wrap sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.receiver") }}</text>
          <view class="recv sh-fill">
            <text class="txt-body recv__who">
              {{ order.receiver.name || "—" }}
              <text v-if="order.receiver.phone" class="sh-num">　{{ order.receiver.phone }}</text>
            </text>
            <text class="txt-caption recv__addr">{{ order.receiver.address }}</text>
          </view>
        </view>
      </view>

      <view class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("order.items") }}</text>
        <view v-for="it in order.items" :key="it.skuNo" class="sh-row item sh-mt-sm">
          <sh-cover class="item__cover" :src="it.cover"></sh-cover>
          <view class="sh-fill">
            <text class="txt-body item__title">{{ it.title }}</text>
            <text class="sh-muted">{{ it.spec }} × {{ it.qty }}</text>
          </view>
          <text class="sh-num">{{ money(it.price, order.amount.currency) }}</text>
        </view>
        <view class="line total sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.amount") }}</text>
          <text class="txt-title sh-num">
            {{ money(order.amount.payableMinor, order.amount.currency) }}
          </text>
        </view>
      </view>

      <!--
        线下收款。**入口是一个按钮，动作在弹窗里** —— 收钱这件事不该一点就成，
        中间要有一屏让老板核对金额。
      -->
      <view v-if="canConfirmOffline" class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("order.offlinePay") }}</text>
        <text class="txt-caption sh-muted due__hint">{{ $t("order.offlineNotCustodied") }}</text>
        <view class="sh-btn sh-mt-sm" @tap="offlineAsking = true">{{ $t("order.offlinePay") }}</view>
      </view>

      <!-- 快递发货：运单号回填（B-11.4.3） -->
      <view v-if="canShip" class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("order.ship") }}</text>
        <input
          maxlength="64"
          v-model="expressNo"
          class="field__input sh-mt-sm"
          :placeholder="$t('order.expressNo')"
        />
        <view class="sh-btn sh-mt-sm" :class="{ 'sh-btn--muted': !expressNo }" @tap="ship">
          {{ $t("order.ship") }}
        </view>
      </view>

      <!-- 商家自送：老板点一下就是送到了，不做骑手轨迹（ADR-005 §5） -->
      <view v-if="canDeliver" class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("order.delivered") }}</text>
        <view class="sh-btn sh-mt-sm" @tap="delivered">{{ $t("order.delivered") }}</view>
      </view>

      <view v-if="order.expressNo" class="sh-card sh-mt-sm">
        <view class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.expressNo") }}</text>
          <text class="sh-num">{{ order.expressNo }}</text>
        </view>
      </view>

      <!--
        确认收款弹窗。三样东西缺一不可：
          · **大字应收金额** —— 老板照着这个数收，不是照订单原价
          · **已抵扣多少**   —— 少了它，顾客的积分会被当成没用过
          · **平台不代收**   —— 说清楚这笔钱不经平台，出纠纷时双方对这一点没有分歧
      -->
      <sh-dialog
        :visible="offlineAsking"
        :title="String($t('order.offlinePayTitle'))"
        @close="offlineAsking = false"
      >
        <text class="sh-muted">{{ $t("order.offlineDue") }}</text>
        <text class="txt-mega due sh-num">{{ money(dueMinor, order.amount.currency) }}</text>
        <text v-if="deductedMinor > 0" class="txt-sub due__deducted">
          {{ $t("order.offlineDeducted", { v: money(deductedMinor, order.amount.currency) }) }}
        </text>
        <text class="txt-caption due__hint">{{ $t("order.offlineNotCustodied") }}</text>
        <template #actions>
          <view class="sh-btn sh-btn--muted sh-dialog__act" @tap="offlineAsking = false">
            {{ $t("order.offlineCancel") }}
          </view>
          <view class="sh-btn sh-dialog__act" @tap="confirmOffline">{{ $t("order.offlinePay") }}</view>
        </template>
      </sh-dialog>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.line {
  padding: 10rpx 0;
}
/* 地址是长文本，跟着基线对齐会把标签顶歪 */
.line--wrap {
  align-items: flex-start;
  gap: 32rpx;
}
.recv {
  text-align: end;
}
.recv__who {
  display: block;
}
.recv__addr {
  display: block;
  margin-top: 4rpx;
}
.item {
  gap: 20rpx;
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

.item__title {
  display: block;
}
.total {
  margin-top: 16rpx;
}
/* 应收金额是这一屏唯一要一眼看清的东西 —— 老板照着它收钱。
   字号字重全交给 `.txt-mega`（字阶第八档，60rpx/700）：**此前这里是把那一档
   的四行声明照抄了一遍**，注释还写着「= 字阶的 .txt-mega」—— 于是清单里
   .txt-mega 永远显示「定义了没人用」，而它明明就用在这。 */
.due {
  display: block;
}
.due__deducted {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-primary-text);
}
.due__hint {
  display: block;
  margin-top: 16rpx;
}
</style>
