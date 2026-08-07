<script setup lang="ts">
/*
 * 店铺 tab —— 「挑哪家店」这件事的专属页面。
 *
 * 为什么它值得一个独立 tab：**挑店和挑货是两种不同的决策**，
 * 挤在首页里必然互相挤压（前几轮门店入口在首页反复挪位，根因就是这个）。
 * 邻里购物尤其是「认人先于认货」—— 买谁的菜取决于「阿明家的菜新鲜」，
 * 所以选店本身就是一条主路径，不是首页的附属模块。
 *
 * 三块，按**关系由近到远**排：
 *   1. 我买过的 —— 真实消费过的（从订单聚合，不是收藏、不是浏览足迹），复购主入口
 *   2. 平台推荐 —— 运营位。新店没订单没评分，在任何按成绩排的列表里都垫底，
 *      需要一个不看历史成绩的位置，否则永远冷启动不了
 *   3. 附近的   —— 服务范围覆盖当前社区的，按距离
 * 已经在上面出现过的店不在下面重复出现 —— 一期社区里只有三五家，不去重整页都是同一批。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCommunityStore } from "@/stores/community";
import { useUserStore } from "@/stores/user";
import { ROUTES } from "@shared/utils/constants";
import { distance, isoDate } from "@shared/utils/format";
import type { Merchant, VisitedMerchant } from "@shared/types";

const community = useCommunityStore();
const user = useUserStore();

const visited = ref<VisitedMerchant[]>([]);
const promoted = ref<Merchant[]>([]);
const nearby = ref<Merchant[]>([]);
const loaded = ref(false);

/** 逐层去重：越靠上的关系越强，同一家店只在最强的那一档露面 */
const promotedShown = computed(() => {
  const seen = new Set(visited.value.map((m) => m.merchantNo));
  return promoted.value.filter((m) => !seen.has(m.merchantNo));
});
const nearbyShown = computed(() => {
  const seen = new Set([
    ...visited.value.map((m) => m.merchantNo),
    ...promotedShown.value.map((m) => m.merchantNo),
  ]);
  return nearby.value.filter((m) => !seen.has(m.merchantNo));
});

async function load() {
  const communityNo = community.community?.communityNo;
  const [v, p, n] = await Promise.all([
    // 未登录没有消费记录，但推荐与附近照常要出 —— 不能整页空着
    user.isLogin ? api.visitedMerchants().catch(() => []) : Promise.resolve([]),
    api.promotedMerchants({ communityNo }).catch(() => []),
    api.merchantList({ communityNo }).catch(() => []),
  ]);
  visited.value = v;
  promoted.value = p;
  nearby.value = n;
  loaded.value = true;
}

function open(merchantNo: string) {
  uni.navigateTo({ url: `${ROUTES.merchant}?merchantNo=${merchantNo}` });
}

function goShopping() {
  uni.switchTab({ url: ROUTES.home });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="shops.title" tab="merchants">
    <!-- 1. 我买过的：真实消费过的关系，回购主路径，放最上面 -->
    <template v-if="visited.length">
      <view class="section">
        <text class="sh-h2">{{ $t("shops.visited") }}</text>
        <text class="sh-muted">{{ $t("shops.visitedHint") }}</text>
      </view>
      <view
        v-for="m in visited"
        :key="m.merchantNo"
        class="sh-card card"
        @tap="open(m.merchantNo)"
      >
        <biz-merchant-bar :merchant="m" @tap="open(m.merchantNo)"></biz-merchant-bar>
        <view class="meta">
          <text class="sh-chip sh-num">{{ $t("visited.orders", { n: m.orderCount }) }}</text>
          <text class="sh-chip sh-num">
            {{ $t("visited.last", { d: isoDate(m.lastOrderAt) }) }}
          </text>
          <text class="sh-chip">{{ $t(`merchant.type.${m.type}`) }}</text>
        </view>
      </view>
    </template>

    <!-- 2. 平台推荐：运营位，给新店一个不看历史成绩的位置 -->
    <template v-if="promotedShown.length">
      <view class="section">
        <text class="sh-h2">{{ $t("shops.promoted") }}</text>
        <text class="sh-muted">{{ $t("shops.promotedHint") }}</text>
      </view>
      <view
        v-for="m in promotedShown"
        :key="m.merchantNo"
        class="sh-card card"
        @tap="open(m.merchantNo)"
      >
        <biz-merchant-bar :merchant="m" @tap="open(m.merchantNo)"></biz-merchant-bar>
        <text class="desc">{{ m.desc }}</text>
        <view class="meta">
          <text class="sh-chip">{{ $t(`serviceScope.${m.serviceScope}`) }}</text>
        </view>
      </view>
    </template>

    <!-- 3. 附近的：服务范围覆盖本社区，按距离。密排一点 —— 到这一档只需要认个脸 -->
    <template v-if="nearbyShown.length">
      <view class="section">
        <text class="sh-h2">{{ $t("shops.nearby") }}</text>
        <text class="sh-muted">{{ $t("shops.nearbyHint") }}</text>
      </view>
      <view class="near">
        <view
          v-for="m in nearbyShown"
          :key="m.merchantNo"
          class="near__i"
          @tap="open(m.merchantNo)"
        >
          <text class="near__logo">{{ m.logo }}</text>
          <view class="near__main">
            <text class="near__name">{{ m.name }}</text>
            <text class="near__desc">{{ m.desc }}</text>
          </view>
          <text v-if="m.distance" class="near__dist sh-num">{{ distance(m.distance) }}</text>
        </view>
      </view>
    </template>

    <view v-if="loaded && !visited.length && !promoted.length && !nearby.length" class="empty">
      <text class="empty__text">{{ $t("shops.empty") }}</text>
      <view class="sh-btn empty__btn" @tap="goShopping">{{ $t("visited.go") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.section {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  margin: 32rpx 0 16rpx;
}
.section:first-child {
  margin-top: 4rpx;
}
.card {
  margin-bottom: 20rpx;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 24rpx;
}
.desc {
  display: block;
  margin-top: 18rpx;
  font-size: 23rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
/* 附近的店：一行一家，密排 —— 这一档只是「附近还有谁」，不需要展开介绍 */
.near {
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  background: var(--sh-surface);
  border-radius: 28rpx;
  overflow: hidden;
}
.near__i {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 22rpx 24rpx;
}
.near__logo {
  width: 76rpx;
  height: 76rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  font-size: 40rpx;
  line-height: 76rpx;
  text-align: center;
  flex-shrink: 0;
}
.near__main {
  flex: 1;
  min-width: 0;
}
.near__name {
  display: block;
  font-size: 27rpx;
  font-weight: 600;
  color: var(--sh-ink);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.near__desc {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: var(--sh-sub);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.near__dist {
  flex-shrink: 0;
  font-size: 23rpx;
  color: var(--sh-sub);
}
.empty {
  text-align: center;
  padding: 120rpx 40rpx;
}
.empty__text {
  display: block;
  color: var(--sh-sub);
  font-size: 26rpx;
  margin-bottom: 40rpx;
}
.empty__btn {
  display: inline-block;
  padding-left: 60rpx;
  padding-right: 60rpx;
}
</style>
