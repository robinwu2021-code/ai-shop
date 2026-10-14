<script setup lang="ts">
/*
 * 邀请有礼（TDD-C 端裂变与商家招募 §3.1）。
 *
 * **这一页补的是买家侧此前完全缺失的那一半**：后端从活动配置、分享归因、
 * 新用户注册落台账到首单回填全都通了，而买家没有任何地方看得到「分享能得券」——
 * 于是线上 `mkt_fission_invite` 一行都没有。
 *
 * 一屏三件事：这场活动给什么、我邀到了几个、一个分享按钮。没别的。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { ROUTES } from "@shared/utils/constants";
import { money } from "@shared/utils/format";
import { buildShareMessage, canNativeShare, withAttribution } from "@shared/ports/share";
import type { MyFission } from "@shared/types";

const { t } = useI18n();
const user = useUserStore();

const fission = ref<MyFission | null>(null);
const failed = ref(false);
const nativeShare = canNativeShare();

onLoad(() => {
  void load();
});

async function load() {
  try {
    fission.value = await api.myFission();
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

/** 奖励怎么说：「满 60 减 8」这种带门槛的要连门槛一起说，否则他到结账才发现用不了 */
const rewardText = computed(() => {
  const f = fission.value;
  if (!f) return "";
  if (f.thresholdMinor > 0) {
    return String(t("invite.rewardCut", { m: money(f.thresholdMinor), n: money(f.faceMinor) }));
  }
  return String(t("invite.rewardAny", { n: money(f.faceMinor) }));
});

/**
 * 分享路径。**带 `inviterNo`** —— 没有它，被邀请人注册时后端拿不到邀请人，
 * 台账那一行就不会写（`AuthServiceImpl` 注册分支调 `fissionPort.onRegister`）。
 */
const sharePath = computed(() =>
  withAttribution(ROUTES.home, { title: "", path: ROUTES.home, inviterNo: user.user?.cUserNo }));

onShareAppMessage(() =>
  buildShareMessage({
    title: String(t("invite.shareTitle", { n: rewardText.value })),
    path: ROUTES.home,
    inviterNo: user.user?.cUserNo,
  }));

/**
 * H5 上没有转发，给复制链接（§3.2 B2）。
 * 小程序上这颗按钮是 `open-type="share"`，不走这里。
 */
function copyLink() {
  uni.setClipboardData({
    data: sharePath.value,
    success: () => uni.showToast({ title: t("invite.copied"), icon: "none" }),
  });
}
</script>

<template>
  <sh-scaffold title-key="invite.title" :failed="failed" @retry="load">
    <!--
      没有在跑的活动：**整页只说一句话**，不摆一个点不动的分享按钮。
      入口那一侧也不会把人带到这里来（「我的」页在没活动时整条不显示）。
    -->
    <view v-if="!fission" class="sh-card block">
      <text class="txt-body sh-muted">{{ $t("invite.none") }}</text>
    </view>

    <template v-else>
      <!-- ① 这场活动给什么 -->
      <view class="sh-card block">
        <text class="txt-title">{{ fission.name }}</text>
        <text class="txt-body inv__reward">{{ rewardText }}</text>
        <text class="txt-sub sh-muted inv__rule">
          {{ $t("invite.rule", { a: fission.inviteeCount, b: fission.inviterCount }) }}
        </text>
      </view>

      <!-- ② 我邀到了几个。**两个数并列** —— 奖励是按首单发的，
           只给「已邀请」会让人问「我邀了 3 个怎么只得 1 张」 -->
      <view class="sh-card sh-row inv__stats">
        <view class="sh-fill sh-center inv__stat">
          <text class="txt-hero sh-num">{{ fission.myInvited }}</text>
          <text class="txt-caption sh-muted">{{ $t("invite.invited") }}</text>
        </view>
        <view class="sh-fill sh-center inv__stat">
          <text class="txt-hero sh-num">{{ fission.myConverted }}</text>
          <text class="txt-caption sh-muted">{{ $t("invite.converted") }}</text>
        </view>
      </view>

      <!-- ③ 分享。小程序走转发，H5 走复制链接 —— 两端都要有出口 -->
      <view class="sh-card block">
        <view v-if="nativeShare" class="sh-btn inv__act">
          {{ $t("invite.share") }}
          <button class="inv__share" open-type="share"></button>
        </view>
        <view v-else class="sh-btn inv__act" @tap="copyLink">
          {{ $t("invite.copy") }}
        </view>
        <text class="txt-caption sh-muted inv__tip">{{ $t("invite.tip") }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.inv__reward {
  display: block;
  margin-top: 12rpx;
}
.inv__rule {
  display: block;
  margin-top: 8rpx;
}
.inv__stats {
  gap: 16rpx;
}
.inv__stat {
  flex-direction: column;
}
/* 分享按钮：小程序那颗 open-type 的原生按钮盖在整块上，自己不占视觉 */
.inv__act {
  position: relative;
}
.inv__share {
  position: absolute;
  inset: 0;
  opacity: 0;
}
.inv__tip {
  display: block;
  margin-top: 16rpx;
}
</style>
