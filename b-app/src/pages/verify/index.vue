<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 核销台（B-10.2）。**自提履约的必要条件** —— 没有它，货到自提点没人能核销，
// 订单永远卡在「已到点」，评价也做不了（评价要求订单已完成）。
//
// 两种核销方式并存：扫码（快，货多时唯一可用）与输码（扫不出来时的兜底，
// 老人机拍的码、屏幕反光都很常见）。只做扫码是不够的。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { ROUTES } from "@/shared/nav";
import { scanCode } from "@shared/ports/scan";
import { money } from "@shared/utils/money";
import type {
  CouponRedeemView,
  Order,
  PickupOrder,
  PickupOverview,
  VerifyBatchResult,
} from "@shared/types";

const { t } = useI18n();

/*
 * 核销台上两件事共用一个台子：**取货核销**与**券核销**。
 *
 * 合成一页而不是新开一页，是因为它们发生在同一个人、同一张收银台前：
 * 顾客把手机递过来，店员不该先判断「这是取货码还是券码」再决定去哪一页。
 * 但两者**不能自动识别**：码长得像，认错的代价是核销掉不该核的东西，
 * 而线下核销不可撤销。所以给一个显式的切换，由店员说出他在核什么。
 */
const tab = ref<"pickup" | "coupon">("pickup");

/** 券核销：先看到的那一张（peek 的结果）。**没看到之前不给核销按钮** */
const couponCode = ref("");
const couponView = ref<CouponRedeemView | null>(null);
const couponError = ref("");
const couponDone = ref("");

async function peekCoupon(input?: string) {
  const c = (input ?? couponCode.value).trim();
  if (!c || busy.value) return;
  busy.value = true;
  couponError.value = "";
  couponView.value = null;
  couponDone.value = "";
  try {
    couponView.value = await api.mPeekCouponCode(c);
    couponCode.value = c;
  } catch (e) {
    couponError.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}

async function scanCoupon() {
  const c = await scanCode();
  if (c) void peekCoupon(c);
}

/**
 * 核销。**先二次确认** —— 线下核销不可撤销，东西给出去就收不回来。
 * 确认框里要写清核的是哪一张、核完还剩几次。
 */
async function redeemCoupon() {
  const v = couponView.value;
  if (!v || !v.redeemable || busy.value) return;
  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: t("verify.couponConfirmTitle", { title: v.title }),
      content: v.timesTotal > 1
        ? t("verify.couponConfirmTimes", { n: v.remaining - 1 })
        : t("verify.couponConfirmOnce"),
      confirmText: String(t("verify.couponConfirmBtn")),
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    });
  });
  if (!ok) return;

  busy.value = true;
  try {
    const r = await api.mRedeemCoupon(couponCode.value);
    // duplicated **不是失败**：店员连点了两下，告诉他刚才那次已经成功
    couponDone.value = r.duplicated
      ? String(t("verify.couponDuplicated"))
      : String(t(r.usedUp ? "verify.couponUsedUp" : "verify.couponLeft", { n: r.remaining }));
    couponView.value = { ...v, timesUsed: r.timesUsed, remaining: r.remaining,
      redeemable: r.remaining > 0 };
  } catch (e) {
    couponError.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}

const overview = ref<PickupOverview | null>(null);
const code = ref("");
const busy = ref(false);
const orders = ref<PickupOrder[]>([]);
/** 刚核销成功的单号，用于列表高亮，让店主确认「我刚点的是这单」 */
const justDone = ref("");
const error = ref("");

/**
 * 待核销 = **后端认为还能核的那些**。
 *
 * 判据与 `PickupServiceImpl.doVerify` 同一套：已完成 / 已退 / 已取消 / 未付款
 * 之外的都还能核。此前这里写的是 `status === "ARRIVED"` —— 那是 **mock 的口径**
 * （mock 用主单状态），真实后端发的是子单状态 `WAIT_FULFILL`，
 * 于是这张列表在真机上**永远是空的**，而头部计数是对的：
 * 同一屏上「1 待核销」与「当前没有待核销的订单」并排。
 *
 * **`WAIT_FULFILL`（备货中，还没标到货）也要排除**——此前排除表漏了这一档，
 * 于是这张"待核销"列表里混着还没到货的单：点它核销，后端会用 `NOT_ARRIVED`
 * 拒掉，商家搞不清"明明列在这儿怎么核不了"。备货中的单归分拣页管，见 `preparing`。
 */
const NOT_VERIFIABLE = ["COMPLETED", "CANCELLED", "REFUNDED", "WAIT_PAY", "WAIT_FULFILL"];
const waiting = computed(() => orders.value.filter((o) => !NOT_VERIFIABLE.includes(o.status)));

/**
 * 还在分拣中（没标到货）的单数。**核销台空、分拣台有货**是最容易让商家困惑的一刻——
 * "怎么一个待核销的都没有"，答案往往是"还没点标到货"，这里直接说穿，
 * 不用他自己猜或者跑一趟分拣页才发现。
 */
const preparingCount = computed(() => orders.value.filter((o) => o.status === "WAIT_FULFILL").length);
function goToPicking() {
  uni.navigateTo({ url: ROUTES.picking });
}

async function load() {
  // 重新进页面时清掉上次的失败提示 —— 否则「该订单已核销」会一直挂在那里，
  // 下次进来看到它会以为是这次的结果
  error.value = "";
  [orders.value, overview.value] = await Promise.all([
    api.mPickupOrders(),
    api.mPickupOverview(),
  ]);
}

/**
 * 输码失败后的候选单（按码片段搜出来的）。
 *
 * **这是第三条路**：扫码 → 输全码 → 按片段搜。前两条都要求那串码是完整可读的，
 * 而现场最常见的失败恰恰是「读不全」—— 磨花的小票、反光的屏幕、
 * 邻居只记得后四位。此前走到这一步店主就没辙了，只能让人回家找码。
 */
const candidates = ref<PickupOrder[] | null>(null);

async function verify(input?: string) {
  const c = (input ?? code.value).trim();
  if (!c || busy.value) return;
  busy.value = true;
  error.value = "";
  candidates.value = null;
  try {
    const r = await api.mVerify(c);
    /*
     * **失败也是 code 0**：后端把「码无效 / 已核销 / 不是本点」当成业务结果回，
     * 不抛错。此前这里只 catch 异常，于是任何一次失败都走进成功分支 ——
     * 输一个不存在的码，界面照样提示「核销成功」。
     * 实测过：现场店主会把货交给一个拿废码的人，而且没有任何记录说这单没核掉。
     */
    if (!r.success) {
      throw new Error(t(`verify.reason.${r.reason ?? "UNKNOWN"}`));
    }
    justDone.value = r.subOrderNo ?? "";
    code.value = "";
    uni.showToast({ title: t("verify.done"), icon: "none" });
    await load();
  } catch (e) {
    // 失败原因必须说清楚：已核销 / 不在本点 / 码无效，三种处理方式完全不同
    error.value = (e as Error).message;
    /*
     * 顺手按片段搜一次。**不自动核销搜到的那单** ——
     * 片段可能命中多单，替他选一单就是替他承担「核错人」的风险；
     * 而列出来让他确认，只多一次点击。
     */
    candidates.value = await api.mVerifySearch(c).catch(() => []);
  } finally {
    busy.value = false;
  }
}

/** 从候选里确认核销：这时用的是完整码，走的还是同一条核销路径 */
async function verifyCandidate(o: PickupOrder) {
  candidates.value = null;
  await verify(o.verifyCode);
}

async function scan() {
  // 用户取消扫码也会 reject，这里静默吞掉 —— 取消不是错误，不该弹提示
  const result = await scanCode().catch(() => "");
  if (result) await verify(result);
}

/**
 * 批量核销：连续扫多张码，最后一次提交（B-6.4 扩展，后端已实现 `/biz/pickup/verify/batch`）。
 *
 * 为什么不是「扫一张核销一张」：高峰期一次来七八个邻居，逐张扫要等七八次网络往返，
 * 中间任何一次失败还会打断节奏。攒起来一次提交，失败的逐条回报，店主一眼看到是哪几张有问题。
 */
const batchMode = ref(false);
const basket = ref<string[]>([]);
const batchResult = ref<VerifyBatchResult | null>(null);

function addToBasket(c: string) {
  const v = c.trim();
  if (!v) return;
  // 同一张码扫两次是常见误操作（没看清有没有扫上），去重比报错友好
  if (!basket.value.includes(v)) basket.value.push(v);
  code.value = "";
}

async function scanIntoBasket() {
  const result = await scanCode().catch(() => "");
  if (result) addToBasket(result);
}

async function submitBatch() {
  if (!basket.value.length || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    batchResult.value = await api.mVerifyBatch([...basket.value]);
    basket.value = [];
    await load();
  } finally {
    busy.value = false;
  }
}

function exitBatch() {
  batchMode.value = false;
  basket.value = [];
  batchResult.value = null;
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="verify.title" :denied="!merchant.can('biz:verify')">
    <text class="sh-h1">{{ $t("verify.title") }}</text>

    <!--
      两种核销并排。**不自动识别码的类型**：码长得像，认错的代价是
      核销掉不该核的东西，而线下核销不可撤销 —— 让店员说出他在核什么。
    -->
    <view class="tabs">
      <text
        class="sh-chip"
        :class="{ 'sh-chip--primary': tab === 'pickup' }"
        @tap="tab = 'pickup'"
      >{{ $t("verify.tabPickup") }}</text>
      <text
        class="sh-chip"
        :class="{ 'sh-chip--primary': tab === 'coupon' }"
        @tap="tab = 'coupon'"
      >{{ $t("verify.tabCoupon") }}</text>
    </view>

    <!-- 券核销：先看后核 -->
    <view v-if="tab === 'coupon'" class="sh-card entry">
      <view class="row">
        <input
          v-model="couponCode"
          class="field__input sh-num"
          :placeholder="$t('verify.couponCodePh')"
          confirm-type="done"
          @confirm="peekCoupon()"
        />
        <text class="btn" @tap="peekCoupon()">{{ $t("verify.couponPeek") }}</text>
      </view>
      <view class="sh-btn sh-btn--soft scan" @tap="scanCoupon">{{ $t("verify.scan") }}</view>
      <text v-if="couponError" class="err">{{ couponError }}</text>

      <view v-if="couponView" class="peek">
        <text class="peek__t">{{ couponView.title }}</text>
        <text class="peek__b">{{ couponView.benefitText }}</text>
        <text class="sh-muted peek__d">
          {{ $t("verify.couponHolder", { tail: couponView.phoneTail || "----" }) }}
          <template v-if="couponView.timesTotal > 1">
            · {{ $t("verify.couponRemaining", { n: couponView.remaining, m: couponView.timesTotal }) }}
          </template>
        </text>
        <text v-if="!couponView.redeemable" class="err">
          {{ $t(`verify.couponReason.${couponView.reason}`) }}
        </text>

        <!-- 按钮上就写「不可撤销」：确认框里再写一遍已经晚了半步 -->
        <button
          v-if="couponView.redeemable"
          class="sh-btn sh-btn--primary redeem"
          :disabled="busy"
          @tap="redeemCoupon"
        >
          {{ $t("verify.couponRedeem") }}
        </button>
      </view>

      <view v-if="couponDone" class="done">{{ couponDone }}</view>
    </view>

    <template v-if="tab === 'pickup'">

    <!--
      承接方一进来最关心的数：还有几单没人取。**只留这一个**——
      「今日到货批次」「履约服务费」两格口径未定（R15/B9），后端一期恒发 0，
      跟真实的待核销数字并排显示会被当成"今天真没到货/没收入"长期误读。
      口径定了再放出来，现在藏起来，不是造一个假 0。
    -->
    <view v-if="overview" class="sh-card overview">
      <text class="overview__name">{{ overview.pickupName }}</text>
      <view class="overview__grid overview__grid--single">
        <view class="overview__i">
          <text class="overview__n sh-num" :class="{ 'is-on': overview.pendingVerify }">
            <!-- 用列表算，不用后端那个计数：两处各算一次就会出现
                 「总览说 1 单、下面说没有」，而这正是实测到的那一幕 -->
            {{ waiting.length }}
          </text>
          <text class="sh-muted">{{ $t("verify.ovPending") }}</text>
        </view>
      </view>
    </view>

    <!-- 单张核销 -->
    <view v-if="!batchMode" class="sh-card entry">
      <view class="row">
        <input
          v-model="code"
          class="field__input sh-num"
          :placeholder="$t('verify.codePh')"
          confirm-type="done"
          @confirm="verify()"
        />
        <text class="btn" @tap="verify()">{{ $t("verify.submit") }}</text>
      </view>
      <view class="sh-btn sh-btn--soft scan" @tap="scan">{{ $t("verify.scan") }}</view>
      <text v-if="error" class="err">{{ error }}</text>

      <!--
        输码没核销掉时，按这几位搜出来的候选。**让他确认是哪一单，而不是替他选** ——
        片段可能命中多单，替他选就是替他承担「核错人」的风险。
      -->
      <view v-if="candidates" class="cands">
        <text v-if="!candidates.length" class="sh-muted">{{ $t("verify.searchEmpty") }}</text>
        <template v-else>
          <text class="sh-muted cands__hint">{{ $t("verify.searchHint") }}</text>
          <view v-for="c in candidates" :key="c.subOrderNo" class="cand">
            <view class="cand__main">
              <text class="cand__code sh-num">{{ c.verifyCode }}</text>
              <text class="sh-muted">{{ c.buyerNickname || "—" }}</text>
            </view>
            <text class="btn" @tap="verifyCandidate(c)">{{ $t("verify.submit") }}</text>
          </view>
        </template>
      </view>

      <!-- 高峰期一次来七八个邻居，逐张扫要等七八次往返 -->
      <text class="sh-link" @tap="batchMode = true">{{ $t("verify.batchEnter") }}</text>
    </view>

    <!-- 批量核销：连续扫码攒起来，最后一次提交 -->
    <view v-else class="sh-card entry">
      <view class="row">
        <input
          v-model="code"
          class="field__input sh-num"
          :placeholder="$t('verify.codePh')"
          confirm-type="done"
          @confirm="addToBasket(code)"
        />
        <text class="btn" @tap="addToBasket(code)">{{ $t("verify.batchAdd") }}</text>
      </view>
      <view class="sh-btn sh-btn--soft scan" @tap="scanIntoBasket">
        {{ $t("verify.batchScan") }}
      </view>

      <view v-if="basket.length" class="basket">
        <text
          v-for="c in basket"
          :key="c"
          class="sh-chip sh-num"
          @tap="basket = basket.filter((x) => x !== c)"
        >
          {{ c }} ✕
        </text>
      </view>

      <view
        class="sh-btn batch-submit"
        :class="{ 'is-disabled': !basket.length }"
        @tap="submitBatch"
      >
        {{ $t("verify.batchSubmit", { n: basket.length }) }}
      </view>

      <!-- 失败逐条回报：店主要知道是**哪几张**有问题，而不是「3 成功 2 失败」 -->
      <view v-if="batchResult" class="batch-result">
        <text class="ok">{{ $t("verify.batchOk", { n: batchResult.successCount }) }}</text>
        <view v-for="f in batchResult.failed" :key="f.code" class="fail">
          <text class="sh-num">{{ f.code }}</text>
          <text class="sh-muted">{{ f.reason }}</text>
        </view>
      </view>

      <text class="sh-link" @tap="exitBatch">{{ $t("verify.batchExit") }}</text>
    </view>

    <view class="list-head">
      <text class="sh-h2">{{ $t("verify.waiting") }}</text>
      <text class="sh-muted sh-num">{{ waiting.length }}</text>
    </view>

    <!--
      核销台空、分拣台有货，是最容易让商家困惑的一刻——直接说穿原因，
      不用他自己猜或者跑一趟分拣页才发现是"还没标到货"。
    -->
    <view
      v-if="!waiting.length && preparingCount && merchant.can('biz:receive')"
      class="prep-hint"
      @tap="goToPicking"
    >
      <text>{{ $t("picking.verifyPrepHint", { n: preparingCount }) }}</text>
      <text class="prep-hint__go">›</text>
    </view>
    <sh-empty v-else-if="!waiting.length" :text='$t("verify.empty")'></sh-empty>

    <view
      v-for="o in waiting"
      :key="o.subOrderNo"
      class="sh-card row-item"
      :class="{ 'is-just': justDone === o.subOrderNo }"
    >
      <view class="row-item__main">
        <text class="row-item__code sh-num">{{ o.verifyCode }}</text>
        <text class="sh-muted">{{ o.buyerNickname || "—" }} · {{ o.items.length }} 件</text>
      </view>
      <text class="btn" @tap="verify(o.verifyCode)">{{ $t("verify.doIt") }}</text>
    </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 12rpx;
  margin: 16rpx 0;
}
.peek {
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 2rpx solid var(--sh-faint);
}
.peek__t {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
}
.peek__b {
  display: block;
  margin-top: 6rpx;
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.peek__d {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
}
.redeem {
  margin-top: 20rpx;
}
.done {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: 12rpx;
  background: var(--sh-success-tint);
  font-size: 26rpx;
}

.overview {
  margin-bottom: 14rpx;
}
.overview__name {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.overview__grid {
  display: flex;
  margin-top: 16rpx;
}
.overview__i {
  flex: 1;
  text-align: center;
}
/* 只剩一格时不用撑满整行居中——那样看着像在藏什么，靠左更像"这里本来就只有一个数" */
.overview__grid--single {
  justify-content: flex-start;
}
.overview__grid--single .overview__i {
  flex: none;
  text-align: left;
}
.overview__n {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-sub);
  margin-bottom: 6rpx;
}
/* 有待核销才点亮 —— 全是灰的时候一眼就知道没活儿 */
.overview__n.is-on {
  color: var(--sh-primary-text);
}

.basket {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 20rpx;
}
.batch-submit {
  margin-top: 20rpx;
}
.batch-result {
  margin-top: 20rpx;
  padding-top: 16rpx;
}
.batch-result .ok {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.fail {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: var(--sh-danger);
}
.sh-link {
  display: block;
  margin-top: 20rpx;
}
.is-disabled {
  opacity: 0.45;
}

.entry {
  margin: 24rpx 0;
}
.row {
  display: flex;
  gap: 20rpx;
  align-items: center;
}
.btn {
  padding: 20rpx 32rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 28rpx;
  font-weight: 600;
}
.scan {
  margin-top: 20rpx;
}
.err {
  display: block;
  margin-top: 20rpx;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-danger-tint);
  color: var(--sh-danger);
  font-size: 28rpx;
}

.cands {
  margin-top: 20rpx;
}
.cands__hint {
  display: block;
  margin-bottom: 12rpx;
  font-size: 24rpx;
}
.cand {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 16rpx 0;
  border-top: 2rpx solid var(--sh-line);
}
.cand__main {
  flex: 1;
  min-width: 0;
}
.cand__code {
  display: block;
  font-size: 30rpx;
  /* 600 而不是 700：700 这个项目里只给价格留着，取货码靠字号和留白突出就够 */
  font-weight: 600;
  color: var(--sh-ink);
}
.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 32rpx 8rpx 16rpx;
}
.prep-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 20rpx 24rpx;
  margin: 0 8rpx;
  border-radius: 20rpx;
  background: var(--sh-warning-tint);
  color: var(--sh-warning);
  font-size: 26rpx;
}
.prep-hint__go {
  flex-shrink: 0;
  font-size: 28rpx;
}
/* 列表密度对齐 C 端（平台版式约定）：卡片之间只留一条缝、正文行高 1.35。
   商家一天要扫几十次这类列表，行距每多 10rpx，一屏就少一行。 */
.row-item {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-bottom: 10rpx;
}
.row-item.is-just {
  background: var(--sh-success-tint);
}
.row-item__main {
  flex: 1;
  min-width: 0;
}
.row-item__code {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  letter-spacing: 4rpx;
  color: var(--sh-ink);
}
</style>
