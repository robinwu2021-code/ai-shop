<script setup lang="ts">
// 一条料号（搜索结果、批量查、料号详情顶上都是它）。
// 第二行说的是**怎么命中的**（开头一致 / 中段一致 / 相近），不是排名分数 ——
// 采购一眼就知道这条是不是他要的，而分数说明不了任何事。
// 行情只给档位：精确数量加上批号，同行一眼就能认出是谁家的货。
import { computed } from "vue";
import type { ElecPartHit } from "@shared/types";
import { MATCH, QTY_BAND, SOURCE_BAND, leadOf, priceOf, qtyOf } from "@/shared/format";

const props = withDefaults(defineProps<{ hit: ElecPartHit; showMatch?: boolean }>(), { showMatch: true });

const m = computed(() => props.hit.market ?? null);
const badge = computed(() => {
  if (!m.value) return { text: "暂无库存", cls: "sh-chip--dashed-quiet" };
  if (m.value.spot) return { text: "现货", cls: "sh-chip--success" };
  // 交期没人说：有货，但**不能说成现货，也不能说成期货** —— 两个都是在替供应商承诺
  if (m.value.leadDaysMin == null) return { text: "有库存", cls: "" };
  return { text: `期货 ${m.value.leadDaysMin} 天`, cls: "sh-chip--warning" };
});
const meta = computed(() =>
  [props.hit.mfrKnown ? props.hit.mfrName : props.hit.mfrName ? `${props.hit.mfrName}（厂牌未确认）` : "厂牌未确认",
    props.hit.packageName, props.showMatch ? MATCH[props.hit.match] : ""]
    .filter(Boolean).join(" · "));
</script>

<template>
  <view class="card">
    <view class="sh-row sh-row--between">
      <text class="txt-strong sh-num mpn">{{ hit.mpn }}</text>
      <text class="sh-chip" :class="badge.cls">{{ badge.text }}</text>
    </view>
    <text class="txt-caption sh-muted line">{{ meta }}</text>
    <text v-if="m" class="txt-sub line">
      库存 {{ QTY_BAND[m.qtyBand] }} · {{ SOURCE_BAND[m.sourceBand] }}<text v-if="m.priceFromE6 != null"> ·
        <text class="txt-ink">{{ priceOf(m.priceFromE6) }} 起</text><text v-if="m.priceFromQty && m.priceFromQty > 1"
          class="sh-muted">（{{ qtyOf(m.priceFromQty) }} 片起）</text></text>
      <text v-if="!m.spot && m.leadDaysMin == null" class="sh-muted"> · {{ leadOf(null) }}</text>
    </text>
    <text v-else class="txt-sub sh-muted line">可以询价，平台帮你找</text>
  </view>
</template>

<style scoped>
.card {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.mpn {
  word-break: break-all;
}
.line {
  display: block;
}
</style>
