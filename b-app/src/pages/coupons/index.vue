<script setup lang="ts">
// 商家自己的券（P4）。
//
// 这一页只回答三个问题：**这张券会花多少钱、发出去多少、还能不能发**。
// 不做数据图表 —— 小店老板要的是「这张券我还敢不敢再发一批」，不是转化漏斗。
//
// ⚠️ 「最大敞口」那一行是这一页最重要的数字：商家填「200 张 × 5 元」时
// 心里想的是「发 200 张」，不是「最多赔一千」。把它显示出来，
// 比在他发完之后再解释便宜得多。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { MemberSegment, MerchantCoupon } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const list = ref<MerchantCoupon[]>([]);
const segments = ref<MemberSegment[]>([]);
const includeEnded = ref(false);
const busy = ref(false);

const active = computed(() => list.value.filter((c) => c.status === "ACTIVE"));

async function load() {
  const [cs, sg] = await Promise.all([
    api.mCoupons(includeEnded.value).catch(() => []),
    api.mMemberSegments().catch(() => []),
  ]);
  list.value = cs;
  segments.value = sg;
}

async function run(fn: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 权益一句话：折扣券要把「打几折 + 最多减多少」一起说，只说折扣是不完整的 */
function benefitText(c: MerchantCoupon) {
  if (c.benefitMode === "PERCENT") {
    const zhe = (c.benefitValue / 1000).toFixed(1);
    return t("coupons.benefitPercent", { z: zhe, cap: money(c.benefitCapMinor ?? 0) });
  }
  if (c.benefitMode === "GIFT") return t("coupons.benefitGift");
  if (c.benefitMode === "FREE_SHIP") return t("coupons.benefitFreeShip");
  return c.minAmountMinor
    ? t("coupons.benefitCashWithMin", { n: money(c.benefitValue), m: money(c.minAmountMinor) })
    : t("coupons.benefitCash", { n: money(c.benefitValue) });
}

function go(url: string) {
  uni.navigateTo({ url });
}

function toggleStatus(c: MerchantCoupon) {
  const next = c.status === "ACTIVE" ? "PAUSED" : "ACTIVE";
  run(() => api.mSetCouponStatus(c.couponNo, next));
}

/**
 * 发券。**先选人群，再确认一次** —— 发出去的券收不回来。
 * 确认框里要把「发给哪一群、多少人」说清楚，只写「确定发放吗」等于没说。
 */
async function issue(c: MerchantCoupon) {
  if (!segments.value.length) {
    uni.showToast({ title: t("coupons.noSegment"), icon: "none" });
    return;
  }
  const pick = await new Promise<number>((resolve) => {
    uni.showActionSheet({
      itemList: segments.value.map((s) => `${s.name}（${s.lastCount}）`),
      success: (r) => resolve(r.tapIndex),
      fail: () => resolve(-1),
    });
  });
  if (pick < 0) return;
  const seg = segments.value[pick]!;

  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: t("coupons.issueTitle", { name: seg.name }),
      content: t("coupons.issueBody", { n: seg.lastCount, title: c.title }),
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    });
  });
  if (!ok) return;

  try {
    const res = await api.mIssueCoupon(c.couponNo, seg.segmentNo);
    await load();
    // 结果页而不是一句 toast：跳过了多少、为什么跳过，一句话装不下
    go(`/pages/coupon-issues/index?couponNo=${c.couponNo}&issueNo=${res.issueNo}`);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="coupons.title" :denied="!merchant.can('biz:campaign')">
    <view class="bar">
      <text class="sh-chip sh-chip--primary" @tap="go('/pages/coupon-edit/index')">
        ＋ {{ $t("coupons.new") }}
      </text>
      <text class="sh-chip" @tap="go('/pages/coupon-issues/index')">
        {{ $t("coupons.issues") }}
      </text>
      <text
        class="sh-chip"
        :class="{ 'sh-chip--primary': includeEnded }"
        @tap="includeEnded = !includeEnded; load()"
      >
        {{ $t("coupons.showEnded") }}
      </text>
    </view>

    <sh-empty v-if="!list.length" :text="String($t('coupons.empty'))"></sh-empty>

    <view v-for="c in list" :key="c.couponNo" class="sh-card item">
      <view class="item__head">
        <text class="item__name">{{ c.title }}</text>
        <text v-if="c.status !== 'ACTIVE'" class="sh-chip">
          {{ $t(`coupons.status.${c.status}`) }}
        </text>
      </view>
      <text class="benefit">{{ benefitText(c) }}</text>

      <view class="nums">
        <text class="sh-muted sh-num">
          {{ $t("coupons.issued", { n: c.receivedCount, m: c.totalCount ?? "—" }) }}
        </text>
        <!-- 敞口：他填的是张数，看到的必须是钱 -->
        <text v-if="c.maxExposureMinor != null" class="sh-muted sh-num">
          {{ $t("coupons.exposure", { n: money(c.maxExposureMinor) }) }}
        </text>
      </view>
      <text v-if="c.validityMode === 'RELATIVE'" class="sh-muted valid">
        {{ $t("coupons.validRelative", { n: c.validDays }) }}
      </text>
      <text v-if="c.redeemMode === 'STORE_CODE'" class="sh-muted valid">
        {{ $t("coupons.storeCode") }}
      </text>

      <view class="acts">
        <text v-if="c.status === 'ACTIVE'" class="sh-link" @tap="issue(c)">
          {{ $t("coupons.issue") }}
        </text>
        <text class="sh-link" @tap="go(`/pages/coupon-edit/index?couponNo=${c.couponNo}`)">
          {{ $t("coupons.edit") }}
        </text>
        <text v-if="c.status !== 'ENDED'" class="sh-link" @tap="toggleStatus(c)">
          {{ c.status === "ACTIVE" ? $t("coupons.pause") : $t("coupons.resume") }}
        </text>
      </view>
    </view>

    <text v-if="active.length" class="tip">{{ $t("coupons.pauseHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 12rpx;
}
.item {
  margin-bottom: 16rpx;
}
.item__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.item__name {
  font-size: 30rpx;
  font-weight: 600;
}
.benefit {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.nums {
  display: flex;
  gap: 24rpx;
  margin-top: 8rpx;
  font-size: 24rpx;
}
.valid {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
}
.acts {
  display: flex;
  gap: 24rpx;
  margin-top: 16rpx;
}
.tip {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
