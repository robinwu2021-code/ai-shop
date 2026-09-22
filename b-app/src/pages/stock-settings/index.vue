<script setup lang="ts">
// 库存设置 · 记库存的品类（原型 inv-managed-switch s02–s04，TDD-商品纳入进销存开关 §3）。
//
// 设置挂在主体上，各门店共用：同一件货在 A 店记、B 店不记，账就说不清。
// 一类一行开关，「{n} 件商品」告诉他拨这一下会动到多少东西；
// 「默认不记」让他知道那个关着的状态不是出错，是平台给服务类的默认值。
//
// 关掉一个品类走后端的三道判，界面只负责把结果说清楚：
//   BLOCKED       有在途单据 → 列出单据、什么都没改（s04）
//   NEEDS_CONFIRM 还有库存   → 列出实存、确认键写「不记库存」，确认后带 confirm 再来一次（s03）
//   DONE          直接改好
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { toggleInvCategory } from "@/utils/inv-category";
import { ruleNeedsParam, sellRuleText } from "@/shared/inv-mode";
import { ROUTES } from "@/shared/nav";
import { pick, prompt } from "@ai-shop/ui/prompt";
import type { InvCategorySetting, SellRule, SellRuleScope, SellRuleType, StockSyncState } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const rows = ref<InvCategorySetting[]>([]);
const loaded = ref(false);
const failed = ref(false);
/** 正在拨的那一类。**一次只拨一类** —— 两个请求并发时，后回来的会把先回来的界面状态盖掉 */
const busy = ref<string | null>(null);

async function load() {
  try {
    rows.value = await api.mInvCategorySettings();
    failed.value = false;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

async function toggle(row: InvCategorySetting) {
  if (busy.value) return;
  busy.value = row.categoryNo;
  try {
    if (await toggleInvCategory(t, row)) await load();
  } finally {
    busy.value = null;
  }
}

function meta(row: InvCategorySetting): string {
  return row.isDefault && !row.managed
    ? String(t("stockSettings.defaultOff", { n: row.goodsCount }))
    : String(t("stockSettings.goodsCount", { n: row.goodsCount }));
}

// ---------------------------------------------------------------- 线上库存同步（第二期，按当前门店）
//
// 进销存过账后，线上库存按本店规则自动更新。开之前必须做一次期初对齐（后端 70069 也会拦）。
// 改同步与规则要 biz:store:admin：打开同步、定线上放多少货是店主级的经营决定。

const storeNo = computed(() => merchant.storeNo);
const canAdmin = computed(() => merchant.can("biz:store:admin"));
const sync = ref<StockSyncState | null>(null);
const rules = ref<SellRule[]>([]);
const syncBusy = ref(false);

async function loadSync() {
  if (!storeNo.value) return;
  const [st, rs] = await Promise.all([
    api.mStockSync(storeNo.value).catch(() => null),
    api.mSellRules(storeNo.value).catch(() => [] as SellRule[]),
  ]);
  sync.value = st;
  rules.value = rs;
}

function goAlign() {
  uni.navigateTo({ url: ROUTES.stockAlign });
}

async function toggleSync() {
  const st = sync.value;
  if (!st || !storeNo.value || syncBusy.value || !canAdmin.value) return;
  if (!st.alignedAt) {
    uni.showToast({ title: String(t("stockSync.needAlign")), icon: "none" });
    return;
  }
  syncBusy.value = true;
  try {
    sync.value = await api.mSetStockSync(storeNo.value, !st.enabled);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    syncBusy.value = false;
  }
}

/** 某一级设过的规则；类目没设过（或设成跟随）返回 undefined */
function ruleOf(scope: SellRuleScope, ref: string): SellRule | undefined {
  const r = rules.value.find((x) => x.scopeType === scope && x.scopeRef === ref);
  return r && r.ruleType !== "INHERIT" ? r : undefined;
}

const defaultRuleText = computed(() => sellRuleText(t, ruleOf("STORE", storeNo.value ?? "")));

function categoryRuleText(categoryNo: string): string {
  const r = ruleOf("CATEGORY", categoryNo);
  return r ? sellRuleText(t, r) : String(t("stockSync.rule.INHERIT"));
}

/** 进销存管着的类目才有线上可售规则可言 */
const managedRows = computed(() => rows.value.filter((r) => r.managed));

const RULE_TYPES: SellRuleType[] = ["ALL", "RESERVE", "RATIO", "CAP", "MANUAL"];

async function editRule(scope: SellRuleScope, scopeRef?: string) {
  if (!storeNo.value || !canAdmin.value) return;
  const types: SellRuleType[] = scope === "STORE" ? RULE_TYPES : ["INHERIT", ...RULE_TYPES];
  const cur = scope === "STORE" ? ruleOf("STORE", storeNo.value) : ruleOf(scope, scopeRef ?? "");
  const i = await pick({
    title: String(t("stockSync.rules")),
    items: types.map((x) => String(t(`stockSync.ruleOpt.${x}`))),
    selected: Math.max(0, types.indexOf(cur?.ruleType ?? (scope === "STORE" ? "ALL" : "INHERIT"))),
  });
  const type = i === null ? undefined : types[i];
  if (!type) return;
  let param = 0;
  if (ruleNeedsParam(type)) {
    const v = await prompt({
      title: String(t(`stockSync.param.${type}`)),
      value: cur?.ruleType === type ? String(cur.param) : "",
      type: "number",
      maxlength: 5,
    });
    if (v === null) return;
    param = Math.max(0, Math.floor(Number(v.trim()) || 0));
  }
  try {
    await api.mSaveSellRule(storeNo.value, { scopeType: scope, scopeRef, ruleType: type, param });
    rules.value = await api.mSellRules(storeNo.value);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onShow(() => {
  // 同步那一段的标题右侧写当前店名（多店时）：要门店列表
  void merchant.ensureStores();
  void load();
  void loadSync();
});
</script>

<template>
  <sh-scaffold title-key="stockSettings.title" :denied="!merchant.can('biz:goods')"
    :failed="failed"
    @retry="load"
  >
    <view class="sh-card">
      <text class="txt-title">{{ $t("stockSettings.section") }}</text>
      <sh-empty v-if="!rows.length" :pending="!loaded" :text="String($t('stockSettings.empty'))"></sh-empty>
      <view
        v-for="row in rows"
        :key="row.categoryNo"
        class="sh-row sh-row--between line"
        @tap="toggle(row)"
      >
        <view class="name">
          <text class="txt-strong">{{ row.name }}</text>
          <text class="txt-caption sh-muted">{{ meta(row) }}</text>
        </view>
        <sh-switch :model-value="row.managed" :disabled="busy !== null"></sh-switch>
      </view>
    </view>
    <view class="notes">
      <text class="txt-caption sh-muted">{{ $t("stockSettings.sharedHint") }}</text>
      <text class="txt-caption sh-muted">{{ $t("stockSettings.goodsHint") }}</text>
    </view>

    <!-- 线上库存同步 · 本店。多店时店名写在标题右侧 -->
    <view v-if="sync" class="sh-card sh-mt-md">
      <sh-section :title="String($t('stockSync.section'))">
        <text v-if="merchant.multiStore" class="txt-caption sh-muted">{{ merchant.currentStore?.name }}</text>
      </sh-section>
      <view class="sh-row sh-row--between line">
        <text class="txt-body">{{ $t("stockSync.status") }}</text>
        <text class="txt-body" :class="{ 'sh-muted': sync.state !== 'SYNCING' }">{{ $t(`stockSync.state.${sync.state}`) }}</text>
      </view>
      <view class="sh-row sh-row--between line" @tap="goAlign">
        <text class="txt-body">{{ $t("stockSync.align") }}</text>
        <sh-go :text="sync.alignedAt ? String($t('stockAlign.done')) : ''"></sh-go>
      </view>
      <view class="sh-row sh-row--between line" @tap="toggleSync">
        <text class="txt-body">{{ $t("stockSync.switchLabel") }}</text>
        <sh-switch :model-value="sync.enabled" :disabled="syncBusy || !canAdmin || !sync.alignedAt"></sh-switch>
      </view>
      <text class="txt-caption sh-muted">{{ $t("stockSync.hint") }}</text>
    </view>

    <!-- 线上可售规则 · 本店：默认一行，进销存管着的类目各一行 -->
    <view v-if="sync" class="sh-card sh-mt-md">
      <sh-section :title="String($t('stockSync.rules'))"></sh-section>
      <view class="sh-row sh-row--between line" @tap="editRule('STORE')">
        <text class="txt-body">{{ $t("stockSync.ruleDefault") }}</text>
        <sh-go :text="defaultRuleText"></sh-go>
      </view>
      <view
        v-for="row in managedRows"
        :key="`rule-${row.categoryNo}`"
        class="sh-row sh-row--between line"
        @tap="editRule('CATEGORY', row.categoryNo)"
      >
        <text class="txt-body">{{ row.name }}</text>
        <sh-go :text="categoryRuleText(row.categoryNo)"></sh-go>
      </view>
      <text class="txt-caption sh-muted">{{ $t("stockSync.ruleHint") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.line {
  padding: 24rpx 0;
}
.name {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.notes {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  padding: 16rpx 8rpx 0;
}
</style>
