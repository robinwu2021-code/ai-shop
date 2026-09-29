<script setup lang="ts">
// 我的询价（原型 e07）。五种状态：待报价（写预计时间）、已报价（写有效期）、暂无货源、
// 报价已过期、已接受。过期不把单子藏起来：行情变了、平台不再兑现那个价，这件事要说出来。
import { computed, ref } from "vue";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api, errMsg } from "@/api";
import { ensureLogin } from "@/shared/auth";
import { ROUTES, go } from "@/shared/routes";
import { rfqHint, rfqStatusText, rfqTitle, rfqTotal } from "@/shared/rfq";
import type { ElecRfq } from "@shared/types";

const SIZE = 20;
const TABS = [
  { key: "ALL", label: "全部" },
  { key: "SUBMITTED", label: "待报价" },
  { key: "QUOTED", label: "已报价" },
  { key: "ENDED", label: "已结束" },
] as const;

const tab = ref<string>("ALL");
const list = ref<ElecRfq[]>([]);
const page = ref(1);
const done = ref(false);
const loaded = ref(false);
const failed = ref("");

onShow(async () => {
  if (!(await ensureLogin())) return;
  await reload();
});
onReachBottom(() => void more());

async function reload() {
  page.value = 1;
  done.value = false;
  failed.value = "";
  try {
    list.value = await api.myRfqs(1, SIZE);
    done.value = list.value.length < SIZE;
  } catch (e) {
    failed.value = errMsg(e);
  } finally {
    loaded.value = true;
  }
}

async function more() {
  if (done.value || !loaded.value) return;
  const next = await api.myRfqs(page.value + 1, SIZE).catch(() => [] as ElecRfq[]);
  page.value += 1;
  list.value = list.value.concat(next);
  done.value = next.length < SIZE;
}

const shown = computed(() => list.value.filter((r) => {
  if (tab.value === "ALL") return true;
  if (tab.value === "ENDED") return r.status === "CLOSED" || r.status === "EXPIRED" || r.status === "ACCEPTED";
  return r.status === tab.value;
}));

function chipOf(r: ElecRfq): string {
  if (r.status === "QUOTED") return "sh-chip--primary";
  if (r.status === "SUBMITTED") return "sh-chip--warning";
  if (r.status === "ACCEPTED") return "sh-chip--success";
  return "sh-chip--dashed-quiet";
}
</script>

<template>
  <sh-scaffold title-key="title.rfqs" :pending="!loaded" :failed="!!failed" :failed-text="failed" @retry="reload">
    <sh-tabs :items="TABS" :active="tab" line @change="tab = $event"></sh-tabs>

    <sh-empty v-if="loaded && !shown.length" text="还没有询价" tip="搜料号，在料号页点「询价」">
    </sh-empty>

    <view class="sh-cells list">
      <view v-for="r in shown" :key="r.rfqNo" class="sh-cell" @tap="go(ROUTES.rfq, { rfqNo: r.rfqNo })">
        <view class="sh-row sh-row--between">
          <text class="txt-strong sh-num title">{{ rfqTitle(r) }}</text>
          <text class="sh-chip" :class="chipOf(r)">{{ rfqStatusText(r) }}</text>
        </view>
        <text class="txt-caption sh-muted block">{{ rfqHint(r) }}</text>
        <text v-if="r.status === 'QUOTED' && rfqTotal(r)" class="txt-sub block">合计 ¥{{ rfqTotal(r) }}</text>
      </view>
    </view>
    <text v-if="done && list.length > 5" class="txt-caption sh-muted end">没有更多了</text>
  </sh-scaffold>
</template>

<style scoped>
.list {
  margin-top: 16rpx;
}
.title {
  flex: 1;
  min-width: 0;
  word-break: break-all;
  margin-inline-end: 16rpx;
}
.block {
  display: block;
  margin-top: 8rpx;
}
.end {
  display: block;
  text-align: center;
  padding: 28rpx 0;
}
</style>
