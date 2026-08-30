<script setup lang="ts">
/**
 * 挑货 —— 开单时选货那一步，进货 / 报损 / 调拨三处共用。
 *
 * **抽出来是因为判据不一致，不是因为代码重复。** 抽之前三处各写一个裸列表：
 * 没有搜索、没有已选计数，几百个 SKU 时得滚十几屏去找一件 —— 而盘点页本轮
 * 刚加了搜索，于是同一个动作在四个地方是四种操作。重复的行数很少（七八行），
 * 真正的问题是**同一件事长得不一样**。
 *
 * **搜索在端上做，不发请求。** 挑货的候选一次取 200 条已经在手里，
 * 每敲一个字发一趟的话，弱网下商家看到的是列表跳来跳去。
 *
 * 盘点页不用这个件：它是整页勾选（先选一批再开单），形态不同 ——
 * 硬塞进弹层反而把「一次盘一批」压回「一次挑一件」。
 */
import { computed, ref, watch } from "vue";
import type { StockBalance } from "@shared/types";

const props = withDefaults(
  defineProps<{
    visible: boolean;
    title: string;
    items: StockBalance[];
    /** 已选的 itemId。已选的置灰并标出来 —— 不标的话商家会重复点同一件 */
    picked?: string[];
    /** 右侧那个数的说明，如「账面 {n}」「可用 {n}」。各屏关心的数不一样 */
    qtyLabel?: (b: StockBalance) => string;
  }>(),
  { picked: () => [], qtyLabel: undefined },
);

const emit = defineEmits<{ pick: [b: StockBalance]; close: [] }>();

const keyword = ref("");

// 关掉时清空关键词：留着的话下次打开是上一次的筛选结果，
// 而商家以为看到的是全部 —— 一个静默的空列表
watch(() => props.visible, (v) => {
  if (!v) keyword.value = "";
});

const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  if (!k) return props.items;
  return props.items.filter(
    (b) => b.name.toLowerCase().includes(k) || (b.specText ?? "").toLowerCase().includes(k),
  );
});

function isPicked(b: StockBalance): boolean {
  return props.picked.includes(b.itemId);
}
</script>

<template>
  <sh-sheet :visible="visible" :title="title" @close="emit('close')">
    <!--
      搜索常驻，不做成「货多了才出现」：出现与否取决于数据，人就学不会它在哪。

      类是 `field__input`（base.css 里定义的那一个）。**没有 `sh-input` 这个类** ——
      写它不会报错，只是没有边框：那一行看上去是一句灰色提示语，
      商家根本不知道能点进去打字。
    -->
    <input
      v-model="keyword"
      class="field__input pick__search"
      :placeholder="String($t('stockPick.searchPh'))"
      :maxlength="32"
      confirm-type="search"
    />

    <text v-if="picked.length" class="sh-hint pick__count">
      {{ $t("stockPick.picked", { n: picked.length }) }}
    </text>

    <sh-empty v-if="!shown.length" compact :text="String($t('stockPick.empty'))"></sh-empty>

    <view
      v-for="b in shown"
      :key="b.itemId"
      class="pick sh-row sh-row--between sh-row--baseline"
      :class="{ 'pick--on': isPicked(b) }"
      @tap="emit('pick', b)"
    >
      <!--
        「已下架」跟在名字后面，不另起一行 —— 它是**这一行是哪件货**的一部分，
        不是附加信息。线上有 13 组同名同规格的物料，弹层里几行完全一样
        （同库位、库存也一样），不标出来商家挑哪一行都不知道自己挑的是什么。
      -->
      <text class="txt-body">
        {{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}
        <text v-if="b.flags.includes('OFF_SALE')" class="sh-muted">{{ $t("stock.offSale") }}</text>
      </text>
      <text class="sh-muted sh-num">
        {{ qtyLabel ? qtyLabel(b) : b.onHand }}
      </text>
    </view>
  </sh-sheet>
</template>

<style scoped>
.pick__search {
  margin-bottom: 12rpx;
}
.pick__count {
  display: block;
  padding-bottom: 8rpx;
}
.pick {
  padding: 20rpx 0;
}
.pick + .pick {
  border-top: var(--sh-hairline-soft);
}
/* 已选的压暗但**仍可点** —— 再点一次是加一行（同一件货分两笔进货是常事），
   禁掉的话商家以为坏了 */
.pick--on {
  opacity: 0.45;
}
</style>
