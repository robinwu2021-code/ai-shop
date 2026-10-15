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
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { askMchSubscribe } from "@/shared/mch-subscribe";
import { refundProgressKey, hasHistoricalPoints } from "@shared/strategies/order-phase1";
import { money } from "@shared/utils/money";
import { datetime } from "@shared/utils/datetime";
import type { AfterSale, Order } from "@shared/types";

const { t } = useI18n();

/** 五档履约进度文案（AC15）。空档位（老数据无快照）不显示。 */
const stageText = (s?: string | null) => (s ? String(t(`afterSale.stage.${s}`)) : "");

/** 「整单退款，将同时退回用户的券「满 30 减 5」、200 积分，并收回赠送的 50 积分」 */
function impactText(r: Row): string {
  const i = r.as.impact;
  if (!i) return "";
  const parts: string[] = [];
  if (i.couponTitle) parts.push(String(t("afterSale.impactCoupon", { name: i.couponTitle })));
  if (i.pointsReturn || i.pointsRevoke) parts.push(String(t("afterSale.historicalPoints")));
  const head = parts.length ? String(t("afterSale.impactReturn", { what: parts.join("、") })) : "";
  return head;
}

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
const tab = ref<"pending" | "all">("pending");
const pending = computed(() => list.value.filter((r) =>
  ["APPLIED", "WAIT_RECEIPT", "RETURNING"].includes(r.as.status)));
const visible = computed(() => tab.value === "pending" ? pending.value : list.value);
/** 正在驳回的单号 —— 展开理由输入框 */
const rejecting = ref("");
const reason = ref("");
const restock = ref<Record<string, boolean>>({});
const busy = ref(false);

/**
 * 老数据（以及后端补字段之前的数据）可能没有 `afterSale`。
 * 缺就按「待处理的仅退款」看待 —— 宁可多给商家一次处理机会，也不能让单子卡在页面上没有任何按钮。
 */
const asStatus = (r: Row) => r.as.status;
const asType = (r: Row) => r.as.type;

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);

async function load() {
  try {
    rejecting.value = "";
    reason.value = "";
    const [afterSales, orders] = await Promise.all([
      api.mAfterSaleList(),
      api.mOrderList({ size: 50 }),
    ]);
    const byNo = new Map(orders.records.map((o) => [o.orderNo, o]));
    list.value = afterSales.map((as) => ({ as, order: byNo.get(as.subOrderNo) }));
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

async function agree(r: Row) {
  if (busy.value) return;
  // 小程序里顺带攒订阅额度（TDD-微信订阅消息优先 AC8）
  askMchSubscribe();
  busy.value = true;
  try {
    await api.mApproveAfterSale(r.as.afterSaleNo, "", undefined, "CASH");
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
  await runRefund(() => api.mConfirmReturn(r.as.afterSaleNo, restock.value[r.as.afterSaleNo] === true));
}

/**
 * 在途仅退款：签收后由商家确认；未备货的申请已在后端直接进入退款，不会出现在此处。
 * 不等签收也可以点 —— 商家判断不用等时自己决定。
 */
async function confirmRefund(r: Row) {
  await runRefund(() => api.mConfirmRefund(r.as.afterSaleNo));
}

async function runRefund(call: () => Promise<unknown>) {
  if (busy.value) return;
  askMchSubscribe();
  busy.value = true;
  try {
    await call();
    uni.showToast({ title: t("afterSale.accepted"), icon: "none" });
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
  askMchSubscribe();
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

    <view class="tabs sh-row">
      <text class="sh-chip" :class="{ 'sh-chip--warning': tab === 'pending' }" @tap="tab = 'pending'">{{ $t("afterSale.pendingTab") }} ({{ pending.length }})</text>
      <text class="sh-chip" :class="{ 'sh-chip--warning': tab === 'all' }" @tap="tab = 'all'">{{ $t("afterSale.allTab") }}</text>
    </view>

    <sh-empty v-if="!visible.length" :pending="!loaded" :failed="failed" @retry="load" :text='$t("afterSale.empty")'></sh-empty>

    <view v-for="r in visible" :key="r.as.afterSaleNo" class="sh-card sh-mt-sm">
      <view class="item__head sh-row sh-row--between sh-row--baseline">
        <text class="txt-body">{{ r.order?.buyerNickname || "—" }}</text>
        <text class="sh-muted">{{ datetime(r.as.updatedAt) }}</text>
      </view>
      <view class="item__tags sh-row">
        <text class="sh-chip">{{ $t(`afterSale.type${asType(r)}`) }}</text>
        <!-- 履约进度实况（AC15）：商家据此判断货还拦不拦得住、该不该全额退 -->
        <text v-if="r.as.fulfillStage" class="sh-chip sh-chip--warning">{{ stageText(r.as.fulfillStage) }}</text>
        <text class="txt-caption sh-muted sh-num item__no">{{ r.as.subOrderNo }}</text>
      </view>
      <text v-if="r.as.reason" class="sh-muted reason">
        {{ $t("afterSale.buyerReason") }}{{ r.as.reason }}
      </text>

      <view class="goods sh-wrap">
        <text v-for="it in r.order?.items || []" :key="it.skuNo" class="sh-chip">
          {{ it.title }} ×{{ it.qty }}
        </text>
      </view>

      <!-- 金额取**售后单自己的** refundMinor，不是订单的应付：
           一张子订单可以只退其中一件，也可以先后发起多次。
           也因此不再挂 `v-if="r.order"` —— 订单没匹配上时，
           退款金额这一行照样该出现，那是这一页最要紧的一个数。 -->
      <view class="item__amount sh-row sh-row--between sh-row--baseline">
        <text class="sh-muted">{{ $t("afterSale.refundAmount") }}</text>
        <text class="txt-price sh-num is-danger">
          {{ money(r.as.approvedMinor ?? r.as.refundMinor, r.order?.amount.currency) }}
        </text>
      </view>

      <!--
        **同意之前说清会一并退回什么**（待办设计 P2c）。整单最后一笔才有；
        券若是本店发的，退回等于少收一次核销 —— 要在点下去之前看见。
        ⚠️ 它是**独立的一行**，不在下面那条 v-if 链里：此前它是链头，一出现就把同意/驳回/确认收货整块吞掉，
        商家只能干等超时自动同意。
      -->
      <text class="txt-caption sh-muted blk">{{ $t("afterSale.fullCash") }}</text>
      <text v-if="r.as.refundExecution" class="txt-caption blk">{{ $t(refundProgressKey(r.as)) }}</text>
      <text v-if="r.order && hasHistoricalPoints(r.order)" class="txt-caption blk">{{ $t("afterSale.historicalPoints") }}</text>
      <text v-if="impactText(r)" class="txt-caption sh-muted impact blk">{{ impactText(r) }}</text>

      <template v-if="rejecting === r.as.afterSaleNo">
        <textarea
          v-model="reason"
          class="field__area"
          :placeholder="$t('afterSale.reasonPh')"
          maxlength="80"
        />
        <view class="btns sh-row">
          <text class="sh-btn sh-btn--sm sh-btn--muted txt-strong btn" @tap="rejecting = ''">{{ $t("common.cancel") }}</text>
          <text class="sh-btn sh-btn--sm sh-btn--danger-solid txt-strong btn" @tap="reject(r)">{{ $t("afterSale.confirmReject") }}</text>
        </view>
      </template>

      <view v-else-if="asStatus(r) === 'APPLIED'" class="btns sh-row">
        <text class="sh-btn sh-btn--sm sh-btn--muted txt-strong btn" @tap="rejecting = r.as.afterSaleNo">
          {{ $t("afterSale.reject") }}
        </text>
        <text class="sh-btn sh-btn--sm txt-strong btn" @tap="agree(r)">
          {{ asType(r) === "RETURN_REFUND" ? $t("afterSale.agreeReturn") : $t("afterSale.agree") }}
        </text>
      </view>

      <!-- 货在途已同意：签收后由商家确认退款（不自动退）；不等签收也可以直接确认 -->
      <view v-else-if="asStatus(r) === 'WAIT_RECEIPT'" class="btns sh-row">
        <text class="sh-muted sh-fill">{{ $t("afterSale.waitReceiptHint") }}</text>
        <text class="sh-btn sh-btn--sm txt-strong btn btn--auto" @tap="confirmRefund(r)">
          {{ $t("afterSale.confirmRefund") }}
        </text>
      </view>

      <!-- 2026-10-14 起退货两段是独立状态：WAIT_RETURN（等寄回）/ RETURNING（已寄回待确认） -->
      <view v-else-if="asStatus(r) === 'WAIT_RETURN'" class="waiting">
        <text class="sh-muted">{{ $t("afterSale.waitReturn") }}</text>
      </view>

      <view v-else-if="asStatus(r) === 'REFUNDING'" class="waiting">
        <text class="sh-muted">{{ $t("afterSale.refundingHint") }}</text>
      </view>

      <view v-else-if="asStatus(r) === 'RETURNING'">
        <sh-option class="restock-option sh-mt-sm" :selected="restock[r.as.afterSaleNo] === true" :disabled="busy" @tap="restock[r.as.afterSaleNo] = !restock[r.as.afterSaleNo]">
          <text>{{ restock[r.as.afterSaleNo] ? '☑' : '☐' }} {{ $t("afterSale.restock") }}</text>
        </sh-option>
        <view class="btns sh-row">
        <view class="express sh-fill">
          <text class="sh-muted">{{ $t("afterSale.returnExpress") }}</text>
          <text class="sh-num">{{ r.as.returnExpressNo }}</text>
        </view>
        <text class="sh-btn sh-btn--sm txt-strong btn btn--auto" @tap="confirmReturn(r)">
          {{ $t("afterSale.confirmReceived") }}
        </text>
        </view>
      </view>

      <!-- 已退款也要说一句。此前这一支没有分支，卡片下方是**一片空白** ——
           而空白与「还没轮到我处理」长得一模一样。
           极速退单独说：那类单商家可见不可拒，不说清楚会以为是自己漏点了。 -->
      <view v-else-if="asStatus(r) === 'REFUNDED'" class="waiting">
        <text class="sh-muted">
          {{ r.as.refundPoints ? $t("afterSale.donePointsHint", { n: r.as.refundPoints }) : $t("afterSale.doneHint") }}
        </text>
      </view>

      <view v-else-if="asStatus(r) === 'REJECTED'" class="waiting">
        <text class="sh-muted">{{ $t("afterSale.rejectedHint") }}</text>
      </view>

      <view v-else-if="asStatus(r) === 'CLOSED'" class="waiting">
        <text class="sh-muted">{{ $t("afterSale.closedHint") }}</text>
      </view>

      <view v-else-if="asStatus(r) === 'ARBITRATING'" class="waiting">
        <text class="sh-chip sh-chip--warning">{{ $t("afterSale.disputed") }}</text>
        <text class="sh-muted sh-mt-xs blk">{{ $t("afterSale.disputedHint") }}</text>
      </view>
    </view>

    <text v-if="visible.length" class="tip sh-hint">{{ $t("afterSale.hint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.tabs {
  gap: 16rpx;
  margin-top: 20rpx;
}
.item__tags {
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

.item__no {
  display: block;
  margin-top: 4rpx;
}
.goods {
  margin: 20rpx 0;
}
.item__amount {
  padding: 20rpx 0;
}
/*
 * `align-items: center` 不是排版偏好，是**药丸按钮的前提**：
 * 默认的 stretch 会把按钮拉到与同行最高的那格一样高（运单号那格是两行），
 * 而 `border-radius: 9999px` 在被拉高的盒子上不再是药丸，是椭圆。
 */
.btns {
  margin-top: 20rpx;
}
.btn {
  flex: 1;
  text-align: center;
  padding: 24rpx 0;
}
/*
 * 与运单号同行的那个按钮**按文字宽度收**，不跟着 `flex: 1` 平分。
 * 平分的结果是两边都只有半屏：按钮把「确认收到退货并退款」九个字撑满，
 * 运单号那边被挤到剩不下一行，20 位的单号折成两截。
 * 一行里一个是可变信息、一个是固定文案时，该收的是文案那一侧。
 */
.btn--auto {
  flex: 0 0 auto;
  padding-inline: 24rpx;
}

.tip {
  margin: 32rpx 8rpx;
}
.impact {
  display: block;
  margin-top: 12rpx;
}
.approve {
  border-top: 2rpx solid var(--sh-line);
  padding-top: 20rpx;
}
.amount-in {
  gap: 4rpx;
}
/* 金额输入框右对齐、窄一点 —— 它只放一个数 */
.amount-field {
  width: 180rpx;
  text-align: end;
}
.modes {
  gap: 16rpx;
}
</style>
