<script setup lang="ts">
// 我的询价（原型 e07，底部菜单「询价」）。五种状态：待报价（写预计时间）、已报价（写有效期）、暂无货源、
// 报价已过期、已接受。过期不把单子藏起来：行情变了、平台不再兑现那个价，这件事要说出来。
import { computed, ref } from "vue";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api, errMsg } from "@/api";
import { goLogin, tryLogin } from "@/shared/auth";
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
const guest = ref(false);

onShow(async () => {
  guest.value = !(await tryLogin());
  if (!guest.value) await reload();
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
  <!-- 加载中 / 失败不交给 sh-scaffold：那两态它不渲染正文，底部菜单会跟着消失 ——
       独立发布时这是根页面，没有返回键，失败了就困在这一页 -->
  <sh-scaffold title-key="title.rfqs">
    <view v-if="guest" class="guest">
      <sh-empty text="登录后看你的询价" tip="报价出来会在这里，也会发消息告诉你"></sh-empty>
      <view class="sh-btn" @tap="goLogin">去登录</view>
    </view>
    <sh-empty v-else-if="failed" bare failed :failed-text="failed" @retry="reload"></sh-empty>
    <template v-else-if="loaded">
      <view class="sh-row sh-row--between create" @tap="go(ROUTES.rfqCreate)">
        <text class="txt-sub sh-muted">手上有料号清单？直接写下来问价</text>
        <text class="sh-link">新询价 ›</text>
      </view>
      <sh-tabs :items="TABS" :active="tab" line @change="tab = $event"></sh-tabs>

      <sh-empty v-if="!shown.length" text="还没有询价" tip="搜料号，在料号页点「询价」">
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
    </template>

    <el-tabbar active="rfq"></el-tabbar>
  </sh-scaffold>
</template>

<style scoped>
.guest .sh-btn {
  margin-top: 24rpx;
}
.create {
  padding: 4rpx 12rpx 20rpx;
}
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
