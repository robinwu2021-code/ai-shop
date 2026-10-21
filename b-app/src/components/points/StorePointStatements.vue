<script setup lang="ts">
// 门店积分账单：财务人员查看当前及历史修订，确认账单或提出异议。
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { confirm as confirmDialog } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { api } from "@/api";
import type { StorePointStatement, StorePointStatementChange, StorePointStatementLinePage } from "@shared/types";
const props = defineProps<{ typeCode: string; typeName?: string; storeNo: string; targetStatement?: string }>();
const merchant = useMerchantStore();
const { t } = useI18n();
const rows = ref<StorePointStatement[]>([]);
const failed = ref(false);
const busy = ref(false);
const loading = ref(false);
const hasMore = ref(false);
const next = ref(0);
const reasons = ref<Record<string, string>>({});
const details = ref<Record<string, StorePointStatementLinePage & { revision: number; loading: boolean; failed: boolean }>>({});
const pending = new Map<string, StorePointStatementChange>();
let epoch = 0;
function active(token: number) {
  return token === epoch && merchant.storeNo === props.storeNo && merchant.can("biz:finance");
}
async function load(more = false) {
  const token = epoch;
  if (!props.typeCode || !props.storeNo || !active(token)) return;
  loading.value = true;
  try {
    const page = props.targetStatement
      ? { rows: [await api.mStorePointStatement(props.typeCode, props.targetStatement)], next: 0, hasMore: false }
      : await api.mStorePointStatements(props.typeCode, more ? next.value : 0, 20);
    if (!active(token)) return;
    rows.value = more ? [...rows.value, ...page.rows] : page.rows;
    next.value = page.next;
    hasMore.value = page.hasMore;
    failed.value = false;
  } catch { if (active(token)) failed.value = true; }
  finally { if (token === epoch) loading.value = false; }
}
watch(() => [props.typeCode, props.storeNo, props.targetStatement], () => {
  epoch++; rows.value = []; reasons.value = {}; details.value = {}; pending.clear(); next.value = 0;
  hasMore.value = false; failed.value = false; loading.value = false;
  void load();
}, { immediate: true });
async function decide(row: StorePointStatement, dispute: boolean) {
  const token = epoch;
  if (busy.value || !active(token) || row.storeNo !== props.storeNo || row.typeCode !== props.typeCode || row.state !== "DRAFT") return;
  const reason = dispute ? (reasons.value[row.statementNo] ?? "").trim() : String(t("points.statementChecked"));
  if (!reason) return;
  busy.value = true;
  try {
    if (!await confirmDialog({ title: String(t("points.statements")), hint: String(t(dispute ? "points.statementDisputeHint" : "points.statementConfirmHint")) })) return;
    if (!active(token)) return;
    const key = `${row.statementNo}:${row.revision}:${dispute}:${reason}`;
    let request = pending.get(key);
    if (!request) {
      request = { requestId: `B-ST-${Date.now()}-${Math.random().toString(36).slice(2)}`, typeCode: row.typeCode,
        statementNo: row.statementNo, revision: row.revision, reason };
      pending.set(key, request);
    }
    if (dispute) await api.mDisputeStorePointStatement(request);
    else await api.mConfirmStorePointStatement(request);
    if (active(token)) { pending.delete(key); await load(); }
  } catch { if (active(token)) failed.value = true; }
  finally { busy.value = false; }
}
async function showLines(row: StorePointStatement, revision: number, more = false) {
  const token = epoch;
  if (!active(token)) return;
  const previous = details.value[row.statementNo];
  if (previous?.loading) return;
  const cursor = more && previous?.revision === revision ? previous.next : 0;
  details.value[row.statementNo] = { rows: more ? previous?.rows ?? [] : [], next: cursor,
    hasMore: false, revision, loading: true, failed: false };
  try {
    const page = await api.mStorePointStatementLines(row.typeCode, row.statementNo, revision, cursor, 20);
    if (!active(token)) return;
    const current = details.value[row.statementNo];
    if (!current || current.revision !== revision) return;
    details.value[row.statementNo] = { ...page, rows: more ? [...current.rows, ...page.rows] : page.rows,
      revision, loading: false, failed: false };
  } catch {
    const current = details.value[row.statementNo];
    if (active(token) && current) current.failed = true;
  } finally {
    const current = details.value[row.statementNo];
    if (active(token) && current) current.loading = false;
  }
}
const stateLabels: Record<string, string> = {
  BUILDING: "points.statementStateBuilding",
  DRAFT: "points.statementStateDraft",
  DISPUTED: "points.statementStateDisputed",
  CONFIRMED: "points.statementStateConfirmed",
  PAYING: "points.statementStatePaying",
  PAID: "points.statementStatePaid",
  CLEARED: "points.statementStateCleared",
};
function stateLabel(state: string) {
  return t(Object.hasOwn(stateLabels, state) ? stateLabels[state]! : "points.statementStateUnknown");
}
function date(at: number) { return new Date(at).toLocaleDateString(); }
</script>
<template>
  <view class="sh-card sh-mt-sm">
    <view class="txt-title">{{ t("points.statements") }}</view>
    <view v-if="typeName" class="sh-muted sh-mt-xs">{{ typeName }}</view>
    <view v-if="failed" class="sh-muted sh-mt-sm">
      <text>{{ t("points.statementFailed") }}</text>
      <view class="sh-btn sh-btn--muted" @click="load()">{{ t("points.statementRefresh") }}</view>
    </view>
    <view v-if="!loading && !failed && !rows.length" class="sh-muted sh-mt-xs">{{ t("points.noStatements") }}</view>
    <view v-for="row in rows" :key="row.statementNo" class="sh-mt-sm">
      <text>{{ row.statementNo }} · {{ date(row.fromMillis) }} — {{ date(row.toMillis) }}</text>
      <view>{{ t("points.statementRevision") }} {{ row.revision }} · {{ row.points }} {{ t("points.pointsUnit") }} · {{ stateLabel(row.state) }}</view>
      <view v-for="revision in row.revisions" :key="revision.number" class="sh-muted">
        {{ t("points.statementRevision") }} {{ revision.number }} · {{ revision.points }}
      </view>
      <view data-test="details" class="sh-btn sh-btn--muted" @click="showLines(row, row.revision)">{{ t("points.statementDetails") }}</view>
      <view v-for="revision in row.revisions" :key="`detail-${revision.number}`" class="sh-muted" @click="showLines(row, revision.number)">
        {{ t("points.statementDetails") }} · {{ t("points.statementRevision") }} {{ revision.number }}
      </view>
      <view v-if="details[row.statementNo]">
        <text>{{ t("points.statementRevision") }} {{ details[row.statementNo]?.revision }}</text>
        <view v-for="(line, index) in details[row.statementNo]?.rows" :key="index">
          {{ t("points.statementSourceOrder") }} {{ line.orderNo || line.subOrderNo }} · {{ line.points }} {{ t("points.pointsUnit") }}
        </view>
        <view v-if="details[row.statementNo]?.failed">{{ t("points.statementFailed") }}</view>
        <view v-if="details[row.statementNo]?.hasMore && !details[row.statementNo]?.loading" class="sh-btn sh-btn--muted"
          @click="showLines(row, details[row.statementNo]?.revision ?? row.revision, true)">{{ t("points.statementMore") }}</view>
      </view>
      <view v-if="row.disputeReason">{{ row.disputeReason }}</view>
      <template v-if="row.state === 'DRAFT'">
        <textarea v-model="reasons[row.statementNo]" :placeholder="t('points.statementDisputeReason')" maxlength="500" />
        <view data-test="confirm" class="sh-btn" :class="{ 'is-disabled': busy }" @click="decide(row, false)">{{ t("points.statementConfirm") }}</view>
        <view data-test="dispute" class="sh-btn sh-btn--muted" :class="{ 'is-disabled': busy }" @click="decide(row, true)">{{ t("points.statementDispute") }}</view>
      </template>
    </view>
    <view v-if="hasMore && !loading" class="sh-btn sh-btn--muted" @click="load(true)">{{ t("points.statementMore") }}</view>
  </view>
</template>
