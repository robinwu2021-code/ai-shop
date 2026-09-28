<script setup lang="ts">
/*
 * 发放 · 选人（原型 m17，由 s18 改版）。
 *
 * 「发给」一行打开选人面板（与活动、发消息同一块）：分层、标签、人群、来源多选，取或。
 * 此前是四个预设层加「我的人群」—— 选不到标签，而「给爱吃辣的发一张券」恰恰是小店最常见的一次发放。
 *
 * 选完当场算两个数：命中与收得到。**最多支出按收得到算**，按钮写实际张数 ——
 * 按命中算的话，线索会员那几张永远发不出去，却让支出看起来比真实的大。
 * 从会员名单「对这批人」、人群详情「发给他们」过来时，受众已经预选好。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { confirm } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { takePendingAudience } from "@/shared/audience";
import { money } from "@shared/utils/money";
import type { AudienceItem, AudiencePreview, MerchantCoupon } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const couponNo = ref("");
const c = ref<MerchantCoupon | null>(null);
const failed = ref(false);
const busy = ref(false);
const showPicker = ref(false);
/** 默认发给全部会员 —— 与此前「@ALL」选中是同一个默认 */
const items = ref<AudienceItem[]>([{ type: "ALL", value: "*" }]);
const label = ref("");
const preview = ref<AudiencePreview | null>(null);

async function load() {
  if (!couponNo.value) return;
  try {
    c.value = await api.mCoupon(couponNo.value);
    failed.value = false;
    await recount();
  } catch {
    failed.value = true;
  }
}

async function recount() {
  preview.value = await api.mAudiencePreview({ audiences: items.value }).catch(() => null);
}

function onPick(next: AudienceItem[], text: string) {
  items.value = next;
  label.value = text;
  showPicker.value = false;
  void recount();
}

const reachable = computed(() => preview.value?.reachable ?? 0);
const left = computed(() => (c.value?.totalCount == null ? null : Math.max(0, c.value.totalCount - c.value.receivedCount)));

/** 单张最多优惠 × 次数：现金按面额，折扣按封顶，兑换 / 免运费不计 */
const perCoupon = computed(() => {
  const x = c.value;
  if (!x) return 0;
  const one = x.benefitMode === "CASH" ? x.benefitValue : x.benefitMode === "PERCENT" ? x.benefitCapMinor ?? 0 : 0;
  return one * (x.timesTotal || 1);
});
/** 实际能发出的张数：收得到的人与剩余张数取小 */
const willIssue = computed(() => (left.value == null ? reachable.value : Math.min(reachable.value, left.value)));
const maxSpend = computed(() => willIssue.value * perCoupon.value);

async function send() {
  const x = c.value;
  if (!x || busy.value || !willIssue.value) return;
  const ok = await confirm({
    title: tt("couponSend.confirmTitle", { name: label.value }),
    hint: tt("couponSend.confirmBody", { n: willIssue.value, title: x.title }),
  });
  if (!ok) return;
  busy.value = true;
  try {
    const r = await api.mIssueCoupon(x.couponNo, null, items.value);
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
  label.value = tt("audience.allMembers");
  const pending = takePendingAudience();
  if (pending) {
    items.value = pending.items;
    label.value = pending.label;
  }
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
        <view class="sh-cell sh-row sh-row--between" @tap="showPicker = true">
          <text class="txt-body sh-muted">{{ $t("couponSend.audience") }}</text>
          <view class="sh-row">
            <text class="txt-body">{{ label }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponSend.matched") }}</text>
          <text class="txt-body sh-num">{{ preview?.matched ?? "…" }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponSend.reachable") }}</text>
          <text class="txt-body sh-num">{{ preview?.reachable ?? "…" }}</text>
        </view>
      </view>

      <view class="sh-row sh-row--between spend">
        <text class="txt-body sh-muted">{{ $t("couponSend.maxSpend") }}</text>
        <text class="txt-title sh-num">{{ money(maxSpend) }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--muted sh-fill" @tap="back">{{ $t("couponSend.cancel") }}</view>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': busy || !willIssue }" @tap="send">
            {{ $t("couponSend.submitN", { n: willIssue }) }}
          </view>
        </view>
      </sh-actionbar>

      <biz-audience-picker
        :visible="showPicker"
        :model-value="items"
        @close="showPicker = false"
        @confirm="onPick"
      ></biz-audience-picker>
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
