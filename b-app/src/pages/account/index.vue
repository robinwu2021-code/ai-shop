<script setup lang="ts">
/**
 * 账号（TDD-B 端账号二级页）。
 *
 * <p><b>为什么单开一页</b>：这三行是「我的」里最低频的 —— 用户名一年改一次、
 * 登录账号只读、密码偶尔改 —— 却压着「收入 / 结算 / 员工 / 收款设置」那些每天要用的，
 * 占掉首屏约三分之一。收进二级页之后首屏留给真正要用的功能。
 *
 * <p><b>定位没变，层级变了。</b>「我的」那一页的注释写着它是「账号维度的东西」，
 * 本次不是把账号信息赶出去，是把平铺的三行收成一行入口 + 这一页。
 *
 * <p>两个 handler 与 `hasPassword` 的加载从 `me/index.vue` <b>原样搬来</b>，
 * 不重写 —— 它们各自的注释记着为什么那么写，一并带过来了。
 */
import { onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

/** 设没设过密码 —— 决定那一行显示「修改」还是「去设置」 */
const hasPassword = ref(false);

onMounted(async () => {
  if (!merchant.profile) await merchant.loadProfile();
  hasPassword.value = (await api.mHasPassword().catch(() => null))?.hasPassword ?? false;
});

/** 用户名（显示名）：改的是「我自己」那一行。店员/店主都能改各自的 */
async function editDisplayName() {
  const name = (await prompt({
    title: String(t("me.username")),
    placeholder: String(t("me.usernamePh")),
  })) ?? "";
  if (!name.trim()) return;
  try {
    await api.mSetDisplayName(name.trim());
    await merchant.loadProfile();
    uni.showToast({ title: t("me.usernameSaved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 设置 / 修改密码。用系统输入框而不是单开一页：这是一个字段的表单，
 * 为它建一页要连带处理返回、校验、键盘遮挡三件事，收益不抵成本。
 *
 * <p>设过就是「修改」，没设过是「设置」—— 两个词对应的心理动作不同，
 * 含糊成一个「密码」会让人不知道点进去会发生什么。
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
</script>

<template>
  <sh-scaffold title-key="account.title">
    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="editDisplayName">
        <text class="txt-body cell__label">{{ $t("me.username") }}</text>
        <text class="txt-caption cell__value">{{ merchant.profile?.displayName || $t("me.usernameUnset") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <!--
        登录账号只读。它存在的理由是「多店 / 多人时分不清此刻是哪个身份」——
        改密码、找回都要先对上这个号。第三方登录没有手机号时留空，补绑入口在登录页。
      -->
      <view class="sh-cell sh-row sh-row--between">
        <text class="txt-body cell__label">{{ $t("me.account") }}</text>
        <text class="txt-caption cell__value sh-num">{{ merchant.profile?.phone || "—" }}</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="editPassword">
        <text class="txt-body cell__label">{{ $t("me.password") }}</text>
        <text class="txt-caption cell__value">
          {{ hasPassword ? $t("me.passwordSet") : $t("me.passwordUnset") }}
        </text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.cell__label {
  flex: 1;
}
.cell__value {
  color: var(--sh-sub);
  margin-inline-end: 8rpx;
}
</style>
