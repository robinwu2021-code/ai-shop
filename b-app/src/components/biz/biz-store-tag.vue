<script setup lang="ts">
/**
 * 页头的「当前门店」胶囊。
 *
 * <b>两种形态，一个组件</b>：
 * - 工作台（可点）：门店这件事的**唯一入口** —— 点进门店管理，在那里切店、改名、开新店。
 * - 门店维度的作业页（`readonly`）：只说清「这一屏属于哪家店」，不带切换动作。
 *   曾经每页都能切，结果是人在商品页切了店、回工作台看的是另一家的数字。
 *   这里保留店名是因为改错门店的成本很高（改的是价格、送货范围这类东西）。
 *
 * 单店主体只在可点形态下渲染 —— 那时它是「门店管理」的门，不是上下文提示。
 */
import { computed, onMounted } from "vue";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";

const props = defineProps<{ readonly?: boolean }>();
const merchant = useMerchantStore();

onMounted(() => {
  void merchant.ensureStores();
});

/** 只读形态只为消歧义：一家店没有歧义可消 */
const show = computed(() =>
  props.readonly
    ? merchant.multiStore
    : merchant.multiStore || merchant.can("biz:store:admin"));

/** 一家店的人点进去是「管理」（在那里开第二家），多店的人点进去是「切换」 */
const actionKey = computed(() =>
  merchant.multiStore ? "storePick.switch" : "storePick.manage");

function go() {
  if (props.readonly) return;
  uni.navigateTo({ url: ROUTES.stores });
}
</script>

<template>
  <view v-if="show" class="tag" :class="{ 'tag--flat': readonly }" @tap="go">
    <sh-icon
      name="store"
      :size="18"
      :color="readonly ? 'var(--sh-sub)' : 'var(--sh-primary-text)'"
    ></sh-icon>
    <text class="tag__name">{{ merchant.currentStore?.name || "—" }}</text>
    <sh-go v-if="!readonly" :text="String($t(actionKey))"></sh-go>
  </view>
</template>

<style scoped>
/* 左置胶囊：带底色与边界，看得出「这是一个控件」——右对齐小字的教训 */
.tag {
  display: inline-flex;
  align-items: center;
  gap: 10rpx;
  margin-bottom: 16rpx;
  padding: 12rpx 20rpx;
  border-radius: 44rpx;
  background: var(--sh-primary-tint);
}
/* 只读：褪成灰底，长得不像能点 —— 点了没反应的控件比没有控件更糟 */
.tag--flat {
  padding: 8rpx 0;
  background: transparent;
}
.tag__name {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.tag--flat .tag__name {
  font-weight: 400;
  color: var(--sh-sub);
}
</style>
