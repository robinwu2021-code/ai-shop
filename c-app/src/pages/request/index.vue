<script setup lang="ts">
// 求团需求详情：需求 → 邻居 +1 → 商家报价对比 → 发起人选定。
//
// 这页的核心是**报价对比**。C 端发起需求后会有多个商家来报，
// 发起人要能一眼看出「谁便宜、起订量够不够、报价里含不含安装」。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { useCommunityStore } from "@/stores/community";
import { buildShareMessage } from "@shared/ports/share";
import { ROUTES } from "@shared/utils/constants";
import { isoDate, money } from "@shared/utils/format";
import type { GroupRequest, Quote } from "@shared/types";

const { t } = useI18n();
const user = useUserStore();
const community = useCommunityStore();

const request = ref<GroupRequest | null>(null);

/** 只有发起人能选定报价 */
const isInitiator = computed(
  () => !!request.value && request.value.initiatorNickname === user.user?.nickname,
);

async function load(requestNo: string) {
  request.value = await api.requestDetail(requestNo);
  uni.setNavigationBarTitle({ title: request.value.title });
}

async function toggle() {
  const r = request.value;
  if (!r) return;
  request.value = await api.toggleInterest(r.requestNo);
}

async function choose(q: Quote) {
  const r = request.value;
  if (!r || !isInitiator.value) return;
  try {
    request.value = await api.chooseQuote(r.requestNo, q.quoteNo);
    uni.showToast({ title: String(t("request.chosen")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 这家有没有涨过价？返回上一次的报价（比现价低才算涨价），没涨过返回 null。
 * 只公示「涨价」不公示「降价」—— 降价对买家没有风险，没必要给商家添堵。
 */
function lastRaise(q: Quote): number | null {
  const prev = q.revisions[q.revisions.length - 1];
  if (!prev || prev.priceMinor >= q.priceMinor) return null;
  return prev.priceMinor;
}

async function confirm() {
  const r = request.value;
  if (!r || r.confirmed) return;
  try {
    request.value = await api.confirmRequest(r.requestNo);
    uni.showToast({ title: String(t("request.confirmedOk")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 该报价的起订量还差几人 */
function shortBy(q: Quote): number {
  return Math.max(0, q.minCount - (request.value?.interestedCount ?? 0));
}

onLoad((o) => {
  const no = (o?.requestNo as string) || "";
  if (no) load(no);
});

onShareAppMessage(() => {
  const r = request.value;
  return buildShareMessage({
    title: String(t("request.share", { title: r?.title ?? "", n: r?.interestedCount ?? 0 })),
    path: `${ROUTES.request}?requestNo=${r?.requestNo ?? ""}`,
    merchantNo: community.pickup?.hostMerchantNo,
    inviterNo: user.user?.cUserNo,
  });
});
</script>

<template>
  <sh-scaffold v-if="request">
    <!-- 需求本身 -->
    <view class="sh-card">
      <view class="head">
        <text class="head__avatar">{{ request.initiatorAvatar }}</text>
        <view class="head__main">
          <text class="sh-h2">{{ request.title }}</text>
          <text class="head__by">
            {{ $t("groups.startedBy", { name: request.initiatorNickname }) }} ·
            {{ request.pickupName }}
          </text>
        </view>
      </view>

      <text class="desc">{{ request.desc }}</text>

      <view v-if="request.images.length" class="imgs">
        <view v-for="(img, i) in request.images" :key="i" class="img">{{ img }}</view>
      </view>

      <view class="facts">
        <view class="fact">
          <text class="fact__k">{{ $t("request.expect") }}</text>
          <text class="fact__v sh-num">{{ request.expectQty }}</text>
        </view>
        <view v-if="request.budgetMinor" class="fact">
          <text class="fact__k">{{ $t("request.budget") }}</text>
          <text class="fact__v sh-num">{{ money(request.budgetMinor) }}</text>
        </view>
        <view class="fact">
          <text class="fact__k">{{ $t("request.deadline") }}</text>
          <text class="fact__v sh-num">{{ isoDate(request.expireAt) }}</text>
        </view>
      </view>
    </view>

    <!-- 意向邻居。这里必须说清楚「+1 不是下单」 -->
    <view class="sh-card block">
      <text class="sh-h2">{{ $t("request.neighbours", { n: request.interestedCount }) }}</text>
      <text class="sh-muted nothint">{{ $t("request.notOrder") }}</text>
      <view class="members">
        <view v-for="(n, i) in request.neighbours" :key="i" class="member">
          <text class="member__a">{{ n.avatar }}</text>
          <text class="member__n">{{ n.nickname }}</text>
        </view>
      </view>
    </view>

    <!-- 商家报价：按价格从低到高 -->
    <view class="sh-card block">
      <text class="sh-h2">{{ $t("request.quotes", { n: request.quotes.length }) }}</text>
      <text class="sh-muted nothint">{{ $t("request.quoteHint") }}</text>

      <view v-for="(q, i) in request.quotes" :key="q.quoteNo" class="quote" :class="{ 'is-chosen': q.chosen }">
        <view class="quote__head">
          <text class="quote__logo">{{ q.merchant.logo }}</text>
          <view class="quote__who">
            <view class="quote__name-row">
              <text class="quote__name">{{ q.merchant.name }}</text>
              <text v-if="i === 0 && request.quotes.length > 1" class="sh-chip sh-chip--primary tiny">
                {{ $t("request.lowest") }}
              </text>
              <text v-if="q.chosen" class="sh-chip sh-chip--primary tiny">
                {{ $t("request.chosenTag") }}
              </text>
              <!-- 改过价就公示：不审核，但谁涨价谁被看见 -->
              <text
                v-if="lastRaise(q) !== null"
                class="sh-chip sh-chip--warning tiny sh-num"
              >
                {{ $t("request.wasPriced", { p: money(lastRaise(q)!) }) }}
              </text>
              <text
                v-if="q.merchant.breachCount > 0"
                class="sh-chip sh-chip--danger tiny sh-num"
              >
                {{ $t("request.breach", { n: q.merchant.breachCount }) }}
              </text>
            </view>
            <sh-rating :value="q.merchant.rating" :size="22"></sh-rating>
          </view>
          <text class="quote__price sh-num">{{ money(q.priceMinor) }}</text>
        </view>

        <text class="quote__desc">{{ q.desc }}</text>

        <view class="quote__meta">
          <text class="sh-chip sh-num">{{ $t("request.minCount", { n: q.minCount }) }}</text>
          <text v-if="shortBy(q) > 0" class="sh-chip sh-chip--warning sh-num">
            {{ $t("request.shortBy", { n: shortBy(q) }) }}
          </text>
          <text v-else class="sh-chip sh-chip--primary">{{ $t("request.qualified") }}</text>
          <text class="sh-chip sh-num">{{ $t("request.validUntil", { d: isoDate(q.validUntil) }) }}</text>
          <text v-if="q.locked" class="sh-chip sh-chip--primary">{{ $t("request.locked") }}</text>
        </view>

        <view
          v-if="isInitiator && !q.chosen && request.status !== 'MATCHED'"
          class="sh-btn sh-btn--soft quote__btn"
          @tap="choose(q)"
        >
          {{ $t("request.choose") }}
        </view>
      </view>

      <sh-empty bare v-if="!request.quotes.length" :text='$t("request.noQuote")'></sh-empty>

      <!-- 防加价说明：机制要让用户看见才有用，藏起来等于没有 -->
      <view class="antihike">
        <text class="antihike__text">{{ $t("request.antiHike") }}</text>
      </view>
    </view>

    <!-- 已选定报价：+1 的邻居各自二次确认 -->
    <view v-if="request.status === 'MATCHED'" class="sh-card block matched">
      <text class="sh-h2">{{ $t("request.matchedTitle") }}</text>
      <text class="sh-muted nothint">{{ $t("request.matchedHint") }}</text>
      <view class="matched__row">
        <text class="matched__price sh-num">{{ money(request.lockedPriceMinor ?? 0) }}</text>
        <text class="sh-chip sh-chip--primary sh-num">
          {{ $t("request.confirmedCount", { n: request.confirmedCount ?? 0, t: request.interestedCount }) }}
        </text>
      </view>
    </view>

    <view class="actionbar">
      <view
        v-if="request.status === 'MATCHED'"
        class="sh-btn"
        :class="{ 'is-disabled': request.confirmed }"
        @tap="confirm"
      >
        {{ request.confirmed ? $t("request.confirmed") : $t("request.confirm") }}
      </view>
      <view
        v-else
        class="sh-btn"
        :class="{ 'sh-btn--soft': request.interested }"
        @tap="toggle"
      >
        {{ request.interested ? $t("request.joined") : $t("request.join") }}
      </view>
    </view>
    <view class="spacer" />
  </sh-scaffold>
</template>

<style scoped>
.head {
  display: flex;
  gap: 20rpx;
  align-items: center;
}
.head__avatar {
  width: 84rpx;
  height: 84rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  text-align: center;
  line-height: 84rpx;
  font-size: 40rpx;
  flex-shrink: 0;
}
.head__main {
  flex: 1;
  min-width: 0;
}
.head__by {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 8rpx;
}
.desc {
  display: block;
  font-size: 26rpx;
  color: var(--sh-ink);
  line-height: 1.65;
  margin-top: 24rpx;
}
.imgs {
  display: flex;
  gap: 12rpx;
  margin-top: 20rpx;
}
.img {
  width: 150rpx;
  height: 150rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48rpx;
}
.facts {
  margin-top: 24rpx;
}
.fact {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 0;
}
.fact__k {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.fact__v {
  font-size: 24rpx;
  color: var(--sh-ink);
}
.block {
  margin-top: 20rpx;
}
.nothint {
  display: block;
  margin-top: 10rpx;
  line-height: 1.6;
}
.members {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-top: 24rpx;
}
.member {
  display: flex;
  align-items: center;
  gap: 10rpx;
  background: var(--sh-faint);
  border-radius: 9999px;
  padding: 12rpx 24rpx;
}
.member__a {
  font-size: 26rpx;
}
.member__n {
  font-size: 24rpx;
  color: var(--sh-ink);
}
.quote {
  margin-top: 24rpx;
  background: var(--sh-faint);
  border-radius: 32rpx;
  padding: 26rpx;
}
.quote.is-chosen {
  background: var(--sh-primary-tint);
}
.quote__head {
  display: flex;
  align-items: center;
  gap: 18rpx;
}
.quote__logo {
  width: 68rpx;
  height: 68rpx;
  border-radius: 24rpx;
  background: var(--sh-surface);
  text-align: center;
  line-height: 68rpx;
  font-size: 34rpx;
  flex-shrink: 0;
}
.quote__who {
  flex: 1;
  min-width: 0;
}
/* 标签多的时候让它们换行，而不是把商家名挤成「邻…」——
   名字是这里最该看清的信息，标签可以下一行 */
.quote__name-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8rpx;
  margin-bottom: 8rpx;
}
.quote__name {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
  /* 名字整体优先占位，不参与压缩 */
  flex: 0 0 auto;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tiny {
  padding: 4rpx 14rpx;
  font-size: 24rpx;
  flex-shrink: 0;
}
.quote__price {
  font-size: 34rpx;
  font-weight: 700;
  color: var(--sh-ink);
  flex-shrink: 0;
}
.quote__desc {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
  margin-top: 18rpx;
}
.quote__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 18rpx;
}
.quote__btn {
  margin-top: 22rpx;
  padding-top: 20rpx;
  padding-bottom: 20rpx;
  font-size: 26rpx;
}
.antihike {
  margin-top: 24rpx;
  background: var(--sh-faint);
  border-radius: 24rpx;
  padding: 22rpx 26rpx;
}
.antihike__text {
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.7;
}
.matched__row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-top: 24rpx;
}
.matched__price {
  font-size: 48rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.is-disabled {
  opacity: 0.45;
}
.actionbar {
  position: fixed;
  inset-inline: 28rpx;
  bottom: calc(28rpx + env(safe-area-inset-bottom));
}
.spacer {
  height: 180rpx;
}
</style>
