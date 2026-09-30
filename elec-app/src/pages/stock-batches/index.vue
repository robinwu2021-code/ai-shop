<script setup lang="ts">
// 上传记录。每一次上传都在这里：已上架的、待确认的、放弃的、作废的、过期的、解析失败的。
// 点一条进确认页（待确认的可以接着确认，其余只读）；有问题行的可以导出，改好按增量补传。
import { ref } from "vue";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api, errMsg } from "@/api";
import { handleElecError } from "@/shared/errors";
import { ensureLogin } from "@/shared/auth";
import { openSheet } from "@/shared/file";
import { ROUTES, go, withQuery } from "@/shared/routes";
import { BATCH_STATUS, qtyOf } from "@/shared/format";
import type { ElecBatchStatus, ElecBatchSummary } from "@shared/types";

const SIZE = 20;
/** 解析失败的原因（后端给的是错误码的文案键） */
const FAIL: Record<string, string> = {
  "err.elec.upload_format": "文件格式不支持，另存为 .xlsx 再传",
  "err.elec.upload_no_header": "表里是空的",
  "err.elec.upload_too_many_rows": "行数太多，分成几张表",
};
const TONE: Partial<Record<ElecBatchStatus, string>> = {
  APPLIED: "txt-primary", PARSED: "warn", NEED_MAPPING: "warn", FAILED: "danger",
};

const list = ref<ElecBatchSummary[]>([]);
const page = ref(1);
const done = ref(false);
const loaded = ref(false);
const failed = ref("");
const busy = ref(false);

onShow(async () => {
  if (!(await ensureLogin())) return;
  await reload();
});
onReachBottom(() => void more());

async function reload() {
  page.value = 1;
  failed.value = "";
  try {
    list.value = await api.myBatches(1, SIZE);
    done.value = list.value.length < SIZE;
  } catch (e) {
    failed.value = errMsg(e);
  } finally {
    loaded.value = true;
  }
}

async function more() {
  if (done.value || !loaded.value) return;
  const next = await api.myBatches(page.value + 1, SIZE).catch(() => [] as ElecBatchSummary[]);
  page.value += 1;
  list.value = list.value.concat(next);
  done.value = next.length < SIZE;
}

function open(b: ElecBatchSummary) {
  if (b.status === "FAILED") return;
  if (b.status === "NEED_MAPPING") {
    go(ROUTES.stockUpload);
    return;
  }
  go(withQuery(ROUTES.stockPreview, { batchNo: b.batchNo }));
}

function timeOf(s?: string | null): string {
  return s ? s.replace("T", " ").slice(5, 16) : "";
}

async function exportProblems(b: ElecBatchSummary) {
  if (busy.value) return;
  busy.value = true;
  try {
    const bytes = await api.batchProblems(b.batchNo);
    const base = (b.fileName || b.batchNo).replace(/\.[A-Za-z0-9]{1,5}$/, "");
    await openSheet(bytes, `${base}_问题行.xlsx`);
  } catch (e) {
    handleElecError(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="title.stockBatches" :failed="!!failed" :failed-text="failed" @retry="reload">
    <sh-empty v-if="loaded && !list.length" text="还没有上传过">
      <template #action>
        <view class="sh-btn sh-btn--sm" @tap="go(ROUTES.stockUpload)">上传库存</view>
      </template>
    </sh-empty>
    <view v-else class="sh-cells">
      <view v-for="b in list" :key="b.batchNo" class="sh-cell" @tap="open(b)">
        <view class="sh-row sh-row--between">
          <text class="txt-body name">{{ b.fileName || b.batchNo }}</text>
          <text class="txt-caption" :class="TONE[b.status] ?? 'sh-muted'">{{ BATCH_STATUS[b.status] }}</text>
        </view>
        <text class="txt-caption sh-muted line">
          {{ timeOf(b.createdAt) }} · {{ b.mode === "REPLACE" ? "全量替换" : "增量" }}
          <template v-if="b.status !== 'FAILED' && b.rowTotal"> · {{ qtyOf(b.rowTotal) }} 行</template>
        </text>
        <text v-if="b.status === 'FAILED'" class="txt-caption danger line">{{ FAIL[b.failCode ?? ""] ?? "没能读出这张表" }}</text>
        <text v-else-if="b.status === 'APPLIED'" class="txt-caption sh-muted line">
          新增 {{ qtyOf(b.toInsert) }} · 更新 {{ qtyOf(b.toUpdate) }} · 下架 {{ qtyOf(b.toDelist) }}
        </text>
        <view v-if="b.status === 'APPLIED' && b.rowInvalid + b.rowWarn > 0" class="sh-row acts" @tap.stop>
          <text class="txt-caption warn">{{ qtyOf(b.rowInvalid) }} 行没上架 · {{ qtyOf(b.rowWarn) }} 行有提醒</text>
          <text class="sh-link" @tap="exportProblems(b)">导出</text>
          <text class="sh-link" @tap="go(ROUTES.stockUpload)">补传</text>
        </view>
      </view>
      <text v-if="done && list.length" class="txt-caption sh-muted end">没有更早的了</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.name {
  flex: 1;
  min-width: 0;
  margin-right: 16rpx;
  word-break: break-all;
}
.line {
  display: block;
  margin-top: 6rpx;
}
.acts {
  gap: 24rpx;
  margin-top: 12rpx;
}
.warn {
  color: var(--sh-warning);
}
.danger {
  color: var(--sh-danger);
}
.end {
  display: block;
  padding: 28rpx;
  text-align: center;
}
</style>
