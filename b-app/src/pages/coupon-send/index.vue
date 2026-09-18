<script setup lang="ts">
/*
 * 发放 · 选人群（原型 s18）。单选列表与新建活动的玩法面板同一种写法：选中项变红、右侧打勾。
 *
 * 前四行是**预设人群**（全部会员 / 新客 / 熟客 / 沉睡会员），按会员分层现筛，不用先去会员页存人群 ——
 * 「发给沉睡会员」是最常见的一次发放。存下来的人群收在「我的人群」里。
 *
 * 底部写「最多支出」：人数 × 单张最大优惠。商家选的是一群人，要为之负责的是钱。
 * 发出去收不回来，所以点「发放」还要再确认一次，确认框里写清发给谁、多少人。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { confirm } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { MemberSegment, MemberStats, MerchantCoupon } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const couponNo = ref("");
const c = ref<MerchantCoupon | null>(null);
const stats = ref<MemberStats | null>(null);
const segments = ref<MemberSegment[]>([]);
const failed = ref(false);
const busy = ref(false);
const picked = ref("@ALL");
const showMine = ref(false);

async function load() {
  if (!couponNo.value) return;
  try {
    const [one, st, sg] = await Promise.all([api.mCoupon(couponNo.value), api.mMemberStats(), api.mMemberSegments()]);
    c.value = one;
    stats.value = st;
    segments.value = sg;
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

/** 预设人群。熟客 = 忠实客（LOYAL），与活动受众里「熟客」同一个口径 */
const presets = computed(() => {
  const s = stats.value;
  if (!s) return [];
  return [
    { key: "@ALL", label: tt("couponSend.preset.ALL"), n: s.newCount + s.regularCount + s.loyalCount + s.sleepingCount },
    { key: "@NEW", label: tt("couponSend.preset.NEW"), n: s.newCount },
    { key: "@LOYAL", label: tt("couponSend.preset.LOYAL"), n: s.loyalCount },
    { key: "@SLEEPING", label: tt("couponSend.preset.SLEEPING"), n: s.sleepingCount },
  ];
});

const pickedMine = computed(() => segments.value.find((s) => s.segmentNo === picked.value) ?? null);
const pickedCount = computed(() =>
  pickedMine.value ? pickedMine.value.lastCount : presets.value.find((p) => p.key === picked.value)?.n ?? 0);
const pickedLabel = computed(() =>
  pickedMine.value ? pickedMine.value.name : presets.value.find((p) => p.key === picked.value)?.label ?? "");

const left = computed(() => (c.value?.totalCount == null ? null : Math.max(0, c.value.totalCount - c.value.receivedCount)));

/** 单张最多优惠 × 次数：现金按面额，折扣按封顶，兑换 / 免运费不计 */
const perCoupon = computed(() => {
  const x = c.value;
  if (!x) return 0;
  const one = x.benefitMode === "CASH" ? x.benefitValue : x.benefitMode === "PERCENT" ? x.benefitCapMinor ?? 0 : 0;
  return one * (x.timesTotal || 1);
});
const maxSpend = computed(() => {
  const n = left.value == null ? pickedCount.value : Math.min(pickedCount.value, left.value);
  return n * perCoupon.value;
});

function pickMine(no: string) {
  picked.value = no;
  showMine.value = false;
}

async function send() {
  const x = c.value;
  if (!x || busy.value || !pickedCount.value) return;
  const ok = await confirm({
    title: tt("couponSend.confirmTitle", { name: pickedLabel.value }),
    hint: tt("couponSend.confirmBody", { n: pickedCount.value, title: x.title }),
  });
  if (!ok) return;
  busy.value = true;
  try {
    const r = await api.mIssueCoupon(x.couponNo, picked.value);
    uni.redirectTo({ url: `${ROUTES.couponIssues}?couponNo=${x.couponNo}&issueNo=${r.issueNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function back() {
  uni.navigateBack();
}

onLoad((q) => {
  couponNo.value = String(q?.couponNo ?? "");
  void load();
});
</script>

<template>
  <sh-scaffold title-key="couponSend.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <template v-if="c">
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponSend.coupon") }}</text>
          <text class="txt-body">{{ c.title }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponSend.left") }}</text>
          <text class="txt-body sh-num">{{ left == null ? $t("couponSend.unlimited") : $t("couponSend.leftN", { n: left }) }}</text>
        </view>
      </view>

      <text class="txt-caption sh-muted grp">{{ $t("couponSend.to") }}</text>
      <view class="sh-cells">
        <view v-for="p in presets" :key="p.key" class="sh-cell sh-row sh-row--between" @tap="picked = p.key">
          <text class="txt-body" :class="{ 'txt-primary': picked === p.key }">{{ p.label }}</text>
          <view class="sh-row">
            <text class="txt-body sh-muted sh-num">{{ p.n }}</text>
            <sh-icon v-if="picked === p.key" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
          </view>
        </view>
        <view class="sh-cell sh-row sh-row--between" @tap="showMine = true">
          <text class="txt-body" :class="{ 'txt-primary': !!pickedMine }">{{ pickedMine?.name || $t("couponSend.mine") }}</text>
          <view class="sh-row">
            <text class="txt-body sh-muted sh-num">{{ $t("couponSend.mineN", { n: segments.length }) }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
      </view>

      <view class="sh-row sh-row--between spend">
        <text class="txt-body sh-muted">{{ $t("couponSend.maxSpend") }}</text>
        <text class="txt-title sh-num">{{ money(maxSpend) }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--muted sh-fill" @tap="back">{{ $t("couponSend.cancel") }}</view>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': busy || !pickedCount }" @tap="send">
            {{ $t("couponSend.submit") }}
          </view>
        </view>
      </sh-actionbar>

      <sh-sheet :visible="showMine" :title="tt('couponSend.mine')" @close="showMine = false">
        <view class="sh-cells">
          <view v-for="s in segments" :key="s.segmentNo" class="sh-cell sh-row sh-row--between" @tap="pickMine(s.segmentNo)">
            <text class="txt-body" :class="{ 'txt-primary': picked === s.segmentNo }">{{ s.name }}</text>
            <view class="sh-row">
              <text class="txt-body sh-muted sh-num">{{ s.lastCount }}</text>
              <sh-icon v-if="picked === s.segmentNo" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
            </view>
          </view>
        </view>
        <sh-empty v-if="!segments.length" line :text="tt('couponSend.mineEmpty')"></sh-empty>
      </sh-sheet>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.grp {
  display: block;
  padding: 0 8rpx;
}
.spend {
  padding: 8rpx 8rpx 0;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
