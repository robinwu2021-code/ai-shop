<script setup lang="ts">
// 订单详情：码 → 状态时间线 → 商品 → 金额 → 履约信息 → 操作。
// 码放最上面：待取货的用户打开订单，十有八九就是来看码的。
import { computed, ref } from "vue";
import { codeLabelKey, showVerifyCode, statusTone } from "@shared/strategies/order-view";
import { useI18n } from "vue-i18n";
import { onShow, onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { buildShareMessage, canNativeShare } from "@shared/ports/share";
import { api } from "@/api";
import { CATEGORY_TYPE, ROUTES } from "@shared/utils/constants";
import { countdown, datetime, money } from "@shared/utils/format";
import type { GroupBuy, InvoiceRequest, Order, OrderStatus } from "@shared/types";
import { confirm, prompt } from "@ai-shop/ui/prompt";
import { orderNoOf } from "@/shared/order-no";
import { openWxWaybillTracking } from "@/ports/wx-logistics";
import { traceStepKey } from "@shared/strategies/trace-step";
import { EXPRESS_COMPANIES } from "@shared/utils/express-companies";

const { t } = useI18n();
/**
 * 快递公司名（TDD-快递100商家寄件 §7 AC14）。订单一直带着 `expressCompany`（微信 delivery_id），
 * 只是没显示 —— 买家光看一串运单号，不知道该去哪家查。认不出的码原样显示，不吞掉。
 */
/** 摘要行的状态词。与步骤条共用 shared 里那一份判定，别在两处各写一个三分支 */
const traceStep = computed(() => (order.value?.trace ? traceStepKey(order.value.trace) : "picked"));
/** 有没有轨迹。没有就不渲染轨迹那一段 */
const hasTrace = computed(() => !!order.value?.trace?.nodes?.length);
/**
 * 状态头的底色档（原型 c-order-detail）：待办给暖色、进行中给主色、终态给灰。
 * 与 statusTone（文字色，库件给）分开 —— 这里只决定 header 背景，不碰字色。
 */
const statusBg = computed(() => {
  const s = order.value?.status;
  if (s === "WAIT_PAY" || s === "WAIT_OFFLINE_PAY") return "head--warn";
  if (s === "COMPLETED" || s === "CANCELLED" || s === "REFUNDED") return "head--done";
  return "head--active";
});

/**
 * 待付款的剩余时间（状态头右上）。**只在还没过期时给** —— 过期了后端会把单关掉，
 * 这时再显示一个负数倒计时比不显示更糟。非待付款态恒空。
 */
const payLeft = computed(() => {
  const o = order.value;
  if (!o || o.status !== "WAIT_PAY" || !o.payDeadlineAt) return "";
  const left = o.payDeadlineAt - Date.now();
  return left > 0 ? countdown(left) : "";
});

/** 有没有「货去哪」这件事要说：收货人 / 自提点 / 到货日 / 预约。都没有就不画那张卡 */
const hasDelivery = computed(() => {
  const o = order.value;
  if (!o) return false;
  return !!(o.receiver?.name || o.receiver?.address || o.pickupName || o.arriveDate || o.appointmentAt);
});

/** 商品卡的店头：门店名优先，没有门店（老数据/主体单）退回主体名 */
const shopName = computed(() => order.value?.store?.storeName || order.value?.merchantName || "");

/**
 * 吸底条的次要操作。**一次只给一颗** —— 底部放三颗以上就谁也点不准，
 * 低频的（开票、再来一单）留在页内那一排。
 */
const secondaryOp = computed<"cancel" | "afterSale" | "buyAgain">(() => {
  if (canCancel.value) return "cancel";
  if (canAfterSale.value) return "afterSale";
  return "buyAgain";
});

/** 吸底条的主操作：按「此刻最该做什么」排序 —— 去支付 > 评价 > 售后 > 再来一单 */
const primaryKey = computed<"pay" | "review" | "afterSale" | "buyAgain">(() => {
  const o = order.value;
  if (o?.status === "WAIT_PAY") return "pay";
  if (canReview.value) return "review";
  if (canAfterSale.value && secondaryOp.value !== "afterSale") return "afterSale";
  return "buyAgain";
});

const primaryLabel = computed(() => {
  switch (primaryKey.value) {
    case "pay":
      return String(t("orders.pay"));
    case "review":
      return String(t("review.writeTitle"));
    case "afterSale":
      return String(t("order.afterSale"));
    default:
      return String(t("order.buyAgain"));
  }
});

function runPrimary() {
  switch (primaryKey.value) {
    case "pay":
      pay();
      break;
    case "review":
      review();
      break;
    case "afterSale":
      afterSale();
      break;
    default:
      buyAgain();
  }
}
/**
 * 这一单的明细由**微信插件**呈现吗。
 *
 * <p>是的话我们只给摘要（状态 + 最新一条 + 入口）：**明细在插件里已经有一份**，
 * 页面上再铺一遍是同一份数据说两次，而且两份的新旧还可能不一致。
 * 插件只在小程序里存在，所以 H5 / App 以及拿不到 token 的单仍然由我们自己铺 ——
 * 否则那些端什么都看不到。
 */
const traceInPlugin = computed(() =>
  order.value?.trace?.displayMode === "wx-plugin" && !!order.value?.trace?.displayToken);
/** 摘要那一行。节点按时间倒序，第一条就是最新 */
const traceLatest = computed(() => order.value?.trace?.nodes?.[0]?.text ?? "");

const expressCompanyName = computed(() => {
  const code = order.value?.expressCompany;
  if (!code) return "";
  return EXPRESS_COMPANIES.find((c) => c.code === code)?.name ?? code;
});

const order = ref<Order | null>(null);
/**
 * 这一单参加的团（原型 p08）。团单在订单里多一张进度卡：还差几人、还剩多久、邀请。
 * 此前订单详情看不出这是团单，用户只能回商品页去找那个团。取不到就不画，不拖垮订单页。
 */
const grp = ref<GroupBuy | null>(null);
const nativeShare = canNativeShare();
const grpOpen = computed(() => !!grp.value && grp.value.status === "OPEN" && grp.value.expireAt > Date.now());
const orderNo = ref("");

/**
 * 「接下来会发生什么」。**按状态说**，没写过说明的状态返回空串（整行不显示）——
 * 编一句放之四海皆准的话，等于什么都没说。
 */
/** 已取消 / 已退款的单：券与积分去了哪。有一项说一项 */
const returnedLines = computed(() => {
  const r = order.value?.returned;
  if (!r) return [] as string[];
  const out: string[] = [];
  if (r.couponTitle) out.push(String(t("order.returned.coupon", { name: r.couponTitle })));
  if (r.pointsReturned) out.push(String(t("order.returned.points", { n: r.pointsReturned })));
  if (r.pointsClawedBack) out.push(String(t("order.returned.clawback", { n: r.pointsClawedBack })));
  return out;
});

const nextStepText = computed(() => {
  const o = order.value;
  if (!o) return "";
  const key = `order.next.${o.status}`;
  const text = String(t(key));
  // vue-i18n 找不到键时原样返回键名 —— 那正是「这个状态还没写过说明」
  return text === key ? "" : text;
});

/** 复制订单号。找客服要念这一串，照着屏幕抄最容易抄错 */
function copyOrderNo() {
  const no = order.value?.orderNo;
  if (!no) return;
  uni.setClipboardData({ data: no });
}
/** 运单号单独一颗复制：买家想去别处查就让他查，别堵着 */
function copyExpressNo() {
  const no = order.value?.expressNo;
  if (!no) return;
  uni.setClipboardData({ data: no });
}
/**
 * 打开微信官方物流页（TDD-物流轨迹多渠道 §2.6）。条件编译收在 ports/wx-logistics 里，
 * 这里只管「没打开成功就提示一句」。打不开的常因是插件没初始化好，不白跳。
 */
function openWxTracking() {
  const token = order.value?.trace?.displayToken;
  if (!token) return;
  // **先打开、再刷新**：打开发生在点击回调里，不能排在 await 后面（同 requestSubscribe 的手势问题）
  // 打不开就说一句：常因是插件没初始化好或版本老，不白跳
  if (!openWxWaybillTracking(token)) {
    uni.showToast({ title: String(t("trace.wxUnavailable")), icon: "none" });
  }
  refreshTrace();
}

/**
 * 问一次物流页端点（TDD-物流模块 批 3）：它会顺带向微信校正一次状态（10 分钟内不重复），
 * 回来的新状态 / 到柜提示盖到详情上 —— 买家从插件页返回时看到的是新的。拿不到就留着详情里的，不提示。
 */
async function refreshTrace() {
  try {
    const fresh = await api.orderTrace(orderNo.value);
    if (fresh && order.value) {
      order.value = { ...order.value, trace: { ...order.value.trace, ...fresh } };
    }
  } catch {
    /* 物流页是锦上添花：失败照用详情里的 */
  }
}
/**
 * 拉挂了。
 *
 * ⚠️ **此前整页挂在 `v-if="order"` 上**：拉不到就连 `sh-scaffold` 都不渲染 ——
 * 没有标题栏、没有皮肤根节点、没有一个字。那不是「加载中」的样子，
 * 是「这个 App 坏了」的样子，而用户唯一能做的是退出去再进来。
 */
const failed = ref(false);
/**
 * 后端说的那句话。**有就显示它，没有才回落到「多半是网络不通」** ——
 * 「订单不存在」这种情况下告诉用户去检查网络，是一句**错的**解释，
 * 而错的解释比没有解释更费时间。
 */
const failReason = ref("");

/*
 * 状态集合**标注成 `OrderStatus[]`**，不是裸的字符串数组。
 *
 * ⚠️ 这不是洁癖：下面 `canAfterSale` 原本写的是
 * `["PAID","ARRIVED","SHIPPED","COMPLETED"]`，而 `ARRIVED` / `SHIPPED`
 * 在状态模型重整时已经并成 `FULFILLING`（见 `OrderStatus` 的注释）——
 * **于是履约中的订单一直没有售后入口**，而那正是「货不对、货损了」
 * 最常被发现的时候。
 *
 * 类型系统当时抓不到：数组字面量被推断成 `string[]`，
 * 而 `Array<string>.includes()` 收任何字符串，两个「合法的字符串、
 * 非法的状态」编译器无话可说。标注之后再写错当场编译不过。
 */
const AFTER_SALE_STATES: readonly OrderStatus[] = ["PAID", "FULFILLING", "COMPLETED"];
/** 不能开票的状态。同样标注 —— 这里原本还排除着一个不存在的 `"CLOSED"` */
const NO_INVOICE_STATES: readonly OrderStatus[] = ["WAIT_PAY", "WAIT_OFFLINE_PAY", "CANCELLED"];

/**
 * 能不能取消。**判据对齐后端状态机**（`OrderStateMachine.ORDER`）：
 *   WAIT_PAY         → {PAID, CANCELLED, CLOSED}
 *   WAIT_OFFLINE_PAY → {PAID, CANCELLED}
 *   PAID             → {}          ← 空集
 *
 * ⚠️ 原本写的是 `WAIT_PAY || PAID`，**两个方向都错**：
 * 已付款的单会显示一个按钮、二次确认还承诺「库存将释放」，点下去必然报错；
 * 而当面付待收款（后端明确允许取消）反倒没有入口。
 *
 * 已付款要退钱走的是**售后**，不是取消 —— 也就是上面那条刚修好的路。
 */
const canCancel = computed(
  () => order.value?.status === "WAIT_PAY" || order.value?.status === "WAIT_OFFLINE_PAY" || batchCancellable.value,
);
/**
 * 社区集单（原型 s37）：**已付款**的集单单在截单前也能取消 —— 后端走全额退款，不是关单。
 * 截单时刻由后端给（`cancellableUntil`），过了它就不再给，端上只看「有没有」且没过点。
 */
const batchCancellable = computed(
  () => !!order.value?.cancellableUntil && order.value.cancellableUntil > Date.now(),
);
function batchTime(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")} `
    + `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
const canAfterSale = computed(
  () => !!order.value && AFTER_SALE_STATES.includes(order.value.status),
);
const canReview = computed(
  () => order.value?.status === "COMPLETED" && !order.value?.reviewed,
);

/*
 * 开票（ADR-017 §3.4 条件 2）。**此前这里什么都没有** ——
 * C 端只有下单前一句「本商家无法开具发票」，连申请的地方都没有。
 * 而归集路径要成立，「平台开票给消费者」是四个必要条件之一：
 * 没有入口 = 没有履行途径。
 *
 * 开的是**平台的票**，不是商家的 —— 所以这个按钮跟商家有没有执照无关。
 */
const invoice = ref<InvoiceRequest | null>(null);
// 没成交就没有可开的票；被驳回过可以改抬头重申请
const canInvoice = computed(
  () =>
    !!order.value &&
    !NO_INVOICE_STATES.includes(order.value.status) &&
    (!invoice.value || invoice.value.status === "REJECTED"),
);

async function loadInvoice() {
  if (!orderNo.value) return;
  // 查不到是常态（这单还没申请过），后端返回 null 而不是报错
  invoice.value = await api.invoiceOfOrder(orderNo.value);
}

async function applyInvoice() {
  /*
   * ⚠️ 原来这里是 `const { confirm, content } = await uni.showModal(...)` ——
   * **解构出的 `confirm` 会遮蔽同名的组合式**，同一个文件里另一处确认弹层
   * 一旦挪进这个函数就会静默拿到一个布尔值。换成 `prompt()` 顺带消掉这个隐患。
   *
   * `showModal` 的 `content` 在 `editable: true` 时是**输入框初值**不是说明文字 ——
   * 这一处用对了（填的是当前抬头），但那个二义在 B 端害过三次。
   * `prompt()` 把它拆成 `value`（初值）与 `hint`（说明）两个参数，坑长不出来。
   */
  const title = await prompt({
    title: String(t("invoice.applyTitle")),
    placeholder: String(t("invoice.titlePh")),
    value: invoice.value?.title ?? "",
  });
  if (!title?.trim()) return;
  const email = await prompt({
    title: String(t("invoice.emailTitle")),
    placeholder: String(t("invoice.emailPh")),
    value: invoice.value?.email ?? "",
  });
  if (!email?.trim()) return;
  try {
    // 这一版只收个人抬头：单位抬头要税号，而一个 showModal 收不了两个字段。
    // 收不全就开不出票 —— 与其半途报错，不如这一版先只做个人抬头，
    // 单位抬头等专门的表单页
    invoice.value = await api.applyInvoice({
      orderNo: orderNo.value,
      titleType: "PERSONAL",
      title: title.trim(),
      email: email.trim(),
    });
    uni.showToast({ title: String(t("invoice.applied")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 那串码叫什么：自提码 / 核销码 / 兑换码。三端共用一份判据 */
const codeLabel = computed(() =>
  codeLabelKey(order.value?.items[0]?.type, order.value?.fulfillment),
);
// 快递/配送送到买家手上，没有核销这一步，不显示码（否则像冒出一个假取货码）
const showCode = computed(() =>
  showVerifyCode(order.value?.items[0]?.type, order.value?.fulfillment),
);

const isVirtualOrCard = computed(() => {
  const type = order.value?.items[0]?.type;
  return type === CATEGORY_TYPE.VIRTUAL || type === CATEGORY_TYPE.CARD;
});

async function load() {
  if (!orderNo.value) {
    /*
     * **没有单号时也要给出个交代**，不能直接 return。
     *
     * 此前 return 掉之后页面上只剩一个标题栏 —— 一整片白，没有任何文字、
     * 没有重试、也没有出路。而微信《小程序订单管理》的规范里写明
     * 「页面不出现加载失败等 bug」，这种空白正是会被驳回的形态。
     *
     * 走 failed 这条既有的空态：它已经有「没能加载出来 + 重试」的样子，
     * 不必为这一种情形再造一个新界面。
     */
    failed.value = true;
    failReason.value = String(t("order.noOrderNo"));
    return;
  }
  failed.value = false;
  failReason.value = "";
  try {
    // 并行拉：开票状态与订单详情互不依赖，串行只会让页面多等一个来回
    const [o] = await Promise.all([api.orderDetail(orderNo.value), loadInvoice()]);
    order.value = o;
    grp.value = o.groupNo ? await api.groupBuyDetail(o.groupNo).catch(() => null) : null;
  } catch (e) {
    // 留着上一次的 `order`：从售后页返回时重拉失败，把已经看到的详情
    // 清成空白只会更糟
    failed.value = true;
    failReason.value = (e as Error).message || "";
  }
}

function openGroup() {
  if (grp.value) uni.navigateTo({ url: `${ROUTES.group}?groupNo=${grp.value.groupNo}` });
}

// 团单页上的「邀请邻居来拼」分享的是团页，不是订单（订单只有本人看得了）
onShareAppMessage(() => {
  const g = grp.value;
  return buildShareMessage({
    title: g ? String(t("group.shareNeed", { n: g.need, title: g.title })) : "",
    path: g ? `${ROUTES.group}?groupNo=${g.groupNo}` : ROUTES.home,
  });
});

async function cancel() {
  const o = order.value;
  if (!o) return;
  const ok = await confirm({
    title: String(t("pay.cancelTitle")),
    hint: String(batchCancellable.value ? t("order.batchCancelTip") : t("pay.cancelTip")),
  });
  if (!ok) return;
  try {
    order.value = await api.cancelOrder(o.orderNo);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function pay() {
  uni.navigateTo({ url: `${ROUTES.pay}?orderNo=${orderNo.value}` });
}

function afterSale() {
  uni.navigateTo({ url: `${ROUTES.afterSale}?orderNo=${orderNo.value}` });
}

function review() {
  const first = order.value?.items.find((it) => !it.isGift);
  uni.navigateTo({
    url: `${ROUTES.reviewWrite}?orderNo=${orderNo.value}&goodsNo=${first?.goodsNo ?? ""}`,
  });
}

/**
 * 售后进行中的两个后续动作（C-FF 售后闭环后半段）。
 * 放在订单页而不是售后页：用户回头找的是「我那个订单」，不是「那次申请」。
 */
async function fillExpress() {
  const o = order.value;
  if (!o) return;
  const no = (await prompt({
    title: String(t("afterSale.returnExpressTitle")),
    placeholder: String(t("afterSale.returnExpressPh")),
  }))?.trim();
  if (!no) return;
  try {
    // 返回的是售后单，不是订单 —— 赋给 order 会把整个详情页覆盖成一张售后单。
    // 售后单变了就重新拉一次订单，让时间线与状态一起对上
    await api.fillReturnExpress(o.afterSale!.afterSaleNo, no);
    await load();
    uni.showToast({ title: String(t("afterSale.returnExpressOk")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function dispute() {
  const o = order.value;
  if (!o) return;
  const reason = (await prompt({
    title: String(t("afterSale.disputeTitle")),
    placeholder: String(t("afterSale.disputePh")),
  }))?.trim();
  if (!reason) return;
  try {
    await api.raiseDispute(o.afterSale!.afterSaleNo, reason);
    await load();
    uni.showToast({ title: String(t("afterSale.disputeOk")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function buyAgain() {
  const first = order.value?.items.find((it) => !it.isGift);
  if (first) uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${first.goodsNo}` });
}

/*
 * 从微信「小程序购物订单」点进来时，参数是**支付单号**（可能带 -2 / -3 后缀），
 * 不是订单号 —— 要先还原，否则重试付成功的那些单点开是一片空。见 orderNoOf。
 */
onLoad((q) => {
  orderNo.value = orderNoOf((q?.orderNo as string) || "");
});

// 用 onShow 而非 onLoad 加载：从售后页返回时状态会变，必须重新拉
onShow(load);
</script>

<template>
  <sh-scaffold title-key="order.title">
    <!--
      **外壳常在**：拉不到也要有标题栏与皮肤根节点。
      此前整页挂在 `v-if="order"` 上，失败时是一整片白。
    -->
    <sh-empty
      v-if="!order && failed"
      :text="String($t('common.loadFailed'))"
      :tip="String(failReason || $t('common.loadFailedTip'))"
    >
      <template #action>
        <view class="sh-btn sh-btn--sm" @tap="load">{{ $t("common.retry") }}</view>
      </template>
    </sh-empty>

    <template v-if="order">
    <!--
      订单详情重排（原型 prototypes/c-order-detail.html，2026-10-10）。
      恒定骨架：状态头 → 收货/自提 → 店铺+商品 → 金额 → 订单信息 → 附属卡 → 吸底操作条。
      「货到哪、我该做什么」提到最顶，对账要念的（单号/时间）沉到下面。
    -->

    <!-- ① 状态头：满宽大字 + 按状态底色；已发货把物流并进来（原型 s01–s04） -->
    <view class="sh-card block ostat" :class="statusBg">
      <view class="ostat__head">
        <view class="sh-row sh-row--between ostat__line">
          <text class="ostat__st" :class="statusTone(order.status)">
            {{ hasTrace ? $t(`trace.step.${traceStep}`) : $t(`orderStatus.${order.status}`) }}
          </text>
          <!-- 右上：待付款给倒计时；走插件的单给物流入口 -->
          <text v-if="payLeft" class="txt-caption ostat__cd sh-num">{{ payLeft }}</text>
          <sh-go
            v-else-if="traceInPlugin"
            :text="String($t('trace.openWx'))"
            @tap="openWxTracking"
          ></sh-go>
        </view>
        <!-- 有物流就说最新那条（比状态词具体）；没有就说「接下来会发生什么」 -->
        <text v-if="hasTrace" class="txt-caption ostat__sub">{{ traceLatest }}</text>
        <text v-else-if="nextStepText" class="txt-caption ostat__sub">{{ nextStepText }}</text>
        <!-- 承运商 + 单号：已发货才有，点一下复制 -->
        <text v-if="order.expressNo" class="txt-caption ostat__sub sh-num" @tap="copyExpressNo">
          {{ expressCompanyName ? `${expressCompanyName} ${order.expressNo}` : order.expressNo }}
          <text class="txt-primary">{{ $t("order.copy") }}</text>
        </text>
      </view>
      <!-- 退了什么说什么（P3）：只在后端真查到时出现 -->
      <view v-if="returnedLines.length" class="ostat__body returned">
        <text v-for="(l, i) in returnedLines" :key="i" class="txt-caption txt-primary">{{ l }}</text>
      </view>
      <!--
        轨迹明细只在插件不可用时铺（走微信插件的单，明细在插件那一屏；两边新旧还可能不一致）。
        不画地图：城市级示意回答不了「今天到不到」。
      -->
      <view v-if="hasTrace && !traceInPlugin" class="ostat__body">
        <sh-trace :trace="order.trace!" :show-map="false" @open-wx="openWxTracking"></sh-trace>
      </view>
    </view>

    <view v-if="batchCancellable" class="sh-notice sh-notice--warning">
      <text class="txt-caption">{{ $t("order.batchCancelBefore", { t: batchTime(order.cancellableUntil!) }) }}</text>
    </view>

    <!-- ② 收货 / 自提：紧跟状态。快递给收货人，自提给取件点与码 —— 一张卡回答「货去哪」 -->
    <view v-if="hasDelivery" class="sh-card block recvcard">
      <view class="sh-row sh-row--between">
        <text class="txt-caption sh-muted">{{ $t("track.receiverTitle") }}</text>
        <text v-if="order.fulfillment" class="txt-caption sh-chip tiny">
          {{ $t(`fulfillment.${order.fulfillment}`) }}
        </text>
      </view>
      <view v-if="order.receiver && (order.receiver.name || order.receiver.phone)" class="sh-row sh-row--between recv">
        <text class="txt-strong">{{ order.receiver.name }}</text>
        <text v-if="order.receiver.phone" class="txt-caption sh-muted sh-num">{{ order.receiver.phone }}</text>
      </view>
      <text v-if="order.receiver?.address" class="txt-caption sh-muted">{{ order.receiver.address }}</text>
      <!-- 自提：取件点 / 到货日 / 预约时刻 -->
      <text v-if="order.pickupName" class="txt-strong">{{ order.pickupName }}</text>
      <text v-if="order.arriveDate" class="txt-caption sh-muted sh-num">
        {{ $t("order.batchPickup") }} {{ order.arriveDate }}
      </text>
      <text v-if="order.appointmentAt" class="txt-caption sh-muted sh-num">
        {{ $t("order.appointment") }} {{ datetime(order.appointmentAt) }}
      </text>
    </view>

    <!--
      码：待取货的用户主要就是来看这个，所以紧跟取件信息。
      **一张卡，标签按品类变** —— 自提码 / 核销码 / 兑换码后端合在同一个字段。
    -->
    <view v-if="order.verifyCode && order.status !== 'COMPLETED' && showCode" class="sh-card codecard">
      <text class="txt-caption codecard__label">{{ $t(codeLabel) }}</text>
      <text class="txt-hero codecard__v sh-num">{{ order.verifyCode }}</text>
      <text class="txt-caption codecard__hint">
        {{ isVirtualOrCard ? $t("order.redeemHint") : $t("order.codeHint") }}
      </text>
    </view>

    <!-- 拼团进度（原型 p08）：团单才有。整张卡进团页 -->
    <view v-if="grp" class="sh-card block grpcard" @tap="openGroup">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">
          {{ grpOpen ? $t("order.groupNeed", { n: grp.need }) : $t(`group.status.${grp.status}`) }}
        </text>
        <text class="txt-caption sh-num">{{ grp.joinedCount }} / {{ grp.minCount }}</text>
      </view>
      <text v-if="grpOpen" class="txt-caption txt-quiet sh-num">
        {{ $t("order.groupLeft", { t: countdown(grp.expireAt - Date.now()) }) }}
      </text>
      <button v-if="grpOpen && nativeShare" class="sh-btn sh-btn--sm grpcard__invite" open-type="share" @tap.stop>
        {{ $t("group.invite") }}
      </button>
    </view>

    <!-- ③ 店铺 + 商品：商品上面给店名，买家才知道这单是谁发的（原型 s01–s04 的店头） -->
    <view class="sh-card block">
      <text v-if="shopName" class="txt-strong shop">{{ shopName }}</text>
      <biz-sku-row
        v-for="(it, i) in order.items"
        :key="i"
        :cover="it.cover"
        :title="it.title"
        :spec="it.spec"
      >
        <template #right>
          <view class="row__right">
            <text v-if="it.isGift" class="txt-caption sh-chip sh-chip--danger tiny">
              {{ $t("promo.gift") }}
            </text>
            <text v-else class="txt-strong row__price sh-num">{{ money(it.price) }}</text>
            <text class="txt-caption row__qty sh-num">×{{ it.qty }}</text>
          </view>
        </template>
      </biz-sku-row>
    </view>

    <!-- ④ 金额 -->
    <view class="sh-card block">
      <view class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("confirm.goods") }}</text>
        <text class="txt-caption amt__v sh-num txt-ink">{{ money(order.amount.goodsMinor) }}</text>
      </view>
      <view class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("confirm.freight") }}</text>
        <text class="txt-caption amt__v sh-num txt-ink">
          {{ order.amount.freightMinor ? money(order.amount.freightMinor) : $t("confirm.free") }}
        </text>
      </view>
      <view v-if="order.amount.discountMinor" class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("confirm.discount") }}</text>
        <text class="txt-caption amt__v sh-num is-danger">-{{ money(order.amount.discountMinor) }}</text>
      </view>
      <!-- 减的是什么，直接写在下面（TDD-C端优惠依据）。后端取不到名字的条目不会下发 -->
      <view
        v-for="(d, k) in order.discountLines ?? []"
        :key="k"
        class="amt sh-row sh-row--between sh-row--top"
      >
        <text class="txt-caption sh-muted">
          {{ $t(d.kind === "COUPON" ? "confirm.fromCoupon" : "confirm.fromActivity", { name: d.name }) }}
        </text>
        <text class="txt-caption amt__v sh-num sh-muted">-{{ money(d.amountMinor) }}</text>
      </view>
      <view v-if="order.amount.weighAdjustMinor" class="amt sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("order.weighAdjust") }}</text>
        <text class="txt-caption amt__v sh-num txt-ink">{{ money(order.amount.weighAdjustMinor) }}</text>
      </view>
      <view class="amt amt--total sh-row sh-row--between sh-row--top">
        <text class="txt-caption">{{ $t("order.paid") }}</text>
        <text class="txt-price sh-num">
          {{ money(order.amount.paidMinor || order.amount.payableMinor) }}
        </text>
      </view>
    </view>

    <!--
      ⑤ 订单信息独立成卡（原型）：单号 + 各节点时刻。
      此前它并在金额卡尾部，而找客服念单号与核对金额是两件事；时间线原来是一列带圆点的长表，
      占掉大半屏——这里压成每节点一行，信息不丢、版面短一半。
    -->
    <view class="sh-card block">
      <view class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-caption fact__k">{{ $t("order.orderNo") }}</text>
        <!-- 找客服时他要念这一串：给一颗复制，别让人照着屏幕抄 -->
        <text class="txt-caption fact__v sh-num" @tap="copyOrderNo">
          {{ order.orderNo }} <text class="txt-primary">{{ $t("order.copy") }}</text>
        </text>
      </view>
      <view class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-caption fact__k">{{ $t("order.createdAt") }}</text>
        <text class="txt-caption fact__v sh-num">{{ datetime(order.createdAt) }}</text>
      </view>
      <view
        v-for="(n, i) in order.timeline"
        :key="i"
        class="fact sh-row sh-row--between sh-row--top"
      >
        <text class="txt-caption fact__k sh-muted">{{ n.label }}</text>
        <text class="txt-caption fact__v sh-num sh-muted">{{ datetime(n.at) }}</text>
      </view>
    </view>

    <!-- 商家披露：分账场景下必须让用户知道钱付给了谁（ADR-002 §5）。
         购物车跨商家会拆成多笔子订单，一单只对应一家 —— 不说清楚，
         用户看到账单上出现陌生商户名会直接当成盗刷。 -->
    <view v-if="order.merchantName" class="sh-card block">
      <text class="txt-sub disclose">{{ $t("order.providedBy", { m: order.merchantName }) }}</text>
      <text v-if="(order.payGroupSize ?? 1) > 1" class="sh-muted disclose__hint">
        {{ $t("order.splitHint") }}
      </text>
    </view>

    <!-- 售后进行中：把「下一步该我做什么」直接摆出来，别让用户自己找入口。
         判据是**售后单存在**，不是订单状态 —— 订单在售后期间保持原状态。 -->
    <view v-if="order.afterSale" class="sh-card as">
      <text class="txt-body as__title">
        {{ $t(`afterSale.status.${order.afterSale.status}`) }}
      </text>
      <text v-if="order.afterSale.merchantReply" class="as__reply">
        {{ $t("afterSale.merchantReply") }}{{ order.afterSale.merchantReply }}
      </text>
      <text class="txt-caption as__hint">{{ $t(`afterSale.statusHint.${order.afterSale.status}`) }}</text>
      <view
        v-if="order.afterSale.status === 'REFUNDING' && order.afterSale.type === 'RETURN_REFUND' && !order.afterSale.returnExpressNo"
        class="sh-btn as__btn"
        @tap="fillExpress"
      >
        {{ $t("afterSale.fillExpress") }}
      </view>
      <view
        v-else-if="order.afterSale.status === 'REJECTED'"
        class="sh-btn sh-btn--soft as__btn"
        @tap="dispute"
      >
        {{ $t("afterSale.raiseDispute") }}
      </view>
    </view>

    <!-- 开票状态。**申请完就没下文**是这类入口最常见的失败 -->
    <view v-if="invoice" class="sh-card invoicecard">
      <text class="sh-muted">{{ $t("invoice.section") }}</text>
      <text class="invoicecard__st">{{ $t(`invoiceStatus.${invoice.status}`) }}</text>
      <text v-if="invoice.status === 'ISSUED'" class="sh-muted">
        {{ $t("invoice.sentTo", { email: invoice.email }) }}
      </text>
      <text v-if="invoice.rejectReason" class="sh-muted">{{ invoice.rejectReason }}</text>
    </view>

    <!-- 低频操作留在页内：吸底条只放主次两颗，其余（开票 / 再来一单）在这里 -->
    <view class="ops sh-wrap">
      <view v-if="canInvoice" class="txt-sub sh-btn sh-btn--soft op" @tap="applyInvoice">
        {{ invoice ? $t("invoice.reapply") : $t("invoice.apply") }}
      </view>
      <view v-if="secondaryOp !== 'buyAgain'" class="txt-sub sh-btn sh-btn--muted op" @tap="buyAgain">
        {{ $t("order.buyAgain") }}
      </view>
    </view>

    <!-- 吸底条的占位：不留这块，最后一张卡会被压在条下面 -->
    <view class="barph" />

    <!--
      ⑥ 吸底操作条（原型）：左边实付、右边主次两颗。
      京东淘宝都把「该做什么」钉在底部 —— 此前这几颗按钮排在页尾，
      长单要滚到底才看得见「去支付」。
    -->
    <view class="bar">
      <view class="bar__sum">
        <text class="txt-caption sh-muted">{{ $t("order.paid") }}</text>
        <text class="txt-price sh-num bar__price">
          {{ money(order.amount.paidMinor || order.amount.payableMinor) }}
        </text>
      </view>
      <view v-if="secondaryOp === 'cancel'" class="txt-sub sh-btn sh-btn--muted bar__btn" @tap="cancel">
        {{ $t("order.cancel") }}
      </view>
      <view v-else-if="secondaryOp === 'afterSale'" class="txt-sub sh-btn sh-btn--soft bar__btn" @tap="afterSale">
        {{ $t("order.afterSale") }}
      </view>
      <view v-else-if="secondaryOp === 'buyAgain'" class="txt-sub sh-btn sh-btn--muted bar__btn" @tap="buyAgain">
        {{ $t("order.buyAgain") }}
      </view>
      <view class="txt-sub sh-btn bar__btn bar__btn--main" @tap="runPrimary">{{ primaryLabel }}</view>
    </view>
    </template>

  </sh-scaffold>
</template>

<style scoped>
.recv { margin-top: 8rpx; }
.returned {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  margin-top: 12rpx;
}
/* 拼团进度卡（p08）：三行竖排，邀请按钮贴左 */
.grpcard {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.grpcard__invite {
  align-self: flex-start;
  margin: 8rpx 0 0;
}

.as__title {
  display: block;
}
.as__reply,
.as__hint {
  display: block;
  margin-top: 8rpx;
}
.as__btn {
  margin-top: 24rpx;
}

.disclose {
  display: block;
  color: var(--sh-ink);
}
.disclose__hint {
  display: block;
  margin-top: 8rpx;
}

.codecard {
  text-align: center;
  background: var(--sh-primary-tint);
}
.codecard--redeem {
  background: var(--sh-warning-tint);
  margin-top: 20rpx;
}
.codecard__label {
  display: block;
  color: var(--sh-primary-text);
}
.codecard--redeem .codecard__label {
  color: var(--sh-warning);
}
.codecard__v {
  display: block;
  letter-spacing: 8rpx;
  margin-top: 16rpx;
}
.codecard__hint {
  display: block;
  margin-top: 16rpx;
}
/* 只留版面。**颜色交给库件**（.txt-primary / .is-warning / .txt-quiet，
   由 statusTone 给）—— 页内 scoped 选择器带 [data-v-x]，权重比全局库件高，
   这里留一条 color 就会把库件那条压掉，而闸门看不出来。 */
.status {
  display: block;
}
/* 状态头（原型 c-order-detail）：**满宽**大字 + 按状态档的底色，像京东/淘宝那张醒目的头。
   卡自己 padding 归零，头与身各自留白，底色才能铺满整张卡宽。
   只给背景与字号，字色仍交给 statusTone 库件（见上面那条注释的理由）。 */
.ostat {
  padding: 0;
  overflow: hidden;
}
.ostat__head {
  padding: 28rpx 24rpx 24rpx;
}
.ostat.head--warn .ostat__head {
  background: var(--sh-warning-tint);
}
.ostat.head--active .ostat__head {
  background: var(--sh-primary-tint);
}
.ostat.head--done .ostat__head {
  background: var(--sh-faint);
}
.ostat__line {
  align-items: baseline;
}
.ostat__st {
  font-size: 44rpx;
  font-weight: 600;
  line-height: 1.25;
}
.ostat__cd {
  flex-shrink: 0;
}
.ostat__sub {
  display: block;
  margin-top: 10rpx;
}
.ostat__body {
  padding: 20rpx 24rpx;
}

/* 收货 / 自提：一张卡回答「货去哪」 */
.recvcard {
  gap: 8rpx;
}

/* 商品卡的店头 */
.shop {
  display: block;
  margin-bottom: 12rpx;
}

/* 吸底操作条：左边实付、右边主次两颗。占位块把最后一张卡顶起来，别被条压住 */
.bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  background: var(--sh-card);
  border-top: 1rpx solid var(--sh-line);
}
.bar__sum {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.bar__price {
  line-height: 1.2;
}
/* 吸底的按钮不能被 flex 压扁：库件的 sh-btn 是块级满宽的，放进横向条里会缩成一颗圆球、
   文字挤成两行（2026-10-10 本地实测）。这里把宽度交还给内容，并给足左右内距。 */
.bar__btn {
  flex: 0 0 auto;
  width: auto;
  margin: 0;
  padding-left: 32rpx;
  padding-right: 32rpx;
  min-width: 168rpx;
  white-space: nowrap;
}
.barph {
  height: calc(140rpx + env(safe-area-inset-bottom));
}
.timeline {
  margin-top: 28rpx;
}
.node {
  display: flex;
  gap: 20rpx;
  padding-bottom: 24rpx;
}
.node__dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 9999px;
  background: var(--sh-line);
  margin-top: 8rpx;
  flex-shrink: 0;
}
.node__dot.is-last {
  background: var(--sh-primary);
}

.node__label {
  display: block;
  color: var(--sh-ink);
}
.node__at {
  display: block;
  margin-top: 4rpx;
}
.row__right {
  text-align: end;
  flex-shrink: 0;
}
.tiny {
  padding: 4rpx 16rpx;
}
.row__price {
  display: block;
}
.row__qty {
  display: block;
  margin-top: 4rpx;
}
.amt {
  padding: 12rpx 0;
}
.amt--total {
  margin-top: 12rpx;
}


.fact {
  gap: 32rpx;
  padding: 12rpx 0;
}
.fact__k {
  flex-shrink: 0;
}
.fact__v {
  color: var(--sh-ink);
  text-align: end;
}
.ops {
  gap: 16rpx;
  margin-top: 28rpx;
}
.op {
  flex: 1 0 calc(50% - 16rpx);
  padding-top: 24rpx;
  padding-bottom: 24rpx;
}
.spacer {
  height: 60rpx;
}

/* 进度区域：状态 → 履约落点 → 轨迹 → 订单进度，分段之间一条细线 */
.prog__next {
  display: block;
  margin-top: 8rpx;
}
.prog__sec {
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid var(--sh-line);
}
.prog__row {
  display: block;
  margin-top: 8rpx;
}
.prog__row:first-child {
  margin-top: 0;
}
.prog__cap {
  display: block;
  margin-bottom: 12rpx;
}
</style>
