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

/**
 * 两种身份，两条登录路。
 *
 * **老板**走消费者账号（入驻是他发起的，那时他本来就是消费者）；
 * **店员**走商家账号（`mch_account`）—— 他多半没有也不需要消费者账号，
 * 要求他先注册成消费者才能上班，是把雇佣关系硬塞进一个消费关系里。
 *
 * 做成一个切换而不是自动判断：同一个手机号可能两边都有（老板自己也可能被加成员工），
 * 猜错的表现是「登进去什么都看不到」，而人不会知道自己登错了身份。
 */
const asStaff = ref(false);

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
    if (asStaff.value) {
      await merchant.staffLogin(phone.value, code.value);
      // 店员进的是工作台，不是入驻页 —— 店已经开好了，他只是来上班的
      uni.switchTab({ url: ROUTES.home });
      return;
    }
    const cred = await method.acquire(phone.value, code.value);
    const profile = await merchant.login({ ...cred, agreed: true });
    // 未入驻直接去申请；已入驻回工作台。少一次「登录成功」的空转
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
      <text class="sh-muted mt">{{ asStaff ? $t("login.staffHint") : $t("login.hint") }}</text>
    </view>

    <!--
      身份切换放在最上面：它决定后面所有输入的含义。
      放下面的话人会先填完再发现选错，而选错的表现是「登进去什么都看不到」。
    -->
    <view class="who">
      <text class="who__i" :class="{ 'is-on': !asStaff }" @tap="asStaff = false">
        {{ $t("login.asOwner") }}
      </text>
      <text class="who__i" :class="{ 'is-on': asStaff }" @tap="asStaff = true">
        {{ $t("login.asStaff") }}
      </text>
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
      快捷登录只给老板：微信/Apple 拿到的是**消费者身份**，
      而员工要的是商家账号那条路 —— 混在一起会让店员点了微信却登成消费者。
    -->
    <template v-if="quickMethods.length && !asStaff">
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
.who {
  display: flex;
  gap: 16rpx;
  margin: 24rpx 32rpx 0;
}
.who__i {
  flex: 1;
  padding: 18rpx 0;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 26rpx;
  text-align: center;
  color: var(--sh-sub);
}
.who__i.is-on {
  background: var(--sh-primary-tint);
  color: var(--sh-primary);
  font-weight: 600;
}
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
