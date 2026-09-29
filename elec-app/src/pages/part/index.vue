<script setup lang="ts">
// 料号详情（原型 e04）。行情三格、参考起价、一句「实际价格按询价单给」，底栏询价。
// 写「参考价」并说清为什么不是最终价：拿着 ¥6.85 去询 2000 片、报回来 ¥7.2 会觉得被坑，
// 而那只是数量与批次不同。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, errMsg } from "@/api";
import { ROUTES, go } from "@/shared/routes";
import { COND, QTY_BAND, SOURCE_BAND, leadOf, priceOf, qtyOf } from "@/shared/format";
import type { ElecPartHit } from "@shared/types";

const partNo = ref("");
const hit = ref<ElecPartHit | null>(null);
const failed = ref("");

onLoad((q) => {
  partNo.value = q?.partNo ? decodeURIComponent(String(q.partNo)) : "";
  void load();
});

async function load() {
  failed.value = "";
  try {
    hit.value = await api.partDetail(partNo.value);
  } catch (e) {
    failed.value = errMsg(e);
  }
}

const m = computed(() => hit.value?.market ?? null);
const stats = computed(() => {
  const x = m.value;
  if (!x) return [];
  return [
    { value: QTY_BAND[x.qtyBand], label: "库存" },
    { value: x.sourceBand === "ONE" ? "1 家" : "多家", label: "货源" },
    { value: x.dcYearMax ? String(x.dcYearMax) : "—", label: "最新批次" },
  ];
});
const mfr = computed(() => {
  const h = hit.value;
  if (!h) return "";
  return h.mfrKnown ? (h.mfrName ?? "") : h.mfrName ? `${h.mfrName}（厂牌未确认）` : "厂牌未确认";
});

function ask() {
  const h = hit.value;
  if (!h) return;
  go(ROUTES.rfqCreate, { partNo: h.partNo, mpn: h.mpn, mfr: h.mfrKnown ? h.mfrName : "" });
}
</script>

<template>
  <sh-scaffold title-key="title.part" :pending="!hit && !failed" :failed="!!failed" :failed-text="failed" @retry="load">
    <template v-if="hit">
      <view class="sh-card">
        <text class="txt-title sh-num mpn">{{ hit.mpn }}</text>
        <text class="txt-sub sh-muted line">{{ [mfr, hit.packageName].filter(Boolean).join(" · ") }}</text>
        <text v-if="hit.description" class="txt-sub line">{{ hit.description }}</text>

        <view v-if="m" class="stats">
          <sh-stat :items="stats"></sh-stat>
        </view>
        <view v-else class="sh-notice sh-notice--muted stats">
          <text class="txt-sub">平台库里现在没有这个料号的货 —— 正是询价最有用的时候，平台帮你找</text>
        </view>
      </view>

      <view v-if="m" class="sh-card block">
        <view class="sh-row sh-row--between">
          <text class="txt-sub">参考价（含税）</text>
          <text class="txt-caption sh-muted">以询价单为准</text>
        </view>
        <view class="sh-row sh-row--baseline price">
          <text v-if="m.priceFromE6 != null" class="txt-price">{{ priceOf(m.priceFromE6) }}</text>
          <text v-if="m.priceFromE6 != null" class="txt-sub"> 起</text>
          <text v-else class="txt-sub sh-muted">有货，但还没有报价</text>
          <text v-if="m.priceFromQty && m.priceFromQty > 1" class="txt-caption sh-muted">　{{ qtyOf(m.priceFromQty) }} 片起</text>
        </view>
        <text class="txt-caption sh-muted line">实际价格按询价单给，受数量、批次、交期影响</text>
        <sh-kv label="交期" between divided>{{ m.spot ? "有现货" : leadOf(m.leadDaysMin) }}</sh-kv>
        <sh-kv v-if="m.conds.length" label="货况" between divided>{{ m.conds.map((c) => COND[c]).join(" / ") }}</sh-kv>
        <sh-kv label="货源" between divided>{{ SOURCE_BAND[m.sourceBand] }}</sh-kv>
      </view>

      <view class="sh-row sh-row--between hint" @tap="go(ROUTES.lookup, { text: hit.mpn })">
        <text class="txt-caption sh-muted">找不到要的批次或数量？直接询价，平台帮你找货</text>
        <text class="sh-link nowrap">加入批量查</text>
      </view>

      <sh-actionbar>
        <view class="sh-btn" @tap="ask">询价</view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.mpn {
  display: block;
  word-break: break-all;
}
.line {
  display: block;
  margin-top: 8rpx;
}
.stats {
  margin-top: 28rpx;
}
.block {
  margin-top: 24rpx;
}
.price {
  margin-top: 12rpx;
}
.hint {
  padding: 24rpx 12rpx;
  gap: 16rpx;
}
.nowrap {
  flex: none;
  white-space: nowrap;
}
</style>
