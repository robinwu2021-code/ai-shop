<script setup lang="ts">
// 我的套餐（B-11.13 · 增值包 P4）。
//
// 这一页要同时对三种人说清楚三件不同的事：
//   · FREE 的人 —— 「你现在有 1 家店的额度，升上去能有 3 家 + 跨店对比」，
//     并且**如果还没试用过，当场就能点开通**（意图最强的一刻不要让他去联系客服）
//   · 生效中的人 —— 「你买了什么、用了多少、什么时候到期」
//   · 掉下来的人 —— 「哪几家店被压成只读了」。这一条是整页里最要紧的：
//     只说「部分门店已停用」，他得自己一家家点开去找，而那几家店正在丢单
//
// ─────────────────────────────────────────────────────────────────────────────
// 两个容易做错的地方
// ─────────────────────────────────────────────────────────────────────────────
// ① **宽限期不是失效**。GRACE 档的门店、子账号、跨店数据一样都没少，
//    这时候显示「已失效」只会让他打客服电话确认自己的店还在不在。
//    文案是「即将到期，请尽快续费」，配警示色而不是危险色。
// ② **用量一律用后端给的数**（`storeUsed` / `staffUsed`）。
//    自己拿门店列表 `.length` 数会把停用的店算进去，而建店那道闸只数营业中的 ——
//    表现是「页面说 3/3 满了，实际还能建一家」，两边都觉得自己没错。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MerchantPlan } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const plan = ref<MerchantPlan | null>(null);
const failed = ref(false);
const trialing = ref(false);

/** 剩余天数。向上取整 —— 还剩 3 小时要显示「1 天」而不是「0 天」 */
const daysLeft = computed(() => {
  const at = plan.value?.expireAt;
  if (!at) return null;
  return Math.max(0, Math.ceil((at - Date.now()) / 86_400_000));
});

const statusTone = computed(() => {
  switch (plan.value?.status) {
    case "GRACE":
      // 警示而不是危险：他的能力一样都没少，只是要续费了
      return "sh-chip--warning";
    case "EXPIRED":
      return "sh-chip--danger";
    default:
      return "sh-chip--primary";
  }
});

async function load() {
  failed.value = false;
  try {
    plan.value = await api.mMyPlan();
  } catch {
    failed.value = true;
  }
}

/**
 * 开通试用。
 *
 * 二次确认里写明**天数与到期后会发生什么** —— 「试用到期不自动扣费，
 * 但超出额度的门店会转为只读」。不说后半句的话，试用结束那天他会以为系统坏了。
 */
async function startTrial() {
  const p = plan.value;
  if (!p?.trialTier || trialing.value) return;
  const tier = p.tiers.find((x) => x.planCode === p.trialTier);
  const ok = await confirm({ title: String(String(t("plan.trialConfirmTitle", { n: p.trialDays ?? 0 }))), hint: String(String(t("plan.trialConfirmBody", { name: tier?.name ?? p.trialTier }))), confirmText: String(String(t("plan.trialConfirmOk"))) });
  if (!ok) return;
  trialing.value = true;
  try {
    // 后端直接回新视图，不必再拉一次 —— 少一次请求，也少一次「点完了没反应」的空窗
    plan.value = await api.mStartTrial();
    uni.showToast({ title: String(t("plan.trialStarted")), icon: "none" });
  } catch {
    uni.showToast({ title: String(t("plan.trialFailed")), icon: "none" });
  } finally {
    trialing.value = false;
  }
}

/**
 * 升档 / 续费：一期**没有在线支付**，这里给的是「联系平台」。
 *
 * 刻意不做成一个假的「立即购买」按钮：点下去只弹一句「请联系客服」的按钮，
 * 比一开始就写明「联系平台开通」更让人觉得被耍了。
 */
function contact() {
  void confirm({
    title: String(t("plan.contactTitle")),
    hint: String(t("plan.contactBody")),
    alert: true,
  });
}

onShow(load);
</script>

<template>
  <!--
    只有老板看得到（`biz:store:admin`）：这一页答的是「主体买了什么」，
    与建店、挂收款号同属主体结构面。店长看到额度数字只会去催老板买单，
    而他不是做这个决定的人。后端那两条端点也挂同一个码。
  -->
  <sh-scaffold title-key="plan.title" :denied="!merchant.can('biz:store:admin')">
    <view v-if="failed" class="sh-card fail">
      <text class="txt-title">{{ $t("plan.failed") }}</text>
      <view class="sh-btn fail__go" @tap="load">{{ $t("common.retry") }}</view>
    </view>

    <template v-if="plan">
      <!-- 当前档位 -->
      <view class="sh-card cur">
        <view class="cur__head sh-row">
          <text class="txt-display">{{ plan.planName }}</text>
          <text class="sh-chip" :class="statusTone">{{ $t(`plan.status${plan.status}`) }}</text>
        </view>
        <text v-if="daysLeft !== null" class="sh-muted">
          {{ $t("plan.daysLeft", { n: daysLeft }) }}
        </text>
        <text v-else class="sh-muted">{{ $t("plan.noExpiry") }}</text>

        <!-- 用量两行。**分母是生效额度**（覆盖值优先），与建店那道闸同一个数 -->
        <view class="use">
          <view class="use__i">
            <text class="txt-display sh-num">{{ plan.storeUsed }}/{{ plan.storeQuota }}</text>
            <text class="sh-muted">{{ $t("plan.storeQuota") }}</text>
          </view>
          <view class="use__i">
            <text class="txt-display sh-num">{{ plan.staffUsed }}/{{ plan.staffQuota }}</text>
            <text class="sh-muted">{{ $t("plan.staffQuota") }}</text>
          </view>
          <view class="use__i">
            <sh-icon v-if="plan.crossStoreStats" name="check" :size="34" color="var(--sh-ink)"></sh-icon>
            <text v-else class="txt-display">—</text>
            <text class="sh-muted">{{ $t("plan.crossStore") }}</text>
          </view>
        </view>
      </view>

      <!--
        宽限期横幅。**能力全保留**这句话必须出现在这里 ——
        店主看到「即将到期」的第一反应是「我的店是不是已经关了」。
      -->
      <view v-if="plan.status === 'GRACE'" class="sh-notice sh-notice--warning banner">
        <text class="txt-bold">{{ $t("plan.graceTitle") }}</text>
        <text class="txt-caption">{{ $t("plan.graceBody") }}</text>
      </view>

      <!--
        降级横幅：**写明是哪几家店**。这是整页最要紧的一块 ——
        那几家店正在丢单，而它们在门店列表里与「店主自己停用的」长得一模一样。
      -->
      <view v-if="plan.suspendedStores.length" class="sh-notice sh-notice--danger banner">
        <text class="txt-bold">
          {{ $t("plan.suspendedTitle", { n: plan.suspendedStores.length }) }}
        </text>
        <text class="txt-caption">{{ plan.suspendedStores.join("、") }}</text>
        <text class="txt-caption">{{ $t("plan.suspendedBody") }}</text>
      </view>

      <!-- 三档对比 -->
      <text class="txt-title sec">{{ $t("plan.tiers") }}</text>
      <view v-for="tier in plan.tiers" :key="tier.planCode" class="sh-card tier">
        <view class="tier__head sh-row">
          <text class="txt-title">{{ tier.name }}</text>
          <text v-if="tier.current" class="sh-chip sh-chip--primary">{{ $t("plan.tierCurrent") }}</text>
        </view>
        <text class="sh-muted">
          {{ $t("plan.tierLine", { stores: tier.storeQuota, staff: tier.staffQuota }) }}
        </text>
        <text class="sh-muted">
          {{ tier.crossStoreStats ? $t("plan.tierCross") : $t("plan.tierNoCross") }}
        </text>
      </view>

      <!--
        试用按钮的显隐**只看 trialTier**，不自己用 planCode + trialUsed 推 ——
        那样会漏掉「平台把试用天数配成 0」这种情况（按钮在，点了报错）。
      -->
      <view v-if="plan.trialTier" class="sh-btn act" :class="{ 'is-busy': trialing }" @tap="startTrial">
        {{ $t("plan.trialBtn", { n: plan.trialDays ?? 0 }) }}
      </view>
      <text v-else-if="plan.trialUsed" class="sh-muted act__hint">{{ $t("plan.trialUsed") }}</text>

      <!--
        「升级 / 续费」的形态**跟着上面那颗试用按钮走**：
        有试用可点时它是次操作（tint 胶囊），没有时它就是这一页唯一的动作（实心）。
        此前这里写的是 `sh-btn--ghost` —— **库里没有这一档**，于是它无条件渲染成实心，
        与试用按钮并排时是两颗一模一样的主按钮。不报错，也没人会去查一个类名存不存在。
      -->
      <view class="sh-btn act" :class="{ 'sh-btn--soft': plan.trialTier }" @tap="contact">
        {{ $t("plan.contactBtn") }}
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.fail {
  text-align: center;
}
.fail__go {
  margin-top: 16rpx;
}
.cur__head {
  gap: 12rpx;
}
.use {
  display: flex;
  margin-top: 20rpx;
}
.use__i {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

/* 形态归 .sh-notice（tint 底与同名字色）；这里只留「两行竖排」 */
.banner {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}


/* 三档卡片之间要有缝。**此前一条外边距规则都没有** —— 三张 sh-card 上下相贴，
   看着像一整块被切了两刀，分不出「这是三个可比的档位」。用列表行基准 14rpx。 */
.tier + 
.tier__head {
  gap: 12rpx;
  margin-bottom: 8rpx;
}
/* 34rpx/600 = 字阶的标题档（同 .txt-title）。原先的 32rpx 不在字阶上：
   与 34 只差 1px，分不出层级，却让「调整全局标题字号」这类改动漏掉这一处 */


.act__hint {
  display: block;
  text-align: center;
}
.is-busy {
  opacity: 0.6;
}
</style>
