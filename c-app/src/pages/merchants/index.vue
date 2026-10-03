<script setup lang="ts">
/*
 * 店铺 tab —— 「挑哪家店」这件事的专属页面（TDD-C端门店化与门店门户 s01/s02）。
 *
 * **单位是门店，不是主体**：同一主体下的几家店各占一行、叫门店名。此前按主体列，
 * 四家店在这里只有一行、叫公司名，点进去还可能是一家已经停用的店。
 *
 * 两段，按关系由近到远：
 *   1. 我的店 —— 买过的一直在；只逛过的留 30 天。每行第二行说**为什么它在这里**
 *      （买过几次 / 朋友分享 / 逛过）。「朋友分享」那一行就是给商家的回报：
 *      别人点开他分享的门店，这家店就留在对方的列表里
 *   2. 附近   —— 能卖到这个社区、营业中，去掉上一段已有的；有位置按距离，没位置按评分
 * 「平台推荐」不再放这里（2026-09-29 拍板）。没登录就没有第一段 —— 不是空着留个标题。
 */
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCommunityStore } from "@/stores/community";
import { useLocationStore } from "@/stores/location";
import { useUserStore } from "@/stores/user";
import { ROUTES } from "@shared/utils/constants";
import type { StoreCard } from "@shared/types";

const community = useCommunityStore();
const location = useLocationStore();
const user = useUserStore();

const mine = ref<StoreCard[]>([]);
const nearby = ref<StoreCard[]>([]);
const loaded = ref(false);
/** 问过的全没取到。**与「这一带确实没有店」是两件事** */
const failed = ref(false);

async function load() {
  // 距离按买家生效地址算（与下单取自提点同一个点）；没有地址就不传，后端按评分排
  const at = location.active;
  const point = at?.latE6 != null && at?.lngE6 != null ? { latE6: at.latE6, lngE6: at.lngE6 } : {};
  /*
   * `allSettled` 而不是各自 `.catch(() => [])`：后者把「没取到」抹成「空」，
   * 两条全挂时页面会说「这一带还没有店」—— 而真相是一条都没取到。
   */
  const [m, n] = await Promise.allSettled([
    user.isLogin ? api.myStores(point) : Promise.resolve([] as StoreCard[]),
    api.storeNearby({ ...point, communityNo: community.community?.communityNo, size: 50 }),
  ]);
  mine.value = m.status === "fulfilled" ? m.value : [];
  nearby.value = n.status === "fulfilled" ? n.value.records : [];
  // 未登录时第一条是恒 fulfilled 的空数组，不能算进「全挂了」—— 否则出错态对游客永远到不了
  const asked = user.isLogin ? [m, n] : [n];
  failed.value = asked.every((r) => r.status === "rejected");
  loaded.value = true;
}

function open(s: StoreCard) {
  uni.navigateTo({ url: `${ROUTES.store}?no=${s.storeNo}&from=LIST` });
}

function goShopping() {
  uni.switchTab({ url: ROUTES.home });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="shops.title" tab="merchants">
    <view v-if="mine.length" class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("shops.mine") }}</text>
        <text class="txt-caption txt-quiet">{{ $t("shops.mineHint") }}</text>
      </view>
      <biz-store-row v-for="s in mine" :key="s.storeNo" :store="s" @tap="open(s)"></biz-store-row>
    </view>

    <view v-if="nearby.length" class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("shops.nearby") }}</text>
      </view>
      <biz-store-row v-for="s in nearby" :key="s.storeNo" :store="s" @tap="open(s)"></biz-store-row>
    </view>

    <sh-empty
      v-if="!mine.length && !nearby.length" :pending="!loaded" :failed="failed" @retry="load"
      :text="String($t('shops.empty'))"
    >
      <template #action>
        <view class="sh-btn sh-btn--sm" @tap="goShopping">{{ $t("visited.go") }}</view>
      </template>
    </sh-empty>
  </sh-scaffold>
</template>
