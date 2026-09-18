<script setup lang="ts">
/*
 * 我的券（原型 s24）。与 B 端券列表同一种卡：名称 + 状态 / 一行信息。
 * 买家那边的「状态」就是**还能不能用、还能用多久**：「7 天后过期」「剩 3 次」「可用」。
 *
 * 领券不在这一页：在商品详情的「领券」那一行（s36）—— 买家是在看货的时候才想起领券，
 * 专门跑到券中心去领是店家的想象。
 *
 * 到店出示的券（次卡等）点进去是出示码（s25）；下单抵扣的券不用点，结算时自动减。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { ROUTES } from "@shared/utils/constants";
import { money } from "@shared/utils/format";
import type { Coupon, MyStoreCoupon } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));

type Tab = "usable" | "used" | "expired";
const TABS = [
  { key: "usable", label: tt("coupon.tab.usable") },
  { key: "used", label: tt("coupon.tab.used") },
  { key: "expired", label: tt("coupon.tab.expired") },
] as const;
const tab = ref<Tab>("usable");

const store = ref<MyStoreCoupon[]>([]);
const center = ref<Coupon[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    const [mine, list] = await Promise.all([
      api.myStoreCoupons(),
      // 领券中心那一套（平台券 / 老的店铺券）：领过的也算我的券。取不到不拖垮整页
      api.couponList().catch(() => [] as Coupon[]),
    ]);
    store.value = mine;
    center.value = list.filter((c) => c.received);
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

const DAY = 86_400_000;

function md(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

interface Row {
  key: string;
  title: string;
  tab: Tab;
  state: string;
  /** 状态字用主色（还能用）还是灰（用完 / 过期） */
  live: boolean;
  meta: string;
  /** 到店出示的券：点进去出示码 */
  userCouponNo?: string;
}

function storeRow(c: MyStoreCoupon): Row {
  const used = c.status === "USED" || c.remaining <= 0;
  const tabOf: Tab = c.usableNow ? "usable" : used ? "used" : "expired";
  let state = tt("coupon.state.usable");
  if (tabOf === "used") state = tt("coupon.state.used");
  else if (tabOf === "expired") state = tt("coupon.state.expired");
  else if (c.timesTotal > 1) state = tt("coupon.state.left", { n: c.remaining });
  else if (c.expireAt && c.expireAt - Date.now() < 7 * DAY) {
    state = tt("coupon.state.expireIn", { n: Math.max(1, Math.ceil((c.expireAt - Date.now()) / DAY)) });
  }
  const shop = c.merchantName || "";
  const meta = c.redeemMode === "STORE_CODE"
    ? [shop, tt("coupon.inStore"), tt("coupon.until", { d: md(c.expireAt) })].filter(Boolean).join(" · ")
    : [shop, c.scopeType && c.scopeType !== "ALL" ? tt("coupon.scopeSome") : tt("coupon.scopeAll")]
      .filter(Boolean).join(" · ");
  return {
    key: c.userCouponNo, title: c.title, tab: tabOf, state, live: tabOf === "usable", meta,
    userCouponNo: c.redeemMode === "STORE_CODE" && tabOf === "usable" ? c.userCouponNo : undefined,
  };
}

function centerRow(c: Coupon): Row {
  const live = c.endAt > Date.now();
  const rule = c.type === "DISCOUNT"
    ? tt("coupon.rate", { n: (c.discountRate / 1000).toFixed(1).replace(/\.0$/, "") })
    : money(c.faceMinor);
  return {
    key: c.couponNo, title: c.title, tab: live ? "usable" : "expired",
    state: live ? tt("coupon.state.usable") : tt("coupon.state.expired"), live,
    meta: [c.scopeDesc || tt("coupon.scopeAll"),
      c.thresholdMinor ? tt("coupon.threshold", { p: money(c.thresholdMinor) }) : rule].join(" · "),
  };
}

const rows = computed(() => [...store.value.map(storeRow), ...center.value.map(centerRow)]);
const shown = computed(() => rows.value.filter((r) => r.tab === tab.value));

function open(r: Row) {
  if (r.userCouponNo) uni.navigateTo({ url: `${ROUTES.couponCode}?userCouponNo=${r.userCouponNo}` });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="coupon.title" :failed="failed" @retry="load">
    <sh-tabs :items="TABS" :active="tab" @change="(k: string) => (tab = k as Tab)"></sh-tabs>

    <view v-for="r in shown" :key="r.key" class="sh-card card" @tap="open(r)">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ r.title }}</text>
        <text class="txt-caption" :class="r.live ? 'txt-primary' : 'sh-muted'">{{ r.state }}</text>
      </view>
      <text class="txt-sub sh-muted card__meta sh-num">{{ r.meta }}</text>
    </view>

    <sh-empty v-if="!shown.length" :pending="!loaded" :text="tt('coupon.empty')" :tip="tt('coupon.emptyTip')"></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.card__meta {
  display: block;
  margin-top: 8rpx;
}
</style>
