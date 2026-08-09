<script setup lang="ts">
import type { MasterData, MerchantApplyStatus, MerchantSubject } from "@shared/types";
// 我的：登录入口 + 归属信息 + 外观与语言。
// 列表项之间用间距分块，不用分隔线（扁平色块风格）。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { useCommunityStore } from "@/stores/community";
import { FEATURES, ROUTES } from "@shared/utils/constants";

const { t } = useI18n();
const user = useUserStore();
const community = useCommunityStore();
const themeVisible = ref(false);
const points = ref(0);
const unread = ref(0);

function gotoLogin() {
  uni.navigateTo({ url: ROUTES.login });
}
function gotoCommunity() {
  uni.navigateTo({ url: ROUTES.community });
}

function gotoPoints() {
  uni.navigateTo({ url: ROUTES.points });
}

function gotoMessages() {
  uni.navigateTo({ url: ROUTES.messages });
}

function gotoCoupons() {
  uni.navigateTo({ url: ROUTES.coupons });
}

function gotoCards() {
  uni.navigateTo({ url: ROUTES.cards });
}

function gotoOrders() {
  uni.navigateTo({ url: ROUTES.orders });
}

function gotoAddress() {
  uni.navigateTo({ url: ROUTES.address });
}

function gotoGroupHost() {
  uni.navigateTo({ url: ROUTES.groupHost });
}

function gotoGroups() {
  uni.navigateTo({ url: ROUTES.groups });
}

function gotoVisited() {
  uni.switchTab({ url: ROUTES.merchants });
}

async function applyMerchant() {
  merchantVisible.value = true;
  if (!master.value) master.value = await api.masterData().catch(() => null);
}

const merchantVisible = ref(false);
const mForm = ref({
  name: "",
  // 主体类型：个人 → 个体户 → 企业，门槛前低后高（ADR-002 §4）。
  // 默认「个人」—— 一期的目标是「入驻容易」，让摆摊的邻居也能开
  subject: "MICRO" as MerchantSubject,
  contactName: "",
  contactPhone: "",
  category: "",
  desc: "",
  /**
   * 行业。**决定能不能以小微主体进件**（微信白名单按行业给），
   * 也是 points_forced 的来源。此前这张表没有这个字段，
   * 于是所有从 C 端入驻的商家 industry 恒空 —— 进件时才发现主体选错了。
   */
  industry: "",
});

/** 主数据：行业与主体都从服务端取，微信放开白名单时不用发版 */
const master = ref<MasterData | null>(null);
const industries = computed(() => master.value?.industries ?? []);

/** 小微受行业白名单管控，其余主体不受。还没选行业时不禁用，免得看着像坏了 */
function subjectAllowed(sub: MerchantSubject) {
  const meta = master.value?.subjects.find((x) => x.subjectType === sub);
  if (!meta?.industryGated) return true;
  const ind = industries.value.find((i) => i.industry === mForm.value.industry);
  return !ind || ind.microAllowed;
}
const mValid = computed(
  () =>
    mForm.value.name.trim() &&
    mForm.value.contactName.trim() &&
    /^\d{11}$/.test(mForm.value.contactPhone.trim()) &&
    mForm.value.category.trim(),
);

/**
 * 入驻进度。**此前提交完这一行还写着「个体户/企业均可」** ——
 * 商家不知道审到哪一步，只能打电话问运营。
 */
const applyStatus = ref<MerchantApplyStatus | null>(null);
const applyStatusText = computed(() =>
  applyStatus.value
    ? String(t(`merchant.applyStatus.${applyStatus.value.status}`))
    : String(t("merchant.applyHint")),
);

async function submitMerchant() {
  if (!mValid.value) return;
  applyStatus.value = await api.merchantApply({ ...mForm.value });
  merchantVisible.value = false;
  uni.showToast({ title: String(t("merchant.applySubmitted")), icon: "none" });
}

onShow(() => {
  if (user.isLogin) {
    user.loadProfile();
    // 没申请过返回 null，不是错误 —— 失败也不该影响整页
    api.myMerchantApply().then((a) => (applyStatus.value = a)).catch(() => {});
  }
  if (FEATURES.points) api.pointAccount().then((a) => (points.value = a.balance));
  api.messageList().then((m) => (unread.value = m.filter((x) => !x.read).length));
});
</script>

<template>
  <sh-scaffold title-key="tab.me" tab="me">
    <view class="sh-card head" @tap="!user.isLogin && gotoLogin()">
      <text class="head__avatar">{{ user.user?.avatar || "🙂" }}</text>
      <view class="head__main">
        <text class="head__name">
          {{ user.isLogin ? user.user?.nickname : $t("me.login") }}
        </text>
        <text class="head__sub">
          {{ user.isLogin ? user.user?.phone : $t("me.loginHint") }}
        </text>
      </view>
    </view>

    <view class="cells">
      <view class="cell" @tap="gotoCommunity">
        <text class="cell__label">{{ $t("me.myCommunity") }}</text>
        <text class="cell__value">{{ community.pickup?.name || $t("me.unset") }}</text>
      </view>
      <view class="cell">
        <text class="cell__label">{{ $t("me.myStores") }}</text>
        <text class="cell__value">{{ community.hostName || "—" }}</text>
      </view>
    </view>

    <!-- 交易：订单、券、地址 —— 买东西相关的都在这一组 -->
    <view class="cells">
      <view class="cell" @tap="gotoMessages">
        <text class="cell__label">{{ $t("message.title") }}</text>
        <text class="cell__value sh-num">
          {{ unread ? $t("message.unread", { n: unread }) : $t("message.allRead") }}
        </text>
      </view>
      <view class="cell" @tap="gotoOrders">
        <text class="cell__label">{{ $t("orders.title") }}</text>
        <text class="cell__value">{{ $t("orders.entryHint") }}</text>
      </view>
      <view class="cell" @tap="gotoCoupons">
        <text class="cell__label">{{ $t("coupon.title") }}</text>
        <text class="cell__value">{{ $t("coupon.entryHint") }}</text>
      </view>
      <view v-if="FEATURES.cards" class="cell" @tap="gotoCards">
        <text class="cell__label">{{ $t("cards.title") }}</text>
        <text class="cell__value">{{ $t("cards.entryHint") }}</text>
      </view>
      <view class="cell" @tap="gotoAddress">
        <text class="cell__label">{{ $t("address.title") }}</text>
        <text class="cell__value">{{ $t("address.entryHint") }}</text>
      </view>
      <view v-if="FEATURES.points" class="cell" @tap="gotoPoints">
        <text class="cell__label">{{ $t("points.title") }}</text>
        <text class="cell__value sh-num">{{ $t("points.entryHint", { n: points }) }}</text>
      </view>
    </view>

    <!-- 邻里：团、买过的店、入驻 —— 与「人」相关的一组 -->
    <view class="cells">
      <view class="cell" @tap="gotoGroupHost">
        <text class="cell__label">{{ $t("groupHost.title") }}</text>
        <text class="cell__value">{{ $t("groupHost.entryHint") }}</text>
      </view>
      <view class="cell" @tap="gotoGroups">
        <text class="cell__label">{{ $t("groups.title") }}</text>
        <text class="cell__value">{{ $t("groups.entryHint") }}</text>
      </view>
      <view class="cell" @tap="gotoVisited">
        <text class="cell__label">{{ $t("visited.title") }}</text>
        <text class="cell__value">{{ $t("visited.hint") }}</text>
      </view>
      <view class="cell" @tap="applyMerchant">
        <text class="cell__label">{{ $t("merchant.apply") }}</text>
        <text class="cell__value">{{ applyStatusText }}</text>
      </view>
    </view>

    <!-- 设置：与生意无关，放最后 -->
    <view class="cells">
      <view class="cell" @tap="themeVisible = true">
        <text class="cell__label">{{ $t("me.appearance") }}</text>
        <text class="cell__value">{{ $t("me.appearanceValue") }}</text>
      </view>
      <view class="cell">
        <text class="cell__label">{{ $t("me.help") }}</text>
        <text class="cell__value">{{ $t("me.helpValue") }}</text>
      </view>
    </view>

    <sh-theme-sheet v-model:visible="themeVisible"></sh-theme-sheet>

    <!-- 商家入驻申请 -->
    <view v-if="merchantVisible" class="sheet">
      <view class="sheet__mask" @tap="merchantVisible = false" />
      <view class="sheet__panel">
        <view class="sheet__grip" />
        <text class="sh-h2">{{ $t("merchant.apply") }}</text>
        <text class="sh-muted sheet__hint">{{ $t("merchant.applyFormHint") }}</text>

        <!-- 行业排在主体之前：它决定主体能不能选小微，顺序反了人会白挑一次 -->
        <view class="types">
          <view
            v-for="i in industries"
            :key="i.industry"
            class="type"
            :class="{ 'is-on': mForm.industry === i.industry }"
            @tap="mForm.industry = i.industry"
          >
            {{ i.name }}
          </view>
        </view>

        <view class="types">
          <view
            v-for="tp in ['MICRO', 'INDIVIDUAL', 'ENTERPRISE']"
            :key="tp"
            class="type"
            :class="{
              'is-on': mForm.subject === tp,
              'is-blocked': !subjectAllowed(tp as MerchantSubject),
            }"
            @tap="subjectAllowed(tp as MerchantSubject) && (mForm.subject = tp as MerchantSubject)"
          >
            {{ $t(`merchant.subject.${tp}`) }}
          </view>
        </view>
        <!-- 禁用要给理由：光变灰只会让人反复点它 -->
        <text v-if="!subjectAllowed('MICRO')" class="blocked-tip">
          {{ $t("merchant.microBlocked") }}
        </text>

        <input v-model="mForm.name" class="field" :placeholder="$t('merchant.shopName')" />
        <input v-model="mForm.category" class="field" :placeholder="$t('merchant.category')" />
        <input v-model="mForm.contactName" class="field" :placeholder="$t('merchant.contact')" />
        <input
          v-model="mForm.contactPhone"
          class="field"
          type="number"
          maxlength="11"
          :placeholder="$t('merchant.phone')"
        />
        <input v-model="mForm.desc" class="field" :placeholder="$t('merchant.descPh')" />

        <view class="sh-btn sheet__save" :class="{ 'is-disabled': !mValid }" @tap="submitMerchant">
          {{ $t("merchant.submitApply") }}
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.sheet {
  position: fixed;
  inset: 0;
  z-index: 100;
}
.sheet__mask {
  position: absolute;
  inset: 0;
  background: var(--sh-scrim);
}
.sheet__panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--sh-surface);
  border-radius: 44rpx 44rpx 0 0;
  padding: 24rpx 36rpx calc(48rpx + env(safe-area-inset-bottom));
}
.sheet__grip {
  width: 72rpx;
  height: 8rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  margin: 0 auto 32rpx;
}
.sheet__hint {
  display: block;
  margin-top: 10rpx;
  line-height: 1.6;
}
.types {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}
.type {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  border-radius: 24rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 26rpx;
}
.type.is-blocked {
  opacity: 0.4;
}
.blocked-tip {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-danger);
}
.type.is-on {
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-weight: 600;
}
.field {
  background: var(--sh-faint);
  border-radius: 24rpx;
  padding: 26rpx 28rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
  margin-top: 16rpx;
}
.sheet__save {
  margin-top: 32rpx;
}
.is-disabled {
  opacity: 0.45;
}
.head {
  display: flex;
  align-items: center;
  gap: 28rpx;
}
.head__avatar {
  width: 104rpx;
  height: 104rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  text-align: center;
  line-height: 104rpx;
  font-size: 52rpx;
  flex-shrink: 0;
}
.head__main {
  flex: 1;
  min-width: 0;
}
.head__name {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  line-height: 1.3;
  color: var(--sh-ink);
}
.head__sub {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 8rpx;
}
/*
 * 分组密排，不是一行一张浮起的卡。
 * 原先 11 行各自带背景与圆角、彼此留 8rpx 空隙 —— 满屏漂着 11 个孤立色块，
 * 行与行的间距比行本身还抢眼，看起来像 11 个功能模块，而它们其实是一张清单。
 * 现在**归属靠分组表达，留白只出现在组与组之间**。
 */
.cells {
  margin-top: 20rpx;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  background: var(--sh-surface);
  border-radius: 24rpx;
  overflow: hidden;
}
/* 32rpx 内边距 + 12rpx 行距，一屏只放得下 8 行，翻起来很累。
   收到 22/8 之后仍有 ~76rpx 行高（远超 44pt 的点按下限），一屏多两三行 */
.cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  padding: 24rpx 26rpx;
}
.cell__label {
  font-size: 28rpx;
  color: var(--sh-ink);
  flex-shrink: 0;
}
.cell__value {
  font-size: 26rpx;
  color: var(--sh-sub);
  text-align: end;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
