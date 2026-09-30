<script setup lang="ts">
// 历史记录：搜过的词、看过的料号。从「我的 → 历史记录」与找料页「最近看过 · 全部」进来。
// 只记在这台手机上（换手机就没了），所以页脚说一句；清空要确认 —— 点错了没法找回。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { confirm } from "@ai-shop/ui/prompt";
import { ROUTES, go } from "@/shared/routes";
import { agoOf } from "@/shared/format";
import {
  clearSearches, clearViewed, recentSearches, rememberSearch, viewedParts, type ViewedPart,
} from "@/shared/recent";

const TABS = [
  { key: "viewed", label: "看过的料号" },
  { key: "search", label: "搜过的" },
] as const;
type Tab = (typeof TABS)[number]["key"];

const tab = ref<Tab>("viewed");
const searches = ref<string[]>([]);
const viewed = ref<ViewedPart[]>([]);

// 参数叫 view 不叫 tab：页面的查询参数会作为属性落到根组件 sh-scaffold 上，
// 而 sh-scaffold 有一个 `tab` 属性（传了就当 tab 页：藏返回键、画它自己的底部菜单）
onLoad((q) => {
  if (q?.view === "search") tab.value = "search";
});
onShow(refresh);

function refresh() {
  searches.value = recentSearches();
  viewed.value = viewedParts();
}

const empty = computed(() => (tab.value === "search" ? !searches.value.length : !viewed.value.length));

function search(k: string) {
  rememberSearch(k);
  go(ROUTES.search, { keyword: k });
}

async function clear() {
  const what = tab.value === "search" ? "搜过的词" : "看过的料号";
  if (!(await confirm({ title: `清空${what}？`, confirmText: "清空", danger: true }))) return;
  if (tab.value === "search") clearSearches();
  else clearViewed();
  refresh();
}
</script>

<template>
  <sh-scaffold title-key="title.history">
    <sh-tabs :items="TABS" :active="tab" line @change="tab = $event as Tab"></sh-tabs>

    <sh-empty v-if="empty" :text="tab === 'search' ? '还没搜过' : '还没看过料号'" tip="在找料页搜料号，这里会记下来"></sh-empty>

    <view v-else-if="tab === 'viewed'" class="sh-cells list">
      <view v-for="p in viewed" :key="p.partNo" class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.part, { partNo: p.partNo })">
        <view class="grow">
          <text class="txt-body sh-num block mpn">{{ p.mpn }}</text>
          <text class="txt-caption sh-muted block">{{ p.mfr || "厂牌未确认" }}</text>
        </view>
        <text class="txt-caption sh-muted">{{ agoOf(new Date(p.at).toISOString()) }}</text>
      </view>
    </view>

    <view v-else class="sh-cells list">
      <view v-for="k in searches" :key="k" class="sh-cell sh-row sh-row--between" @tap="search(k)">
        <text class="txt-body sh-num grow mpn">{{ k }}</text>
        <sh-icon name="search" :size="32" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <view v-if="!empty" class="sh-row sh-row--between foot">
      <text class="txt-caption sh-muted">只记在这台手机上</text>
      <text class="sh-link sh-link--quiet" @tap="clear">清空</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.list {
  margin-top: 16rpx;
}
.grow {
  flex: 1;
  min-width: 0;
  margin-inline-end: 16rpx;
}
.block {
  display: block;
}
.mpn {
  word-break: break-all;
}
.foot {
  padding: 28rpx 12rpx;
}
</style>
