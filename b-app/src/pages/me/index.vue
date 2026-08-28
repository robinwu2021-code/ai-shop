<script setup lang="ts">
import { MERCHANT_LOGO_FALLBACK } from "@shared/utils/constants";
// 我的（复用 C 端的外观面板：4 皮肤 × 明暗 × 三语 × 多市场）。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { useMerchantStore } from "@/stores/merchant";
import { refreshUnread, unreadCount } from "@/stores/messages";
import { ROUTES } from "@/shared/nav";
import { api } from "@/api";
import type { MerchantPlan } from "@shared/types";
import { prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();
const sheetOpen = ref(false);
const plan = ref<MerchantPlan | null>(null);

/** 额度用完：这一行的数字转警示色 —— 它是升档的第一次提示，也是最自然的那一次 */
const quotaFull = computed(() => !!plan.value && plan.value.storeUsed >= plan.value.storeQuota);

const statusKey = computed(() => `me.status${merchant.profile?.status ?? "NONE"}`);

function go(url: string) {
  uni.navigateTo({ url });
}

function goLogin() {
  uni.navigateTo({ url: ROUTES.login });
}

function later() {
  uni.showToast({ title: "该功能在后续批次交付", icon: "none" });
}

/**
 * 套餐副标题。
 *
 * <p>**先 `ensureScope()` 再 `can()`**：权限还没加载时 `can()` fail-closed 返回 false，
 * 直接判的话——从推送深链进到这一页的老板会**永远看不到这一行**，而且不会重试。
 *
 * <p>**静默失败**：拿不到就不显示副标题，不弹错。店长（无 `biz:store:admin`）
 * 调这条是 403，而他本来就看不到这一行 —— 为一个不显示的副标题弹红字，
 * 是把权限设计做成了故障。
 */
async function loadPlan() {
  if (!merchant.isLogin) return;
  await merchant.ensureScope().catch(() => null);
  if (!merchant.can("biz:store:admin")) return;
  plan.value = await api.mMyPlan().catch(() => null);
}

/**
 * 登录密码。**设过与没设过是两种心理动作**（修改 / 设置），文案要分开。
 * 拿不到就当没设过：这一行只影响文案，不值得为它弹错。
 */
const hasPassword = ref(false);

async function loadHasPassword() {
  if (!merchant.isLogin) return;
  hasPassword.value = (await api.mHasPassword().catch(() => null))?.hasPassword ?? false;
}

/**
 * 设置 / 修改密码。用系统输入框而不是单开一页：这是一个字段的表单，
 * 为它建一页要连带处理返回、校验、键盘遮挡三件事，收益不抵成本。
 */
async function editPassword() {
  // password: true —— showModal 做不到打点，输密码时整屏都看得见
  const pwd = (await prompt({
    title: String(t(hasPassword.value ? "me.passwordSet" : "me.passwordUnset")),
    placeholder: String(t("login.passwordPh")),
    password: true,
  })) ?? "";
  if (!pwd.trim()) return;
  // 与后端 PWD_MIN_LEN 一致；端上先挡一道是为了少一次必失败的往返
  if (pwd.trim().length < 6) {
    uni.showToast({ title: t("me.passwordTooShort"), icon: "none" });
    return;
  }
  try {
    await api.mSetPassword(pwd.trim());
    hasPassword.value = true;
    uni.showToast({ title: t("me.passwordSaved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function logout() {
  // 解绑要在清令牌**之前** —— 之后就没有可用的令牌了。
  // 门店共用一台手机换班时，上一班的人不该继续收到这家店的订单推送
  await merchant.unbindPushDevice();
  merchant.logout();
  uni.showToast({ title: "已退出", icon: "none" });
}

onShow(() => {
  void merchant.loadProfile().catch(() => null);
  // 名下有几张证照 —— 决定「证照与账户」这一行出不出现
  void merchant.ensureEntityGroups();
  void loadPlan();
  void loadHasPassword();
  // 从消息页返回时角标要立即回落，不等下一轮 30s 轮询
  void refreshUnread();
});
</script>

<template>
  <sh-scaffold title-key="tab.me" tab="me">
    <view v-if="!merchant.isLogin" class="sh-card head sh-row" @tap="goLogin">
      <text class="txt-title">{{ $t("me.notLogin") }}</text>
      <text class="sh-muted">{{ $t("me.notLoginHint") }}</text>
    </view>

    <view v-else class="sh-card head sh-row">
      <text class="head__logo">{{ merchant.profile?.logo || MERCHANT_LOGO_FALLBACK }}</text>
      <view class="sh-fill">
        <text class="txt-title">{{ merchant.profile?.name || $t("me.store") }}</text>
        <text class="sh-chip" :class="merchant.isActive ? 'sh-chip--primary' : 'sh-chip--warning'">
          {{ $t(statusKey) }}
        </text>
      </view>
    </view>

    <!--
      分组密排，不是一行一张卡。
      原先每一行都套 sh-card：卡片自带内边距、圆角与投影，五行就变成五块互不相干的浮起色块，
      中间的留白比行本身还显眼 —— 「看起来像五个功能模块」，而它们其实只是一张设置清单。
      改成组内密排（同 C 端「我的」）：**分组表达归属，间距只在组与组之间**。
    -->
    <view class="cells">
      <!-- 结算与经营数据按 perms 裁剪：店员看不到「钱」，也看不到客户资产 -->
      <!--
        **「我的收入」补一个门。** 这一页（B-11.9）此前全 app 没有一处跳得过去 ——
        路由注册了、也随包发到了线上，而它自己是好的。真机上验别的东西时撞出来的。

        <p>摆这儿的判据只有两条：它要 `biz:finance`（与结算同一个），
        而结算是明细、收入是总览 —— 总览在明细之前。
        <b>这是补位不是定案</b>：做「钱」那条线的人如果另有排布（比如提到工作台），
        以那边为准，把这一行删掉就是。有门总比没门强。
      -->
      <view v-if="merchant.can('biz:finance')" class="cell sh-row sh-row--between" @tap="go(ROUTES.income)">
        <text class="txt-body cell__label">{{ $t("me.income") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <view v-if="merchant.can('biz:finance')" class="cell sh-row sh-row--between" @tap="go(ROUTES.settle)">
        <text class="txt-body cell__label">{{ $t("me.settle") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <!--
        员工与授权。**唯一入口原先在工作台第二屏**，用户实际反馈是「没看到添加员工的功能」。
        放这里而不是往工作台上提：工作台按「今天要干的活」排，加员工不是每天干的事。
      -->
      <!--
        这里**没有门店入口**：门店从工作台顶部那颗店名胶囊进（切店、改名、开新店都在那一页）。
        「我的」是账号维度的东西 —— 密码、语言、套餐；门店是经营维度的，
        两处都摆一个门就又回到「同一件事三个入口，人记不住走哪个」。
      -->
      <view v-if="merchant.can('biz:store:admin')" class="cell sh-row sh-row--between" @tap="go(ROUTES.staff)">
        <text class="txt-body cell__label">{{ $t("me.staff") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <!-- 收款设置：商户维度的钱袋子。「我的」本身就是商户视角，不必再造一层「商户」 -->
      <view v-if="merchant.can('biz:finance')" class="cell sh-row sh-row--between" @tap="go(ROUTES.payment)">
        <text class="txt-body cell__label">{{ $t("me.payment") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <!--
        资质证照与收款设置同属主体档案：一套照片管所有门店，不按店分。
        原先挂在门店管理页里 —— 那一页现在只答「哪家店、今天怎么样」，
        一年动一次的执照摆在那儿只会把每天要看的数字往下挤。
      -->
      <view v-if="merchant.can('biz:store')" class="cell sh-row sh-row--between" @tap="go(ROUTES.qualifications)">
        <text class="txt-body cell__label">{{ $t("stores.qualEntry") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <!--
        证照与账户（多证照）。**只在名下不止一张证照时出现**。

        单证照商家点进去只会看到一条记录，而上面那两行（收款设置、资质证件）
        已经把他那张证照的全部内容拆开摆好了 —— 再多一个入口只会让他犹豫走哪个。
        多证照时反过来：那两行只能管「当前这张」，第二张证照没有任何别的路进得去。

        与「切换门店」是两条互不打架的路：那个是「我现在要在哪家店干活」（每天），
        这个是「我名下有哪几张执照」（一年动不了几次），点它不会切换当前门店。
      -->
      <view
        v-if="merchant.multiEntity && merchant.can('biz:store:admin')"
        class="cell sh-row sh-row--between"
        @tap="go(ROUTES.entities)"
      >
        <text class="txt-body cell__label">{{ $t("entities.title") }}</text>
        <!-- 数的是**证照**不是门店：这一行进的是证照列表页，那里一条就是一张证照 -->
        <text class="txt-caption cell__sub sh-fill">{{ $t("entities.count", { n: merchant.entityGroups.length }) }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <!--
        我的套餐。**副标题带数字**（「成长版 · 门店 2/3」）——
        只写档位名的话，他要点进去才知道自己满没满，而满没满正是他要看的。
        额度用完时数字转警示色：这一行本身就是升档的第一次提示。

        与员工同一个码（biz:store:admin，只有老板）：这一页答的是「主体买了什么」。
        店长看到额度只会去催老板买单，而他不是做这个决定的人。
      -->
      <view v-if="merchant.can('biz:store:admin')" class="cell sh-row sh-row--between" @tap="go(ROUTES.plan)">
        <text class="txt-body cell__label">{{ $t("plan.meCell") }}</text>
        <text v-if="plan" class="txt-caption cell__value" :class="{ 'cell__value--warn': quotaFull }">
          {{ $t("plan.meSub", { name: plan.planName, used: plan.storeUsed, quota: plan.storeQuota }) }}
        </text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <view v-if="merchant.can('biz:customer')" class="cell sh-row sh-row--between" @tap="go(ROUTES.stats)">
        <text class="txt-body cell__label">{{ $t("me.stats") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <view class="cells">
      <!-- 消息：新订单/售后/评价的落点。红点数与 tabBar 角标同源（30s 轮询） -->
      <view v-if="merchant.isLogin" class="cell sh-row sh-row--between" @tap="go(ROUTES.messages)">
        <text class="txt-body cell__label">{{ $t("me.messages") }}</text>
        <text v-if="unreadCount" class="sh-badge-count cell__badge sh-num">
          {{ unreadCount > 99 ? "99+" : unreadCount }}
        </text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <!-- 登录密码：设过就是「修改」，没设过是「设置」——
           两个词对应的心理动作不同，含糊成一个「密码」会让人不知道点进去会发生什么 -->
      <view v-if="merchant.isLogin" class="cell sh-row sh-row--between" @tap="editPassword">
        <text class="txt-body cell__label">{{ $t("me.password") }}</text>
        <text class="txt-caption cell__value">
          {{ hasPassword ? $t("me.passwordSet") : $t("me.passwordUnset") }}
        </text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <view class="cell sh-row sh-row--between" @tap="sheetOpen = true">
        <text class="txt-body cell__label">{{ $t("me.appearance") }}</text>
        <text class="txt-caption cell__value">{{ $t("me.appearanceValue") }}</text>
      </view>
      <view class="cell sh-row sh-row--between" @tap="later">
        <text class="txt-body cell__label">{{ $t("me.help") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <!-- 退出登录单独一组：它与上面几项不是同类，紧挨着放容易误点 -->
    <view v-if="merchant.isLogin" class="cells">
      <view class="cell sh-row sh-row--between" @tap="logout">
        <text class="txt-body cell__label cell__label--danger">{{ $t("me.logout") }}</text>
      </view>
    </view>

    <sh-theme-sheet v-model:visible="sheetOpen"></sh-theme-sheet>
  </sh-scaffold>
</template>

<style scoped>
.head {
  gap: 24rpx;
}
.head__logo {
  font-size: 64rpx;
  width: 108rpx;
  height: 108rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  text-align: center;
  line-height: 108rpx;
}

.head__main .sh-chip {
  margin-top: 12rpx;
}
/* 组：组间留白，组内不留 —— 归属靠分组表达，不靠每行浮起 */
.cells {
  margin-top: 20rpx;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  background: var(--sh-surface);
  border-radius: 24rpx;
  overflow: hidden;
}
.cell {
  gap: 24rpx;
  padding: 28rpx;
}
.cell__label {
  flex-shrink: 0;
}
/* 右侧补充数字（「3 家门店」）：与主标题同一行、色浅一档，
   不换行也不抢视线 —— 它是判断要不要点进去的依据，不是标题的一部分 */
.cell__sub {
  text-align: end;
}
/* 退出登录用警示色：它是不可逆动作，和「查看结算单」不该长得一样 */
.cell__label--danger {
  color: var(--sh-danger);
}
.cell__badge {
  flex-shrink: 0;
}
.cell__value {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 额度用完：这一行的数字要看得出来不对劲，但不是报错 —— 他没做错任何事 */
.cell__value--warn {
  color: var(--sh-warning);
}
</style>
