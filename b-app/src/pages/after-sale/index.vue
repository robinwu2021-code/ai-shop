<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
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
import type { AfterSale, Order } from "@shared/types";

const { t } = useI18n();

/**
 * 一行 = 一张售后单 + 它所属的订单。
 *
 * **两者必须分开取**：`/biz/after-sale` 返回的是售后单（后端 AfterSaleVO），
 * 而这一页要展示的买家、商品、金额都在订单上。此前这里把售后列表直接
 * 当成 `Order[]` 用 —— 类型对不上，且列表本身还是按一个不存在的订单状态
 * (`REFUNDING`) 筛的，所以真实环境下这一页恒为空。
 */
type Row = { as: AfterSale; order?: Order };

const list = ref<Row[]>([]);
/** 正在驳回的单号 —— 展开理由输入框 */
const rejecting = ref("");
const reason = ref("");
const busy = ref(false);

/**
 * 老数据（以及后端补字段之前的数据）可能没有 `afterSale`。
 * 缺就按「待处理的仅退款」看待 —— 宁可多给商家一次处理机会，也不能让单子卡在页面上没有任何按钮。
 */
const asStatus = (r: Row) => r.as.status;
const asType = (r: Row) => r.as.type;

async function load() {
  rejecting.value = "";
  reason.value = "";
  const [afterSales, orders] = await Promise.all([
    api.mAfterSaleList(),
    api.mOrderList({ size: 50 }),
  ]);
  const byNo = new Map(orders.records.map((o) => [o.orderNo, o]));
  list.value = afterSales.map((as) => ({ as, order: byNo.get(as.subOrderNo) }));
}

async function agree(r: Row) {
  if (busy.value) return;
  busy.value = true;
  try {
    await api.mApproveAfterSale(r.as.afterSaleNo, "");
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
async function confirmReturn(r: Row) {
  if (busy.value) return;
  busy.value = true;
  try {
    await api.mConfirmReturn(r.as.afterSaleNo);
    uni.showToast({ title: t("afterSale.refunded"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function reject(r: Row) {
  if (!reason.value.trim()) {
    uni.showToast({ title: t("afterSale.needReason"), icon: "none" });
    return;
  }
  if (busy.value) return;
  busy.value = true;
  try {
    await api.mRejectAfterSale(r.as.afterSaleNo, reason.value.trim());
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
  <sh-scaffold title-key="afterSale.title" :denied="!merchant.can('biz:aftersale')">
    <text class="txt-display">{{ $t("afterSale.title") }}</text>

    <sh-empty v-if="!list.length" :text='$t("afterSale.empty")'></sh-empty>

    <view v-for="r in list" :key="r.as.afterSaleNo" class="sh-card sh-mt-sm">
      <view class="item__head">
        <text class="txt-body">{{ r.order?.buyerNickname || "—" }}</text>
        <text class="sh-muted">{{ datetime(r.as.updatedAt) }}</text>
      </view>
      <view class="item__tags">
        <text class="sh-chip">{{ $t(`afterSale.type${asType(r)}`) }}</text>
        <text class="txt-caption sh-muted sh-num item__no">{{ r.as.subOrderNo }}</text>
      </view>
      <text v-if="r.as.reason" class="sh-muted reason">
        {{ $t("afterSale.buyerReason") }}{{ r.as.reason }}
      </text>

      <view class="goods">
        <text v-for="it in r.order?.items || []" :key="it.skuNo" class="sh-chip">
          {{ it.title }} ×{{ it.qty }}
        </text>
      </view>

      <!-- 金额取**售后单自己的** refundMinor，不是订单的应付：
           一张子订单可以只退其中一件，也可以先后发起多次。
           也因此不再挂 `v-if="r.order"` —— 订单没匹配上时，
           退款金额这一行照样该出现，那是这一页最要紧的一个数。 -->
      <view class="item__amount">
        <text class="sh-muted">{{ $t("afterSale.refundAmount") }}</text>
        <text class="txt-price sh-num amount">
          {{ money(r.as.refundMinor, r.order?.amount.currency) }}
        </text>
      </view>

      <template v-if="rejecting === r.as.afterSaleNo">
        <textarea
          v-model="reason"
          class="field__area"
          :placeholder="$t('afterSale.reasonPh')"
          maxlength="80"
        />
        <view class="btns">
          <text class="sh-btn sh-btn--sm sh-btn--muted txt-strong btn" @tap="rejecting = ''">{{ $t("common.cancel") }}</text>
          <text class="sh-btn sh-btn--sm txt-strong btn btn--danger" @tap="reject(r)">{{ $t("afterSale.confirmReject") }}</text>
        </view>
      </template>

      <!-- 按售后状态给动作。后端没有独立的「等寄回 / 已收货」两态：
           同意即 REFUNDING，是否已寄回看 returnExpressNo 有没有值 -->
      <view v-else-if="asStatus(r) === 'APPLIED'" class="btns">
        <text class="sh-btn sh-btn--sm sh-btn--muted txt-strong btn" @tap="rejecting = r.as.afterSaleNo">
          {{ $t("afterSale.reject") }}
        </text>
        <text class="sh-btn sh-btn--sm txt-strong btn" @tap="agree(r)">
          {{ asType(r) === "RETURN_REFUND" ? $t("afterSale.agreeReturn") : $t("afterSale.agree") }}
        </text>
      </view>

      <view
        v-else-if="asStatus(r) === 'REFUNDING' && asType(r) === 'RETURN_REFUND' && !r.as.returnExpressNo"
        class="waiting"
      >
        <text class="sh-muted">{{ $t("afterSale.waitReturn") }}</text>
      </view>

      <view
        v-else-if="asStatus(r) === 'REFUNDING' && asType(r) === 'RETURN_REFUND'"
        class="btns"
      >
        <view class="express sh-fill">
          <text class="sh-muted">{{ $t("afterSale.returnExpress") }}</text>
          <text class="sh-num">{{ r.as.returnExpressNo }}</text>
        </view>
        <text class="sh-btn sh-btn--sm txt-strong btn btn--auto" @tap="confirmReturn(r)">
          {{ $t("afterSale.confirmReceived") }}
        </text>
      </view>

      <!-- 已退款也要说一句。此前这一支没有分支，卡片下方是**一片空白** ——
           而空白与「还没轮到我处理」长得一模一样。
           极速退单独说：那类单商家可见不可拒，不说清楚会以为是自己漏点了。 -->
      <view v-else-if="asStatus(r) === 'REFUNDED'" class="waiting">
        <text class="sh-muted">
          {{ r.as.instant ? $t("afterSale.instantHint") : $t("afterSale.doneHint") }}
        </text>
      </view>

      <view v-else-if="asStatus(r) === 'REJECTED'" class="waiting">
        <text class="sh-muted">{{ $t("afterSale.rejectedHint") }}</text>
      </view>

      <view v-else-if="asStatus(r) === 'ARBITRATING'" class="waiting">
        <text class="sh-chip sh-chip--warning">{{ $t("afterSale.disputed") }}</text>
        <text class="sh-muted sh-mt-xs blk">{{ $t("afterSale.disputedHint") }}</text>
      </view>
    </view>

    <text v-if="list.length" class="tip sh-hint">{{ $t("afterSale.hint") }}</text>
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
/*
 * 标签与单号**各占一行**。`<text>` 是行内元素，两个挨着写会连成一串，
 * 而这一格只有半屏宽 —— 断行落在「退货运单 / 号SF7788990011」上，
 * 把「运单号」三个字劈成两半。标签本身不许断（nowrap），
 * 单号可断（运单号比这一格宽是常态）。
 */
.express {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.express > text:first-child {
  white-space: nowrap;
}
.express > text:last-child {
  word-break: break-all;
}
.blk {
  display: block;
}

.item__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.item__no {
  display: block;
  margin-top: 4rpx;
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
  color: var(--sh-danger);
}
/*
 * `align-items: center` 不是排版偏好，是**药丸按钮的前提**：
 * 默认的 stretch 会把按钮拉到与同行最高的那格一样高（运单号那格是两行），
 * 而 `border-radius: 9999px` 在被拉高的盒子上不再是药丸，是椭圆。
 */
.btns {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 20rpx;
}
.btn {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
}
/*
 * 与运单号同行的那个按钮**按文字宽度收**，不跟着 `flex: 1` 平分。
 * 平分的结果是两边都只有半屏：按钮把「确认收到退货并退款」九个字撑满，
 * 运单号那边被挤到剩不下一行，20 位的单号折成两截。
 * 一行里一个是可变信息、一个是固定文案时，该收的是文案那一侧。
 */
.btn--auto {
  flex: 0 0 auto;
  padding-left: 24rpx;
  padding-right: 24rpx;
}

.btn--danger {
  background: var(--sh-danger);
  color: #fff;
}
.tip {
  margin: 32rpx 8rpx;
}
</style>
