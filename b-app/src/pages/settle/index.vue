<script setup lang="ts">
// 结算单（B-11.9）。
//
// 展示口径的三条硬规则：
//   1. **退款要扣回**。已分账的订单退款要先回退分账再退款（ADR-002 §3），
//      账面上不能出现「退过款还照结」的钱。
//   2. **佣金按客流来源分档**。自带客流建议零佣金 —— 他带来的客户在别家的消费才是
//      平台的收益（ADR-004 §6）。商家在这里看到自己带客的实际好处。
//   3. **履约服务费单列**。它是供货方付、自提点承接方收；本店两个角色都担时账面抵消，
//      但必须分别列出来，否则店主看不懂钱去哪了。
//
// ⚠️ 费率与服务费口径未定（B9/B10），页面上明确标注，不装作已经定了。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { monthDay } from "@shared/utils/datetime";
import { ROUTES } from "@/shared/nav";
import type {
  RateCard,
  SettleBill,
} from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();
const bills = ref<SettleBill[]>([]);
const rate = ref<RateCard | null>(null);
const allStores = ref(false);

/**
 * 只看某一天（`yyyy-MM-dd`，按**成交日**）。入口是收入页的每日流水那一行。
 *
 * ⚠️ **不叫 `tab`、不叫 `title` 之类** —— 查询参数名撞上 `sh-scaffold` 的属性名时，
 * 它会被当成外壳配置透下去（本仓库踩过：`?tab=` 让页面长出一条空菜单、返回键消失）。
 */
const day = ref("");

/**
 * 筛选态下**把无关的卡收起来**：四个入口卡、费率卡、积分卡。
 *
 * 商家是来核一天的账的，不是来查积分账本的。全留着的话，
 * 他要先划过三块与这一天无关的内容才看到那几笔。
 */
const dayMode = computed(() => Boolean(day.value));

/** 这一天的小计。**从筛出来的行自己加** —— 再调一次每日流水就是第二个真源 */
const daySum = computed(() => bills.value.reduce((n, b) => n + b.netMinor, 0));

function clearDay() {
  day.value = "";
  void load();
}

/**
 * 行上的日期。**给成交日，不给 createdAt。**
 *
 * 每日流水按成交日聚合，而 `createdAt` 是入库时刻 —— 两者可以差一天。
 * 点开「10-08 这一天」之后行上显示 10-07，看起来像筛坏了，
 * 而商家的下一步是打电话说「你们筛错了」。
 *
 * 存量行没有成交日，说出来而不是回落到 `createdAt`：回落的话它会显示成某一天，
 * 而那一天的每日流水里并没有它 —— 清单与流水从此对不上。
 */
function billDay(b: SettleBill) {
  return b.accruedAt == null ? String(t("settle.accruedNone")) : monthDay(b.accruedAt);
}

const SCOPES = [
  { all: false, labelKey: "settle.scopeCurrent" },
  { all: true, labelKey: "settle.scopeAll" },
];

const multiStore = computed(() => merchant.multiStore);

/**
 * 批次状态的色调判据是**「球在谁那边」**，不是状态好不好听：
 * BLOCKED 要商家知道（警告色），RELEASED 是好消息（主色），其余都是过程态（默认）。
 * 全都上色等于都没上色。
 */
function batchTone(st: string) {
  if (st === "BLOCKED") return "sh-chip--warning";
  if (st === "RELEASED") return "sh-chip--primary";
  return "";
}

/** 万分比 → 百分数。后端存的是万分比整数（2% = 200），直接显示会变成 200% */
const pct = (bp: number) => `${(bp / 100).toFixed(bp % 100 === 0 ? 0 : 2)}%`;

/**
 * 快递费那一行右边的小标签。**没有要说的就不给标签**（返回空串）。
 *
 * 四种情形要说四句不同的话，而金额本身分不开它们：
 *   商家自寄        ¥0.00  平台没垫钱，不扣 —— 他不必管
 *   代寄未称重      ¥0.00  要扣，只是快递100 还没回传 —— 他要等
 *   代寄已扣        -¥X    正常
 *   超重 / 超上限   -¥X    他会问「为什么比运费多」，得先答上来
 */
function freightTag(b: SettleBill): string {
  if (b.freightShipMode === "MERCHANT_SELF") {
    return t("settle.freightSelf");
  }
  if (b.freightDiffReason === "OVER_CAP") {
    return t("settle.freightOverCap");
  }
  if (b.freightDiffReason === "OVERWEIGHT") {
    return t("settle.freightOverweight");
  }
  // 代寄而金额还是 0：称重回传还没到
  return b.freightCostMinor > 0 ? "" : t("settle.freightPending");
}

/** 流水上是门店号，商家认的是门店名。查不到就原样显示号 —— 空白比一个号更难查 */
function storeName(storeNo?: string) {
  if (!storeNo) return "—";
  return merchant.stores.find((s) => s.storeNo === storeNo)?.name ?? storeNo;
}

function switchScope(all: boolean) {
  allStores.value = all;
  void load();
}

/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);
async function load() {
  try {
    [bills.value, rate.value] = await Promise.all([
      api.mSettleList(allStores.value, day.value || undefined),
      api.mRateCard(),
    ]);
    failed.value = false;
  } catch {
    // 账单没兜底（见上）：它挂了这一屏就没内容 —— 而「本期没有可结算的单」
    // 与「没取到」在界面上一模一样，前者该等下个账期，后者该重试
    failed.value = true;
  }
}

function go(url: string) {
  uni.navigateTo({ url });
}

/*
 * `onLoad` 而不是 `onShow`：参数只在进页面那一次给，
 * 而 `onShow` 每次回到前台都跑 —— 放那儿会在从二级页返回时把筛选态重置。
 */
onLoad((q) => {
  day.value = (q?.day as string) ?? "";
});

onShow(() => {
  void load();
  /*
   * 门店列表：页内那个「按门店 / 全部门店」的作用域切换靠它判多店。
   * 不拉的话整条消失，而账还是按某一家店的范围出的 ——
   * 钱的口径没写出来，比数字本身更容易吵起来。
   */
  void merchant.ensureStores().catch(() => null);
});
</script>

<template>
  <sh-scaffold title-key="settle.title" :denied="!merchant.can('biz:finance')">
    <text class="txt-display">{{ $t("settle.title") }}</text>

    <!--
      只看某一天。**从收入页的每日流水那一行点进来的。**
      小计从筛出来的行自己加，不再调一次每日流水 —— 那会是第二个真源，
      而两处对不上的那天商家只会理解成「平台算错了我的钱」。
    -->
    <view v-if="dayMode" class="sh-card dayfilter">
      <view class="sh-row sh-row--between sh-row--baseline">
        <text class="txt-title sh-num">{{ $t("settle.dayFilter", { d: day }) }}</text>
        <text class="sh-link" @tap="clearDay">{{ $t("settle.dayClear") }}</text>
      </view>
      <text class="txt-caption sh-muted dayfilter__sum sh-num">
        {{ $t("settle.dayBills", { n: bills.length, a: money(daySum) }) }}
      </text>
    </view>

    <!--
      入口摆在最上面。**它们是「钱去哪了」的另外几半** ——
      结算单说的是「挣了多少」，收款账户说的是「打到哪张卡」，
      保证金说的是「押着多少、还差多少」。
      （提现入口已撤：钱按账期打，不走申请，见 ADR-011。）
    -->
    <view v-if="!dayMode" class="entries sh-row">
      <view class="entries__item sh-card" @tap="go(ROUTES.payoutAccount)">
        <text class="txt-title">{{ $t("payoutAccount.title") }}</text>
        <text class="sh-muted entries__hint">{{ $t("settle.entryPayoutAccount") }}</text>
      </view>
      <view class="entries__item sh-card" @tap="go(ROUTES.deposit)">
        <text class="txt-title">{{ $t("deposit.title") }}</text>
        <text class="sh-muted entries__hint">{{ $t("settle.entryDeposit") }}</text>
      </view>
      <view class="entries__item sh-card" @tap="go(ROUTES.invoice)">
        <text class="txt-title">{{ $t("invoice.title") }}</text>
        <text class="sh-muted entries__hint">{{ $t("settle.entryInvoice") }}</text>
      </view>
      <view class="entries__item sh-card" @tap="go(ROUTES.statement)">
        <text class="txt-title">{{ $t("statement.title") }}</text>
        <text class="sh-muted entries__hint">{{ $t("settle.entryStatement") }}</text>
      </view>
    </view>

    <!-- 费率卡放在账单**之前**：先说清楚怎么算，再看算出来多少。
         把费率讲明白是「自带客流零佣金」这个策略能起作用的前提 —— 商家算不清自己能拿多少，
         就不会有动力把老客带进来 -->
    <view v-if="rate && !dayMode" class="sh-card ratecard">
      <text class="txt-title">{{ $t("settle.rateTitle") }}</text>
      <view class="ratecard__row sh-row sh-row--between">
        <text class="sh-chip sh-chip--primary">{{ $t("order.trafficMERCHANT_OWNED") }}</text>
        <text class="txt-body sh-num">{{ pct(rate.merchantOwnedRate) }}</text>
      </view>
      <view class="ratecard__row sh-row sh-row--between">
        <text class="sh-chip">{{ $t("order.trafficPLATFORM") }}</text>
        <text class="txt-body sh-num">{{ pct(rate.platformRate) }}</text>
      </view>
      <text class="sh-muted ratecard__note">{{ rate.note }}</text>
    </view>

    <view v-if="!dayMode" class="sh-card" @tap="go('/pages/points/index')">
      <text class="txt-title">{{ $t("points.storeLedger") }}</text>
      <text class="sh-muted">{{ $t("points.storeLedgerHint") }}</text>
    </view>

    <!--
      门店范围。**多店才显示** —— 单店商家看到「全部门店」只会疑惑还有别的店。
      钱的作用域与订单页共用同一套惯例（allStores + 后端 allowedStoresOrAll），
      不另写一套：两套实现迟早有一套忘了跟上授权模型的变化。
    -->
    <view v-if="multiStore" class="scope">
      <text
        v-for="opt in SCOPES"
        :key="String(opt.all)"
        class="sh-chip"
        :class="{ 'sh-chip--primary': allStores === opt.all }"
        @tap="switchScope(opt.all)"
      >{{ $t(opt.labelKey) }}</text>
    </view>

    <sh-empty v-if="!bills.length"
          :failed="failed"
          @retry="load" :text='$t("settle.empty")'></sh-empty>

    <!--
      **一笔子订单一行**，不是周期账单 —— 后端 stl_bill 就是这个粒度。
      此前这里按「周账单」渲染（billNo / periodStart / orderCount），
      而那些字段后端从来没有过：mock 下好看，连真后端整片空白。
    -->
    <view v-for="b in bills" :key="b.settleNo" class="sh-card bill">
      <view class="bill__head sh-row sh-row--between">
        <!-- 成交日，不是入库时刻 —— 见 billDay() 的理由 -->
        <text class="txt-strong sh-num">{{ billDay(b) }}</text>
        <text
          class="sh-chip"
          :class="b.status === 'SPLIT' ? 'sh-chip--primary' : 'sh-chip--warning'"
        >{{ $t(`settle.status${b.status}`) }}</text>
      </view>

      <view class="bill__amount sh-row sh-row--between sh-row--baseline">
        <text class="sh-muted">{{ $t("settle.net") }}</text>
        <text class="txt-hero sh-num">{{ money(b.netMinor) }}</text>
      </view>

      <view class="rows">
        <view class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.gross") }}</text>
          <text class="sh-num">{{ money(b.grossMinor) }}</text>
        </view>
        <!--
          运费两行**只在快递单出现**（freightShipMode 为空 = 自提或自送，没有快递费这回事）。
          非快递单硬摆两个 ¥0.00，是给最常见的那类单加两行噪音。
        -->
        <view v-if="b.freightShipMode" class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.freightIncome") }}</text>
          <text class="sh-num">+{{ money(b.freightIncomeMinor) }}</text>
        </view>
        <view v-if="b.freightShipMode" class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.freightCost") }}</text>
          <!--
            **同样是 ¥0.00，两件事**：商家自寄（平台没垫钱，不扣）与
            平台代寄但还没称重回传（要扣，只是数还没回来）。
            只显示金额的话这两行长得一模一样，而商家的下一步完全不同 ——
            前者不必管，后者要等。判据取 freightShipMode，不是「金额是不是 0」。
          -->
          <view class="sh-row">
            <text v-if="freightTag(b)" class="sh-chip row__tag">{{ freightTag(b) }}</text>
            <text class="sh-num" :class="{ 'is-danger': b.freightCostMinor > 0 }">
              {{ b.freightCostMinor > 0 ? "-" + money(b.freightCostMinor) : money(0) }}
            </text>
          </view>
        </view>
        <view class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.commission") }}（{{ pct(b.commissionRate) }}）</text>
          <text class="sh-num is-danger">-{{ money(b.commissionMinor) }}</text>
        </view>
        <view class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.fulfillFee") }}</text>
          <text class="sh-num is-danger">-{{ money(b.serviceFeeMinor) }}</text>
        </view>
        <!--
          **「什么时候到」和「多少钱」是两个问题**，这一页此前只答了后一个。
          商家拿一个金额去对银行流水，对不上就来找客服，
          而客服看到的也只有同一个金额 —— 那通电话谁都答不上来。

          三档说的是三件不同的事，不能合成一句：
            没有 settleableAt = 售后期还没过（**这个他能自己推进**：催买家确认收货）；
            有应结日 = 哪天放（等着就行）；
            批次挂起 = 卡住了，原因照抄后端原话（含数字与阈值）。
        -->
        <view class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.dueAt") }}</text>
          <text v-if="b.dueAt" class="sh-num">{{ monthDay(b.dueAt) }}</text>
          <text v-else class="sh-muted">{{ $t("settle.notSettleable") }}</text>
        </view>
        <view v-if="b.batchStatus" class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.batchNo") }}</text>
          <view class="sh-row">
            <text v-if="b.batchDocumentNo" class="sh-num">{{ b.batchDocumentNo }}</text>
            <text class="sh-chip batch__chip" :class="batchTone(b.batchStatus)">
              {{ $t(`settle.batchStatus${b.batchStatus}`) }}
            </text>
          </view>
        </view>
        <text v-if="b.batchBlockedReason" class="sh-hint row batch__why">
          {{ b.batchBlockedReason }}
        </text>
        <!-- 多店商家必须看得见「哪家店挣的」和「打给哪个号」：
             只给其中一个，他就无法回答「河坊街店这个月的钱进了哪张卡」 -->
        <view v-if="multiStore" class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.store") }}</text>
          <text>{{ storeName(b.storeNo) }}</text>
        </view>
        <view v-if="multiStore && b.payMerchantNo" class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.payTo") }}</text>
          <text class="sh-num">{{ b.payMerchantNo }}</text>
        </view>
      </view>
    </view>

    <text class="tip sh-hint">{{ $t("settle.rateHint") }}</text>
    <text class="tip sh-hint">{{ $t("settle.pendingHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>

.entries {
  /*
   * 四个入口，一行放不下 —— 两列换行。一行四个的话每个只剩指甲盖宽，
   * 标题会折成两行而副文案挤没。
   *
   * ⚠️ **不写 margin-top**：`.sh-scaffold > * + *` 已经给了统一的块间距，
   * 顶层块自己再写一条就压过它 —— 这一页的间距从此与别处不同，
   * 而它长得像「本该如此」，没人会去量。
   */
  flex-wrap: wrap;
  gap: 16rpx;
}
.entries__item {
  /*
   * ⚠️ **必须 border-box。** sh-card 是 content-box（项目默认），
   * 于是 flex-basis 算的是**内容宽**，再加上左右各 24rpx 内边距就超过一半，
   * 两个放不下 —— 表现是四张卡竖着排、右半边空着。
   * 计算样式里 flex-basis 一切正常（171.5px），只有量**盒子的实际宽度**
   * 才看得出它是 196px。
   */
  box-sizing: border-box;
  flex: 0 0 calc(50% - 8rpx);
}
.entries__hint {
  display: block;
  margin-top: 8rpx;
}

.ratecard__row {
  margin-top: 16rpx;
}

.ratecard__note {
  display: block;
  margin-top: 16rpx;
}

.batch__chip {
  /* 逻辑属性：阿语下徽标要跟着翻到日期的另一侧，写死 left 它不会翻 */
  margin-inline-start: 12rpx;
}
.batch__why {
  display: block;
}

.dayfilter__sum {
  display: block;
  margin-top: 8rpx;
}

.bill__amount {
  margin: 24rpx 0;
}

.rows {
  border-radius: 24rpx;
  background: var(--sh-faint);
  padding: 8rpx 24rpx;
}
/*
 * 金额左边的小标签：说清这个 ¥0 是「不用扣」还是「还没回传」。
 *
 * **底色要换成 surface**：`.sh-chip` 默认底是 `--sh-faint`，而它正好是
 * 外层 `.rows` 的底色（同一个 #E4E5E8）—— 直接用等于把标签画成隐形。
 * 截图里那一行看着像裸文字，而 class 是在的、圆角也生效了，
 * 只有把两处的值摆在一起才看得出是同色。
 */
.row__tag {
  /* 逻辑属性：阿语下整行翻转，标签要留在金额的「起始侧之前」而不是恒在左边 */
  margin-inline-end: 12rpx;
  background: var(--sh-surface);
  color: var(--sh-sub);
}

.row {
  padding: 16rpx 0;
}
.tip {
  margin: 0 8rpx;
}
</style>
