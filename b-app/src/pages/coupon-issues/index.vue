<script setup lang="ts">
// 发放结果与发放记录（P4）。
//
// **这一页存在的唯一理由是那句「跳过 12 个」**。
// 商家选了 37 个人、实发 25 张，如果只弹一句「发放成功」，
// 他会以为 37 个人都收到了 —— 直到某个顾客说没收到，而那时已经隔了几天，
// 谁也说不清是没发还是没送到。
//
// 所以三个数字要并排显示：命中、发出、跳过；跳过还要写明白分别是为什么。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { CouponIssueBatch, MemberSegment, MerchantCoupon } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const couponNo = ref("");
/** 刚发完那一批：从列表跳过来时高亮它 */
const highlight = ref("");
const list = ref<CouponIssueBatch[]>([]);
const coupons = ref<MerchantCoupon[]>([]);
const segments = ref<MemberSegment[]>([]);

const latest = computed(() => list.value.find((b) => b.issueNo === highlight.value));

async function load() {
  const [bs, cs, sg] = await Promise.all([
    api.mCouponIssues(couponNo.value || undefined).catch(() => []),
    api.mCoupons(true).catch(() => []),
    api.mMemberSegments().catch(() => []),
  ]);
  list.value = bs;
  coupons.value = cs;
  segments.value = sg;
}

function couponTitle(no: string) {
  return coupons.value.find((c) => c.couponNo === no)?.title || no;
}

function segmentName(no?: string | null) {
  if (!no) return t("couponIssues.allMembers");
  return segments.value.find((s) => s.segmentNo === no)?.name || no;
}

function stamp(ts: number) {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

onLoad((q) => {
  couponNo.value = (q?.couponNo as string) ?? "";
  highlight.value = (q?.issueNo as string) ?? "";
});
onShow(load);
</script>

<template>
  <sh-scaffold title-key="couponIssues.title" :denied="!merchant.can('biz:campaign')">
    <!-- 刚发完那一批：三个数字并排，跳过原因逐条列出 -->
    <view v-if="latest" class="sh-card fresh">
      <text class="fresh__t">{{ $t("couponIssues.done", { title: couponTitle(latest.couponNo) }) }}</text>
      <sh-stat
        :items="[
          { value: latest.planned, label: String($t('couponIssues.planned')) },
          { value: latest.issued, label: String($t('couponIssues.issued')), tone: 'ok' },
          { value: latest.skipped, label: String($t('couponIssues.skipped')),
            tone: latest.skipped > 0 ? 'warn' : undefined },
        ]"
      ></sh-stat>

      <view v-if="latest.skipReasons.length" class="reasons">
        <text v-for="r in latest.skipReasons" :key="r.reason" class="reason">
          {{ $t(`couponIssues.reason.${r.reason}`, { n: r.count }) }}
        </text>
      </view>
      <text class="sh-muted amount">
        {{ $t("couponIssues.amount", { n: money(latest.amountMinor) }) }}
      </text>
    </view>

    <sh-empty v-if="!list.length" :text="String($t('couponIssues.empty'))"></sh-empty>

    <view v-for="b in list" :key="b.issueNo" class="sh-card sh-mb-sm">
      <view class="item__head">
        <text class="item__name">{{ couponTitle(b.couponNo) }}</text>
        <text class="sh-muted">{{ stamp(b.issuedAt) }}</text>
      </view>
      <text class="sh-muted seg">
        {{ $t("couponIssues.toSegment", { name: segmentName(b.segmentNo) }) }}
      </text>
      <text class="sh-num nums">
        {{ $t("couponIssues.line", { i: b.issued, s: b.skipped, a: money(b.amountMinor) }) }}
      </text>
      <view v-if="b.skipReasons.length" class="reasons">
        <text v-for="r in b.skipReasons" :key="r.reason" class="reason">
          {{ $t(`couponIssues.reason.${r.reason}`, { n: r.count }) }}
        </text>
      </view>
    </view>

    <text v-if="list.length" class="tip">{{ $t("couponIssues.hint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.fresh {
  border: 2rpx solid var(--sh-primary);
  margin-bottom: 16rpx;
}
.fresh__t {
  font-size: 28rpx;
  font-weight: 600;
}
.reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.reason {
  font-size: 24rpx;
  color: var(--sh-sub);
  background: var(--sh-faint);
  border-radius: 16rpx;
  padding: 6rpx 12rpx;
}
.amount {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
}

.item__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.item__name {
  font-size: 28rpx;
  font-weight: 600;
}
.seg {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
}
.nums {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
}
.tip {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
