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
import type { IncomeSummary, MyDebt, MySettleBatch } from "@shared/types";

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

async function load() {
  /*
   * 三件事各自 catch：账期与欠款是本批新接的口子，
   * 老后端上会 404 —— 绑在一起的话，一个新功能会把整页收入数据带走，
   * 而收入才是这一页存在的理由。
   */
  const [s, b, d] = await Promise.all([
    api.mIncomeSummary(allStores.value),
    api.mSettleBatches().catch(() => []),
    api.mMyDebt().catch(() => null),
  ]);
  sum.value = s;
  batches.value = b;
  debt.value = d;
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
  <sh-scaffold title-key="income.title" :denied="!canView">
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
.batch__chip {
  margin-left: 12rpx;
}
.debt__row {
  margin-top: 12rpx;
}

/* 在途那一档用暖色底：它是「要留意」而不是「有问题」 */
.hold { background: var(--sh-faint); }
</style>
