<script setup lang="ts">
// 上架前确认（原型 e16）。五类：新增、更新、下架、未变、有问题 —— 看的是**解析之后**的值，
// 让他核对「平台是不是这么理解我的表」。**确认之前一行库存都没动**，数据只在服务器内存里，上传起一小时内有效。
//
// 全量替换最危险的情况是他传了一张只有半截的表（导出时筛过、或者少了一个 sheet）——
// 要下架的默认展开；过了线还要他确认此刻的下架数（后端按此刻重算，不信预览时的数）。
// 从「上传记录」点进来的已上架 / 已放弃等批次是只读的：只看数和问题，能导出问题行。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, toast } from "@/api";
import { codeOf, handleElecError } from "@/shared/errors";
import { batchFileName, getBatch, setBatch } from "@/shared/batch";
import { openSheet } from "@/shared/file";
import { ROUTES, backTo } from "@/shared/routes";
import { BATCH_STATUS, issueText, qtyOf } from "@/shared/format";
import type { ElecBatchPreview, ElecIssue, ElecPreviewKind, ElecPreviewRow } from "@shared/types";

/** 下架护栏：此刻的下架数与他确认的不一致 */
const E_DELIST_CONFIRM = 90016;
const PAGE = 50;

const p = ref<ElecBatchPreview | null>(null);
const loading = ref(true);
/** 端上选的文件名（刚上传时）；从记录进来用后端记下的原名 */
const fileName = ref("");
const tab = ref<ElecPreviewKind>("INSERT");
const rows = ref<ElecPreviewRow[]>([]);
const page = ref(1);
const more = ref(false);
const busy = ref(false);

onLoad(async (q) => {
  const no = q?.batchNo ? String(q.batchNo) : "";
  fileName.value = batchFileName();
  p.value = getBatch(no);
  if (!p.value && no) {
    try {
      p.value = await api.batchDetail(no);
    } catch (e) {
      handleElecError(e);
    }
  }
  loading.value = false;
  if (!p.value) return;
  const x = p.value;
  tab.value = x.toDelist ? "DELIST" : x.rowInvalid + x.rowWarn ? "PROBLEM" : x.toUpdate ? "UPDATE" : "INSERT";
  void load(true);
});

const pending = computed(() => p.value?.status === "PARSED");
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
const tabs = computed(() => {
  const x = p.value;
  if (!x) return [];
  const problems = { key: "PROBLEM", label: `有问题 ${qtyOf(x.rowInvalid + x.rowWarn)}` };
  // 只读的批次只有问题可看（数据不在内存里了）
  if (!pending.value) return [problems];
  return [
    { key: "INSERT", label: `新增 ${qtyOf(x.toInsert)}` },
    { key: "UPDATE", label: `更新 ${qtyOf(x.toUpdate)}` },
    { key: "DELIST", label: `下架 ${qtyOf(x.toDelist)}` },
    { key: "UNCHANGED", label: `未变 ${qtyOf(x.unchanged)}` },
    problems,
  ];
});
const hasProblems = computed(() => !!p.value && p.value.rowInvalid + p.value.rowWarn > 0);
const canApply = computed(() => pending.value && !!p.value && p.value.rowValid > 0 && !busy.value);
const minutesLeft = computed(() => {
  const d = p.value?.deadline;
  if (!d || !pending.value) return null;
  return Math.max(0, Math.ceil((new Date(d.replace(" ", "T")).getTime() - Date.now()) / 60000));
});
/** 只读批次的问题：详情里带的前 100 处 */
const storedIssues = computed<ElecIssue[]>(() => (pending.value ? [] : p.value?.issues ?? []));

async function load(reset: boolean) {
  const x = p.value;
  if (!x || !pending.value) {
    if (x && !pending.value) tab.value = "PROBLEM";
    return;
  }
  if (reset) {
    page.value = 1;
    rows.value = [];
  }
  try {
    const got = await api.batchRows(x.batchNo, tab.value, page.value, PAGE);
    rows.value = reset ? got : [...rows.value, ...got];
    more.value = got.length === PAGE;
  } catch (e) {
    handleElecError(e);
  }
}

function onTab(k: string) {
  tab.value = k as ElecPreviewKind;
  void load(true);
}

function loadMore() {
  page.value += 1;
  void load(false);
}

/** 更新的行：变了的那几个字段「旧 → 新」 */
function changes(r: ElecPreviewRow): string {
  const b = r.before ?? {};
  const out: string[] = [];
  if ("qty" in b) out.push(`数量 ${qtyOf(Number(b.qty))} → ${qtyOf(r.qty ?? 0)}`);
  if ("dateCode" in b) out.push(`批号 ${b.dateCode ?? "—"} → ${r.dateCode}`);
  if ("priceE6" in b) out.push("价格变了");
  const rest = Object.keys(b).filter((k) => !["qty", "dateCode", "priceE6"].includes(k)).length;
  if (rest) out.push(`另有 ${rest} 项`);
  return out.join(" · ");
}

async function confirmDelist(n: number): Promise<boolean> {
  return new Promise((resolve) => uni.showModal({
    title: `确认下架 ${qtyOf(n)} 行？`,
    content: "这些料号这次的表里没有。下架后买家就搜不到了",
    success: (r) => resolve(r.confirm),
    fail: () => resolve(false),
  }));
}

async function apply() {
  const x = p.value;
  if (!x || !canApply.value) return;
  if (x.toDelist && !(await confirmDelist(x.toDelist))) return;
  busy.value = true;
  try {
    const out = await api.applyBatch(x.batchNo, x.toDelist ? { expectDelist: x.toDelist } : undefined);
    setBatch(out);
    p.value = out;
    toast("已上架");
    setTimeout(() => backTo(ROUTES.supplier), 600);
  } catch (e) {
    if (codeOf(e) === E_DELIST_CONFIRM) {
      // 预览之后库存变了：重新取此刻的数，再问他一次
      p.value = await api.batchDetail(x.batchNo).catch(() => p.value);
      toast("库存刚有变动，下架行数已更新，请再确认一次");
      void load(true);
    } else {
      handleElecError(e);
    }
  } finally {
    busy.value = false;
  }
}

async function cancel() {
  const x = p.value;
  if (!x || busy.value) return;
  busy.value = true;
  try {
    await api.cancelBatch(x.batchNo);
    backTo(ROUTES.supplier);
  } catch (e) {
    handleElecError(e);
  } finally {
    busy.value = false;
  }
}

async function exportProblems() {
  const x = p.value;
  if (!x || busy.value) return;
  busy.value = true;
  try {
    const bytes = await api.batchProblems(x.batchNo);
    const base = (fileName.value || x.fileName || x.batchNo).replace(/\.[A-Za-z0-9]{1,5}$/, "");
    await openSheet(bytes, `${base}_问题行.xlsx`);
  } catch (e) {
    handleElecError(e);
  } finally {
    busy.value = false;
  }
}

/** 冷启动落在这一页、预览也取不到时栈里没有上一页，不能只 navigateBack */
function reupload() {
  uni.redirectTo({ url: ROUTES.stockUpload });
}
</script>

<template>
  <sh-scaffold title-key="title.stockPreview">
    <sh-empty v-if="!p && !loading" text="预览不见了" tip="重新选一次文件就好 —— 预览时库存一行都没动">
      <template #action>
        <view class="sh-btn sh-btn--sm" @tap="reupload">重新上传</view>
      </template>
    </sh-empty>
    <template v-else-if="p">
      <view class="sh-card">
        <sh-stat :items="stats"></sh-stat>
        <text class="txt-caption sh-muted block">
          {{ fileName || p.fileName }} · 共 {{ qtyOf(p.rowTotal) }} 行，能上架 {{ qtyOf(p.rowValid) }} 行 ·
          {{ p.mode === "REPLACE" ? "全量替换" : "增量" }} · {{ p.taxIncluded ? "含税" : "未税" }}
        </text>
        <text v-if="!pending" class="txt-caption sh-muted block">{{ BATCH_STATUS[p.status] }}</text>
        <text v-else-if="minutesLeft !== null" class="txt-caption sh-muted block">{{ minutesLeft }} 分钟内有效</text>
      </view>
      <view v-if="pending && p.rowValid === 0" class="sh-notice sh-notice--danger block">
        <text class="txt-sub">这张表一行能上架的都没有 —— 多半是列选错了，回上一页改</text>
      </view>

      <!-- 间距放在外层：调用点的 class 会落到组件根上（mergeVirtualHostAttributes），display 会被改掉 -->
      <view class="block">
        <sh-tabs :items="tabs" :active="tab" line @change="onTab"></sh-tabs>
      </view>

      <view v-if="!pending" class="sh-cells">
        <view v-for="(x, i) in storedIssues" :key="i" class="sh-cell">
          <text class="txt-caption" :class="x.level === 'ERROR' ? 'danger' : 'warn'">{{ issueText(x) }}</text>
        </view>
        <sh-empty v-if="!storedIssues.length" compact text="没有问题"></sh-empty>
      </view>
      <view v-else class="sh-cells">
        <view v-for="(r, i) in rows" :key="`${r.row}-${i}`" class="sh-cell">
          <view class="sh-row sh-row--between">
            <text class="txt-body sh-num">{{ r.mpn || "（空）" }}</text>
            <text v-if="r.kind === 'DELIST'" class="txt-caption danger">将下架</text>
            <text v-else-if="r.qty !== null && r.qty !== undefined" class="txt-caption sh-muted">{{ qtyOf(r.qty) }}</text>
          </view>
          <text v-if="r.mfr || r.dateCode" class="txt-caption sh-muted">
            {{ [r.mfr, r.dateCode].filter(Boolean).join(" · ") }}
          </text>
          <text v-if="r.kind === 'UPDATE' && r.before" class="txt-caption sh-muted block-line">{{ changes(r) }}</text>
          <text v-for="(x, j) in r.issues" :key="j" class="txt-caption block-line"
            :class="x.level === 'ERROR' ? 'danger' : 'warn'">
            {{ issueText(x) }}{{ x.level === "ERROR" ? "，这一行不上架" : "" }}
          </text>
        </view>
        <text v-if="more" class="sh-link more" @tap="loadMore">再看 {{ PAGE }} 行</text>
        <sh-empty v-if="!rows.length" compact text="这一类没有"></sh-empty>
      </view>

      <view v-if="hasProblems" class="export">
        <text class="sh-link" @tap="exportProblems">导出有问题的行（Excel）</text>
      </view>

      <sh-actionbar v-if="pending">
        <view class="sh-row acts">
          <view class="sh-btn sh-btn--muted grow" @tap="cancel">放弃</view>
          <view class="sh-btn grow" :class="{ 'is-disabled': !canApply }" @tap="apply">{{ busy ? "处理中…" : "确认上架" }}</view>
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
.block-line {
  display: block;
  margin-top: 6rpx;
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
.export {
  padding: 28rpx;
  text-align: center;
}
.acts {
  gap: 20rpx;
  width: 100%;
}
.grow {
  flex: 1;
}
</style>
