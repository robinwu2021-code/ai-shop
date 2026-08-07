<script setup lang="ts">
// 售后处理（B-11.5）。
//
// 设计要点：**驳回必须填理由**。同意是一键的，驳回不是 —— 用户拿不到理由就只能
// 升级平台介入，平台再回头问商家，等于多绕一圈。理由填在这里，争议在这里就收敛掉。
//
// 另外：驳回**不改订单状态**。置回已完成就把「申请平台介入」的路堵死了，
// 用户会以为售后已结束（ADR 里没写，但这是售后设计的常见坑）。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money } from "@shared/utils/money";
import { datetime } from "@shared/utils/datetime";
import type { Order } from "@shared/types";

const { t } = useI18n();

const list = ref<Order[]>([]);
/** 正在驳回的单号 —— 展开理由输入框 */
const rejecting = ref("");
const reason = ref("");
const busy = ref(false);

/**
 * 老数据（以及后端补字段之前的数据）可能没有 `afterSale`。
 * 缺就按「待处理的仅退款」看待 —— 宁可多给商家一次处理机会，也不能让单子卡在页面上没有任何按钮。
 */
const asStatus = (o: Order) => o.afterSale?.status ?? "PENDING";
const asType = (o: Order) => o.afterSale?.type ?? "REFUND_ONLY";

async function load() {
  rejecting.value = "";
  reason.value = "";
  list.value = await api.mAfterSaleList();
}

async function agree(o: Order) {
  if (busy.value) return;
  busy.value = true;
  try {
    await api.mApproveAfterSale(o.afterSale!.afterSaleNo, "");
    uni.showToast({ title: t("afterSale.agreed"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/**
 * 确认收到退货 → 随即退款（B-7.3）。
 * 只对「已寄回」的退货单出现 —— 用户还没寄就点确认，多半是误操作。
 */
async function confirmReturn(o: Order) {
  if (busy.value) return;
  busy.value = true;
  try {
    await api.mConfirmReturn(o.afterSale!.afterSaleNo);
    uni.showToast({ title: t("afterSale.refunded"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function reject(o: Order) {
  if (!reason.value.trim()) {
    uni.showToast({ title: t("afterSale.needReason"), icon: "none" });
    return;
  }
  if (busy.value) return;
  busy.value = true;
  try {
    await api.mRejectAfterSale(o.afterSale!.afterSaleNo, reason.value.trim());
    uni.showToast({ title: t("afterSale.rejected"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="afterSale.title">
    <text class="sh-h1">{{ $t("afterSale.title") }}</text>

    <sh-empty v-if="!list.length" :text='$t("afterSale.empty")'></sh-empty>

    <view v-for="o in list" :key="o.orderNo" class="sh-card item">
      <view class="item__head">
        <text class="item__buyer">{{ o.buyerNickname || "—" }}</text>
        <text class="sh-muted">{{ datetime(o.createdAt) }}</text>
      </view>
      <view class="item__tags">
        <text class="sh-chip">{{ $t(`afterSale.type${asType(o)}`) }}</text>
        <text class="sh-muted sh-num item__no">{{ o.orderNo }}</text>
      </view>
      <text v-if="o.afterSale?.reason" class="sh-muted reason">
        {{ $t("afterSale.buyerReason") }}{{ o.afterSale.reason }}
      </text>

      <view class="goods">
        <text v-for="it in o.items" :key="it.skuNo" class="sh-chip">
          {{ it.title }} ×{{ it.qty }}
        </text>
      </view>

      <view class="item__amount">
        <text class="sh-muted">{{ $t("afterSale.refundAmount") }}</text>
        <text class="sh-num amount">{{ money(o.amount.payableMinor, o.amount.currency) }}</text>
      </view>

      <template v-if="rejecting === o.orderNo">
        <textarea
          v-model="reason"
          class="field__area"
          :placeholder="$t('afterSale.reasonPh')"
          maxlength="80"
        />
        <view class="btns">
          <text class="btn btn--ghost" @tap="rejecting = ''">{{ $t("common.cancel") }}</text>
          <text class="btn btn--danger" @tap="reject(o)">{{ $t("afterSale.confirmReject") }}</text>
        </view>
      </template>

      <!-- 按售后状态给动作：待处理才谈同意/驳回，已寄回才谈确认收货 -->
      <view v-else-if="asStatus(o) === 'PENDING'" class="btns">
        <text class="btn btn--ghost" @tap="rejecting = o.orderNo">{{ $t("afterSale.reject") }}</text>
        <text class="btn" @tap="agree(o)">
          {{ asType(o) === "RETURN_REFUND" ? $t("afterSale.agreeReturn") : $t("afterSale.agree") }}
        </text>
      </view>

      <view v-else-if="asStatus(o) === 'AGREED'" class="waiting">
        <text class="sh-muted">{{ $t("afterSale.waitReturn") }}</text>
      </view>

      <view v-else-if="asStatus(o) === 'RETURNING'" class="btns">
        <view class="express">
          <text class="sh-muted">{{ $t("afterSale.returnExpress") }}</text>
          <text class="sh-num">{{ o.afterSale?.returnExpressNo }}</text>
        </view>
        <text class="btn" @tap="confirmReturn(o)">{{ $t("afterSale.confirmReceived") }}</text>
      </view>

      <view v-else-if="asStatus(o) === 'REJECTED'" class="waiting">
        <text class="sh-muted">{{ $t("afterSale.rejectedHint") }}</text>
      </view>

      <view v-else-if="asStatus(o) === 'DISPUTED'" class="waiting">
        <text class="sh-chip sh-chip--warning">{{ $t("afterSale.disputed") }}</text>
        <text class="sh-muted mt">{{ $t("afterSale.disputedHint") }}</text>
      </view>
    </view>

    <text v-if="list.length" class="tip">{{ $t("afterSale.hint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.item__tags {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.reason {
  display: block;
  margin-top: 8rpx;
}
.waiting {
  margin-top: 20rpx;
}
.express {
  flex: 1;
  min-width: 0;
}
.mt {
  display: block;
  margin-top: 10rpx;
}

.item {
  margin-top: 14rpx;
}
.item__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.item__buyer {
  font-size: 30rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.item__no {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
}
.goods {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin: 20rpx 0;
}
.item__amount {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 20rpx 0;
}
.amount {
  font-size: 34rpx;
  font-weight: 700;
  color: var(--sh-danger);
}
.btns {
  display: flex;
  gap: 16rpx;
  margin-top: 20rpx;
}
.btn {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 28rpx;
  font-weight: 600;
}
.btn--ghost {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
.btn--danger {
  background: var(--sh-danger);
  color: #fff;
}
.tip {
  display: block;
  margin: 32rpx 8rpx;
  font-size: 22rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
