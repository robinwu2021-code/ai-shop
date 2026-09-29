<script setup lang="ts">
// 求购：平台把买家的询价派给库里有货（或可能有货）的供应商。
// 这里**看得到求购、看不到买家**：单号是派单号（与买家的询价单号对不上），收货地只到省。
//
// 打开这一页时要一次订阅授权 —— 覆盖下一条「有新求购」（TDD §2.2：一次授权只够一条）。
// 小程序要求授权由点击触发，所以放在「待报价」那一栏的点击上，而不是 onShow。
import { ref } from "vue";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api, errMsg } from "@/api";
import { ensureLogin } from "@/shared/auth";
import { askSubscribe } from "@/shared/subscribe";
import { ROUTES, go } from "@/shared/routes";
import { DISPATCH_STATUS, priceOf, qtyOf, whenOf } from "@/shared/format";
import { demandLine } from "@/shared/dispatch";
import type { ElecDispatch, ElecDispatchStatus } from "@shared/types";

const SIZE = 20;
/** 「待报价」要把看过没报的也算进来：后端的 status 参数只收一个值，所以这一栏在端上合并 */
const TABS = [
  { key: "OPEN", label: "待报价" },
  { key: "QUOTED", label: "已报价" },
  { key: "DECLINED", label: "已拒绝" },
] as const;

const tab = ref<string>("OPEN");
const list = ref<ElecDispatch[]>([]);
const page = ref(1);
const done = ref(false);
const loaded = ref(false);
const failed = ref("");

onShow(async () => {
  if (!(await ensureLogin())) return;
  await reload();
});
onReachBottom(() => void more());

function fetch(p: number): Promise<ElecDispatch[]> {
  if (tab.value !== "OPEN") return api.myDispatches(tab.value as ElecDispatchStatus, p, SIZE);
  return Promise.all([api.myDispatches("SENT", p, SIZE), api.myDispatches("VIEWED", p, SIZE)])
    .then(([a, b]) => [...a, ...b].sort((x, y) => y.createdAt.localeCompare(x.createdAt)));
}

async function reload() {
  page.value = 1;
  failed.value = "";
  try {
    list.value = await fetch(1);
    done.value = list.value.length < SIZE;
  } catch (e) {
    failed.value = errMsg(e);
  } finally {
    loaded.value = true;
  }
}

async function more() {
  if (done.value || !loaded.value) return;
  const next = await fetch(page.value + 1).catch(() => [] as ElecDispatch[]);
  page.value += 1;
  list.value = list.value.concat(next);
  done.value = next.length < SIZE;
}

function setTab(k: string) {
  if (k === "OPEN") void askSubscribe("dispatch");
  tab.value = k;
  void reload();
}
</script>

<template>
  <sh-scaffold title-key="title.dispatches" :failed="!!failed" :failed-text="failed" @retry="reload">
    <sh-tabs :items="TABS" :active="tab" line @change="setTab"></sh-tabs>
    <view v-if="tab === 'OPEN'" class="sh-row sh-row--between sub" @tap="askSubscribe('dispatch')">
      <text class="txt-caption sh-muted">有新求购时微信通知我</text>
      <text class="sh-link">开启 ›</text>
    </view>

    <sh-empty v-if="loaded && !list.length" :text="tab === 'OPEN' ? '暂时没有求购' : '这里是空的'"
      tip="平台按库存派单：库存传得越全、越新，派到你这里的越多"></sh-empty>

    <view class="sh-cells">
      <view v-for="d in list" :key="d.dispatchNo" class="sh-cell" @tap="go(ROUTES.dispatch, { dispatchNo: d.dispatchNo })">
        <view class="sh-row sh-row--between">
          <text class="txt-strong sh-num mpn">{{ d.mpn }} × {{ qtyOf(d.qty) }}</text>
          <text class="txt-caption" :class="d.status === 'QUOTED' ? 'txt-primary' : d.status === 'DECLINED' ? 'sh-muted' : 'warn'">
            {{ DISPATCH_STATUS[d.status] }}
          </text>
        </view>
        <text class="txt-caption sh-muted block">
          {{ whenOf(d.createdAt) }}<text v-if="d.mfr"> · {{ d.mfr }}</text><text v-if="d.targetE6"> · 目标价 {{ priceOf(d.targetE6) }}</text>
        </text>
        <text v-if="demandLine(d)" class="txt-caption sh-muted block">{{ demandLine(d) }}</text>
        <text v-if="d.inStock" class="txt-caption txt-primary block">你库里有 {{ qtyOf(d.inStock) }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.sub {
  padding: 16rpx 12rpx;
}
.mpn {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}
.block {
  display: block;
  margin-top: 6rpx;
}
.warn {
  color: var(--sh-warning);
}
</style>
