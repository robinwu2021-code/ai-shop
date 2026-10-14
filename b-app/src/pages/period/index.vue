<script setup lang="ts">
/*
 * 社区集单 · 一期（原型 s20 收单中 / s33 未达起订量）。
 *
 * 收单中：倒计时 + 三个数 + 按商品 / 按自提点的份数；底部「提前截单 / 去采购」。
 * 未达起订：大字写「32 / 50 份」，一句处理时限；底部「取消本期 / 照常发货」——
 *   起订量是商家的保护线，没到量时让商家自己决定，超时默认取消，免得买家等一场空。
 * 已成：底部只剩「去采购」。
 *
 * 份数只算已付款且未退的（后端现算），与订单明细逐行对得上。
 */
import { computed, onUnmounted, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { confirm } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { BatchPeriodDetail } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const periodNo = ref("");
const d = ref<BatchPeriodDetail | null>(null);
const failed = ref(false);
const busy = ref(false);
const now = ref(Date.now());
const tick = setInterval(() => { now.value = Date.now(); }, 1000);
onUnmounted(() => clearInterval(tick));

async function load() {
  if (!periodNo.value) return;
  try {
    d.value = await api.mPeriod(periodNo.value);
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

const p = computed(() => d.value?.period ?? null);
const open = computed(() => p.value?.status === "OPEN" && p.value.cutoffAt > now.value);

const countdown = computed(() => {
  if (!p.value) return "";
  const s = Math.max(0, Math.floor((p.value.cutoffAt - now.value) / 1000));
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
});

function hhmm(ms: number): string {
  const x = new Date(ms);
  return `${String(x.getHours()).padStart(2, "0")}:${String(x.getMinutes()).padStart(2, "0")}`;
}

function mdhm(ms: number): string {
  const x = new Date(ms);
  return `${String(x.getMonth() + 1).padStart(2, "0")}-${String(x.getDate()).padStart(2, "0")} ${hhmm(ms)}`;
}

async function cutoff() {
  if (busy.value || !p.value) return;
  const ok = await confirm({ title: String(t("period.cutoffTitle")), hint: String(t("period.cutoffBody")) });
  if (!ok) return;
  await act(() => api.mCutoffPeriod(p.value!.periodNo));
}

async function decide(action: "CANCEL" | "PROCEED") {
  if (busy.value || !p.value) return;
  if (action === "CANCEL") {
    const ok = await confirm({
      title: String(t("period.cancelTitle")),
      hint: String(t("period.cancelBody", { n: p.value.customers })),
      danger: true,
    });
    if (!ok) return;
  }
  await act(() => api.mDecidePeriod(p.value!.periodNo, action));
}

async function act(fn: () => Promise<unknown>) {
  busy.value = true;
  try {
    await fn();
    uni.showToast({ title: String(t("period.done")), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 去采购：进货单按这一期的 SKU 汇总预填 */
function purchase() {
  if (!p.value) return;
  uni.navigateTo({ url: `${ROUTES.purchaseEdit}?periodNo=${p.value.periodNo}` });
}

onLoad((q) => {
  periodNo.value = String(q?.periodNo ?? "");
});
onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold
    title-key="periods.title"
    :denied="!merchant.can('biz:campaign')"
    :failed="failed"
    @retry="load"
  >
    <template v-if="p && d">
      <!-- 大字：收单中是倒计时；未达起订是「份数 / 起订」；其余是截单时刻 -->
      <view class="hero">
        <text class="txt-caption sh-muted sh-num">{{ p.periodDate }} · {{ p.activityName }}</text>
        <template v-if="p.status === 'SHORT'">
          <text class="txt-display sh-num">{{ $t("period.shortHead", { q: p.qty, m: p.minQty ?? 0 }) }}</text>
          <text class="txt-sub sh-muted hero__sub">{{ $t("period.shortSub") }}</text>
        </template>
        <template v-else-if="open">
          <text class="txt-display sh-num">{{ countdown }}</text>
          <text class="txt-sub sh-muted hero__sub">
            {{ $t("period.countdown") }} · {{ $t("period.pickupAt", { d: p.pickupDate.slice(5), t: p.pickupFrom || "" }) }}
          </text>
        </template>
        <template v-else>
          <text class="txt-title sh-num">{{ $t("period.cutAt", { t: mdhm(p.cutoffAt) }) }}</text>
          <text class="txt-sub sh-muted hero__sub">{{ $t("period.pickupAt", { d: p.pickupDate.slice(5), t: p.pickupFrom || "" }) }}</text>
        </template>
      </view>

      <view class="sh-card">
        <sh-stat :items="[
          { value: p.qty, label: String($t('period.qty')) },
          { value: p.customers, label: String($t('period.customers')) },
          { value: money(p.amountMinor), label: String($t('period.amount')) },
        ]"></sh-stat>
      </view>

      <view v-if="p.status === 'SHORT' && p.decideDeadline" class="sh-notice sh-notice--warning">
        <text class="txt-caption">{{ $t("period.shortNote", { t: mdhm(p.decideDeadline) }) }}</text>
      </view>

      <template v-if="d.byGoods.length">
        <text class="txt-caption sh-muted grp">{{ $t("period.byGoods") }}</text>
        <view class="sh-cells">
          <view v-for="g in d.byGoods" :key="g.goodsNo" class="sh-cell sh-row sh-row--between">
            <text class="txt-body">{{ g.title }}</text>
            <text class="txt-body sh-num">{{ $t("period.pieces", { n: g.qty }) }}</text>
          </view>
        </view>
      </template>

      <template v-if="d.byPickup.length">
        <text class="txt-caption sh-muted grp">{{ $t("period.byPickup") }}</text>
        <view class="sh-cells">
          <view v-for="x in d.byPickup" :key="x.pickupNo || '-'" class="sh-cell sh-row sh-row--between">
            <text class="txt-body">{{ x.pickupName || x.pickupNo || "—" }}</text>
            <text class="txt-body sh-num">{{ $t("period.pieces", { n: x.qty }) }}</text>
          </view>
        </view>
      </template>

      <sh-actionbar v-if="p.status !== 'CANCELLED'">
        <view class="sh-row bar">
          <template v-if="p.status === 'SHORT'">
            <view class="sh-btn sh-btn--danger sh-fill" :class="{ 'is-disabled': busy }" @tap="decide('CANCEL')">
              {{ $t("period.cancelPeriod") }}
            </view>
            <view class="sh-btn bar__main" :class="{ 'is-disabled': busy }" @tap="decide('PROCEED')">
              {{ $t("period.proceed") }}
            </view>
          </template>
          <template v-else-if="p.status === 'OPEN'">
            <view class="sh-btn sh-btn--muted sh-fill" :class="{ 'is-disabled': busy }" @tap="cutoff">
              {{ $t("period.cutoffNow") }}
            </view>
            <view class="sh-btn bar__main" @tap="purchase">{{ $t("period.purchase") }}</view>
          </template>
          <view v-else class="sh-btn sh-fill" @tap="purchase">{{ $t("period.purchase") }}</view>
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16rpx 0 8rpx;
}
.hero__sub {
  margin-top: 8rpx;
}
.grp {
  display: block;
  padding: 0 8rpx;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
