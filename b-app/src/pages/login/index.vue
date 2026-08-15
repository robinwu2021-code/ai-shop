<script setup lang="ts">
// 商家登录 / 注册（B-11.1.0）。**登录即注册** —— 商家第一次进来不该先填一遍注册表
// 再登录一遍，中间那步没有任何信息增量。首次登录建号，随后引导去入驻。
//
// 端差异全在 `loginMethods()` 里，页面**不写 `#ifdef`**：
//   小程序 → 微信一键取手机号（主）+ 手机号 OTP（兜底）
//   App    → 手机号 OTP（主）+ 微信 + Apple（仅 iOS）
//   H5     → 手机号 OTP
//
// 手机号是商家账号的**主标识**：店铺要能在换手机、换店员、转让时交接，
// 绑死在某个 openid 上反而麻烦 —— 第三方登录之后仍要补绑手机号。
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { loginMethods, type LoginMethod } from "@shared/ports/auth";

const { t } = useI18n();
const merchant = useMerchantStore();

/*
 * **不问「你是老板还是店员」**（2026-08-15 拍板）。
 *
 * 此前这里有一个身份切换，理由写的是「猜错的表现是登进去什么都看不到」。
 * 但那个理由针对的是**端上猜**；后端不用猜 —— 它按手机号查得到：
 * 老板行记在 `mch_entity.owner_user_no`，店员行记在 `mch_account.login_phone`。
 *
 * 让人自己选的代价比多点一下大得多：**选错的表现是「验证码错误」或
 * 「你不是店员」**，两句都在说谎，而真正的原因是他点了另一个 tab。
 * 何况「我是不是店员」这个身份本来就是老板给的，他自己未必知道。
 *
 * 判定顺序在后端：老板 → 店员 → 新用户（见 BizAuthController.login）。
 */

const methods = loginMethods();
const phoneMethod = methods.find((m) => m.needsPhone);
const quickMethods = computed(() => methods.filter((m) => !m.needsPhone));

const phone = ref("");
const code = ref("");
const agreed = ref(false);
const submitting = ref(false);
const sending = ref(false);

/** 验证码倒计时。没有它用户会连点，短信费与频控两头出事 */
const left = ref(0);
let timer: ReturnType<typeof setInterval> | undefined;
onUnmounted(() => clearInterval(timer));

const phoneOk = computed(() => /^\d{11}$/.test(phone.value));
const canSubmit = computed(() => phoneOk.value && code.value.length >= 4 && agreed.value);

function requireAgree(): boolean {
  if (agreed.value) return true;
  uni.showToast({ title: t("login.needAgree"), icon: "none" });
  return false;
}

/*
 * 发验证码。**此前这里只是把 1234 填进输入框** —— 从不调用后端，
 * 于是「端上没有发码这条链」被 mock 完全盖住：真实环境里没人收得到码。
 */
async function sendCode() {
  if (!phoneOk.value) {
    uni.showToast({ title: t("login.phoneInvalid"), icon: "none" });
    return;
  }
  // 倒计时里不许再点：连点会在短信费与频控两头出事
  if (left.value > 0 || sending.value) return;
  sending.value = true;
  try {
    await api.mSendOtp(phone.value);
    left.value = 60;
    timer = setInterval(() => {
      left.value -= 1;
      if (left.value <= 0) clearInterval(timer);
    }, 1000);
    uni.showToast({ title: t("login.otpSent"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    sending.value = false;
  }
}

function showAgreement() {
  uni.showModal({
    title: t("login.agreementTitle"),
    content: t("login.agreementBody"),
    showCancel: false,
  });
}

async function doLogin(method: LoginMethod) {
  if (!requireAgree()) return;
  if (method.needsPhone && !canSubmit.value) return;
  if (submitting.value) return;
  submitting.value = true;
  try {
    const cred = await method.acquire(phone.value, code.value);
    const profile = await merchant.login({ ...cred, agreed: true });
    /*
     * 进哪一屏看**后端判出来的身份**，不看端上选了什么：
     * 未入驻（status NONE）→ 入驻页；已入驻或店员 → 工作台。
     * 店员的 status 不是 NONE（他所在主体已经开好店了），所以自然走到工作台。
     */
    if (profile.status === "NONE") uni.redirectTo({ url: ROUTES.apply });
    else uni.switchTab({ url: ROUTES.home });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="login.title">
    <view class="head">
      <text class="sh-h1">{{ $t("login.title") }}</text>
      <text class="sh-muted mt">{{ $t("login.hint") }}</text>
    </view>

    <!-- 手机号 OTP：所有端都有，且是商家账号的主标识 -->
    <view v-if="phoneMethod" class="sh-card">
      <view class="field">
        <text class="field__label">{{ $t("login.phone") }}</text>
        <input
          v-model="phone"
          class="field__input sh-num"
          type="number"
          maxlength="11"
          :placeholder="$t('login.phonePh')"
        />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("login.code") }}</text>
        <view class="row">
          <input
            v-model="code"
            class="field__input sh-num flex1"
            type="number"
            maxlength="6"
            :placeholder="$t('login.codePh')"
          />
          <text class="send" :class="{ 'is-off': left > 0 }" @tap="sendCode">
            {{ left > 0 ? $t("login.resend", { s: left }) : $t("login.sendCode") }}
          </text>
        </view>
      </view>

      <view class="sh-btn submit" :class="{ 'is-off': !canSubmit }" @tap="doLogin(phoneMethod)">
        {{ $t("login.submit") }}
      </view>
    </view>

    <!--
      快捷登录（微信/Apple）拿到的是**消费者身份**，后端仍按手机号判身份 ——
      但店员多半没有消费者账号，第三方登录后要补绑手机号才认得出他是谁。
      所以这条路对店员是绕远，不是不通。
    -->
    <template v-if="quickMethods.length">
      <view class="divider">
        <text class="sh-muted">{{ $t("login.orQuick") }}</text>
      </view>
      <view
        v-for="m in quickMethods"
        :key="m.id"
        class="sh-btn quick"
        :class="{ 'sh-btn--soft': !m.primary }"
        @tap="doLogin(m)"
      >
        {{ $t(m.labelKey) }}
      </view>
    </template>

    <!-- 协议勾选：注册的合规前置，默认不勾 -->
    <view class="agree" @tap="agreed = !agreed">
      <text class="agree__box" :class="{ 'is-on': agreed }">{{ agreed ? "✓" : "" }}</text>
      <text class="agree__text">
        {{ $t("login.agreePrefix") }}
        <text class="agree__link" @tap.stop="showAgreement">{{ $t("login.agreementTitle") }}</text>
      </text>
    </view>

    <text class="tip">{{ $t("login.phoneIsIdentity") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.head {
  padding: 40rpx 8rpx 32rpx;
}
.mt {
  display: block;
  margin-top: 12rpx;
  line-height: 1.6;
}
.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.flex1 {
  flex: 1;
}
.send {
  padding: 22rpx 28rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary);
  font-size: 26rpx;
  font-weight: 600;
}
.send.is-off {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
.submit {
  margin-top: 8rpx;
}
.submit.is-off {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
.divider {
  text-align: center;
  margin: 40rpx 0 24rpx;
}
.quick {
  margin-bottom: 20rpx;
}
.agree {
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
  margin: 32rpx 8rpx 0;
}
.agree__box {
  width: 36rpx;
  height: 36rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-on-primary);
  font-size: 24rpx;
  text-align: center;
  line-height: 36rpx;
  flex-shrink: 0;
}
.agree__box.is-on {
  background: var(--sh-primary);
}
.agree__text {
  flex: 1;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.5;
}
.agree__link {
  color: var(--sh-primary);
}
.tip {
  display: block;
  margin: 24rpx 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
