<script setup lang="ts">
// 团详情：阶梯价、参团邻居、参团。
// 这页要讲清楚一件事 —— **人越多，所有人（含已参团的）都更便宜**。
// 这是与美团/拼多多最大的不同，所以阶梯表是页面主体，不是附属信息。
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { useCommunityStore } from "@/stores/community";
import { buildShareMessage } from "@shared/ports/share";
import { GOODS_COVER_FALLBACK, ROUTES } from "@shared/utils/constants";
import { countdown, money } from "@shared/utils/format";
import type { GroupBuy } from "@shared/types";

const { t } = useI18n();
const user = useUserStore();
const community = useCommunityStore();

const group = ref<GroupBuy | null>(null);
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;

const closed = computed(() => !!group.value && now.value > group.value.expireAt);
const off = computed(() =>
  group.value
    ? Math.round((1 - group.value.groupPrice / group.value.basePrice) * 100)
    : 0,
);

async function load(groupNo: string) {
  group.value = await api.groupBuyDetail(groupNo);
  uni.setNavigationBarTitle({ title: group.value.title });
}

async function join() {
  const g = group.value;
  if (!g || g.joined || closed.value) return;
  try {
    const res = await api.joinGroupBuy(g.groupNo, 1);
    group.value = res.group;
    // 把团推到新档位时，明确告诉用户「先买的邻居也退钱了」——
    // 这正是本方案区别于其它拼团的地方，不说出来用户感知不到
    uni.showToast({
      title: res.justReached
        ? String(t("group.upgraded", { p: money(res.refundPerMember) }))
        : String(t("group.joinedOk")),
      icon: "none",
      duration: 2600,
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function openGoods() {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${group.value?.goodsNo}` });
}

onLoad((q) => {
  const no = (q?.groupNo as string) || "";
  if (no) load(no);
  timer = setInterval(() => (now.value = Date.now()), 1000);
});

onUnmounted(() => clearInterval(timer));

// 分享文案自动带进度 —— 「还差 1 人到 85 折」比「快来拼团」有效得多
onShareAppMessage(() => {
  const g = group.value;
  const title = g && !g.reached
    ? String(t("group.shareNeed", { n: g.need, title: g.title }))
    : String(t("group.shareMax", { title: g?.title ?? "" }));
  return buildShareMessage({
    title,
    path: `${ROUTES.group}?groupNo=${g?.groupNo ?? ""}`,
    merchantNo: community.pickup?.hostMerchantNo,
    inviterNo: user.user?.cUserNo,
  });
});
</script>

<template>
  <sh-scaffold v-if="group">
    <!-- 头部：当前价 + 自提点 -->
    <view class="sh-card">
      <view class="head sh-row">
        <sh-cover class="head__cover sh-center" :src="group.cover || GOODS_COVER_FALLBACK" @tap="openGoods"></sh-cover>
        <view class="sh-fill">
          <text class="txt-title">{{ group.title }}</text>
          <text class="txt-caption head__pickup">📍 {{ group.pickupName }}</text>
        </view>
      </view>

      <view class="price">
        <text class="txt-hero sh-num">{{ money(group.groupPrice) }}</text>
        <text v-if="off > 0" class="txt-sub price__base sh-num">{{ money(group.basePrice) }}</text>
        <text v-if="off > 0" class="sh-chip sh-chip--danger sh-num">-{{ off }}%</text>
      </view>

      <view class="cd sh-row sh-row--between">
        <text class="txt-caption cd__label is-warning">{{ $t("group.cutoff") }}</text>
        <text class="txt-body cd__v sh-num is-warning">{{ countdown(group.expireAt - now) }}</text>
      </view>
    </view>

    <!-- 成团进度：单档，够人就成 -->
    <view class="sh-card block">
      <text class="txt-title">{{ $t("group.progress") }}</text>
      <text class="sh-muted tierhint">{{ $t("group.tierHint") }}</text>

      <view v-if="!group.reached" class="goal">
        <text class="txt-strong goal__text">{{ $t("group.needMore", { n: group.need }) }}</text>
      </view>
      <view v-else class="goal goal--max">
        <text class="txt-strong goal__text">{{ $t("group.done") }}</text>
      </view>
    </view>

    <!-- 参团邻居 -->
    <view class="sh-card block">
      <text class="txt-title">{{ $t("group.neighbours", { n: group.joinedCount }) }}</text>
      <view class="members sh-wrap">
        <view v-for="(m, i) in group.members" :key="i" class="member sh-row">
          <text class="txt-body">{{ m.avatar }}</text>
          <text class="txt-caption member__n">{{ m.nickname }}</text>
        </view>
      </view>
    </view>

    <!-- 不成团怎么办 —— 必须写清楚，这是用户敢下单的前提 -->
    <view class="sh-card block notice">
      <text class="txt-caption">{{ $t("group.fallback") }}</text>
    </view>

    <sh-actionbar :pad="180">
      <view
        class="sh-btn"
        :class="{ 'is-disabled': group.joined || closed }"
        @tap="join"
      >
        {{ closed ? $t("group.closed") : group.joined ? $t("group.joinedBtn") : $t("group.join") }}
      </view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.head {
  gap: 24rpx;
}
.head__cover {
  width: 130rpx;
  height: 130rpx;
  border-radius: 32rpx;
  background: var(--sh-primary-tint);
  font-size: 64rpx;
  flex-shrink: 0;
}

.head__pickup {
  display: block;
  margin-top: 8rpx;
}
.price {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  margin-top: 28rpx;
}

.price__base {
  text-decoration: line-through;
}
.cd {
  margin-top: 24rpx;
  background: var(--sh-warning-tint);
  border-radius: 24rpx;
  padding: 20rpx 26rpx;
}
.block {
  margin-top: 20rpx;
}
.tierhint {
  display: block;
  margin-top: 8rpx;
}
.goal {
  margin-top: 24rpx;
  background: var(--sh-primary-tint);
  border-radius: 24rpx;
  padding: 22rpx 26rpx;
}
.goal--max {
  background: var(--sh-success-tint);
}
.goal__text {
  color: var(--sh-primary-text);
}
.goal--max .goal__text {
  color: var(--sh-success);
}
.members {
  gap: 16rpx;
  margin-top: 24rpx;
}
.member {
  gap: 8rpx;
  background: var(--sh-faint);
  border-radius: 9999px;
  padding: 12rpx 24rpx;
}

.member__n {
  color: var(--sh-ink);
}

.notice {
  background: var(--sh-faint);
}
</style>
