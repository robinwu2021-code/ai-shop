<script setup lang="ts">
/*
 * 批量打 / 去一个标签（原型 m06；名单「对这批人」与效果页「下单的人打标签」两处用）。
 *
 * 一次只动**一个**标签：同时打三个，撤的时候就说不清哪批人是哪次打的。
 * 选定标签就当场试算，按钮上方写「其中 5 人已有，实际新增 32 人」—— 这是 AC-2，
 * 不写的话商家会拿名单上的 37 去对标签页上涨了 32，然后以为漏打了。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { BatchTagResult, MemberSegmentRule, MemberTag } from "@shared/types";

const props = defineProps<{
  visible: boolean;
  /** 圈人方式一：会员号 */
  memberNos?: string[];
  /** 圈人方式二：筛选条件（后端当场筛） */
  rule?: MemberSegmentRule;
  scopeStoreNo?: string;
  /** 标题里的人数 */
  count: number;
}>();
const emit = defineEmits<{ close: []; done: [r: BatchTagResult] }>();

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));

const tags = ref<MemberTag[]>([]);
const tagNo = ref("");
const action = ref<"ADD" | "REMOVE">("ADD");
const preview = ref<BatchTagResult | null>(null);
const busy = ref(false);

watch(() => props.visible, async (v) => {
  if (!v) return;
  tagNo.value = "";
  preview.value = null;
  action.value = "ADD";
  const all = await api.mMemberTags();
  tags.value = all.filter((x) => x.tagType === "MCH" && x.status === "ACTIVE");
});

function target() {
  return props.memberNos?.length
    ? { memberNos: props.memberNos }
    : { rule: props.rule ?? {}, scopeStoreNo: props.scopeStoreNo };
}

watch([tagNo, action], async () => {
  preview.value = null;
  if (!tagNo.value) return;
  try {
    preview.value = await api.mBatchTagMembers({ ...target(), tagNo: tagNo.value, action: action.value });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
});

const hint = computed(() => {
  const p = preview.value;
  if (!p) return "";
  const parts = [tt(action.value === "ADD" ? "batchTag.willAdd" : "batchTag.willRemove",
    { a: p.alreadyInState, n: p.willChange })];
  if (p.skippedFull) parts.push(tt("batchTag.full", { n: p.skippedFull }));
  return parts.join(" · ");
});

async function apply() {
  if (!tagNo.value || busy.value || !preview.value?.willChange) return;
  busy.value = true;
  try {
    const r = await api.mBatchTagMembers({ ...target(), tagNo: tagNo.value, action: action.value, confirm: true });
    uni.showToast({ title: tt("batchTag.done", { n: r.willChange }), icon: "none" });
    emit("done", r);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-sheet :visible="visible" :title="tt('batchTag.title', { n: count })" @close="emit('close')">
    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between">
        <text class="txt-body sh-muted">{{ $t("batchTag.action") }}</text>
        <view class="sh-row seg">
          <text class="sh-chip" :class="{ 'sh-chip--primary': action === 'ADD' }" @tap="action = 'ADD'">{{ $t("batchTag.add") }}</text>
          <text class="sh-chip" :class="{ 'sh-chip--primary': action === 'REMOVE' }" @tap="action = 'REMOVE'">{{ $t("batchTag.remove") }}</text>
        </view>
      </view>
    </view>

    <text class="txt-caption sh-muted grp">{{ $t("batchTag.pick") }}</text>
    <view class="sh-cells">
      <view v-for="tg in tags" :key="tg.tagNo" class="sh-cell sh-row sh-row--between" @tap="tagNo = tg.tagNo">
        <text class="txt-body" :class="{ 'txt-primary': tagNo === tg.tagNo }">{{ tg.name }}</text>
        <view class="sh-row">
          <text class="txt-body sh-muted sh-num">{{ tg.count }}</text>
          <sh-icon v-if="tagNo === tg.tagNo" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
    </view>
    <sh-empty v-if="!tags.length" compact bare :text="tt('batchTag.noTags')"></sh-empty>

    <template #foot>
      <text v-if="hint" class="txt-caption sh-muted foot__hint">{{ hint }}</text>
      <view class="sh-row bar">
        <view class="sh-btn sh-btn--muted sh-fill" @tap="emit('close')">{{ $t("batchTag.cancel") }}</view>
        <view class="sh-btn bar__main" :class="{ 'is-disabled': busy || !preview?.willChange }" @tap="apply">
          {{ $t(action === "ADD" ? "batchTag.add" : "batchTag.remove") }}
        </view>
      </view>
    </template>
  </sh-sheet>
</template>

<style scoped>
.grp {
  display: block;
  padding: 16rpx 8rpx 0;
}
.seg {
  gap: 12rpx;
}
.foot__hint {
  display: block;
  padding-bottom: 16rpx;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
