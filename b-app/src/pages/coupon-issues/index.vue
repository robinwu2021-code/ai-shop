<script setup lang="ts">
/*
 * 发放结果（原型 s16）与发放记录。
 *
 * 带 issueNo 进来 = 刚发完那一批：大字写「25 人 · 已发出」，跳过的人**按原因分行列出** ——
 * 不说原因，商家会以为少发了、再发一遍。底部「完成」回券详情。
 * 不带 = 这张券（或全部券）的发放记录，一批一张卡。
 */
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { CouponIssueBatch, MemberSegment, MerchantCoupon } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const couponNo = ref("");
const issueNo = ref("");
const list = ref<CouponIssueBatch[]>([]);
const coupons = ref<MerchantCoupon[]>([]);
const segments = ref<MemberSegment[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    const [bs, cs, sg] = await Promise.all([
      api.mCouponIssues(couponNo.value || undefined),
      api.mCoupons(true),
      api.mMemberSegments(),
    ]);
    list.value = bs;
    coupons.value = cs;
    segments.value = sg;
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

const one = computed(() => (issueNo.value ? list.value.find((b) => b.issueNo === issueNo.value) ?? null : null));

function couponTitle(no: string) {
  return coupons.value.find((c) => c.couponNo === no)?.title || no;
}

/** 人群名：预设键（@ALL 等）翻成字，存下来的人群查名字 */
function segmentName(no?: string | null) {
  if (!no || no === "@ALL") return tt("couponSend.preset.ALL");
  if (no.startsWith("@")) return tt(`couponSend.preset.${no.slice(1)}`);
  return segments.value.find((s) => s.segmentNo === no)?.name || no;
}

function stamp(ts: number) {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function done() {
  uni.navigateBack();
}

onLoad((q) => {
  couponNo.value = (q?.couponNo as string) ?? "";
  issueNo.value = (q?.issueNo as string) ?? "";
});
onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold
    :title-key="issueNo ? 'couponIssues.resultTitle' : 'couponIssues.title'"
    :denied="!merchant.can('biz:campaign')"
    :failed="failed"
    @retry="load"
  >
    <!-- 刚发完那一批 -->
    <template v-if="one">
      <view class="hero">
        <text class="txt-display sh-num">{{ $t("couponIssues.people", { n: one.issued }) }}</text>
        <text class="txt-sub sh-muted hero__sub">{{ $t("couponIssues.sentTo", { name: segmentName(one.segmentNo) }) }}</text>
      </view>

      <template v-if="one.skipped">
        <text class="txt-caption sh-muted grp">{{ $t("couponIssues.skippedN", { n: one.skipped }) }}</text>
        <view class="sh-cells">
          <view v-for="r in one.skipReasons" :key="r.reason" class="sh-cell sh-row sh-row--between">
            <text class="txt-body">{{ $t(`couponIssues.reason.${r.reason}`) }}</text>
            <text class="txt-body sh-num">{{ r.count }}</text>
          </view>
        </view>
      </template>

      <view class="sh-row sh-row--between spend">
        <text class="txt-body sh-muted">{{ $t("couponIssues.maxSpend") }}</text>
        <text class="txt-title sh-num">{{ money(one.amountMinor) }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-btn" @tap="done">{{ $t("couponIssues.done") }}</view>
      </sh-actionbar>
    </template>

    <!-- 发放记录 -->
    <template v-else>
      <view v-for="b in list" :key="b.issueNo" class="sh-card card">
        <view class="sh-row sh-row--between">
          <text class="txt-strong">{{ segmentName(b.segmentNo) }}</text>
          <text class="txt-caption sh-muted sh-num">{{ stamp(b.issuedAt) }}</text>
        </view>
        <text v-if="!couponNo" class="txt-sub sh-muted card__meta">{{ couponTitle(b.couponNo) }}</text>
        <text class="txt-caption sh-muted sh-num card__metric">
          {{ $t("couponIssues.line", { i: b.issued, s: b.skipped, a: money(b.amountMinor) }) }}
        </text>
      </view>
      <sh-empty v-if="!list.length" :pending="!loaded" :text="tt('couponIssues.empty')"></sh-empty>
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
.spend {
  padding: 8rpx 8rpx 0;
}
.card__meta {
  display: block;
  margin-top: 8rpx;
}
.card__metric {
  display: block;
  margin-top: 12rpx;
}
</style>
