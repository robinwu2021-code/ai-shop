<script setup lang="ts">
// 登录：端差异全在 ports/auth（小程序=微信一键；App/H5=手机号 OTP）。页面不写 #ifdef。
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { useUserStore } from "@/stores/user";
import { acquireCredential } from "@shared/ports/auth";

const { t } = useI18n();
const user = useUserStore();
const phone = ref("");
const otp = ref("");
const loading = ref(false);

/** 裂变归因：分享链接带进来的邀请人 / 团长，登录时一并提交 */
const inviterNo = ref("");
const merchantNo = ref("");

onLoad((q) => {
  inviterNo.value = (q?.inviterNo as string) || "";
  merchantNo.value = (q?.merchantNo as string) || "";
});

async function submit() {
  loading.value = true;
  try {
    const cred = await acquireCredential(phone.value, otp.value);
    await user.login({ ...cred, inviterNo: inviterNo.value, merchantNo: merchantNo.value });
    uni.showToast({ title: String(t("login.success")), icon: "none" });
    setTimeout(() => uni.navigateBack(), 400);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="login.submit">
    <view class="head">
      <text class="sh-h1">{{ $t("login.title") }}</text>
      <text class="sh-muted head__sub">{{ $t("login.sub") }}</text>
    </view>

    <view class="form">
      <input
        v-model="phone"
        class="field"
        type="number"
        :placeholder="$t('login.phone')"
        maxlength="11"
      />
      <input
        v-model="otp"
        class="field"
        type="number"
        :placeholder="$t('login.otp')"
        maxlength="6"
      />
    </view>

    <view class="sh-btn submit" :class="{ 'is-loading': loading }" @tap="submit">
      {{ loading ? $t("login.submitting") : $t("login.submit") }}
    </view>

    <text class="agree">{{ $t("login.agree") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.head {
  margin-top: 72rpx;
}
.head__sub {
  display: block;
  margin-top: 16rpx;
}
.form {
  margin-top: 72rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.field {
  background: var(--sh-surface);
  border-radius: 28rpx;
  padding: 32rpx;
  font-size: 30rpx;
  color: var(--sh-ink);
}
.submit {
  margin-top: 32rpx;
}
.submit.is-loading {
  opacity: 0.55;
}
.agree {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: var(--sh-sub);
  margin-top: 40rpx;
  line-height: 1.6;
}
</style>
