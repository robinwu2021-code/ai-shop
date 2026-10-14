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
import type { CouponIssueBatch, MemberSegment, MemberTag, MerchantCoupon } from "@shared/types";
import { audienceLabel } from "@/shared/audience";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const couponNo = ref("");
const issueNo = ref("");
const list = ref<CouponIssueBatch[]>([]);
const coupons = ref<MerchantCoupon[]>([]);
const segments = ref<MemberSegment[]>([]);
const tags = ref<MemberTag[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    const [bs, cs, sg, tg] = await Promise.all([
      api.mCouponIssues(couponNo.value || undefined),
      api.mCoupons(true),
      api.mMemberSegments(),
      api.mMemberTags().catch(() => []),
    ]);
    list.value = bs;
    coupons.value = cs;
    segments.value = sg;
    tags.value = tg;
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

/**
 * 发给了谁。新批次带受众项（标签 / 分层 / 人群，取或），按名字拼；
 * 旧批次只有人群号或预设键 —— 只认人群号的话，按标签发的批次会显示成「全部会员」。
 */
function whom(b: CouponIssueBatch) {
  if (b.audiences?.length) {
    return audienceLabel(b.audiences, { tags: tags.value, segments: segments.value }, tt);
  }
  return segmentName(b.segmentNo);
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
        <text class="txt-sub sh-muted hero__sub">{{ $t("couponIssues.sentTo", { name: whom(one) }) }}</text>
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
          <text class="txt-strong">{{ whom(b) }}</text>
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
