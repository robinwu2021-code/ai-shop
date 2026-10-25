<script setup lang="ts">
import ShPointsEarning from "@ai-shop/ui/components/sh-points-earning.vue";
import type { OrderPointsEarning } from "@shared/types";
// 订单详情 + 履约动作（B-11.4.2/4.3/4.7）。
//
// 一个页面只给一个主动作：快递单给「填运单号发货」，自送单给「已送达」，
// 自提单什么都不给 —— 自提的动作在履约台（核销），不在这里。
import { computed, ref, watch } from "vue";
import { onLoad, onShow, onPullDownRefresh } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { ROUTES } from "@/shared/nav";
import { askMchSubscribe } from "@/shared/mch-subscribe";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { datetime } from "@shared/utils/datetime";
import { buildOrderCopyText } from "@/utils/order-copy";
import { FULFILLMENT } from "@shared/utils/constants";
import { expressOrderStatusKey, traceStatusKey } from "@shared/strategies/trace-step";
import { fulfillmentAllowed } from "@shared/strategies/order-phase1";
import { EXPRESS_COMPANIES } from "@shared/utils/express-companies";
import { confirm } from "@ai-shop/ui/prompt";
import type { AfterSale, Order, ShipmentTrace } from "@shared/types";
import type { ExpressPickup, LogisticsUpdateRecord, MerchantInventoryTrace } from "@/api/contract";

const { t } = useI18n();
const merchant = useMerchantStore();

const order = ref<Order | null>(null);
const earningRows = ref<OrderPointsEarning[]>([]);
const earningFailed = ref(false);
let earningEpoch = 0;
async function loadEarning() {
  const no = order.value?.orderNo;
  const store = merchant.storeNo;
  const token = ++earningEpoch;
  earningRows.value = []; earningFailed.value = false;
  if (!no) return;
  try {
    const rows = await api.mOrderPointsEarning(no);
    if (token === earningEpoch && store === merchant.storeNo && order.value?.orderNo === no) earningRows.value = rows;
  } catch { if (token === earningEpoch && store === merchant.storeNo && order.value?.orderNo === no) earningFailed.value = true; }
}

watch(() => merchant.storeNo, () => { ++earningEpoch; earningRows.value = []; earningFailed.value = false; });

const inventoryTrace = ref<MerchantInventoryTrace | null>(null);
const activeAfterSale = ref<AfterSale | null>(null);
type FulfillmentOptions = Awaited<ReturnType<typeof api.mFulfillmentOptions>>;
const fulfillmentOptions = ref<FulfillmentOptions | null>(null);
const changeReason = ref("");
const alternative = computed(() => fulfillmentOptions.value?.options.find(
  (option) => option.fulfillment !== order.value?.fulfillment));
const shipSync = ref<Order["logisticsSync"]>(null);
const currentSync = computed(() => order.value?.logisticsSync ?? shipSync.value);
/**
 * 物流轨迹那份（TDD-物流模块 B2）：比详情里的多承运商、单号、到柜。
 * 拿不到（老后端 / 网络）就用详情里带的那份 —— 两份读的是同一张运单，只是字段多少不同
 */
const fullTrace = ref<ShipmentTrace | null>(null);
const updateRecords = ref<LogisticsUpdateRecord[]>([]);
const updateSource = (source: string) => String(t(source === "wx" || source === "wx-follow"
  ? "order.updateSourceWx" : source === "kuaidi100" ? "order.updateSourceK100"
    : source === "yto" ? "order.updateSourceYto" : "order.updateSourceOther"));
const updateMode = (mode: string) => String(t(mode === "PUSH" ? "order.updateModePush" : "order.updateModeQuery"));
const updateOutcome = (outcome: string) => String(t(outcome === "ADVANCED" ? "order.updateAdvanced"
  : outcome === "NODES_ADDED" ? "order.updateNodesAdded"
    : outcome === "UNCHANGED" ? "order.updateUnchanged"
      : outcome === "NO_RESULT" ? "order.updateNoResult"
        : outcome === "THROTTLED" ? "order.updateThrottled" : "order.updateFailed"));
const shownTrace = computed(() => fullTrace.value ?? order.value?.trace ?? null);
const signedBeforeOrder = computed(() => order.value?.status === "FULFILLING"
  && currentSync.value?.status === "DELIVERED"
  && !!shownTrace.value?.signedAt && !!order.value?.createdAt
  && shownTrace.value.signedAt < order.value.createdAt);
/** 物流详情弹框。主页面只留一行摘要，这一行点开才是地图与全部节点 */
const traceOpen = ref(false);
/** 摘要行上的状态词。与步骤条共用 shared 里那一份判定，别在两处各写一个三分支 */
const traceStep = computed(() => {
  const current = order.value;
  if (current) {
    const projected = expressOrderStatusKey(current);
    if (projected) return projected;
    if (current.status === "COMPLETED" || current.status === "CANCELLED"
        || current.status === "REFUNDED") return `order.status${current.status}`;
  }
  const status = currentSync.value?.status ?? shownTrace.value?.status;
  if (status === "PENDING") return "order.syncPending";
  return status
    ? traceStatusKey({ status: status as NonNullable<Order["trace"]>["status"], nodes: [] })
    : "trace.awaitingPickup";
});
/** 摘要行上的最新一条。节点按时间倒序，第一条就是最新 */
const traceLatest = computed(() => shownTrace.value?.nodes?.[0]?.text ?? "");
/** 承运商显示名。拿不到译名就显示原码 —— 显示「STO」好过显示空白 */
const carrierLabel = computed(() => {
  const code = shownTrace.value?.carrier || order.value?.expressCompany || "";
  return carriers.find((c) => c.code === code)?.name || code;
});

/** 已取消 / 已退款的单：券与积分的去向（后端只在这两个状态、只在详情给） */
const returnedLines = computed(() => {
  const r = order.value?.returned;
  if (!r) return [] as string[];
  const out: string[] = [];
  if (r.couponTitle) out.push(String(t("order.returnedCoupon", { name: r.couponTitle })));
  if (r.pointsReturned) out.push(String(t("order.returnedPoints", { n: r.pointsReturned })));
  if (r.pointsClawedBack) out.push(String(t("order.returnedClawback", { n: r.pointsClawedBack })));
  return out;
});
const expressNo = ref("");
const correctingWaybill = ref(false);
const correctedNo = ref("");
const correctedCarrierIdx = ref(-1);
const correctionReason = ref("");
const canCorrectWaybill = computed(() => order.value?.fulfillment === FULFILLMENT.EXPRESS
  && order.value.status === "FULFILLING" && !!order.value.expressNo && merchant.can("biz:ship")
  && !activeAfterSale.value);
/**
 * 选中的快递公司下标。**默认 -1（未选）**，不默认第一家 ——
 * 默认一家的话，商家不点也「选」了顺丰，发出去的一半是错的快递公司，
 * 而买家点「查看物流」查无此单。宁可强制他选一次。
 */
const carrierIdx = ref(-1);
const carriers = EXPRESS_COMPANIES;
const busy = ref(false);
const refreshingTrace = ref(false);

async function refreshTrace() {
  const current = order.value;
  if (!current?.expressNo || current.status !== "FULFILLING" || refreshingTrace.value || busy.value) return;
  refreshingTrace.value = true;
  busy.value = true;
  try {
    const trace = await api.mOrderTrace(current.orderNo, true);
    const latest = await api.mOrderDetail(current.orderNo);
    order.value = latest;
    fullTrace.value = trace;
    updateRecords.value = await api.mOrderUpdates(current.orderNo).catch(() => []);
    failed.value = false;
    const key = latest.status !== current.status ? "order.traceOrderUpdated"
      : latest.logisticsSync?.status === "DELIVERED" && latest.status === "FULFILLING"
        ? "order.traceAwaitingProof"
      : latest.logisticsSync?.status !== current.logisticsSync?.status ? "order.traceWaybillUpdated"
      : trace?.refreshable ? "order.traceRefreshUnchanged" : "order.traceRefreshUnavailable";
    uni.showToast({ title: String(t(key)), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    refreshingTrace.value = false;
    busy.value = false;
  }
}

/** 快递单且已付款 → 该发货 */
/*
 * **状态对 + 有权限，两个都要**。原先只判状态：客服有 `biz:order:view` 能进详情页，
 * 于是「发货」按钮画给了他，点下去 70006 —— 而他既不该发货，也没有任何办法拿到这个权限。
 */
const canShip = computed(
  () => !activeAfterSale.value && order.value?.fulfillment === FULFILLMENT.EXPRESS
    && fulfillmentAllowed(order.value, merchant.can("biz:ship")),
);
/** 自送单且已付款 → 该送。同样两个都要判（见 {@link canShip}） */
const canDeliver = computed(
  () => !activeAfterSale.value && order.value?.fulfillment === FULFILLMENT.DELIVERY
    && fulfillmentAllowed(order.value, merchant.can("biz:ship")),
);

/**
 * 待发货单（快递 / 自送）→ 可备货。备货只记时刻、不改状态，是售后判进度的分水岭：
 * 生鲜备货后非质量退款从「自由退」变成「商家确认」（售后规则 §2.1）。
 * 用 `biz:ship` 权限（备货是发货前一步）。已备货仍显示这张卡（给「已备货」状态）。
 */
const canPrepare = computed(
  () => !activeAfterSale.value && fulfillmentAllowed(order.value, merchant.can("biz:ship"))
    && (order.value?.fulfillment === FULFILLMENT.EXPRESS
      || order.value?.fulfillment === FULFILLMENT.DELIVERY),
);

async function prepare() {
  if (!order.value || !canPrepare.value || busy.value || order.value.preparedAt) return;
  busy.value = true;
  try {
    await api.mPrepare(order.value.orderNo);
    await load(currentNo.value);
    uni.showToast({ title: t("order.prepared"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

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

/** 这次没取到。**与「这个东西不存在」是两件事** —— 预填失败留下的是一张空表单，
 *  照着它填完保存，存出来的是一条新的，原来那条还在 */
const failed = ref(false);
/** 重试要把单号带回去 —— `@retry` 不带参数 */
const currentNo = ref("");

async function confirmOffline() {
  if (!order.value || busy.value) return;
  // 小程序里顺带攒「新订单 / 售后 / 评价」的订阅额度（TDD-微信订阅消息优先 AC8）；App 里什么都不发生
  askMchSubscribe();
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
  currentNo.value = orderNo;
  try {
    await merchant.ensureScope();
    order.value = await api.mOrderDetail(orderNo);
    void loadEarning();
    inventoryTrace.value = await api.mOrderInventoryTrace(orderNo).catch(() => null);
    activeAfterSale.value = merchant.can("biz:aftersale")
      ? await api.mActiveAfterSaleForOrder(orderNo).catch(() => null) : null;
    fulfillmentOptions.value = merchant.can("biz:ship")
      ? await api.mFulfillmentOptions(orderNo).catch(() => null) : null;
    failed.value = false;
    fullTrace.value = null;
    inventoryTrace.value = null;
    updateRecords.value = [];
    void loadPickup();
    if (order.value?.fulfillment === FULFILLMENT.EXPRESS && order.value.status === "PAID") void loadShipDefaults();
  } catch {
    // 此前这句是裸的：拉挂了是一个没人接的 Promise 拒绝，
    // 界面上一个字都不说，整页停在空白
    failed.value = true;
  }
}

async function changeFulfillment() {
  if (!order.value || !alternative.value?.allowed || !fulfillmentOptions.value
      || !changeReason.value.trim() || busy.value) {
    if (!changeReason.value.trim()) uni.showToast({ title: t("order.changeFulfillmentReasonRequired"), icon: "none" });
    return;
  }
  const yes = await confirm({
    title: String(t("order.changeFulfillmentConfirm")),
    confirmText: String(t("order.changeFulfillmentAction")),
    cancelText: String(t("common.cancel")),
  });
  if (!yes) return;
  busy.value = true;
  try {
    await api.mChangeFulfillment(order.value.orderNo, {
      targetFulfillment: alternative.value.fulfillment,
      reason: changeReason.value.trim(),
      expectedVersion: fulfillmentOptions.value.version,
    });
    changeReason.value = "";
    await load(currentNo.value);
    uni.showToast({ title: t("order.changeFulfillmentDone"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    await load(currentNo.value);
  } finally {
    busy.value = false;
  }
}

function openActiveAfterSale() {
  if (!activeAfterSale.value) return;
  uni.navigateTo({ url: `${ROUTES.afterSale}?afterSaleNo=${encodeURIComponent(activeAfterSale.value.afterSaleNo)}&store=${encodeURIComponent(merchant.storeNo || "")}` });
}

async function loadTrace(orderNo: string) {
  try {
    fullTrace.value = await api.mOrderTrace(orderNo);
  } catch {
    fullTrace.value = null;
  }
  updateRecords.value = await api.mOrderUpdates(orderNo).catch(() => []);
}

/**
 * 复制订单信息发给供应商（§5）。纯端上拼装，数据就是详情已有的，不另查。
 * 供应商要的是拣货发货要素，**不含售价** —— 拼装口径见 buildOrderCopyText。
 */
function copyForSupplier() {
  if (!order.value) return;
  uni.setClipboardData({
    data: buildOrderCopyText(order.value, t),
    success: () => uni.showToast({ title: t("order.copied"), icon: "none" }),
  });
}

async function ship() {
  if (!order.value || !canShip.value || !expressNo.value.trim() || busy.value) return;
  const carrier = carriers[carrierIdx.value];
  if (!carrier) {
    uni.showToast({ title: t("order.pickCarrier"), icon: "none" });
    return;
  }
  busy.value = true;
  try {
    const check = await api.mShipPrecheck(order.value.orderNo, expressNo.value.trim(), carrier.code);
    if (check.verdict === "INVALID") throw new Error(check.reason || String(t("order.invalidWaybill")));
    let confirmed = false;
    if (check.confirmationRequired) {
      confirmed = await confirm({
        title: String(t("order.waybillHistoryTitle")),
        hint: check.reason || String(t("order.waybillHistoryHint")),
        confirmText: String(t("order.confirmWaybill")),
      });
      if (!confirmed) return;
    }
    askMchSubscribe();
    const committed = await api.mShip(order.value.orderNo, expressNo.value.trim(), carrier.code, confirmed);
    shipSync.value = committed.logisticsSync;
    order.value = committed;
    await load(currentNo.value);
    uni.showToast({ title: t(shipSync.value?.pending ? "order.shippedSyncing" : "order.shipped"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function startWaybillCorrection() {
  correctedNo.value = order.value?.expressNo ?? "";
  correctedCarrierIdx.value = carriers.findIndex((c) => c.code === order.value?.expressCompany);
  correctionReason.value = "";
  correctingWaybill.value = true;
}

async function correctWaybill() {
  if (!order.value || !canCorrectWaybill.value || busy.value) return;
  if (correctedCarrierIdx.value < 0 || !correctedNo.value.trim() || !correctionReason.value.trim()) {
    uni.showToast({ title: String(t("order.waybillIncomplete")), icon: "none" });
    return;
  }
  const carrier = carriers[correctedCarrierIdx.value];
  if (!carrier || (carrier.code === order.value.expressCompany
      && correctedNo.value.trim() === order.value.expressNo)) {
    uni.showToast({ title: String(t("order.waybillUnchanged")), icon: "none" });
    return;
  }
  busy.value = true;
  try {
    const corrected = await api.mCorrectWaybill(order.value.orderNo, correctedNo.value.trim(), carrier.code,
      correctionReason.value.trim());
    shipSync.value = corrected.logisticsSync;
    order.value = corrected;
    correctingWaybill.value = false;
    await load(currentNo.value);
    uni.showToast({ title: String(t(shipSync.value?.pending ? "order.shippedSyncing" : "order.waybillCorrected")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function delivered() {
  if (!order.value || !canDeliver.value || busy.value) return;
  askMchSubscribe();
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

// ---------------------------------------------------------------- 叫快递上门（TDD-快递100商家寄件）

/** 最近一张取件单。叫过就显示它的进度，不再给表单 —— 同一单同时只能有一张进行中的 */
const pickup = ref<ExpressPickup | null>(null);
const PICKUP_OPEN = ["CREATED", "ACCEPTED", "PICKED", "DONE"];
/** 取消与失败之后可以重新叫：这时表单回来，上一张的原因留一行 */
const pickupActive = computed(() => !!pickup.value && PICKUP_OPEN.includes(pickup.value.status));
const canCancelPickup = computed(
  () => pickup.value?.status === "CREATED" || pickup.value?.status === "ACCEPTED",
);

/**
 * 买家付的运费与叫快递的实际运费之差（TDD-快递100商家寄件 §8 AC18）。取件后才有实际运费；测试单不算（运费是假的）。
 * subsidy=true：买家付的不够，本店补贴；否则是结余。相等时不显示这一行。
 */
const freightGap = computed(() => {
  const p = pickup.value;
  const o = order.value;
  if (!p || !o || p.freightMinor == null || p.sandbox) return null;
  const diff = o.amount.freightMinor - p.freightMinor;
  if (diff === 0) return null;
  return { subsidy: diff < 0, amount: Math.abs(diff) };
});

function openShipSettings() {
  uni.navigateTo({ url: ROUTES.shipSettings });
}

/**
 * 带出发货设置的默认快递公司。**拉不到不挡发货** —— 那一页是方便，不是前提；
 * 商家手动选一次照样能发。只在他还没动过的时候填，不覆盖手上的输入。
 */
async function loadShipDefaults() {
  // 深链进来时权限可能还没加载：can() 在那时 fail-closed 返回 false，而这里不会再重试
  await merchant.ensureScope();
  if (!merchant.can("biz:store")) return;
  try {
    const s = await api.mShipSetting(merchant.storeNo || "default");
    if (carrierIdx.value < 0 && s.carrier) carrierIdx.value = carriers.findIndex((c) => c.code === s.carrier);
  } catch {
    // 见上：不挡发货
  }
}

async function loadPickup() {
  if (order.value?.fulfillment !== FULFILLMENT.EXPRESS || !merchant.can("biz:ship")) return;
  try {
    pickup.value = await api.mExpressPickup(order.value.orderNo);
  } catch {
    // 取件单拉不到不影响手填发货：那一块照常可用
    pickup.value = null;
  }
}

async function cancelPickup() {
  if (!order.value || busy.value) return;
  const ok = await confirm({
    title: String(t("order.expressCancelAsk")),
    confirmText: String(t("order.expressCancel")),
    cancelText: String(t("order.expressKeep")),
    danger: true,
  });
  if (!ok) return;
  busy.value = true;
  try {
    pickup.value = await api.mCancelExpress(order.value.orderNo);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

onLoad(async (q) => {
  const no = q?.orderNo;
  if (!no) return;
  /*
   * 深链（微信通知 / App 推送，后端带 src=notify）：订单详情按**当前门店**过滤，
   * 而点通知的人当前选的店多半不是这单的店 —— 不对齐门店会跨店 NOT_FOUND、显示「加载失败」。
   *
   * 链接带了 store（后端已知）就直接切过去；**没带 store（旧通知 / 兜底）就先清当前门店、
   * 不限门店查到这单，再从订单里取它的店切过去** —— 这就是「链接没有门店信息时先查询获得」。
   *
   * 从订单列表正常点进来（没有 src=notify）当前门店已经是对的，不切，省掉两次 loadScope。
   */
  if (q?.src === "notify") {
    if (q?.store) {
      await merchant.switchStore(q.store as string);
      await load(no as string);
    } else {
      await merchant.switchStore("");          // 清当前店 → 请求不带 X-Store-No，跨店也查得到
      await load(no as string);
      const resolved = order.value?.store?.storeNo;
      if (resolved) await merchant.switchStore(resolved);   // 对齐到这单的店，返回列表才对
    }
  } else {
    void load(no as string);
  }
});

/*
 * 返回这一页时重新拉：订单状态可能在别处变了（发货、收款、售后）。
 * onLoad 只在首次进页面触发，从子页（发货设置 / 物流）返回不会重拉 —— 那时靠 onShow。
 * 首次 show 紧跟 onLoad 之后，跳过它那一次，避免重复拉。
 */
let shownOnce = false;
onShow(() => {
  if (shownOnce && currentNo.value) void load(currentNo.value);
  shownOnce = true;
});

/** 下拉刷新：页面一直开着时，状态在别端变了也能主动拉到最新。 */
onPullDownRefresh(async () => {
  if (currentNo.value) await load(currentNo.value);
  uni.stopPullDownRefresh();
});
function openPrintCenter(){if(order.value)uni.navigateTo({url:ROUTES.printCenter+'?orderNo='+encodeURIComponent(order.value.payOrderNo||order.value.orderNo)});}
</script>

<template>
  <!-- 正常入口（订单列表）已判过一次，这里是给刷新与深链兜底 -->
  <sh-scaffold title-key="order.detail" :denied="!merchant.can('biz:order:view')"
    :failed="failed"
    @retry="() => load(currentNo)"
  >
    <template v-if="order">
      <ShPointsEarning :rows="earningRows" :failed="earningFailed" @retry="loadEarning" />
      <view v-if="merchant.can('biz:print:read')" class="sh-card sh-cell"
        @tap="openPrintCenter">
        <text>{{ $t('printCenter.orderEntry') }}</text><sh-go :text="String($t('printCenter.viewTasks'))" />
      </view>
      <view v-if="activeAfterSale" class="sh-card sh-mb-sm sh-row sh-row--between" @tap="openActiveAfterSale">
        <view>
          <text class="txt-strong is-danger">{{ $t('order.tabAfterSale') }}</text>
          <text class="txt-caption sh-muted">{{ activeAfterSale.afterSaleNo }}</text>
        </view>
        <sh-go :text="String($t('message.view'))"></sh-go>
      </view>

      <!--
        物流一栏，**放在最顶上、独立成卡**（淘宝订单详情那种形态）。
        店主打开这一页最常问的就是「货到哪了」，它不该排在订单号、下单时间后面；
        而混在订单信息那堆键值行里，它读起来像又一条属性，不像一件在变化的事。
        整卡可点 → 弹框里是全过程。
      -->
      <view v-if="shownTrace || currentSync" class="sh-card trace-card" @tap="traceOpen = true; loadTrace(currentNo)">
        <view class="sh-row sh-row--between">
          <text class="txt-strong trace-card__st">{{ $t(traceStep) }}</text>
          <sh-go :text="String($t('order.traceView'))"></sh-go>
        </view>
        <!-- 最新一条**最多两行**：一行截得太狠（「预计10月10日到达…」停在「两地」），
             两行能把「哪天到哪儿」说完；再多就该点进去看了 -->
        <text v-if="traceLatest" class="txt-caption sh-muted trace-card__last">{{ traceLatest }}</text>
        <text class="txt-caption sh-muted trace-card__no">
          {{ carrierLabel }} {{ order.expressNo }}
        </text>
      </view>

      <view v-if="inventoryTrace?.reservations.length" class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t('order.inventoryTrace') }}</text>
        <view v-for="reservation in inventoryTrace.reservations" :key="`${reservation.ownerId}:${reservation.reservationId}`"
          class="sh-mt-sm">
          <text class="txt-caption sh-muted">{{ $t('order.inventoryReservation') }} {{ reservation.status }} · V{{ reservation.lifecycleVersion ?? 1 }}</text>
          <view v-for="line in reservation.lines" :key="`${reservation.reservationId}:${line.lineNo}`" class="line">
            <view class="sh-row sh-row--between"><text class="sh-muted">{{ $t('order.inventorySource') }}</text><text>{{ line.locationId }}</text></view>
            <view class="sh-row sh-row--between"><text class="sh-muted">{{ $t('order.inventoryReserved') }}</text><text>{{ line.reservedQty }}</text></view>
            <view class="sh-row sh-row--between"><text class="sh-muted">{{ $t('order.inventoryIssued') }}</text><text>{{ line.issuedQty }} · {{ line.outboundNo || '—' }}</text></view>
            <view v-for="entry in line.issueLedger" :key="entry.id" class="txt-caption sh-muted">
              {{ datetime(new Date(entry.occurredAt).getTime()) }} · {{ entry.balanceBefore }} → {{ entry.balanceAfter }} · {{ entry.operator || entry.createdBy || '—' }}
            </view>
            <view v-for="receipt in line.returns" :key="receipt.receiptNo" class="txt-caption sh-muted">
              {{ $t('order.inventoryReturn') }} {{ receipt.qty }} · {{ receipt.quality }} · {{ receipt.locationId }} · {{ receipt.createdBy || '—' }}
            </view>
          </view>
        </view>
      </view>
      <view v-if="inventoryTrace?.syncEvents.length" class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t('order.inventorySync') }}</text>
        <view v-for="event in inventoryTrace.syncEvents" :key="`${event.eventType}:${event.aggregateId}`" class="line">
          <view class="sh-row sh-row--between"><text>{{ event.eventType }}</text><text>{{ $t(`order.inventorySyncStatusValue.${event.status}`) }}</text></view>
          <view class="txt-caption sh-muted">{{ event.aggregateId }} · {{ $t(`order.inventorySyncSourceValue.${event.source}`) }} · {{ $t(`order.inventorySyncModeValue.${event.outboxMode}`) }}</view>
        </view>
      </view>

      <view class="sh-card">
        <view class="line sh-row sh-row--between">
          <text v-if="order.documentNo" class="sh-muted">{{ $t("order.no") }}</text>
          <view class="sh-row no-row__v">
            <text v-if="order.documentNo" class="sh-num">{{ order.documentNo }}</text>
            <!-- 复制订单信息发给供应商（§5）。**不占一整行**：它是顺手动作，
                 而整宽按钮会把它读成这一页的主动作，挤掉真正的主动作（发货）。
                 贴在订单号右边：复制的正是这张卡上的东西 -->
            <text class="sh-link no-row__copy" @tap.stop="copyForSupplier">
              {{ $t("order.copyToSupplier") }}
            </text>
          </view>
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

      <view v-if="alternative && ['EXPRESS', 'MERCHANT_DELIVERY'].includes(order.fulfillment)"
        class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t('order.changeFulfillmentTitle') }}</text>
        <text class="txt-caption sh-muted blk">
          {{ $t('order.changeFulfillmentTarget') }}{{ $t(`fulfillmentLabel.${alternative.fulfillment}`) }}
        </text>
        <text v-if="!alternative.allowed" class="txt-caption sh-muted blk">{{ alternative.reason }}</text>
        <template v-else>
          <textarea v-model="changeReason" class="field__area sh-mt-sm"
            :placeholder="$t('order.changeFulfillmentReasonPh')" maxlength="200" />
          <text class="sh-btn sh-btn--sm sh-mt-sm" @tap="changeFulfillment">
            {{ $t('order.changeFulfillmentAction') }}
          </text>
        </template>
      </view>

      <view class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("order.items") }}</text>
        <view v-for="it in order.items" :key="it.skuNo" class="sh-row item sh-mt-sm">
          <sh-cover class="item__cover" :src="it.cover" :w="200"></sh-cover>
          <view class="sh-fill">
            <text class="txt-body item__title">{{ it.title }}</text>
            <text class="sh-muted">{{ it.spec }} × {{ it.qty }}</text>
          </view>
          <text class="sh-num">{{ money(it.price, order.amount.currency) }}</text>
        </view>
        <!--
          **这单减了什么、谁出的钱**（优惠券全链路梳理 批 3）。此前商家只看得到应付，
          顾客问「怎么少了 5 块」他答不上来，对账时也分不清是自己让的还是平台补的。
        -->
        <view v-for="(d, i) in order.discountLines ?? []" :key="i" class="line sh-row sh-row--between">
          <text class="sh-muted sh-fill">
            {{ $t(d.kind === "COUPON" ? "order.discountCoupon" : "order.discountActivity", { name: d.name }) }}
            <text v-if="d.funder" class="txt-caption"> · {{ $t(d.funder === "PLATFORM" ? "order.funderPlatform" : "order.funderMerchant") }}</text>
          </text>
          <text class="sh-num is-danger">-{{ money(d.amountMinor, order.amount.currency) }}</text>
        </view>
        <!-- 快递单买家付的运费（TDD-快递100商家寄件 §8 AC18）：按平台运费模板收，已含在应付里 -->
        <view v-if="order.fulfillment === FULFILLMENT.EXPRESS" class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.buyerFreight") }}</text>
          <text class="sh-num">{{ money(order.amount.freightMinor, order.amount.currency) }}</text>
        </view>
        <view class="line total sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.amount") }}</text>
          <text class="txt-title sh-num">
            {{ money(order.amount.payableMinor, order.amount.currency) }}
          </text>
        </view>
      </view>

      <!--
        **已取消 / 已退款：券与积分去了哪**（待办设计 P3）。与 C 端同一份数据 ——
        顾客来问「我的券呢」时，商家要能指着这一块回答。有一项说一项，全空整块不显示。
      -->
      <view v-if="returnedLines.length" class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("order.returnedTitle") }}</text>
        <text v-for="(l, i) in returnedLines" :key="i" class="txt-body returned__line">{{ l }}</text>
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

      <!-- 快递订单录入公司与运单；历史已预约寄件单仍显示原预约进度和取消入口。 -->
      <!--
        开始备货（售后规则 §2.1）：只记时刻、不改状态。生鲜备货后非质量退款要商家确认。
        发货卡之上、与发货同一权限 —— 备货是发货前一步。
      -->
      <view v-if="canPrepare" class="sh-card sh-mt-sm prepare-card">
        <view v-if="order.preparedAt" class="sh-row">
          <text class="sh-chip sh-chip--success">{{ $t("order.prepared") }}</text>
        </view>
        <view v-else class="sh-btn sh-btn--muted" @tap="prepare">{{ $t("order.prepare") }}</view>
      </view>

      <view v-if="pickupActive && pickup" class="sh-card sh-mt-sm">
        <view class="sh-row sh-row--between">
          <view class="sh-row">
            <text class="txt-title">{{ pickup.carrierName }}</text>
            <text v-if="pickup.sandbox" class="sh-chip sh-chip--warning">{{ $t("order.expressSandbox") }}</text>
          </view>
          <text class="sh-chip" :class="pickup.status === 'PICKED' || pickup.status === 'DONE' ? 'sh-chip--primary' : ''">
            {{ $t(`order.expressStatus.${pickup.status}`) }}
          </text>
        </view>
        <view v-if="pickup.courierName" class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.expressCourier") }}</text>
          <text class="sh-num">{{ pickup.courierName }} {{ pickup.courierMobile ?? "" }}</text>
        </view>
        <view v-if="pickup.trackingNo" class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.expressNo") }}</text>
          <text class="sh-num">{{ pickup.trackingNo }}</text>
        </view>
        <view class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.expressWeight") }}</text>
          <text class="sh-num">{{ ((pickup.chargedWeightG ?? pickup.weightG) / 1000).toString() }} kg</text>
        </view>
        <view v-if="pickup.freightMinor != null" class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t("order.expressFreight") }}</text>
          <text class="sh-num">{{ money(pickup.freightMinor) }}</text>
        </view>
        <!--
          买家付的 vs 实际运费（§8 AC18）：差额由本店承担或留给本店 —— 实际运费记在本店欠款上，
          买家付的运费随货款结算给本店。不说清这一行，商家只看到「欠了平台一笔运费」，不知道自己亏没亏。
        -->
        <view v-if="freightGap" class="line sh-row sh-row--between">
          <text class="sh-muted">{{ $t(freightGap.subsidy ? "order.freightSubsidy" : "order.freightSurplus") }}</text>
          <text class="sh-num" :class="freightGap.subsidy ? 'is-danger' : ''">{{ money(freightGap.amount) }}</text>
        </view>
        <view v-if="canCancelPickup" class="sh-btn sh-btn--danger sh-mt-sm" @tap="cancelPickup">
          {{ $t("order.expressCancel") }}
        </view>
      </view>
      <view v-else-if="canShip" class="sh-card sh-mt-sm">
        <view class="sh-row sh-row--between">
          <text class="txt-title">{{ $t("order.ship") }}</text>
          <sh-go :text="String($t('home.shipEntry'))" @tap="openShipSettings"></sh-go>
        </view>
          <!-- 快递公司：横排可点的胶囊，选一个。picker 也行，但发货是高频动作，少一次弹层 -->
          <view class="sh-wrap sh-mt-sm">
            <text
              v-for="(c, i) in carriers"
              :key="c.code"
              class="carrier__chip txt-caption"
              :class="{ 'carrier__chip--on': carrierIdx === i }"
              @tap="carrierIdx = i"
            >{{ c.name }}</text>
          </view>
          <input
            maxlength="64"
            v-model="expressNo"
            class="field__input sh-mt-sm"
            :placeholder="$t('order.expressNo')"
          />
          <view
            class="sh-btn sh-mt-sm"
            :class="{ 'sh-btn--muted': !expressNo || carrierIdx < 0 }"
            @tap="ship"
          >
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
        <text v-if="currentSync?.pending || currentSync?.reason" class="txt-caption sh-muted sh-mt-sm">
          {{ currentSync?.pending ? $t("order.syncPending") : "" }}{{ currentSync?.reason ? ` · ${currentSync.reason}` : "" }}
        </text>
        <text v-if="order.status === 'FULFILLING' && currentSync?.status === 'DELIVERED'"
          class="txt-caption sh-muted sh-mt-sm">
          {{ $t(signedBeforeOrder ? "order.signedBeforeOrder" : "order.traceAwaitingProof") }}
        </text>
        <view v-if="order.fulfillment === FULFILLMENT.EXPRESS && order.status === 'FULFILLING'"
          class="sh-btn sh-btn--muted sh-mt-sm" @tap="refreshTrace">
          {{ $t(refreshingTrace ? "order.traceRefreshing" : "order.traceRefresh") }}
        </view>
        <view v-if="canCorrectWaybill && !correctingWaybill" class="sh-btn sh-btn--muted sh-mt-sm" @tap="startWaybillCorrection">
          {{ $t("order.correctWaybill") }}
        </view>
        <view v-if="canCorrectWaybill && correctingWaybill">
          <view class="sh-wrap sh-mt-sm">
            <text v-for="(c, i) in carriers" :key="c.code" class="carrier__chip txt-caption"
              :class="{ 'carrier__chip--on': correctedCarrierIdx === i }" @tap="correctedCarrierIdx = i">{{ c.name }}</text>
          </view>
          <input v-model="correctedNo" maxlength="64" class="field__input sh-mt-sm" :placeholder="$t('order.expressNo')" />
          <input v-model="correctionReason" maxlength="100" class="field__input sh-mt-sm" :placeholder="$t('order.waybillReason')" />
          <view class="sh-btn sh-mt-sm" :class="{ 'sh-btn--muted': !correctedNo.trim() || !correctionReason.trim() || correctedCarrierIdx < 0 || busy }"
            @tap="correctWaybill">{{ $t("order.saveWaybill") }}</view>
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

    <!--
      物流详情弹框。**不折叠**：点进来就是为了看全过程，再给一个「展开全部」等于多一次点击。
      **B 端先不画地图**（show-map=false）：这是产品取舍，不是画不了 ——
      地图已经能正常渲染（2026-10-09 真机确认，此前整块不出现是 `Array.prototype.at` 的锅）。
      取舍的理由：那张图是「城市级示意」，告诉店主的是「在西安」，
      而店主要判断的是「今天到不到」「要不要催」，那几句在节点文字里。
      要再打开只需去掉这个属性，坐标后端一直在给（LogisticsRegistrationFlowTest 盯着）。
    -->
    <sh-sheet
      v-if="shownTrace"
      :visible="traceOpen"
      :title="$t('order.trace')"
      @close="traceOpen = false"
    >
      <sh-trace :trace="shownTrace" :fold-at="shownTrace.nodes.length" :show-map="false"></sh-trace>
      <view class="sh-mt-md">
        <text class="txt-strong">{{ $t("order.updateHistory") }}</text>
        <text v-if="!updateRecords.length" class="txt-caption sh-muted">{{ $t("order.updateHistoryEmpty") }}</text>
        <view v-for="(item, i) in updateRecords" :key="i" class="sh-mt-sm">
          <text class="txt-caption update-record__line">{{ datetime(item.occurredAt) }} · {{ updateSource(item.source) }} · {{ updateMode(item.mode) }}</text>
          <text class="txt-caption sh-muted update-record__line">{{ updateOutcome(item.outcome) }} · {{ item.beforeStatus || "—" }} → {{ item.afterStatus || "—" }}{{ item.nodesAdded ? ` · +${item.nodesAdded}` : "" }}</text>
          <text v-if="item.detail" class="txt-caption sh-muted update-record__line">{{ item.detail }}</text>
        </view>
      </view>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>
.update-record__line { display: block; }
.returned__line {
  display: block;
  margin-top: 12rpx;
}
.line {
  padding: 8rpx 0;
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
.carrier__chip {
  padding: 8rpx 20rpx;
  border: var(--sh-hairline);
  border-radius: 9999px;   /* full 档：胶囊。字号/字色交给 .txt-caption */
}
.carrier__chip--on {
  border-color: var(--sh-primary);
  color: var(--sh-primary-text);
  background: var(--sh-primary-tint);
}

/* 物流卡：整卡可点。放在最顶上 —— 店主打开这一页最先问的就是「货到哪了」 */
.trace-card__last {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  margin-top: 8rpx;
}
.trace-card__no {
  display: block;
  margin-top: 8rpx;
}

/* 订单号右边的「复制」：顺手动作，不占整行、不抢发货那个主动作 */
.no-row__v {
  gap: 16rpx;
  min-width: 0;
}
.no-row__copy {
  flex: none;
}
</style>
