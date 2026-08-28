<script setup lang="ts">
// 发分服务费明细（B-11.11）。一单一条，数据来自 `stl_bill.points_fee_minor`。
//
// **「未发放」那种要写出原因。** 线下单不发积分是本批新加的规则
// （发分要向商家收费用金，而线下那笔收不到 —— 线上靠分账扣、自营从应付货款净出，
// 线下两条路都没有）。不写的话，商家问「为什么这单没发」没人答得上来。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { datetime } from "@shared/utils/datetime";
import type { MerchantPointsRecord } from "@shared/types";

const merchant = useMerchantStore();
const canView = computed(() => merchant.can("biz:finance"));

const rows = ref<MerchantPointsRecord[]>([]);
const total = computed(() => rows.value.reduce((n, r) => n + r.feeMinor, 0));

async function load() {
  rows.value = await api.mPointsRecords();
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="points.recordsTitle" :denied="!canView">
    <view class="sh-card">
      <view class="line sh-row sh-row--between sh-row--baseline">
        <text class="sh-muted">{{ $t("points.periodTotal") }}</text>
        <text class="txt-price sh-num">{{ money(total) }}</text>
      </view>
    </view>

    <sh-empty v-if="!rows.length" :text="$t('points.recordsEmpty')" />

    <view v-for="r in rows" :key="r.settleNo" class="sh-card sh-mt-xs" :class="{ 'is-none': !r.points }">
      <view class="line sh-row sh-row--between sh-row--baseline">
        <text class="txt-sub sh-num">{{ r.subOrderNo }}</text>
        <!--
          发了分 → 显示费用金；没发 → 显示原因。
          **不显示「¥0.00」** —— 零和「不适用」是两件事，
          而商家看到 0 会以为是算错了。
        -->
        <text v-if="r.points" class="sh-num">{{ money(r.feeMinor) }}</text>
        <text v-else class="sh-chip">{{ $t("points.notGranted") }}</text>
      </view>
      <view class="line sh-row sh-row--between sh-row--baseline">
        <text class="sh-muted">
          {{ r.points ? $t("points.granted", { n: r.points }) : $t("points.notGrantedWhy") }}
        </text>
        <text class="sh-muted sh-num">{{ datetime(r.at) }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.line + .line { margin-top: 8rpx; }

/* 未发放那几条压低存在感：它们不是账，是解释 */
.is-none { opacity: 0.72; }
</style>
