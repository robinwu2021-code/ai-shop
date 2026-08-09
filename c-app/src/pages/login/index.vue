<script setup lang="ts">
// 登录：端差异全在 ports/auth（小程序=微信一键；App/H5=手机号 OTP）。页面不写 #ifdef。
import { onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { useUserStore } from "@/stores/user";
import { acquireCredential } from "@shared/ports/auth";
import { api } from "@/api";

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

/*
 * 发验证码。**此前这一步根本不存在** —— 页面有验证码输入框，
 * 却没有任何地方去发码；mock 下直接填 1234 把这条缺失盖住了，
 * 而真实环境里没有人收得到验证码，登录整条路走不通。
 */
const sending = ref(false);
const left = ref(0);
let timer: ReturnType<typeof setInterval> | undefined;
onUnmounted(() => clearInterval(timer));

async function sendOtp() {
  if (!/^\d{11}$/.test(phone.value)) {
    uni.showToast({ title: String(t("login.phoneInvalid")), icon: "none" });
    return;
  }
  // 倒计时里不许再点：没有它用户会连点，短信费与频控两头出事
  if (left.value > 0 || sending.value) return;
  sending.value = true;
  try {
    await api.sendOtp(phone.value);
    left.value = 60;
    timer = setInterval(() => {
      left.value -= 1;
      if (left.value <= 0) clearInterval(timer);
    }, 1000);
    uni.showToast({ title: String(t("login.otpSent")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    sending.value = false;
  }
}

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
      <view class="otp-row">
        <input
          v-model="otp"
          class="field otp-row__input"
          type="number"
          :placeholder="$t('login.otp')"
          maxlength="6"
        />
        <text class="otp-row__send" :class="{ 'is-off': left > 0 }" @tap="sendOtp">
          {{ left > 0 ? $t("login.resend", { s: left }) : $t("login.sendOtp") }}
        </text>
      </view>
    </view>

    <view class="sh-btn submit" :class="{ 'is-loading': loading }" @tap="submit">
      {{ loading ? $t("login.submitting") : $t("login.submit") }}
    </view>

    <text class="agree">{{ $t("login.agree") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.otp-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.otp-row__input {
  flex: 1;
  min-width: 0;
}
.otp-row__send {
  flex-shrink: 0;
  font-size: 26rpx;
  color: var(--sh-primary);
}
.otp-row__send.is-off {
  color: var(--sh-sub);
}

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
  border-radius: 32rpx;
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
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 40rpx;
  line-height: 1.6;
}
</style>
