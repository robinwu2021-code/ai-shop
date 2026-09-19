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
import { isoDate } from "@shared/utils/format";
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

/** 三条全没取到。**与「这一带确实没有商家」是两件事** */
const failed = ref(false);

async function load() {
  const communityNo = community.community?.communityNo;
  /*
   * 三个接口分开取，未登录没有消费记录，但推荐与附近照常要出 —— 不能整页空着。
   *
   * **用 `allSettled` 而不是各自 `.catch(() => [])`**：后者把「没取到」抹成「空」，
   * 于是三条全挂时这一屏显示「这附近还没有商家入驻」—— 而真相是一条都没取到。
   * 抹掉之后连「全挂了没有」都判断不出来，因为失败和空返回长得一模一样。
   */
  const [v, p, n] = await Promise.allSettled([
    user.isLogin ? api.visitedMerchants() : Promise.resolve([]),
    api.promotedMerchants({ communityNo }),
    api.merchantList({ communityNo }),
  ]);
  visited.value = v.status === "fulfilled" ? v.value : [];
  promoted.value = p.status === "fulfilled" ? p.value : [];
  nearby.value = n.status === "fulfilled" ? n.value : [];
  /*
   * 只有**问过的全挂**才算「没取到」：挂一条时另两块还在，照常显示。
   *
   * **`v` 不能无条件算进来**：未登录时它是 `Promise.resolve([])`，永远 fulfilled，
   * 于是 `every(rejected)` 永远为假 —— 这一页的出错态对未登录用户根本到不了，
   * 而没登录正是它最常见的访客。2026-09-09 在小程序运行时里把请求全打挂才看出来：
   * 三条全失败，页面显示的却是空态「这附近还没有商家入驻」加一颗「去逛逛」。
   * H5 上同样漏，只是我当时是登录态，第一条真的发了出去、也真的挂了。
   */
  const asked = user.isLogin ? [v, p, n] : [p, n];
  failed.value = asked.every((r) => r.status === "rejected");
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
    <!--
      三档**同一副长相**（biz-shop-row）：此前前两档是商家条 + 下面一排 chip、
      第三档另写一份密排行 —— 同一家店换一档就换一个样子。整行可点，不再放「进店 ›」。
    -->
    <!-- 1. 我买过的：真实消费过的关系，回购主路径，放最上面。第二行说买过几单，比评分有用 -->
    <view v-if="visited.length" class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("shops.visited") }}</text>
      </view>
      <biz-shop-row
        v-for="m in visited"
        :key="m.merchantNo"
        :merchant="m"
        :meta="`${$t('visited.orders', { n: m.orderCount })} · ${$t('visited.last', { d: isoDate(m.lastOrderAt) })}`"
        @tap="open(m.merchantNo)"
      ></biz-shop-row>
    </view>

    <!-- 2. 平台推荐：运营位，给新店一个不看历史成绩的位置 -->
    <view v-if="promotedShown.length" class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("shops.promoted") }}</text>
      </view>
      <biz-shop-row
        v-for="m in promotedShown"
        :key="m.merchantNo"
        :merchant="m"
        @tap="open(m.merchantNo)"
      ></biz-shop-row>
    </view>

    <!-- 3. 附近的：服务范围覆盖本社区，按距离 -->
    <view v-if="nearbyShown.length" class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("shops.nearby") }}</text>
      </view>
      <biz-shop-row
        v-for="m in nearbyShown"
        :key="m.merchantNo"
        :merchant="m"
        @tap="open(m.merchantNo)"
      ></biz-shop-row>
    </view>

    <sh-empty
      v-if="!visited.length && !promoted.length && !nearby.length" :pending="!loaded" :failed="failed" @retry="load"
      :text="String($t('shops.empty'))"
    >
      <template #action>
        <view class="sh-btn sh-btn--sm" @tap="goShopping">{{ $t("visited.go") }}</view>
      </template>
    </sh-empty>
  </sh-scaffold>
</template>
