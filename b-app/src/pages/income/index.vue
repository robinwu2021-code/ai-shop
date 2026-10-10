<script setup lang="ts">
// 我的收入（B-11.9）。
//
// **四个数是四种状态，不是四个口袋** —— 它们加起来等于全部结算单。
//
// 在这一页之前，结算页只显示一个「商家实得」，读起来像已到手 ——
// 商家拿它去对银行流水，对不上就来找客服，而客服看到的状态也只有一个词。
// 更糟的是那个词曾经是「已分账」，而底下调的是桩实现：一分钱都没有真的动过。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { datetime, monthDay } from "@shared/utils/datetime";
import { ROUTES } from "@/shared/nav";
import type { DailyFlowPage, IncomeSummary, MyDebt, MySettleBatch } from "@shared/types";

const merchant = useMerchantStore();
const canView = computed(() => merchant.can("biz:finance"));

const sum = ref<IncomeSummary | null>(null);
const allStores = ref(false);
/**
 * 账期批次。**四个汇总数说的是「钱在哪一档」，批次说的是「哪天放、卡在哪」** ——
 * 后者是商家真正打客服电话问的那个问题，而这一页此前一个字都没答。
 */
const batches = ref<MySettleBatch[]>([]);
/**
 * 欠款。**与保证金方向相反，不合成一个「账户余额」**：
 * 保证金是他自己的钱，欠款是他欠平台的。合起来看的话，
 * 退店时「应退多少保证金」就永远算不清了。
 *
 * 余额为 0 时整块不显示 —— 绝大多数商家从没欠过，
 * 给每个人挂一行「欠款 ¥0.00」只会让人以为自己出了什么事。
 */
const debt = ref<MyDebt | null>(null);

/**
 * 每日流水：**按成交日**，与上面四档同一批结算单的另一种切法。
 *
 * 放在这一页而不是单开一屏：四档答「有多少」，账期答「哪天到」，
 * 这张表答「哪天挣的」—— 三个问题在同一屏上，商家才对得起来。
 * 顶部四档仍用 income 那个接口，**这里不重算一遍**：
 * 两处各算一次必然漂移，而漂移的那天没人会发现。
 */
const daily = ref<DailyFlowPage | null>(null);

/**
 * 每日流水看哪一段。**默认仍是「近 30 天」** —— 改成「本月」的话，
 * 月初打开只剩一两行，商家第一反应是「我的数据没了」。
 *
 * 只给这三档，不给日历选择器：`/bills` 与 `/daily-flow` 底下都是无分页全量查询，
 * 放开任意区间等于放开一个没有上限的查询（见 TDD §2「明确不做」）。
 */
/*
 * ⚠️ **词条键写成字面量**，不用 `$t(`income.range${r}`)` 拼。
 * 动态键不进 i18n 对账：这三条会被当成没人用的孤儿词条，
 * 而真正缺词条的那天页面直接露出裸 key。与结算页 `SCOPES` 同一个写法。
 */
const RANGES = [
  { key: "last30", labelKey: "income.rangeLast30" },
  { key: "thisMonth", labelKey: "income.rangeThisMonth" },
  { key: "lastMonth", labelKey: "income.rangeLastMonth" },
] as const;
type Range = (typeof RANGES)[number]["key"];
const range = ref<Range>("last30");

/** `yyyy-MM-dd`。**不用 toISOString** —— 它按 UTC 切，东八区的今天会变成昨天 */
function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/**
 * 区间 → `{from, to}`。**`last30` 返回空** —— 让后端用它自己那个默认，
 * 端上再算一遍近 30 天的话，两处对 "30 天" 的理解迟早差一天。
 */
function rangeQuery(r: Range): { from?: string; to?: string } {
  if (r === "last30") return {};
  const now = new Date();
  const first = new Date(now.getFullYear(), now.getMonth() + (r === "lastMonth" ? -1 : 0), 1);
  // 下个月 0 号 = 这个月最后一天，不用自己判闰年与月长
  const last = new Date(first.getFullYear(), first.getMonth() + 1, 0);
  return { from: ymd(first), to: ymd(last) };
}

function switchRange(r: Range) {
  range.value = r;
  void load();
}

/**
 * 点开某一天 → 结算单页只看那一天。
 *
 * **复用结算单页，不在这儿再画一份逐笔。** 那一页的逐笔渲染里有实测攒出来的东西：
 * 运费四态、批次挂起原话照抄、多店才显示门店与收款号 —— 抄一份必然走岔。
 */
function openDay(day: string) {
  uni.navigateTo({ url: `${ROUTES.settle}?day=${day}` });
}

/**
 * 在途卡了多久。**只给金额的话商家看不出是一笔大的还是很多笔**，
 * 而「卡了多久」才是他真正想问的 —— 客服也是。
 */
const stuckDays = computed(() => {
  const at = sum.value?.oldestInFlightAt;
  if (!at) return 0;
  return Math.floor((Date.now() - at) / 86_400_000);
});

/** 批次状态色调按**「球在谁那边」**分：挂起要他知道，已放款是好消息，其余是过程态 */
function batchTone(st: MySettleBatch["status"]) {
  if (st === "BLOCKED") return "sh-chip--warning";
  if (st === "RELEASED") return "sh-chip--primary";
  return "";
}

/**
 * 带符号的金额。**符号与数字要在同一个表达式里出来** ——
 * 拆成两段插值的话，模板里会出现一个「整个元素就是一个字符」的节点，
 * 而那正是把字符当图标用的写法（守卫盯着它）。
 */
function signed(minor: number) {
  return `${minor > 0 ? "+" : "−"}${money(Math.abs(minor))}`;
}

/** 这次没取到。**与「这儿本来就没有」是两件事** —— 整页内容都挂在拉来的数据后面，
 *  拉不到就是一个只有标题栏的空白页。交给 `sh-scaffold` 的 `failed` 说出来 */
const failed = ref(false);

async function load() {
  /*
   * 三件事各自 catch：账期与欠款是本批新接的口子，
   * 老后端上会 404 —— 绑在一起的话，一个新功能会把整页收入数据带走，
   * 而收入才是这一页存在的理由。
   */
  try {
    const [s, b, d, f] = await Promise.all([
      api.mIncomeSummary(allStores.value),
      api.mSettleBatches().catch(() => []),
      api.mMyDebt().catch(() => null),
      // 每日流水也单独 catch：它是新接的口子，老后端上 404，不该把收入带走
      api.mDailyFlow({ allStores: allStores.value, ...rangeQuery(range.value) }).catch(() => null),
    ]);
    sum.value = s;
    batches.value = b;
    debt.value = d;
    daily.value = f;
    failed.value = false;
  } catch {
    // 收入那一件没兜底（见上），它挂了整页就没内容 —— 那正是要说出来的时候
    failed.value = true;
  }
}

function toggleScope() {
  allStores.value = !allStores.value;
  void load();
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="income.title" :denied="!canView"
    :failed="failed"
    @retry="load"
  >
    <template v-if="sum">
      <view class="txt-sub scope txt-primary" @tap="toggleScope">
        {{ allStores ? $t("income.scopeAll") : $t("income.scopeCurrent") }}
      </view>

      <view class="sh-card">
        <text class="sh-muted">{{ $t("income.received") }}</text>
        <text class="txt-mega amt sh-num">{{ money(sum.receivedMinor) }}</text>
        <text class="txt-caption sub sh-muted">{{ $t("income.receivedHint") }}</text>
      </view>

      <!--
        在途这一档是本批新拆出来的。**此前它混在「已到账」里** ——
        而底下是桩实现，那些钱一分都没动过，商家却以为收到了。
      -->
      <view v-if="sum.inFlightMinor > 0" class="sh-card sh-mt-sm hold">
        <view class="line sh-row sh-row--between sh-row--baseline">
          <text class="sh-muted">{{ $t("income.inFlight") }}</text>
          <text class="txt-price sh-num">{{ money(sum.inFlightMinor) }}</text>
        </view>
        <text class="txt-caption sub sh-muted">
          {{ $t("income.inFlightHint", { n: sum.inFlightCount }) }}
          <text v-if="stuckDays > 0">　{{ $t("income.stuckDays", { d: stuckDays }) }}</text>
        </text>
        <text v-if="sum.oldestInFlightAt" class="txt-caption sub sh-muted sh-num">
          {{ datetime(sum.oldestInFlightAt) }}
        </text>
      </view>

      <view class="sh-card sh-mt-sm">
        <view class="line sh-row sh-row--between sh-row--baseline">
          <text class="sh-muted">{{ $t("income.pending") }}</text>
          <text class="txt-price sh-num">{{ money(sum.pendingMinor) }}</text>
        </view>
        <text class="txt-caption sub sh-muted">{{ $t("income.pendingHint") }}</text>
      </view>

      <!--
        当面收款：**这部分他早就拿到了**。
        不显示的话，他会以为平台还欠着这笔；混进「待结算」更糟。
      -->
      <view v-if="sum.offlineMinor > 0" class="sh-card sh-mt-sm">
        <view class="line sh-row sh-row--between sh-row--baseline">
          <text class="sh-muted">{{ $t("income.offline") }}</text>
          <text class="txt-price sh-num">{{ money(sum.offlineMinor) }}</text>
        </view>
        <text class="txt-caption sub sh-muted">{{ $t("income.offlineHint") }}</text>
      </view>
    </template>

    <!--
      我的账期。**放在四档汇总之后** —— 先回答「有多少」，再回答「哪天到」。
      顺序反过来的话，打开这一页第一眼看到的是一串批次号，
      而商家想看的第一个数是钱。
    -->
    <view v-if="batches.length" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("income.batchTitle") }}</text>
      <text class="txt-caption sub sh-muted">{{ $t("income.batchHint") }}</text>
      <view v-for="b in batches" :key="b.batchNo" class="batch">
        <view class="sh-row sh-row--between sh-row--baseline">
          <view class="sh-row">
            <text class="sh-num">{{ monthDay(b.dueAt) }}</text>
            <text class="sh-chip batch__chip" :class="batchTone(b.status)">
              {{ $t(`settle.batchStatus${b.status}`) }}
            </text>
          </view>
          <text class="txt-price sh-num">{{ money(b.netMinor) }}</text>
        </view>
        <text class="txt-caption sub sh-muted sh-num">
          {{ b.batchNo }}　{{ $t("income.batchBills", { n: b.billCount }) }}
        </text>
        <!--
          放款到哪一步了（V391）。批次「已放款」只说放了，没说钱到哪一步 ——
          商家打客服电话问的正是后者。凭证号给出来：他拿它对自己的银行到账记录。
        -->
        <text v-if="b.paymentRef" class="txt-caption sub sh-num">
          {{ $t("income.batchPaid", { ref: b.paymentRef, d: b.paidAt ? monthDay(b.paidAt) : "" }) }}
        </text>
        <text v-else-if="b.payoutStatus === 'PENDING' || b.payoutStatus === 'EXPORTED'" class="txt-caption sub sh-muted">
          {{ $t("income.batchPayoutPending") }}
        </text>
        <text v-else-if="b.payoutStatus === 'FAILED'" class="txt-caption sub is-warning">
          {{ $t("income.batchPayoutFailed") }}
        </text>
        <!--
          挂起原因**原样展示后端那句话**：它含具体数字与阈值，
          在端上再拼一遍的话，商家看到的和运营看到的就不是同一句 ——
          而客服正是照着运营那句话答的。
        -->
        <template v-if="b.blockedReason">
          <text class="txt-caption sub is-warning">{{ b.blockedReason }}</text>
          <text v-if="b.blockExpireAt" class="txt-caption sub sh-muted">
            {{ $t("income.batchExpire", { d: monthDay(b.blockExpireAt) }) }}
          </text>
        </template>
      </view>
    </view>

    <!--
      每日流水。**放在账期之后** —— 先答「有多少」「哪天到」，再答「哪天挣的」。
      没有流水的那天不占一行：补零会让一屏里大半是空行，
      而商家要找的是有动静的那几天。
    -->
    <view v-if="daily" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("income.dailyTitle") }}</text>
      <text class="txt-caption sub sh-muted">{{ $t("income.dailyHint") }}</text>

      <!--
        看哪一段。**默认仍是「近 30 天」** —— 改成「本月」的话月初只剩一两行，
        商家第一反应是「我的数据没了」。
      -->
      <view class="ranges sh-row">
        <text
          v-for="r in RANGES"
          :key="r.key"
          class="sh-chip"
          :class="{ 'sh-chip--primary': range === r.key }"
          @tap="switchRange(r.key)"
        >{{ $t(r.labelKey) }}</text>
      </view>

      <!--
        整行可点 → 结算单页只看那一天。**此前这一行点不开**：
        商家看到某天的数不对，要核是哪几笔只能去结算单页，而那页是全量倒序、按不了天。
        右边给一枚箭头，不靠「这一行好像能点」让人猜。
      -->
      <view v-for="d in daily.days" :key="d.day" class="day" @tap="openDay(d.day)">
        <view class="sh-row sh-row--between sh-row--baseline">
          <text class="sh-num">{{ d.day }}</text>
          <view class="sh-row sh-row--baseline">
            <text class="txt-price sh-num">{{ money(d.netMinor) }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)" class="day__more"></sh-icon>
          </view>
        </view>
        <text class="txt-caption sub sh-muted">
          {{ $t("income.dailyBills", { n: d.billCount }) }}
          <!-- 退款只在有的时候出现：常态是没有，挂一行「退 ¥0.00」只会让人以为出了事 -->
          <text v-if="d.refundMinor > 0">　{{ $t("income.dailyRefund", { a: money(d.refundMinor) }) }}</text>
          <!--
            **佣金与服务费是扣款里最大的两笔**，而这一行此前一个字都没说。
            契约从一开始就把这两列送上来了（DailyFlowVO），端上零引用 ——
            于是「商家问『这个月我的钱少在哪』只剩这张表能答」只答了快递费那个小头。

            同样只在非零时出现：自带客流零佣金、非自提无服务费，
            天天挂两行 ¥0.00 是噪音。
          -->
          <text v-if="d.commissionMinor > 0">　{{ $t("income.dailyCommission", { a: money(d.commissionMinor) }) }}</text>
          <text v-if="d.serviceFeeMinor > 0">　{{ $t("income.dailyFee", { a: money(d.serviceFeeMinor) }) }}</text>
          <!--
            快递费同理：自提与自送的日子恒为 0，天天挂一行「快递费 ¥0.00」是噪音。
            而快递费是**他自己能改小的那一笔**（把商品重量填准），不说等于不让他改。
          -->
          <text v-if="d.freightCostMinor > 0">　{{ $t("income.dailyFreight", { a: money(d.freightCostMinor) }) }}</text>
        </text>
      </view>

      <!--
        没有成交日的存量单。**不能悄悄丢掉** —— 丢了的话商家把每天加起来
        会发现对不上上面的总览，而那种不一致他只会理解成「平台算错了我的钱」。
      -->
      <text v-if="daily.undatedCount > 0" class="txt-caption sub sh-muted">
        {{ $t("income.dailyUndated", { n: daily.undatedCount, a: money(daily.undatedMinor) }) }}
      </text>

      <text v-if="!daily.days.length && !daily.undatedCount" class="txt-caption sub sh-muted">
        {{ $t("income.dailyEmpty") }}
      </text>
    </view>

    <!--
      欠款。**余额为 0 时整块不出现** —— 这是绝大多数商家的常态。
      它也不参与上面四档的加减：那四档是「平台要给我的」，这一笔是「我欠平台的」。
    -->
    <view v-if="debt && debt.balanceMinor > 0" class="sh-card sh-mt-sm debt">
      <view class="line sh-row sh-row--between sh-row--baseline">
        <text class="sh-muted">{{ $t("income.debt") }}</text>
        <text class="txt-price sh-num is-warning">{{ money(debt.balanceMinor) }}</text>
      </view>
      <text class="txt-caption sub sh-muted">{{ $t("income.debtHint") }}</text>
      <view v-for="t in debt.txns" :key="t.txnNo" class="sh-row sh-row--between debt__row">
        <text class="txt-caption sh-muted">
          {{ monthDay(t.at) }}　{{ t.reason ?? t.sourceNo ?? t.batchNo }}
        </text>
        <!-- **带符号**：都显示成正数的话，一列数字里看不出哪笔是欠、哪笔是还 -->
        <text class="txt-caption sh-num" :class="t.amountMinor > 0 ? 'is-warning' : ''">
          {{ signed(t.amountMinor) }}
        </text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.amt {
  display: block;
  margin-top: 8rpx;
}
/* 同一页上比 .amt 小一档的金额（在途/待结/线下），名字要说清它是钱 */

.sub {
  display: block;
  margin-top: 8rpx;
}
.batch {
  margin-top: 24rpx;
}
.day {
  margin-top: 24rpx;
}
.day__more {
  /* 逻辑属性：阿语下整行翻转，箭头要留在金额的「末尾侧」而不是恒在右边 */
  margin-inline-start: 8rpx;
}
.ranges {
  margin-top: 16rpx;
  gap: 16rpx;
}
.batch__chip {
  /* 逻辑属性：阿语下徽标要跟着翻到日期的另一侧，写死 left 它不会翻 */
  margin-inline-start: 12rpx;
}
.debt__row {
  margin-top: 12rpx;
}

/* 在途那一档用暖色底：它是「要留意」而不是「有问题」 */
.hold { background: var(--sh-faint); }
</style>
