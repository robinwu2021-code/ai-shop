<script setup lang="ts">
/**
 * 挑供应商 —— 开进货单时选「这批货从哪来」。
 *
 * <p><b>它替掉的是一个自由输入框</b>，那个框旁边原本写着「仅作记录，不建立供应商档案」。
 * 名字会漂：同一家「老周粮油」被打成「老周粮油店」「老周」「周老板」，
 * 进货报表按名字聚合，商家看到的是三个供应商、进货额被拆成三份。
 *
 * <p><b>新建就在这一层里完成，不跳页。</b> 商家来这儿是为了记一笔进货，
 * 跳去建档案页再跳回来，中间那张进货单的草稿状态就成了他要操心的事 ——
 * 而他只是想填一个名字。
 *
 * <p><b>搜索在端上做，不发请求。</b> 与挑货那一件同一条规矩：
 * 候选一次取回，每敲一个字发一趟的话，弱网下列表会跳。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import type { Supplier } from "@shared/types";

const props = withDefaults(
  defineProps<{
    visible: boolean;
    items: Supplier[];
    /** 当前已选的 supplierNo，标出来 —— 不标的话商家不知道现在选的是哪家 */
    picked?: string;
    busy?: boolean;
  }>(),
  { picked: "", busy: false },
);

const emit = defineEmits<{
  pick: [s: Supplier];
  /** 新建。父组件负责调接口 —— 弹层不碰网络，它只管「选哪家」这件事 */
  create: [name: string];
  close: [];
}>();

const { t } = useI18n();
const keyword = ref("");

// 关掉时清空关键词：留着的话下次打开是上一次的筛选结果，
// 而商家以为看到的是全部 —— 一个静默的空列表
watch(
  () => props.visible,
  (v) => {
    if (!v) keyword.value = "";
  },
);

const kw = computed(() => keyword.value.trim());

const shown = computed(() => {
  const k = kw.value.toLowerCase();
  if (!k) return props.items;
  return props.items.filter(
    (s) =>
      s.name.toLowerCase().includes(k) ||
      (s.shortName ?? "").toLowerCase().includes(k) ||
      (s.contactName ?? "").toLowerCase().includes(k),
  );
});

/**
 * 搜不到才给「新建」。
 *
 * **恰好同名时不给** —— 那家已经在档案里，再建一条只会被后端 10409 拒掉，
 * 而商家看到的是一次没有理由的失败。
 */
const canCreate = computed(
  () => kw.value.length > 0 && !props.items.some((s) => s.name === kw.value),
);
</script>

<template>
  <sh-sheet :visible="visible" :title="String($t('supplier.pickTitle'))" @close="emit('close')">
    <!--
      类是 `field__input`（base.css 里那一个）。**没有 `sh-input` 这个类** ——
      写它不会报错，只是没有边框：那一行看上去是一句灰色提示语，
      商家根本不知道能点进去打字。
    -->
    <input
      v-model="keyword"
      class="field__input sup__search"
      :placeholder="String($t('supplier.searchPh'))"
      :maxlength="64"
      confirm-type="search"
    />

    <!-- 搜不到就地建，不跳页 -->
    <view v-if="canCreate" class="sh-btn sup__new" @tap="emit('create', kw)">
      {{ busy ? $t("common.loading") : $t("supplier.createAs", { name: kw }) }}
    </view>

    <sh-empty
      v-if="!shown.length && !canCreate"
      compact
      :text="String($t('supplier.empty'))"
    ></sh-empty>

    <view
      v-for="s in shown"
      :key="s.supplierNo"
      class="sup sh-row sh-row--between sh-row--baseline"
      :class="{ 'sup--on': s.supplierNo === picked }"
      @tap="emit('pick', s)"
    >
      <text class="txt-body">{{ s.shortName || s.name }}</text>
      <text v-if="s.contactName" class="sh-muted">{{ s.contactName }}</text>
    </view>
  </sh-sheet>
</template>

<style scoped>
.sup__search {
  margin-bottom: 12rpx;
}

.sup__new {
  margin-bottom: 16rpx;
}

.sup {
  padding: 22rpx 0;
  border-bottom: 1rpx solid var(--sh-line);
}

/* 当前已选的那一行加重 —— 与挑货件的「已选置灰」不同：
   这里是单选，标的是「就是它」，不是「别再点」。
   **红字走 primary-text 不走 primary**：后者是块面色，用在文字上对比度不够，
   而这一行正是要让人一眼看见的那一行。 */
.sup--on {
  color: var(--sh-primary-text);
}
</style>
