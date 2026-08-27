<script setup lang="ts">
import type { MasterData, MerchantApplyStatus, MerchantSubject } from "@shared/types";
// 我的：登录入口 + 归属信息 + 外观与语言。
// 列表项之间用间距分块，不用分隔线（扁平色块风格）。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import PhoneGate from "@/components/phone-gate.vue";
import { useCommunityStore } from "@/stores/community";
import { FEATURES, ROUTES } from "@shared/utils/constants";
import { confirm } from "@ai-shop/ui/prompt";
import { isPhone } from "@shared/utils/validate";

const { t } = useI18n();
const user = useUserStore();
/** 「我的」页的绑定入口。静默登录之后「有账号没手机号」是常态 */
const phoneGate = ref(false);
const community = useCommunityStore();
const themeVisible = ref(false);
const points = ref(0);
const unread = ref(0);

function gotoLogin() {
  uni.navigateTo({ url: ROUTES.login });
}
/**
 * 退出登录。二次确认是必要的 —— 这一格紧挨着「帮助」，误触代价是重新走一遍登录。
 * 真正作废服务端会话在 store 里做（见 stores/user.ts 的说明）。
 */
async function onLogout() {
  // 用 callback 包 Promise，与本文件其他确认弹窗一致 —— uni 的 showModal
  // 在各端上并非都返回 Promise，直接 await 在小程序里拿不到 confirm
  const ok = await confirm({ title: String(t("me.logout")), hint: String(t("me.logoutConfirm")) });
  if (!ok) return;
  await user.logout();
  uni.reLaunch({ url: "/pages/home/index" });
}

/**
 * 注销账号。**微信对有账号体系的小程序要求提供这个入口**（上架审核会查）。
 *
 * <p>与「退出登录」隔开一段距离并用弱化的样式：两者一字之差、后果天差地别 ——
 * 退出登录再登回来就是；注销之后同一个微信进来是**一个全新账号**，
 * 旧账号的订单、卡券、积分他都再也看不到。
 *
 * <p>确认框把后果逐条说出来，而不是「确定要注销吗？」——
 * 那句话没有给他任何判断依据。
 */
async function onDeregister() {
  const ok = await confirm({ title: String(t("me.deregister")), hint: String(t("me.deregisterConfirm")), confirmText: String(t("me.deregisterYes")), danger: true });
  if (!ok) return;
  try {
    await api.deregister();
    await user.logout();
    uni.showToast({ title: String(t("me.deregisterDone")), icon: "none" });
    setTimeout(() => uni.reLaunch({ url: "/pages/home/index" }), 1200);
  } catch (e) {
    /*
     * 有未完成订单时后端回 70028。**要把他送到订单列表去** ——
     * 只说「还有未完成的订单」而不给入口，他得自己翻。
     */
    if ((e as { code?: number }).code === 70028) {
      void confirm({
        title: String(t("me.deregisterBlocked")),
        hint: String(t("me.deregisterBlockedTip")),
        confirmText: String(t("me.viewOrders")),
      }).then((ok) => {
        if (ok) uni.switchTab({ url: "/pages/order/index" });
      });
      return;
    }
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
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

function gotoMemberships() {
  uni.navigateTo({ url: ROUTES.myMemberships });
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
  subject: "NATURAL_PERSON" as MerchantSubject,
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
    isPhone(mForm.value.contactPhone.trim()) &&
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
    // 只取一个数 —— 拉整个列表数未读是把带宽当角标用
    api.unreadMessages().then((n) => (unread.value = n)).catch(() => {});
  } else {
    /*
     * **未登录必须清零**：这一条此前写在 isLogin 块外面 ——
     * 于是未登录的人也会看到「N 条未读」，而那不是他的消息；
     * 接真后端时还会白挨一个 401（并可能触发全局登出跳转）。
     * 不清零的话，登出之后旧数字还挂在那儿。
     */
    unread.value = 0;
  }
  if (FEATURES.points) api.pointAccount().then((a) => (points.value = a.balance));
});
</script>

<template>
  <sh-scaffold title-key="tab.me" tab="me">
    <view class="sh-card head" @tap="!user.isLogin && gotoLogin()">
      <text class="head__avatar">{{ user.user?.avatar || "🙂" }}</text>
      <view class="sh-fill">
        <text class="head__name">
          {{ user.isLogin ? user.user?.nickname : $t("me.login") }}
        </text>
        <!--
          静默登录之后**有账号但没手机号**是常态，此时旧写法显示的是一片空白 ——
          用户不知道那一行为什么空着，更不知道能点。
          现在空着的时候直接说「去绑定」，它就是入口。
        -->
        <text v-if="!user.isLogin" class="head__sub">{{ $t("me.loginHint") }}</text>
        <text v-else-if="user.user?.phone" class="head__sub">{{ user.user.phone }}</text>
        <text v-else class="head__sub head__sub--action" @tap.stop="phoneGate = true">
          {{ $t("me.bindPhone") }}
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
        <!--
          未登录时**什么都不显示**。显示「已全部阅读」是在说一句假话：
          它暗示这个人有消息且都读过了，而他还没登录，平台根本不知道他是谁。
          与上面「我的常去店」未登录时显示「—」是同一个口径。
        -->
        <text v-if="user.isLogin" class="cell__value sh-num">
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
      <!-- 会员与消息：**退订入口必须在显眼处**，藏起来的开关等于没有 -->
      <view class="cell" @tap="gotoMemberships">
        <text class="cell__label">{{ $t("myMembership.title") }}</text>
        <text class="cell__value">{{ $t("myMembership.entryHint") }}</text>
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
      <!-- 此前**整个 c-app 没有退出登录入口** —— store 里的 logout() 是死代码。
           没有入口意味着共用设备上无法结束会话，而令牌在服务端一直有效 -->
      <view v-if="user.isLogin" class="cell" @tap="onLogout">
        <text class="cell__label">{{ $t("me.logout") }}</text>
      </view>
    </view>

    <!--
      注销单独一块、离「退出登录」远一点。两者一字之差、后果天差地别：
      退出登录再登回来就是；注销之后同一个微信进来是一个全新账号。
      微信要求有这个入口（上架审核会查），但不该让它看起来像个常用操作。
    -->
    <view v-if="user.isLogin" class="danger" @tap="onDeregister">
      <text class="danger__text">{{ $t("me.deregister") }}</text>
    </view>

    <sh-theme-sheet v-model:visible="themeVisible"></sh-theme-sheet>

    <!-- 商家入驻申请 -->
    <sh-sheet
      :visible="merchantVisible"
      :title="String($t('merchant.apply'))"
      :hint="String($t('merchant.applyFormHint'))"
      @close="merchantVisible = false"
    >
        <!-- 行业排在主体之前：它决定主体能不能选小微，顺序反了人会白挑一次 -->
        <view class="types">
          <view
            v-for="i in industries"
            :key="i.industry"
            class="sh-seg sh-seg--fill"
            :class="{ 'sh-seg--on': mForm.industry === i.industry }"
            @tap="mForm.industry = i.industry"
          >
            {{ i.name }}
          </view>
        </view>

        <view class="types">
          <view
            v-for="tp in ['MICRO', 'INDIVIDUAL', 'ENTERPRISE']"
            :key="tp"
            class="sh-seg sh-seg--fill"
            :class="{
              'sh-seg--on': mForm.subject === tp,
              'sh-seg--off': !subjectAllowed(tp as MerchantSubject),
            }"
            @tap="subjectAllowed(tp as MerchantSubject) && (mForm.subject = tp as MerchantSubject)"
          >
            {{ $t(`merchant.subject.${tp}`) }}
          </view>
        </view>
        <!-- 禁用要给理由：光变灰只会让人反复点它 -->
        <text v-if="!subjectAllowed('NATURAL_PERSON')" class="blocked-tip">
          {{ $t("merchant.microBlocked") }}
        </text>

        <input maxlength="64" v-model="mForm.name" class="field__input" :placeholder="$t('merchant.shopName')" />
        <input maxlength="64" v-model="mForm.category" class="field__input" :placeholder="$t('merchant.category')" />
        <input maxlength="64" v-model="mForm.contactName" class="field__input" :placeholder="$t('merchant.contact')" />
        <input
          v-model="mForm.contactPhone"
          class="field__input"
          type="number"
          maxlength="11"
          :placeholder="$t('merchant.phone')"
        />
        <input maxlength="255" v-model="mForm.desc" class="field__input" :placeholder="$t('merchant.descPh')" />

        <view class="sh-btn sheet__save" :class="{ 'is-disabled': !mValid }" @tap="submitMerchant">
          {{ $t("merchant.submitApply") }}
        </view>
    </sh-sheet>
    <!--
      **必须留在 sh-scaffold 里面。** 这套 `--sh-*` 变量声明在 `:root, .sh-root` 上，
      而**小程序里没有 `:root`** —— 根节点叫 `page`，那条选择器一个节点都不匹配，
      全靠 scaffold 根节点上的 `.sh-root`。挂到 scaffold 外面就一个变量都继承不到：
      遮罩和卡片背景 `var(--sh-scrim)` / `var(--sh-surface)` 双双落空变透明，
      弹层文字直接浮在商品列表上，**看起来像页面串了行，而不像弹窗坏了**。
      H5 上不会露：浏览器里 `:root` 是匹配的。见 shared/tests/scaffold-scope.test.ts
    -->
    <phone-gate :show="phoneGate" @done="phoneGate = false" @close="phoneGate = false" />
  </sh-scaffold>
</template>

<style scoped>
.head__sub--action {
  color: var(--sh-primary-text);
}

.types {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}
.blocked-tip {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-danger);
}
/* 与 address 逐字节相同的一份重写，现在都走 `.field__input`，只留纵向间距 */
.field__input {
  margin-top: 16rpx;
}
.sheet__save {
  margin-top: 32rpx;
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
.danger {
  margin: 48rpx 0 24rpx;
  padding: 24rpx;
  text-align: center;
}
.danger__text {
  font-size: 26rpx;
  color: var(--sh-sub);
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
