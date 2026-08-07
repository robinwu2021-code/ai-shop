<script setup lang="ts">
// 选社区自提点：定位推荐 → 选社区 → 选自提点 → 绑定归属（同时确定团长）。
// 拒绝定位时降级为列表手动选，不阻塞。
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { useCommunityStore } from "@/stores/community";
import { useCartStore } from "@/stores/cart";
import { getLocation } from "@shared/ports/location";
import { distance } from "@shared/utils/format";
import type { Community, Pickup } from "@shared/types";

const { t } = useI18n();
const community = useCommunityStore();
const cart = useCartStore();
const expanded = ref("");
const locating = ref(false);

async function load() {
  locating.value = true;
  try {
    await getLocation(); // 一期只用于排序/埋点，mock 侧已按距离排好
    await community.loadNearby();
    expanded.value = community.list[0]?.communityNo ?? "";
  } finally {
    locating.value = false;
  }
}

async function choose(c: Community, p: Pickup) {
  const switching = community.bound && community.pickup?.pickupNo !== p.pickupNo;
  if (switching) {
    const ok = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: String(t("community.switchTitle")),
        content: String(t("community.switchTip")),
        success: (r) => resolve(!!r.confirm),
        fail: () => resolve(false),
      });
    });
    if (!ok) return;
  }
  await community.bind(c, p);
  if (switching) await cart.refreshOnCommunityChange();
  uni.showToast({ title: String(t("community.bound")), icon: "none" });
  setTimeout(() => uni.navigateBack(), 400);
}

onLoad(load);
</script>

<template>
  <sh-scaffold title-key="community.title">
    <text v-if="locating" class="hint">{{ $t("community.locating") }}</text>

    <view v-for="c in community.list" :key="c.communityNo" class="cm">
      <view
        class="cm__head"
        @tap="expanded = expanded === c.communityNo ? '' : c.communityNo"
      >
        <view class="cm__main">
          <text class="cm__name">{{ c.name }}</text>
          <text class="cm__addr">{{ c.address }}</text>
        </view>
        <text class="sh-chip">{{ distance(c.distance) }}</text>
      </view>

      <view v-if="expanded === c.communityNo" class="pk-list">
        <view
          v-for="p in c.pickups"
          :key="p.pickupNo"
          class="pk"
          :class="{ 'is-on': community.pickup?.pickupNo === p.pickupNo }"
          @tap="choose(c, p)"
        >
          <text class="pk__avatar">{{ p.hostAvatar }}</text>
          <view class="pk__main">
            <text class="pk__name">{{ p.name }}</text>
            <text class="pk__sub">
              {{ p.hostName }} · {{ p.openHours }} · {{ p.arrivalDesc }}
            </text>
          </view>
          <text class="pk__dist sh-num">{{ distance(p.distance) }}</text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.hint {
  display: block;
  text-align: center;
  color: var(--sh-sub);
  font-size: 26rpx;
  padding: 28rpx 0;
}
.cm {
  background: var(--sh-surface);
  border-radius: 32rpx;
  padding: 28rpx;
  margin-bottom: 20rpx;
}
.cm__head {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.cm__main {
  flex: 1;
  min-width: 0;
}
.cm__name {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  letter-spacing: -0.3rpx;
  color: var(--sh-ink);
}
.cm__addr {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 8rpx;
}
/* 自提点：每个是独立浅色块，靠间距分隔，无分隔线 */
.pk-list {
  margin-top: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.pk {
  display: flex;
  align-items: center;
  gap: 20rpx;
  background: var(--sh-faint);
  border-radius: 28rpx;
  padding: 24rpx;
}
.pk.is-on {
  background: var(--sh-primary-tint);
}
.pk__avatar {
  width: 72rpx;
  height: 72rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  text-align: center;
  line-height: 72rpx;
  font-size: 36rpx;
  flex-shrink: 0;
}
.pk__main {
  flex: 1;
  min-width: 0;
}
.pk__name {
  display: block;
  font-size: 28rpx;
  color: var(--sh-ink);
  font-weight: 600;
}
.pk__sub {
  display: block;
  font-size: 22rpx;
  color: var(--sh-sub);
  margin-top: 6rpx;
}
.pk__dist {
  font-size: 22rpx;
  color: var(--sh-sub);
  flex-shrink: 0;
}
</style>
