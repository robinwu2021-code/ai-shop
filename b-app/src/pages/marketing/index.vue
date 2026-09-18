<script setup lang="ts">
/*
 * 营销（B-11.8）。**它是一个容器，不是一个功能** —— 自己没有数据，
 * 下面装着活动、优惠券、团购三类（见《营销域-概念对齐》§2.1）。
 *
 * ★ **2026-09-18 重写**：这一页原来 415 行，里面还带着**老模型四类活动
 * （mkt_campaign）的建单表单**，与「活动」那一页的玩法一一对应 ——
 * 满减/限时特价/买赠/店铺券 ↔ AMOUNT×CUT / GOODS×PRICE / QTY×GIFT / NONE×COUPON。
 *
 * 两套不只是长得像，是**同时在算价**（CampaignPortRouter 逐商家取更优）：
 * 同一个店两边各建一个满减，顾客只享其一，而商家在两个页面上看到的都是
 * 「进行中」。他以为叠加，实际没有；想停掉还得知道去哪一页停。
 *
 * 老模型线上 0 行（mkt_campaign / mkt_coupon 都是 0），所以退场不用搬数据。
 * 见《TDD-活动与营销活动的合并》。
 */
import { onShow } from "@dcloudio/uni-app";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";

const merchant = useMerchantStore();

/*
 * 三个去处，**每个回答一个不同的问题**：
 *   活动 —— 我在跑什么优惠（配一次就不动）
 *   优惠券 —— 我发了什么券（配一次就不动）
 *   团购 —— 团凑齐了没有（**每天要看**）
 *
 * 团购不是活动的重复：活动里定的是规则（几人成团、成团价），
 * 这一页开的是「团」这个实例。一条规则能开出很多团。
 */
const ENTRIES = [
  { key: "activities", route: ROUTES.activities },
  { key: "coupons", route: ROUTES.coupons },
  { key: "groups", route: ROUTES.groups },
  /*
   * 求团报价：**别人发需求、你去报价**，是进来的活不是配置 ——
   * 与另外三个不同，它有「今天有几单等着你」这种时效性。
   * 但它属于团那条线，留在工作台一级会让营销这条线有两个口。
   */
  { key: "quotes", route: ROUTES.quotes },
] as const;

function go(url: string) {
  uni.navigateTo({ url });
}

onShow(() => {
  // 只为判权，不拉任何列表 —— 这一页没有自己的数据
  void merchant.ensureStores().catch(() => null);
});
</script>

<template>
  <sh-scaffold title-key="marketing.title" :denied="!merchant.can('biz:campaign')">
    <!--
      一行一个去处，整条可点。**不放计数**（「3 个在跑」这类）：
      那要为一个纯导航页拉三份列表，而他点进去立刻就会看到。
    -->
    <view
      v-for="e in ENTRIES"
      :key="e.key"
      class="sh-card entry sh-row sh-row--between"
      @tap="go(e.route)"
    >
      <view class="sh-fill">
        <text class="txt-strong">{{ $t(`marketing.entry.${e.key}`) }}</text>
        <text class="txt-caption sh-muted entry__d">{{ $t(`marketing.entryHint.${e.key}`) }}</text>
      </view>
      <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
    </view>
  </sh-scaffold>
</template>

<style scoped>
/* 整条可点：88rpx 是可点下限，两行内容加卡内边距已经过了 */
.entry {
  min-height: 88rpx;
}
.entry__d {
  display: block;
  margin-top: 4rpx;
}
</style>
