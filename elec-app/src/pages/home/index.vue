<script setup lang="ts">
// 元器件首页（原型 e01）。整页就三件事：搜料号、我的询价、成为供应商。
// **不挂任何商城内容**，也不做「热门料号」—— 第一步没有那些数据，摆上去就是假的。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { ROUTES, go } from "@/shared/routes";
import { clearSearches, recentSearches, rememberSearch } from "@/shared/recent";
import { lastRole, rememberRole } from "@/shared/role";
import type { ElecMe, ElecRfq } from "@shared/types";

const user = useUserStore();
const keyword = ref("");
const recent = ref<string[]>([]);
const rfqs = ref<ElecRfq[]>([]);
const me = ref<ElecMe | null>(null);

/**
 * 上次停在供应商那一面、而且确实还是供应商 → 直接去工作台。
 * 只在**进来那一下**（onLoad）判断，不在 onShow：从工作台切回买家时也会 onShow，那时他就是要看买家这面。
 */
onLoad(async () => {
  if (lastRole() !== "supplier" || !user.isLogin) return;
  const m = await api.me().catch(() => null);
  if (m?.supplier) uni.redirectTo({ url: ROUTES.supplier });
  else rememberRole("buyer");
});

onShow(async () => {
  recent.value = recentSearches();
  if (!user.isLogin) return;
  // 两样都只是「顺手告诉他一声」，拉不到不打扰：首页的本分是搜料号
  const [r, m] = await Promise.allSettled([api.myRfqs(1, 20), api.me()]);
  rfqs.value = r.status === "fulfilled" ? r.value : [];
  me.value = m.status === "fulfilled" ? m.value : null;
});

const quotedCnt = computed(() => rfqs.value.filter((x) => x.status === "QUOTED").length);
const pendingCnt = computed(() => rfqs.value.filter((x) => x.status === "SUBMITTED").length);
const rfqHint = computed(() => {
  if (!user.isLogin) return "";
  if (quotedCnt.value) return `${quotedCnt.value} 单已报价`;
  if (pendingCnt.value) return `${pendingCnt.value} 单待报价`;
  return rfqs.value.length ? "" : "还没询过价";
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
    <el-role-switch v-if="user.isLogin" active="buyer" :me="me"></el-role-switch>
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

    <view class="sh-card block">
      <view class="sh-row sh-row--between" @tap="go(ROUTES.rfqs)">
        <text class="txt-strong">我的询价</text>
        <view class="sh-row">
          <text v-if="rfqHint" class="txt-sub" :class="quotedCnt ? 'txt-primary' : 'sh-muted'">{{ rfqHint }}</text>
          <sh-icon name="chevronRight" :size="32" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
    </view>

    <!-- 页尾：来的人十个有九个是买家，招募不能挡在他要找的东西前面；但必须在这一页。
         已经是供应商的，顶上的切换条就是去工作台的门，这里不再重复一张卡 -->
    <view v-if="!me?.supplier" class="join">
      <text class="txt-sub sh-muted">手上有库存？传上来，买家搜得到就有询价</text>
      <view class="sh-btn sh-btn--soft sh-mt-sm" @tap="go(ROUTES.supplierJoin)">成为供应商</view>
    </view>
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
.join {
  margin-top: 64rpx;
  text-align: center;
}
</style>
