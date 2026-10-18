<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { datetime, isoDate } from "@shared/utils/format";
import type { BuyerPointsAccount, BuyerPointsEntry } from "@/api/contract";

const accounts = ref<BuyerPointsAccount[]>([]);
const typeCode = ref("");
const records = ref<BuyerPointsEntry[]>([]);
const nextCursor = ref(0);
const hasMore = ref(false);
const moreLoading = ref(false);
const loaded = ref(false);
const failed = ref(false);
let requestNo = 0;

const account = computed(() => accounts.value.find((a) => a.typeCode === typeCode.value));
const tabs = computed(() => accounts.value.map((a) => ({
  key: a.typeCode, label: `${a.market} · ${a.typeCode}`,
})));

async function selectType(next: string) {
  typeCode.value = next;
  records.value = [];
  nextCursor.value = 0;
  hasMore.value = false;
  loaded.value = false;
  const current = ++requestNo;
  try {
    const page = await api.pointsEntries(next);
    if (current !== requestNo) return;
    records.value = page.rows;
    nextCursor.value = page.next;
    hasMore.value = page.hasMore;
    failed.value = false;
  } catch {
    if (current !== requestNo) return;
    failed.value = true;
  }
  loaded.value = true;
}

async function loadMore() {
  if (!typeCode.value || !hasMore.value || moreLoading.value) return;
  const current = requestNo;
  moreLoading.value = true;
  try {
    const page = await api.pointsEntries(typeCode.value, nextCursor.value);
    if (current !== requestNo) return;
    records.value = [...records.value, ...page.rows];
    nextCursor.value = page.next;
    hasMore.value = page.hasMore;
  } catch {
    if (current === requestNo) failed.value = true;
  } finally {
    moreLoading.value = false;
  }
}

async function load() {
  try {
    accounts.value = await api.pointsAccounts();
    const next = accounts.value.some((a) => a.typeCode === typeCode.value)
      ? typeCode.value : accounts.value[0]?.typeCode;
    if (next) await selectType(next);
    else {
      typeCode.value = "";
      records.value = [];
      loaded.value = true;
      failed.value = false;
    }
  } catch {
    loaded.value = true;
    failed.value = true;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold :title-key="'points.title'">
    <sh-tabs v-if="tabs.length > 1" :items="tabs" :active="typeCode" @change="selectType"></sh-tabs>
    <view v-if="account" class="sh-card hero">
      <text class="txt-caption hero__label">{{ $t("points.balance") }}</text>
      <text class="txt-hero hero__v sh-num">{{ account.balance }}</text>
      <text class="txt-caption hero__scope">{{ account.market }} · {{ account.typeCode }} · {{ account.currency }}</text>
      <text v-if="account.pendingBalance" class="txt-caption hero__pending txt-primary sh-num">
        {{ $t("points.pendingCount", { n: account.pendingBalance }) }}
      </text>
      <text v-if="account.heldBalance" class="txt-caption hero__pending txt-primary sh-num">
        {{ $t("points.held", { n: account.heldBalance }) }}
      </text>
      <text v-if="account.frozen" class="txt-caption hero__pending txt-primary">{{ $t("points.frozen") }}</text>
      <text v-if="account.status !== 'ACTIVE'" class="txt-caption hero__pending txt-primary">
        {{ $t("points.notActive") }}
      </text>
    </view>

    <view v-if="account" class="sh-card block rules">
      <text class="txt-caption txt-bold rules__title">{{ $t("points.rules") }}</text>
      <text v-if="account.ratePoints && account.rateMinor" class="txt-caption rules__t">
        {{ $t("points.ruleRateRaw", { n: account.ratePoints, m: account.rateMinor, c: account.currency }) }}
      </text>
      <text class="txt-caption rules__t">{{ $t("points.ruleCap", { n: account.deductRatioBp / 100 }) }}</text>
      <text v-if="account.idleDays" class="txt-caption rules__t">
        {{ $t("points.idleRule", { n: account.idleDays }) }}
      </text>
      <text v-if="account.expireAt" class="txt-caption rules__t">
        {{ $t("points.expiresAt", { d: isoDate(account.expireAt) }) }}
      </text>
    </view>

    <view class="sh-card block">
      <text class="txt-title">{{ $t("points.records") }}</text>
      <view v-for="r in records" :key="r.entryNo" class="rec sh-row">
        <view class="sh-fill">
          <text class="txt-sub rec__title">{{ $t(`points.bucket${r.bucket}`) }}</text>
          <text class="txt-caption rec__at sh-num">{{ datetime(r.at) }}</text>
        </view>
        <view class="rec__right">
          <text class="txt-body rec__v sh-num" :class="r.points > 0 ? 'txt-primary' : 'is-danger'">
            {{ r.points > 0 ? "+" : "" }}{{ r.points }}
          </text>
          <text class="txt-caption rec__bal sh-num">{{ $t("points.after", { n: r.balanceAfter }) }}</text>
        </view>
      </view>
      <sh-empty bare v-if="!records.length" :pending="!loaded" :failed="failed"
        @retry="load" :text='$t("points.empty")'></sh-empty>
      <view v-if="records.length && hasMore" class="more txt-caption" @tap="loadMore">
        {{ $t(moreLoading ? "points.loadingMore" : "points.loadMore") }}
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.hero { text-align: center; padding-top: 44rpx; padding-bottom: 36rpx; }
.hero__label, .hero__v, .hero__scope, .hero__pending { display: block; }
.hero__v, .hero__scope, .hero__pending { margin-top: 8rpx; }
.rules__title { display: block; margin-bottom: 16rpx; }
.rules__t { display: block; }
.rec { gap: 24rpx; margin-top: 28rpx; }
.rec__title { display: block; color: var(--sh-ink); }
.rec__at { display: block; margin-top: 4rpx; }
.rec__right { text-align: end; flex-shrink: 0; }
.rec__v, .rec__bal { display: block; }
.rec__bal { margin-top: 4rpx; }
.more { margin-top: 28rpx; text-align: center; color: var(--sh-primary-text); }
</style>
