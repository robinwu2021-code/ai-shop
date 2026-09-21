<script setup lang="ts">
// 订单详情：码 → 状态时间线 → 商品 → 金额 → 履约信息 → 操作。
// 码放最上面：待取货的用户打开订单，十有八九就是来看码的。
import { computed, ref } from "vue";
import { codeLabelKey, statusTone } from "@shared/strategies/order-view";
import { useI18n } from "vue-i18n";
import { onShow, onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { buildShareMessage, canNativeShare } from "@shared/ports/share";
import { api } from "@/api";
import { CATEGORY_TYPE, ROUTES } from "@shared/utils/constants";
import { countdown, datetime, money } from "@shared/utils/format";
import type { GroupBuy, InvoiceRequest, Order, OrderStatus } from "@shared/types";
import { confirm, prompt } from "@ai-shop/ui/prompt";
import { orderNoOf } from "@/shared/order-no";

const { t } = useI18n();

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
    <!-- 码：待取货的用户主要就是来看这个 -->
    <!--
      **一张卡，标签按品类变**。后端把自提码 / 核销码 / 兑换码合在同一个字段里，
      端上此前另外读一个 `redeemCode` —— 那个字段后端从来不发，于是兑换码那张卡
      永远不出现，而虚拟商品的码落进上面这张、被标成「取货码」。
    -->
    <view v-if="order.verifyCode && order.status !== 'COMPLETED'" class="sh-card codecard">
      <text class="txt-caption codecard__label">{{ $t(codeLabel) }}</text>
      <text class="txt-hero codecard__v sh-num">{{ order.verifyCode }}</text>
      <text class="txt-caption codecard__hint">
        {{ isVirtualOrCard ? $t("order.redeemHint") : $t("order.codeHint") }}
      </text>
    </view>

    <view v-if="batchCancellable" class="sh-notice sh-notice--warning">
      <text class="txt-caption">{{ $t("order.batchCancelBefore", { t: batchTime(order.cancellableUntil!) }) }}</text>
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

    <!-- 状态 + 时间线 -->
    <view class="sh-card block">
      <text class="txt-title status" :class="statusTone(order.status)">
        {{ $t(`orderStatus.${order.status}`) }}
      </text>
      <!--
        **状态下面说一句「接下来会发生什么」**（原型 k07）。
        「待发货」三个字只说了此刻，没说他要等什么 —— 而那正是他点进这一页想知道的。
        没写过说明的状态整行不显示：编一句放之四海皆准的话等于什么都没说。
      -->
      <text v-if="nextStepText" class="txt-caption sh-muted status__next">{{ nextStepText }}</text>

      <view class="timeline">
        <view v-for="(n, i) in order.timeline" :key="i" class="node">
          <view class="node__dot" :class="{ 'is-last': i === order.timeline.length - 1 }" />
          <view class="sh-fill">
            <text class="txt-sub node__label">{{ n.label }}</text>
            <text class="txt-caption node__at sh-num">{{ datetime(n.at) }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 商品 -->
    <view class="sh-card block">
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

    <!-- 金额 -->
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

    <!-- 商家披露：分账场景下必须让用户知道钱付给了谁（ADR-002 §5）。
         购物车跨商家会拆成多笔子订单，一单只对应一家 —— 不说清楚，
         用户看到账单上出现陌生商户名会直接当成盗刷。 -->
    <view v-if="order.merchantName" class="sh-card block">
      <text class="txt-sub disclose">{{ $t("order.providedBy", { m: order.merchantName }) }}</text>
      <text v-if="(order.payGroupSize ?? 1) > 1" class="sh-muted disclose__hint">
        {{ $t("order.splitHint") }}
      </text>
    </view>

    <!-- 履约信息 -->
    <view class="sh-card block">
      <!--
        **没有履约方式就整行不出**，不要渲染一个空值。
        此前这一行无条件渲染，而主订单（多商家的单在这一层还没拆开）的
        `fulfillment` 是 null —— 界面上显示的是那个 i18n 键本身（键名 + 点 + null），
        直接漏到用户眼前。注释里不写出那个字面量：模板注释会被渲染进 HTML，
        而 order-detail-review.test.ts 正是按「输出里不许出现它」来判的。
        微信《小程序订单管理》的订单详情 path 传的正是主订单号，
        所以从微信点进来的每一单都会看到它。
      -->
      <view v-if="order.fulfillment" class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-caption fact__k">{{ $t("goods.fulfillment") }}</text>
        <text class="txt-caption fact__v">{{ $t(`fulfillment.${order.fulfillment}`) }}</text>
      </view>
      <view v-if="order.pickupName" class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-caption fact__k">{{ $t("order.pickup") }}</text>
        <text class="txt-caption fact__v">{{ order.pickupName }}</text>
      </view>
      <view v-if="order.arriveDate" class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-caption fact__k">{{ $t("order.batchPickup") }}</text>
        <text class="txt-caption fact__v sh-num">{{ order.arriveDate }}</text>
      </view>
      <view v-if="order.appointmentAt" class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-caption fact__k">{{ $t("order.appointment") }}</text>
        <text class="txt-caption fact__v sh-num">{{ datetime(order.appointmentAt) }}</text>
      </view>
      <view v-if="order.expressNo" class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-caption fact__k">{{ $t("order.express") }}</text>
        <text class="txt-caption fact__v sh-num">{{ order.expressNo }}</text>
      </view>
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
    </view>

    <!-- 售后进行中：把「下一步该我做什么」直接摆出来，别让用户自己找入口。
         判据是**售后单存在**，不是订单状态 —— 订单在售后期间保持原状态
         （已完成的单照样能申请售后），此前 gate 在 order.status==='REFUNDING'
         上，而后端从不下发这个订单状态，整张卡片因此永远不显示 -->
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

    <!-- 开票状态。**申请完就没下文**是这类入口最常见的失败 ——
         用户点完按钮，页面毫无变化，他会以为没提交成功而再点一次 -->
    <view v-if="invoice" class="sh-card invoicecard">
      <text class="sh-muted">{{ $t("invoice.section") }}</text>
      <text class="invoicecard__st">{{ $t(`invoiceStatus.${invoice.status}`) }}</text>
      <text v-if="invoice.status === 'ISSUED'" class="sh-muted">
        {{ $t("invoice.sentTo", { email: invoice.email }) }}
      </text>
      <text v-if="invoice.rejectReason" class="sh-muted">{{ invoice.rejectReason }}</text>
    </view>

    <view class="ops sh-wrap">
      <view v-if="order.status === 'WAIT_PAY'" class="txt-sub sh-btn op" @tap="pay">
        {{ $t("orders.pay") }}
      </view>
      <view v-if="canCancel" class="txt-sub sh-btn sh-btn--muted op" @tap="cancel">
        {{ $t("order.cancel") }}
      </view>
      <view v-if="canReview" class="txt-sub sh-btn op" @tap="review">
        {{ $t("review.writeTitle") }}
      </view>
      <view v-if="canAfterSale" class="txt-sub sh-btn sh-btn--soft op" @tap="afterSale">
        {{ $t("order.afterSale") }}
      </view>
      <view v-if="canInvoice" class="txt-sub sh-btn sh-btn--soft op" @tap="applyInvoice">
        {{ invoice ? $t("invoice.reapply") : $t("invoice.apply") }}
      </view>
      <view class="txt-sub sh-btn sh-btn--muted op" @tap="buyAgain">{{ $t("order.buyAgain") }}</view>
    </view>
    <view class="spacer" />
    </template>
  </sh-scaffold>
</template>

<style scoped>
.status__next {
  display: block;
  margin-top: 8rpx;
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
</style>
