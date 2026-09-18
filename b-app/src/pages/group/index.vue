<script setup lang="ts">
/*
 * 团详情（原型 s10）。版式：大字倒计时 → 人头 → 信息列表 → 底部操作。
 * **团的操作只在这里**（散团、分享）；规则回到「活动」那一行去看。
 *
 * 散团 = 还在拼的团置为已散，参团已付款的单逐张全额退款。已成团的不能散：
 * 买家已经按团价付了款、在等货，要退只能逐单走售后。
 */
import { computed, onUnmounted, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { confirm } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { GroupBuy } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const groupNo = ref("");
const g = ref<GroupBuy | null>(null);
const failed = ref(false);
const busy = ref(false);
const now = ref(Date.now());
const tick = setInterval(() => { now.value = Date.now(); }, 1000);
onUnmounted(() => clearInterval(tick));

async function load() {
  if (!groupNo.value) return;
  try {
    g.value = await api.mGroup(groupNo.value);
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

const live = computed(() => !!g.value && (g.value.status === "OPEN" || g.value.status === "PENDING"));

const countdown = computed(() => {
  if (!g.value) return "";
  const s = Math.max(0, Math.floor((g.value.expireAt - now.value) / 1000));
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
});

/** 人头：已付款的成员 + 还空着的名额（「待加入」），总数 = 成团人数 */
const seats = computed(() => {
  if (!g.value) return [];
  const heads = g.value.members.map((m) => ({ k: (m.nickname || "·").slice(0, 1), empty: false }));
  const gap = Math.max(0, g.value.minCount - heads.length);
  return [...heads, ...Array.from({ length: gap }, () => ({ k: "", empty: true }))];
});

async function dissolve() {
  if (busy.value || !g.value) return;
  const ok = await confirm({
    title: String(t("group.dissolveTitle")),
    hint: String(t("group.dissolveBody", { n: g.value.joinedCount })),
    danger: true,
  });
  if (!ok) return;
  busy.value = true;
  try {
    g.value = await api.mDissolveGroup(g.value.groupNo);
    uni.showToast({ title: String(t("group.dissolved")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 分享：复制买家端团页的链接，商家发到群里 */
function share() {
  if (!g.value) return;
  uni.setClipboardData({
    data: `${String(t("group.shareText", { title: g.value.title, n: g.value.need }))} /c/#/pages/group/index?groupNo=${g.value.groupNo}`,
    success: () => uni.showToast({ title: String(t("group.copied")), icon: "none" }),
  });
}

function openActivity() {
  if (g.value?.activityNo) uni.navigateTo({ url: `${ROUTES.activityEdit}?activityNo=${g.value.activityNo}` });
}

onLoad((q) => {
  groupNo.value = String(q?.groupNo ?? "");
});
onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="group.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <template v-if="g">
      <view class="hero">
        <template v-if="live">
          <text class="txt-display sh-num">{{ countdown }}</text>
          <text class="txt-sub sh-muted hero__sub">{{ $t("group.leftNeed", { n: g.need }) }}</text>
        </template>
        <template v-else>
          <text class="txt-title">{{ $t(`groups.status.${g.status}`) }}</text>
          <text class="txt-sub sh-muted hero__sub sh-num">{{ $t("groups.joinedOf", { n: g.joinedCount, m: g.minCount }) }}</text>
        </template>
      </view>

      <view class="sh-row seats">
        <view v-for="(s, i) in seats" :key="i" class="seat" :class="{ 'seat--empty': s.empty }">
          <text v-if="!s.empty" class="txt-body">{{ s.k }}</text>
          <text v-else class="txt-caption sh-muted">{{ $t("group.seatEmpty") }}</text>
        </view>
      </view>

      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("group.goods") }}</text>
          <text class="txt-body">{{ g.title }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("group.price") }}</text>
          <text class="txt-body sh-num">{{ money(g.groupPrice) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("group.pickup") }}</text>
          <text class="txt-body">{{ g.pickupName || $t("group.anyPickup") }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between" @tap="openActivity">
          <text class="txt-body sh-muted">{{ $t("group.activity") }}</text>
          <view class="sh-row">
            <text class="txt-body">{{ g.activityName || "—" }}</text>
            <sh-icon v-if="g.activityNo" name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
      </view>

      <sh-actionbar v-if="live">
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--danger sh-fill" :class="{ 'is-disabled': busy }" @tap="dissolve">
            {{ $t("group.dissolve") }}
          </view>
          <view class="sh-btn bar__main" @tap="share">{{ $t("group.share") }}</view>
        </view>
      </sh-actionbar>
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
.seats {
  justify-content: center;
  gap: 16rpx;
  flex-wrap: wrap;
}
.seat {
  width: 88rpx;
  height: 88rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
  display: flex;
  align-items: center;
  justify-content: center;
}
.seat--empty {
  background: transparent;
  border: 2rpx dashed var(--sh-line);
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
