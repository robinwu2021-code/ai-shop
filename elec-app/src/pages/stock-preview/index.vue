<script setup lang="ts">
// 上架前确认（原型 e16）。四个数：新增、更新、下架、未变。**确认之前一行库存都没动。**
//
// 全量替换最危险的情况是他传了一张只有半截的表（导出时筛过、或者少了一个 sheet）——
// 要下架的标红、默认展开，他一眼就能发现「这不对」。
// 一行有效的都没有时直接不给确认，否则一次误操作会把他全部库存下架。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { batchFileName, getBatch, setBatch } from "@/shared/batch";
import { ROUTES, backTo } from "@/shared/routes";
import { ROW_PROBLEM, qtyOf } from "@/shared/format";
import type { ElecBatchPreview } from "@shared/types";

const p = ref<ElecBatchPreview | null>(null);
/** 选的那个文件叫什么。后端记的是上传临时路径的名字（小程序与 H5 都拿不到原名），所以用端上的 */
const fileName = ref("");
const tab = ref<"delist" | "problems">("delist");
const busy = ref(false);

onLoad((q) => {
  p.value = getBatch(q?.batchNo ? String(q.batchNo) : "");
  fileName.value = batchFileName();
  if (p.value && !p.value.toDelist) tab.value = "problems";
});

const stats = computed(() => {
  const x = p.value;
  if (!x) return [];
  return [
    { key: "ins", value: qtyOf(x.toInsert), label: "新增" },
    { key: "upd", value: qtyOf(x.toUpdate), label: "更新" },
    { key: "del", value: qtyOf(x.toDelist), label: "下架" },
    { key: "same", value: qtyOf(x.unchanged), label: "未变" },
  ];
});
const tabs = computed(() => [
  { key: "delist", label: `下架 ${qtyOf(p.value?.toDelist ?? 0)}` },
  { key: "problems", label: `认不了 ${qtyOf(p.value?.rowInvalid ?? 0)}` },
]);
const canApply = computed(() => !!p.value && p.value.status === "PARSED" && p.value.rowValid > 0 && !busy.value);

async function apply() {
  const x = p.value;
  if (!x || !canApply.value) return;
  if (x.toDelist) {
    const ok = await new Promise<boolean>((resolve) => uni.showModal({
      title: `确认下架 ${qtyOf(x.toDelist)} 行？`,
      content: "这些料号这次的表里没有。下架后买家就搜不到了",
      success: (r) => resolve(r.confirm),
      fail: () => resolve(false),
    }));
    if (!ok) return;
  }
  busy.value = true;
  try {
    const out = await api.applyBatch(x.batchNo);
    setBatch(out);
    p.value = out;
    toast("已上架");
    setTimeout(() => backTo(ROUTES.supplier), 600);
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

/** 冷启动落在这一页时栈里没有上一页，不能只 navigateBack */
function reupload() {
  uni.redirectTo({ url: ROUTES.stockUpload });
}

function back() {
  uni.navigateBack();
}
</script>

<template>
  <sh-scaffold title-key="title.stockPreview">
    <sh-empty v-if="!p" text="预览不见了" tip="重新选一次文件就好 —— 预览时库存一行都没动">
      <template #action>
        <view class="sh-btn sh-btn--sm" @tap="reupload">重新上传</view>
      </template>
    </sh-empty>
    <template v-else>
      <view class="sh-card">
        <sh-stat :items="stats"></sh-stat>
        <text class="txt-caption sh-muted block">
          {{ fileName || p.fileName }} · 共 {{ qtyOf(p.rowTotal) }} 行，能上架 {{ qtyOf(p.rowValid) }} 行 ·
          {{ p.mode === "REPLACE" ? "全量替换" : "增量" }} · {{ p.taxIncluded ? "含税" : "未税" }}
        </text>
      </view>
      <view v-if="p.rowValid === 0" class="sh-notice sh-notice--danger block">
        <text class="txt-sub">这张表一行能上架的都没有 —— 多半是列选错了，回上一页改</text>
      </view>

      <!-- 间距放在外层：调用点的 class 会落到组件根上（mergeVirtualHostAttributes），display 会被改掉 -->
      <view class="block">
        <sh-tabs :items="tabs" :active="tab" line @change="tab = $event as 'delist' | 'problems'"></sh-tabs>
      </view>
      <view v-if="tab === 'delist'" class="sh-cells">
        <view v-for="m in p.delistSample" :key="m" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-num">{{ m }}</text>
          <text class="txt-caption danger">将下架</text>
        </view>
        <text v-if="p.toDelist > p.delistSample.length" class="txt-caption sh-muted more">
          还有 {{ qtyOf(p.toDelist - p.delistSample.length) }} 行 · 这次的表里没有它们
        </text>
        <sh-empty v-if="!p.toDelist" compact text="没有要下架的"></sh-empty>
      </view>
      <view v-else class="sh-cells">
        <view v-for="x in p.problems" :key="x.row" class="sh-cell">
          <view class="sh-row sh-row--between">
            <text class="txt-body sh-num">{{ x.mpn || "（空）" }}</text>
            <text class="txt-caption warn">认不了</text>
          </view>
          <text class="txt-caption sh-muted">第 {{ x.row }} 行 · {{ ROW_PROBLEM[x.reason] }}，这一行跳过</text>
        </view>
        <sh-empty v-if="!p.problems.length" compact text="每一行都认得出"></sh-empty>
      </view>

      <sh-actionbar v-if="p.status === 'PARSED'">
        <view class="sh-row acts">
          <view class="sh-btn sh-btn--muted grow" @tap="back">放弃</view>
          <view class="sh-btn grow" :class="{ 'is-disabled': !canApply }" @tap="apply">{{ busy ? "上架中…" : "确认上架" }}</view>
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.block {
  display: block;
  margin-top: 24rpx;
}
.sh-card .block {
  margin-top: 16rpx;
}
.danger {
  color: var(--sh-danger);
}
.warn {
  color: var(--sh-warning);
}
.more {
  display: block;
  padding: 20rpx 28rpx;
}
.acts {
  gap: 20rpx;
  width: 100%;
}
.grow {
  flex: 1;
}
</style>
