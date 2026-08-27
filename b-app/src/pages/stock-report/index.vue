<script setup lang="ts">
// 进销存报表（B-8）。
//
// **算式要能在屏幕上算得通**：期初 + 进 − 销 − 损 ± 调 = 期末。
// 对不上说明台账漏了一笔 —— 那正是这张报表存在的理由，所以对不上时要显眼，
// 不能悄悄显示一个期末数了事。
//
// **毛利必须标「估算」并给出算式**：它用的是 SKU 当前的成本价，
// 不是那一天的实际进货成本。不标的话商家会拿它去报税。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockMonthly, StockRank } from "@shared/types";
import { pick } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const month = ref(thisMonth());
const monthly = ref<StockMonthly | null>(null);
const fast = ref<StockRank[]>([]);
const slow = ref<StockRank[]>([]);
const loading = ref(false);

function thisMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

/** 分 → 元。展示用，不参与计算 */
function yuan(minor?: number | null): string {
  return ((minor ?? 0) / 100).toFixed(2);
}

async function load() {
  loading.value = true;
  try {
    // 三段各自兜底：榜单取不到不该让月报也空着
    const [m, f, s] = await Promise.all([
      api.mStockMonthly(month.value).catch(() => null),
      api.mStockRanking({ type: "fast", size: 5 }).catch(() => []),
      api.mStockRanking({ type: "slow", size: 5 }).catch(() => []),
    ]);
    monthly.value = m;
    fast.value = f;
    slow.value = s;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

/** 屏幕上那一行算式。**把数字摆出来让人自己核** —— 只说「账对得上」是要人信 */
const formula = computed(() => {
  const m = monthly.value;
  if (!m) return "";
  const adj = m.adjusted === 0 ? "" : (m.adjusted > 0 ? ` + ${m.adjusted}` : ` − ${-m.adjusted}`);
  return `${m.opening} + ${m.purchased} − ${m.sold} − ${m.lost}${adj} = ${m.closing}`;
});

async function pickMonth() {
  // 只给最近 12 个月：更早的月份商家不会在手机上看，给了只是让列表变长
  const items: string[] = [];
  const d = new Date();
  for (let i = 0; i < 12; i++) {
    items.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`);
    d.setMonth(d.getMonth() - 1);
  }
  const idx = await pick({ items, selected: items.indexOf(month.value) });
  if (idx === null) return;
  month.value = items[idx]!;
  await load();
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="stockReport.title" :denied="!merchant.can('biz:customer')">
    <view class="sh-card hd">
      <text class="txt-strong">{{ $t("stockReport.month") }}</text>
      <text class="sh-link sh-num" @tap="pickMonth">{{ month }} ▾</text>
    </view>

    <template v-if="monthly">
      <view class="sh-block">
        <sh-section pad :title="String($t('stockReport.goodsThisMonth'))"></sh-section>
        <view class="blk">
          <sh-kv between :label="String($t('stockReport.opening'))">
            <text class="sh-num">{{ monthly.opening }}</text>
          </sh-kv>
          <sh-kv between :label="String($t('stockReport.purchased'))">
            <text class="sh-num is-in">+{{ monthly.purchased }}</text>
          </sh-kv>
          <sh-kv between :label="String($t('stockReport.sold'))">
            <text class="sh-num is-out">−{{ monthly.sold }}</text>
          </sh-kv>
          <sh-kv between :label="String($t('stockReport.lost'))">
            <text class="sh-num is-out">−{{ monthly.lost }}</text>
          </sh-kv>
          <sh-kv v-if="monthly.adjusted !== 0" between :label="String($t('stockReport.adjusted'))">
            <text class="sh-num">{{ monthly.adjusted > 0 ? `+${monthly.adjusted}` : monthly.adjusted }}</text>
          </sh-kv>
          <sh-kv between divided :label="String($t('stockReport.closing'))">
            <text class="txt-price sh-num">{{ monthly.closing }}</text>
          </sh-kv>

          <!--
            **算不平要显眼**：它不是显示问题，是台账漏了一笔。
            显示成一句灰字的话，唯一会看见它的人是已经在找问题的人。
          -->
          <view class="formula" :class="monthly.balanced ? 'is-ok' : 'is-bad'">
            <text class="sh-num">{{ formula }}</text>
            <text>{{ monthly.balanced ? $t("stockReport.balanced") : $t("stockReport.unbalanced") }}</text>
          </view>
        </view>
      </view>
    </template>

    <sh-empty v-else-if="!loading" :text="String($t('stockReport.noData'))"></sh-empty>

    <view v-if="fast.length" class="sh-block">
      <sh-section pad :title="String($t('stockReport.fast'))"></sh-section>
      <view class="blk">
        <sh-kv
          v-for="r in fast"
          :key="r.itemId"
          between
          :label="`${r.name}${r.specText ? ` · ${r.specText}` : ''}`"
        >
          <text class="txt-strong sh-num">{{ r.qty }}</text>
        </sh-kv>
      </view>
    </view>

    <view v-if="slow.length" class="sh-block">
      <sh-section pad :title="String($t('stockReport.slow'))"></sh-section>
      <view class="blk">
        <view v-for="r in slow" :key="r.itemId" class="slow">
          <view class="slow__main">
            <text class="txt-body">{{ r.name }}{{ r.specText ? ` · ${r.specText}` : "" }}</text>
            <!--
              **金额只在有的时候画**：滞销榜后端不算金额（`costAmountMinor` 是 null），
              兜底成 ¥0.00 会让人以为这批货不值钱，而它恰恰是压着钱的那批。
            -->
            <text class="txt-caption sh-num">
              {{ r.costAmountMinor == null
                ? $t("stockReport.pressedQty", { n: r.qty })
                : $t("stockReport.pressed", { n: r.qty, money: yuan(r.costAmountMinor) }) }}
            </text>
          </view>
        </view>
      </view>
    </view>

    <!--
      **这里给的是成本，不是毛利。**

      毛利 = 收入 − 成本，而收入不在进销存域：出库单只带成本、不带售价
      （同一件货不同渠道价不一样，写进来就有了第二个真源）。
      原型上那张「毛利（估算）」的卡，前端只能拿「销量 × 当前售价」去凑 ——
      促销、多渠道、改价之后统统对不上，而毛利恰恰是商家会拿去报税的那个数。

      销货成本是这个域自己的真源（台账每一笔都带当时的单位成本），给得起就给。
    -->
    <view v-if="monthly" class="sh-block">
      <sh-section pad :title="String($t('stockReport.money'))"></sh-section>
      <view class="blk">
        <sh-kv between :label="String($t('stockReport.soldCost'))">
          <text class="txt-price sh-num">¥{{ yuan(monthly.soldCostMinor) }}</text>
        </sh-kv>
        <sh-kv between :label="String($t('stockReport.lostCost'))">
          <text class="sh-num is-out">¥{{ yuan(monthly.lostCostMinor) }}</text>
        </sh-kv>
        <text class="txt-caption note">{{ $t("stockReport.moneyHint") }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hd > view > text {
  display: block;
}
.blk {
  padding: 0 26rpx 8rpx;
}
.formula {
  margin-top: 12rpx;
  padding: 12rpx 0 0;
}
.formula > text {
  display: block;
}
.is-ok {
  color: var(--sh-success);
}
.is-bad {
  color: var(--sh-danger);
  font-weight: 600;
}
.is-in {
  color: var(--sh-success);
}
.is-out {
  color: var(--sh-danger);
}
.slow {
  padding: 12rpx 0;
}
.note {
  display: block;
  margin-top: 12rpx;
}
.slow__main > text {
  display: block;
}
</style>
