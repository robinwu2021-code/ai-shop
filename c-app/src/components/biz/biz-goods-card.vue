<script setup lang="ts">
// 商品卡（扁平色块）：图占位是纯色块，信息用 chip 色块，价格不用红色堆砌 —— 靠字重与留白分层。
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { CATEGORY_TYPE } from "@shared/utils/constants";
import { money } from "@shared/utils/format";
import type { Goods } from "@shared/types";

const props = defineProps<{ goods: Goods; countdownText?: string }>();
// add 必须把原始 tap 事件透传出去 —— 「飞入购物车」动效要用它的落点坐标。
// 不透传的话页面里的 $event 是 undefined，动效静默失效。
defineEmits<{ (e: "add", ev: unknown): void; (e: "tap"): void }>();

const isFresh = computed(() => props.goods.type === CATEGORY_TYPE.FRESH);
const isService = computed(() => props.goods.type === CATEGORY_TYPE.SERVICE);
const { t } = useI18n();

/** 生鲜且在倒计时中 —— 这张卡的第二行让位给时效 */
const showCutoff = computed(() => isFresh.value && !!props.countdownText);
/**
 * 时效行的完整文案。**在脚本里拼好再输出一个字符串** ——
 * 模板里嵌套 `<text>` 在 uni 下会被渲染成块级，`white-space: nowrap` 当场失效，
 * 英文「Closes in 05:42 · At pickup point after 4 PM tomorrow」就折成了两行，
 * 卡片跟着变高、与封面又对不齐（中文短，看不出来，只有切英文才暴露）。
 */
const timeText = computed(() => {
  const head = String(t("home.cutoffIn", { t: props.countdownText }));
  return props.goods.arrivalDesc ? `${head} · ${props.goods.arrivalDesc}` : head;
});

const off = computed(() => {
  const o = props.goods.originPrice;
  if (!o || o <= props.goods.price) return 0;
  return Math.round((1 - props.goods.price / o) * 100);
});
</script>

<template>
  <view class="card" @tap="$emit('tap')">
    <view class="card__cover">{{ goods.cover }}</view>

    <view class="card__body">
      <text class="card__title">{{ goods.title }}</text>
      <!--
        第二行**按优先级取内容**，不是固定放描述：
          有活动时效 → 时效（警示色，且带上到货说明）
          没有       → 退回商品描述
        商品描述（「脆甜多汁·产地直发」）是这张卡上最不影响买不买的一行 ——
        扫列表时看的是「什么东西 / 多少钱 / 还剩多久 / 谁在卖」，描述四样都不占。
        把它让给时效之后，价格行不必再与倒计时抢位置，**划线价与折扣得以保留**。
        为什么不干脆删成三行：倒计时只有生鲜有，百货卡会矮一截，一列卡片高矮不齐、
        封面还得跟着变大小。按优先级取内容，两类商品都是四行，等高。
      -->
      <text v-if="showCutoff" class="card__sub card__sub--time">{{ timeText }}</text>
      <text v-else-if="isService && goods.storeName" class="card__sub">
        {{ goods.storeName }}
      </text>
      <text v-else class="card__sub">{{ goods.subtitle }}</text>

      <!-- 价格行只放价格这一件事：现价 + 划线价 + 折扣。
           时效搬到上一行之后，这里三件在英文下也放得开 -->
      <view class="card__foot">
        <text class="price__now sh-num">{{ money(goods.price) }}</text>
        <text v-if="goods.originPrice" class="price__was sh-num">
          {{ money(goods.originPrice) }}
        </text>
        <text v-if="off" class="sh-chip sh-chip--danger sh-num">-{{ off }}%</text>
        <view class="add" @tap.stop="$emit('add', $event)">
          <text class="add__sign">＋</text>
        </view>
      </view>

      <!-- 落款行：谁在卖 + 卖得好不好。位置固定在最下面才好扫 -->
      <view class="card__merchant">
        <text class="card__shop">{{ goods.merchant.logo }} {{ goods.merchant.name }}</text>
        <text class="card__sales sh-num">{{ $t("common.sold", { n: goods.sales }) }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
/*
 * 两栏：左图右字。正文四行（标题 / 副标题 / 价格 / 落款）≈ 168rpx，
 * 与 168 的方形封面天然齐平 —— 高度不用互相迁就，也就没有「图片被拉长」这回事。
 * 之前正文有五行、图片跟着 stretch，卡片高出图片近一倍，图顶在上面右边空一片。
 */
.card {
  display: flex;
  align-items: flex-start;
  gap: 20rpx;
  background: var(--sh-surface);
  border-radius: 28rpx;
  padding: 20rpx;
  margin-bottom: 14rpx;
}
.card__cover {
  width: 168rpx;
  height: 168rpx;
  border-radius: 22rpx;
  background: var(--sh-faint);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 64rpx;
  flex-shrink: 0;
}
.card__body {
  flex: 1;
  min-width: 0;
}
.card__title {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  letter-spacing: -0.2rpx;
  line-height: 1.35;
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card__sub {
  display: block;
  font-size: 24rpx;
  line-height: 1.35;
  color: var(--sh-sub);
  margin-top: 4rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card__foot {
  display: flex;
  align-items: center;
  gap: 10rpx;
  margin-top: 10rpx;
}
/* 卡片里的 chip 比通用件矮一档：通用 chip 是给正文用的，密排列表里显得肿 */
.card__foot .sh-chip {
  padding: 5rpx 14rpx;
  font-size: 21rpx;
}
.price__now {
  font-size: 36rpx;
  font-weight: 700;
  letter-spacing: -0.6rpx;
  color: var(--sh-ink);
  flex-shrink: 0;
}
.price__was {
  font-size: 22rpx;
  color: var(--sh-sub);
  text-decoration: line-through;
  flex-shrink: 0;
}
/* 时效行用警示色：它是「再不下单就没了」，与描述那行的中性灰不是一个分量 */
.card__sub--time {
  color: var(--sh-warning);
}
.add {
  width: 60rpx;
  height: 60rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-inline-start: auto;
}
.add__sign {
  color: var(--sh-primary);
  font-size: 32rpx;
  font-weight: 600;
  line-height: 1;
}
.card__merchant {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 8rpx;
}
.card__shop {
  font-size: 21rpx;
  color: var(--sh-sub);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card__sales {
  font-size: 21rpx;
  color: var(--sh-sub);
  flex-shrink: 0;
}
</style>
