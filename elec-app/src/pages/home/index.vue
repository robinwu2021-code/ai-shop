<script setup lang="ts">
// 找料（原型 e01，底部菜单第一格）。整页只管找：搜料号、批量查、最近搜过、最近看过。
// **不挂任何商城内容**，也不做「热门料号」—— 第一步没有那些数据，摆上去就是假的。
// 「我的询价」与「成为供应商」各有一格菜单（询价 / 供货），这里不再重复一张卡。
import { ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { ROUTES, go } from "@/shared/routes";
import { clearSearches, recentSearches, rememberSearch, viewedParts, type ViewedPart } from "@/shared/recent";
import { lastTab, rememberTab, switchTab } from "@/shared/tabs";

const HOME_SEARCHES = 8;
const HOME_VIEWED = 3;

const user = useUserStore();
const keyword = ref("");
const recent = ref<string[]>([]);
const viewed = ref<ViewedPart[]>([]);

/**
 * 上次停在「供货」、而且确实还是供应商 → 直接去供货：供应商进来多半是回去处理求购，
 * 每次都先落到找料再点一下是白费。只在**进来那一下**（onLoad）判断，从别的格切回找料时不再跳。
 */
onLoad(async () => {
  if (lastTab() !== "supply" || !user.isLogin) return;
  const m = await api.me().catch(() => null);
  if (m?.supplier) switchTab("supply");
  else rememberTab("find");
});

onShow(() => {
  recent.value = recentSearches().slice(0, HOME_SEARCHES);
  viewed.value = viewedParts().slice(0, HOME_VIEWED);
});

function search(k = keyword.value) {
  const v = k.trim();
  if (!v) return;
  rememberSearch(v);
  go(ROUTES.search, { keyword: v });
}

function clearRecent() {
  clearSearches();
  recent.value = [];
}
</script>

<template>
  <sh-scaffold title-key="title.home">
    <view class="sh-searchbox">
      <sh-icon name="search" :size="36" color="var(--sh-sub)"></sh-icon>
      <input
        v-model="keyword"
        class="grow txt-body"
        confirm-type="search"
        placeholder="料号，可带厂牌与数量"
        @confirm="search()"
      />
      <text v-if="keyword" class="sh-link" @tap="search()">搜索</text>
    </view>
    <view class="sh-row sh-row--between lookup" @tap="go(ROUTES.lookup)">
      <text class="txt-sub sh-muted">一次查多个料号</text>
      <text class="sh-link">批量查 ›</text>
    </view>

    <view v-if="recent.length" class="sh-card block">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">最近搜过</text>
        <text class="sh-link sh-link--quiet" @tap="clearRecent">清空</text>
      </view>
      <view class="sh-wrap chips">
        <text v-for="k in recent" :key="k" class="sh-chip" @tap="search(k)">{{ k }}</text>
      </view>
    </view>

    <view v-if="viewed.length" class="block">
      <view class="sh-row sh-row--between head">
        <text class="txt-strong">最近看过</text>
        <text class="sh-link sh-link--quiet" @tap="go(ROUTES.history, { view: 'viewed' })">全部 ›</text>
      </view>
      <view class="sh-cells">
        <view v-for="p in viewed" :key="p.partNo" class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.part, { partNo: p.partNo })">
          <text class="txt-body sh-num mpn">{{ p.mpn }}</text>
          <text class="txt-caption sh-muted">{{ p.mfr || "厂牌未确认" }}</text>
        </view>
      </view>
    </view>

    <el-tabbar active="find"></el-tabbar>
  </sh-scaffold>
</template>

<style scoped>
.grow {
  flex: 1;
}
.lookup {
  padding: 20rpx 12rpx 0;
}
.block {
  margin-top: 24rpx;
}
.chips {
  margin-top: 16rpx;
  gap: 16rpx;
}
.head {
  padding: 0 12rpx 12rpx;
}
.mpn {
  flex: 1;
  min-width: 0;
  word-break: break-all;
  margin-inline-end: 16rpx;
}
</style>
