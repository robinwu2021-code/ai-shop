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
import type { MyStoreCoupon, UserCoupon } from "@shared/types";

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
/** 我领到的那些（`/mp/coupon/mine`）。**不是领券中心** —— 见 load() */
const mineCoupons = ref<UserCoupon[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    /*
     * **走「我的券」，不走领券中心**（TDD-C端我的券接真接口）。
     *
     * 此前这儿是 `couponList().filter(c => c.received)` —— 领券中心回答的是
     * 「现在能领哪些」：活动一下架、被抢光、或者过了可领期，那张券就从返回里消失，
     * 而它**还在用户手里**。于是「我的券」会凭空少几张，
     * 「已使用 / 已过期」两栏更是基本恒空（领券中心不回答用没用过）。
     */
    const [mine, list] = await Promise.all([
      api.myStoreCoupons(),
      api.myCoupons().catch(() => [] as UserCoupon[]),
    ]);
    store.value = mine;
    /*
     * **按券号去重**（优惠券全链路梳理 批 1）：/mp/coupon/mine 现在也带上了新券表里
     * 下单可抵扣的那些，而它们已经在 myStoreCoupons 里了 —— 不去重同一张券会出现两次。
     */
    const seen = new Set(mine.map((c) => c.userCouponNo));
    mineCoupons.value = list.filter((u) => !seen.has(u.userCouponNo));
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

/**
 * 我领到的那一张。**哪一栏由服务端的 status 决定**，不靠 `endAt` 猜 ——
 * 用掉的券多半还没过期，按时间猜会把它放进「可用」。
 */
function mineRow(u: UserCoupon): Row {
  const c = u.coupon;
  const tabOf: Tab = u.status === "USED" ? "used" : u.status === "EXPIRED" ? "expired" : "usable";
  const rule = c.type === "DISCOUNT"
    ? tt("coupon.rate", { n: (c.discountRate / 1000).toFixed(1).replace(/\.0$/, "") })
    : money(c.faceMinor);
  let state = tt("coupon.state.usable");
  if (tabOf === "used") state = tt("coupon.state.used");
  else if (tabOf === "expired") state = tt("coupon.state.expired");
  else if (c.endAt - Date.now() < 7 * DAY) {
    state = tt("coupon.state.expireIn", { n: Math.max(1, Math.ceil((c.endAt - Date.now()) / DAY)) });
  }
  return {
    key: u.userCouponNo, title: c.title, tab: tabOf, state, live: tabOf === "usable",
    // 券的具体信息就在这一行：适用范围 · 门槛（或面额） · 到什么时候
    meta: [
      c.scopeDesc || tt("coupon.scopeAll"),
      c.thresholdMinor ? tt("coupon.threshold", { p: money(c.thresholdMinor) }) : rule,
      tt("coupon.until", { d: md(c.endAt) }),
    ].join(" · "),
  };
}

const rows = computed(() => [...store.value.map(storeRow), ...mineCoupons.value.map(mineRow)]);
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
