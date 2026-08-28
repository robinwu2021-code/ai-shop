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
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { monthDay } from "@shared/utils/datetime";
import { confirm } from "@ai-shop/ui/prompt";
import type {
  MerchantPointAccount,
  MerchantPointsRecord,
  RateCard,
  SettleBill,
} from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();
const bills = ref<SettleBill[]>([]);
const rate = ref<RateCard | null>(null);
const allStores = ref(false);
/**
 * 本店积分：**发分服务费是商家唯一感知到的积分成本**，它是一笔真金白银的支出。
 *
 * 放在结算页而不是单开一页：与账单是同一个人在同一个场景下看的（「这个月我付了多少」）。
 * 单开一页会让「钱」这件事在 B 端有两个入口，而它们本来就该一起看。
 *
 * 此前后端与契约都在，**没有任何一页调用它** —— 于是老板既看不到这笔费用，也关不掉它。
 */
const points = ref<MerchantPointAccount | null>(null);
const togglingPoints = ref(false);
/**
 * 发分服务费明细（一单一条）。**点开才拉** ——
 * 多数时候店主只想看一眼这个月花了多少，不需要逐单核对；
 * 跟着页面一起拉会让结算页多等一个请求，而那个请求大部分时候没人看。
 */
const pointsRecords = ref<MerchantPointsRecord[] | null>(null);

const SCOPES = [
  { all: false, labelKey: "settle.scopeCurrent" },
  { all: true, labelKey: "settle.scopeAll" },
];

const multiStore = computed(() => merchant.multiStore);

/** 万分比 → 百分数。后端存的是万分比整数（2% = 200），直接显示会变成 200% */
const pct = (bp: number) => `${(bp / 100).toFixed(bp % 100 === 0 ? 0 : 2)}%`;

/** 流水上是门店号，商家认的是门店名。查不到就原样显示号 —— 空白比一个号更难查 */
function storeName(storeNo?: string) {
  if (!storeNo) return "—";
  return merchant.stores.find((s) => s.storeNo === storeNo)?.name ?? storeNo;
}

function switchScope(all: boolean) {
  allStores.value = all;
  void load();
}

async function load() {
  // 三件事各自 catch：积分账户还没开通时这条会失败，而账单本身没问题 ——
  // 绑在一起的话，一个没开通的功能会把整页结算数据带走
  [bills.value, rate.value, points.value] = await Promise.all([
    api.mSettleList(allStores.value),
    api.mRateCard(),
    api.mPointsAccount().catch(() => null),
  ]);
}

/**
 * 开 / 关本店积分。
 *
 * **关闭只影响将来**：已发出的分仍有效、已扣的服务费不退 ——
 * 所以这里要二次确认，否则店主会以为关掉就能把这个月的钱要回来。
 * 平台按行业强制开的（`forced`）不给关，那个开关他按了也没用。
 */
async function togglePoints() {
  const p = points.value;
  if (!p || p.forced || togglingPoints.value) return;
  const on = !p.enabled;
  const res = await confirm({ title: String(t(on ? "settle.pointsOnTitle" : "settle.pointsOffTitle")), hint: String(t(on ? "settle.pointsOnHint" : "settle.pointsOffHint")) });
  if (!res) return;
  togglingPoints.value = true;
  try {
    points.value = await api.mPointsToggle({ enabled: on });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    togglingPoints.value = false;
  }
}

/** 展开 / 收起明细。收起时置回 null，下次展开重拉 —— 服务费会随新单增加 */
async function loadPointsRecords() {
  if (pointsRecords.value) {
    pointsRecords.value = null;
    return;
  }
  pointsRecords.value = await api.mPointsRecords().catch(() => []);
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="settle.title" :denied="!merchant.can('biz:finance')">
    <text class="txt-display">{{ $t("settle.title") }}</text>

    <!-- 费率卡放在账单**之前**：先说清楚怎么算，再看算出来多少。
         把费率讲明白是「自带客流零佣金」这个策略能起作用的前提 —— 商家算不清自己能拿多少，
         就不会有动力把老客带进来 -->
    <view v-if="rate" class="sh-card ratecard">
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

    <!--
      本店积分。**它是一笔支出**，所以摆在费率卡下面、账单上面 —— 与「怎么算」同一层。
      不生效时显示后端给的 disabledReason：小微主体要说「升级为个体工商户后可开启」，
      而不是「本店未开启」—— 后者会让商家去按一个他根本按不动的开关。
    -->
    <view v-if="points" class="sh-card points">
      <view class="points__head sh-row sh-row--between">
        <text class="txt-title">{{ $t("settle.pointsTitle") }}</text>
        <text
          v-if="!points.forced"
          class="sh-chip"
          :class="{ 'sh-chip--primary': points.enabled }"
          @tap="togglePoints"
        >{{ $t(points.enabled ? "settle.pointsOn" : "settle.pointsOff") }}</text>
        <!-- 平台按行业强制开的：显示状态但不给按，按了也没用 -->
        <text v-else class="sh-chip sh-chip--primary">{{ $t("settle.pointsForced") }}</text>
      </view>
      <view class="points__row sh-row sh-row--between sh-row--baseline">
        <text class="sh-muted">{{ $t("settle.pointsExpense", { period: points.period }) }}</text>
        <text class="txt-hero sh-num">{{ money(points.periodExpenseMinor) }}</text>
      </view>
      <text v-if="points.disabledReason" class="sh-muted points__note">
        {{ points.disabledReason }}
      </text>
      <text v-else class="sh-muted points__note">{{ $t("settle.pointsHint") }}</text>

      <!--
        明细按需展开：**一笔支出必须能对到单**，否则「这个月 ¥3.76」就是一个
        无法核对的数字 —— 商家对不上的账，早晚变成一张工单。
      -->
      <text v-if="points.periodExpenseMinor > 0" class="points__more" @tap="loadPointsRecords">
        {{ pointsRecords ? $t("settle.pointsFold") : $t("settle.pointsDetail") }}
      </text>
      <view v-if="pointsRecords" class="rows">
        <sh-empty v-if="!pointsRecords.length" :text='$t("settle.pointsEmpty")'></sh-empty>
        <view v-for="r in pointsRecords" :key="r.settleNo + r.subOrderNo" class="sh-row sh-row--between row">
          <text class="sh-muted sh-num">{{ r.subOrderNo }}</text>
          <text class="sh-num">
            {{ $t("settle.pointsQty", { n: r.points }) }}　{{ money(r.feeMinor) }}
          </text>
        </view>
      </view>
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

    <sh-empty v-if="!bills.length" :text='$t("settle.empty")'></sh-empty>

    <!--
      **一笔子订单一行**，不是周期账单 —— 后端 stl_bill 就是这个粒度。
      此前这里按「周账单」渲染（billNo / periodStart / orderCount），
      而那些字段后端从来没有过：mock 下好看，连真后端整片空白。
    -->
    <view v-for="b in bills" :key="b.settleNo" class="sh-card bill">
      <view class="bill__head sh-row sh-row--between">
        <text class="txt-strong sh-num">{{ monthDay(b.createdAt) }}</text>
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
        <view class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.commission") }}（{{ pct(b.commissionRate) }}）</text>
          <text class="sh-num minus">-{{ money(b.commissionMinor) }}</text>
        </view>
        <view class="sh-row sh-row--between row">
          <text class="sh-muted">{{ $t("settle.fulfillFee") }}</text>
          <text class="sh-num minus">-{{ money(b.serviceFeeMinor) }}</text>
        </view>
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

.ratecard__row {
  margin-top: 16rpx;
}

.ratecard__note {
  display: block;
  margin-top: 16rpx;
}


.points__row {
  margin-top: 16rpx;
}
.points__note {
  display: block;
  margin-top: 12rpx;
}



.bill__amount {
  margin: 24rpx 0;
}

.rows {
  border-radius: 24rpx;
  background: var(--sh-faint);
  padding: 8rpx 24rpx;
}
.row {
  padding: 16rpx 0;
}
.minus {
  color: var(--sh-danger);
}
.tip {
  margin: 0 8rpx;
}
</style>
