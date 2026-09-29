<script setup lang="ts">
// 上传 · 列映射（原型 e15）。从微信聊天里选 Excel / CSV；每列猜一个含义，按供应商记住上次的映射。
//
// 各家 ERP 的表头五花八门（型号 / P/N / 料号 / Part No.），猜不准就让他点一下改，改过一次记住 ——
// 第二次上传这一屏基本是直接点下一步。选了「全量替换」就在这里先说一句后果，
// 而不是等他点到下一屏才发现。
import { computed, ref } from "vue";
import { api, errMsg, toast } from "@/api";
import { ensureLogin } from "@/shared/auth";
import { setBatch } from "@/shared/batch";
import { pickSheet, type PickedFile } from "@/shared/file";
import { ROUTES, go } from "@/shared/routes";
import { COLUMN_FIELDS, colLetter, qtyOf } from "@/shared/format";
import type { ElecBatchPreview, ElecImportMode } from "@shared/types";

/** 后端 multipart 上限 5MB（elec-svc application.yml） */
const MAX_BYTES = 5 * 1024 * 1024;

const file = ref<PickedFile | null>(null);
const mode = ref<ElecImportMode>("MERGE");
const taxIncluded = ref(true);
const preview = ref<ElecBatchPreview | null>(null);
/** 本页上改过、还没提交给后端的映射 */
const columns = ref<Record<string, number>>({});
const busy = ref(false);

async function choose() {
  if (!(await ensureLogin())) return;
  let f: PickedFile | null;
  try {
    f = await pickSheet();
  } catch (e) {
    toast(errMsg(e));
    return;
  }
  if (!f) return;
  if (f.size > MAX_BYTES) {
    toast("文件超过 5MB，拆成两张表分两次传");
    return;
  }
  file.value = f;
  await upload();
}

/** 换了导入方式或含税口径要重传：这两样是上传时定的，列映射改不了它们 */
async function upload() {
  if (!file.value) return;
  busy.value = true;
  try {
    preview.value = await api.uploadStock(file.value.path, mode.value, taxIncluded.value);
    columns.value = { ...preview.value.columns };
  } catch (e) {
    preview.value = null;
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

function setMode(m: ElecImportMode) {
  if (mode.value === m) return;
  mode.value = m;
  void upload();
}
function setTax(v: boolean) {
  taxIncluded.value = v;
  void upload();
}

/** 每个字段的下拉：第 0 项是「不导入」，其后是表头各列 */
const options = computed(() => ["不导入", ...(preview.value?.headers ?? []).map((h, i) => `${colLetter(i)}　${h || "（空表头）"}`)]);
function indexOf(key: string): number {
  const c = columns.value[key];
  return c === undefined ? 0 : c + 1;
}
function onPick(key: string, e: { detail: { value: number | string } }) {
  const v = Number(e.detail.value);
  const next = { ...columns.value };
  if (v === 0) delete next[key];
  else next[key] = v - 1;
  columns.value = next;
}

const changed = computed(() => JSON.stringify(columns.value) !== JSON.stringify(preview.value?.columns ?? {}));
const missing = computed(() => COLUMN_FIELDS.filter((f) => f.required && columns.value[f.key] === undefined).map((f) => f.label));

async function next() {
  const p = preview.value;
  if (!p || busy.value) return;
  if (missing.value.length) {
    toast(`「${missing.value.join("」「")}」是哪一列还没选`);
    return;
  }
  busy.value = true;
  try {
    const out = changed.value ? await api.remapBatch(p.batchNo, { columns: columns.value }) : p;
    preview.value = out;
    columns.value = { ...out.columns };
    setBatch(out, file.value?.name ?? "");
    go(ROUTES.stockPreview, { batchNo: out.batchNo });
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="title.stockUpload">
    <view class="sh-card">
      <view v-if="file" class="sh-row sh-row--between">
        <view class="grow">
          <text class="txt-strong name">{{ file.name }}</text>
          <text v-if="preview" class="txt-caption sh-muted block">{{ qtyOf(preview.rowTotal) }} 行</text>
          <text v-else-if="busy" class="txt-caption sh-muted block">正在识别…</text>
        </view>
        <text class="sh-link" @tap="choose">换文件</text>
      </view>
      <view v-else>
        <view class="sh-btn" @tap="choose">选库存表</view>
        <text class="txt-caption sh-muted block center">从微信聊天里选 Excel / CSV，最大 5MB</text>
      </view>
    </view>

    <view class="sh-card block">
      <text class="txt-sub">导入方式</text>
      <view class="sh-row opts">
        <text class="sh-seg sh-seg--fill" :class="{ 'sh-seg--on': mode === 'MERGE' }" @tap="setMode('MERGE')">增量</text>
        <text class="sh-seg sh-seg--fill" :class="{ 'sh-seg--on': mode === 'REPLACE' }" @tap="setMode('REPLACE')">全量替换</text>
      </view>
      <text v-if="mode === 'MERGE'" class="txt-caption sh-muted block">只改表里有的行，别的库存不动</text>
      <text v-else class="txt-caption warn block">全量替换：这张表里没有的行会下架 —— 下一步先给你看会下架哪些</text>
      <view class="sh-row sh-row--between gap">
        <text class="txt-sub">价格含税</text>
        <sh-switch :model-value="taxIncluded" @update:model-value="setTax"></sh-switch>
      </view>
    </view>

    <view v-if="preview" class="sh-card block">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">这几列分别是什么</text>
        <text class="txt-caption sh-muted">猜错了点一下改</text>
      </view>
      <picker v-for="f in COLUMN_FIELDS" :key="f.key" mode="selector" :range="options" :value="indexOf(f.key)"
        @change="onPick(f.key, $event)">
        <view class="sh-row sh-row--between map">
          <text class="txt-sub">{{ f.label }}<text v-if="f.required" class="warn"> *</text></text>
          <text class="txt-sub" :class="columns[f.key] === undefined ? 'sh-muted' : 'txt-ink'">
            {{ options[indexOf(f.key)] }} ▾
          </text>
        </view>
      </picker>
      <text class="txt-caption sh-muted block">「备注」那类列默认不导入：常写着公司名和微信，会泄露身份</text>
    </view>

    <sh-actionbar v-if="preview">
      <view class="sh-btn" :class="{ 'is-disabled': busy }" @tap="next">{{ busy ? "处理中…" : "下一步：预览" }}</view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.grow {
  flex: 1;
  min-width: 0;
}
.name {
  display: block;
  word-break: break-all;
}
.block {
  display: block;
  margin-top: 24rpx;
}
.sh-card .block {
  margin-top: 12rpx;
}
.center {
  text-align: center;
}
.opts {
  gap: 16rpx;
  margin-top: 16rpx;
}
.gap {
  margin-top: 28rpx;
}
.warn {
  color: var(--sh-warning);
}
.map {
  padding: 20rpx 0;
  border-top: var(--sh-hairline);
}
</style>
