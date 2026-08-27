<script setup lang="ts">
// 登录：端差异全在 ports/auth（小程序=微信静默登录；App/H5=手机号 OTP）。页面不写 #ifdef。
//
// **页面渲染什么，取决于 `loginMethods()` 给了什么** —— 此前这里写死了手机号表单，
// 而小程序上实际发出去的是微信 code，用户填的手机号被丢弃：界面与行为是两回事。
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { useUserStore } from "@/stores/user";
import { loginMethods, type LoginMethod } from "@shared/ports/auth";
import { api } from "@/api";

const { t } = useI18n();
const user = useUserStore();
const phone = ref("");
const otp = ref("");
const loading = ref(false);

const methods = loginMethods();
/** 免输入的快捷方式（微信）。小程序上有，H5 上为空数组 */
const quickMethods = computed(() => methods.filter((m) => !m.needsPhone));
/** 手机号 OTP：全端都有，小程序上是兜底 */
const phoneMethod = computed(() => methods.find((m) => m.needsPhone));

/**
 * 手机号表单展不展开。
 *
 * <p>没有快捷方式的端（H5 / App）恒为 true —— 那边手机号就是唯一的路，
 * 收起来只会多一次点击。小程序上默认收起，微信那条才是主按钮。
 */
const showPhone = ref(quickMethods.value.length === 0);

function openDoc(doc: "terms" | "privacy") {
  uni.navigateTo({ url: `/pages/legal/index?doc=${doc}` });
}

/** 先逛逛：回首页。用 switchTab —— 首页是 tab 页，navigateTo 会静默失败 */
function goBrowse() {
  uni.switchTab({ url: "/pages/home/index" });
}

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

async function doLogin(method: LoginMethod) {
  // 只有要手机号的方式才校验手机号 —— 微信登录不需要，此前的写法会拦住它
  if (method.needsPhone && !/^\d{11}$/.test(phone.value)) {
    uni.showToast({ title: String(t("login.phoneInvalid")), icon: "none" });
    return;
  }
  if (loading.value) return;
  loading.value = true;
  try {
    const cred = await method.acquire(phone.value, otp.value);
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

    <!-- 快捷方式（小程序上是微信静默登录）。H5 / App 上 quickMethods 为空，整块不渲染 -->
    <view
      v-for="m in quickMethods"
      :key="m.id"
      class="sh-btn quick"
      :class="{ 'sh-btn--soft': !m.primary, 'is-loading': loading }"
      @tap="doLogin(m)"
    >
      {{ loading ? $t("login.submitting") : $t(m.labelKey) }}
    </view>

    <!--
      **有快捷方式时，手机号那条先收起来。**

      此前两块同时铺开：「微信一键登录」与「登录 / 注册」两个同等分量的主按钮并排，
      而它们做的是同一件事 —— 用户唯一能做的判断是「猜哪个更对」。
      小程序上微信那条是明显更快的路（一次点击、不用等短信），
      所以它当主按钮，手机号退成一行可点的次要入口。

      H5 / App 上 quickMethods 为空，`showPhone` 恒真，那边看到的还是原来的表单。
    -->
    <view
      v-if="quickMethods.length && phoneMethod && !showPhone"
      class="switch"
      @tap="showPhone = true"
    >
      <text class="switch__text">{{ $t("login.orPhone") }}</text>
    </view>

    <template v-if="phoneMethod && showPhone">
      <view class="form">
        <input
          v-model="phone"
          class="login__field"
          type="number"
          :placeholder="$t('login.phone')"
          maxlength="11"
        />
        <view class="otp-row">
          <input
            v-model="otp"
            class="login__field otp-row__input"
            type="number"
            :placeholder="$t('login.otp')"
            maxlength="6"
          />
          <text class="otp-row__send" :class="{ 'is-off': left > 0 }" @tap="sendOtp">
            {{ left > 0 ? $t("login.resend", { s: left }) : $t("login.sendOtp") }}
          </text>
        </view>
      </view>

      <view class="sh-btn submit" :class="{ 'is-loading': loading }" @tap="doLogin(phoneMethod)">
        {{ loading ? $t("login.submitting") : $t("login.submit") }}
      </view>
    </template>

    <!--
      **协议必须能点开。** 原来这一行是纯文本，《用户协议》《隐私政策》点不动 ——
      而它是提审必查项：收集手机号与位置的小程序，用户要能读到那两份东西。
    -->
    <view class="agree">
      <text class="agree__text">{{ $t("login.agreePrefix") }}</text>
      <text class="agree__link" @tap="openDoc('terms')">{{ $t("legal.terms") }}</text>
      <text class="agree__text">{{ $t("login.agreeAnd") }}</text>
      <text class="agree__link" @tap="openDoc('privacy')">{{ $t("legal.privacy") }}</text>
    </view>

    <!--
      **给一条回去的路。** 到这一页的人多半是被 401 弹过来的，
      而商品、门店、团购本来就是游客可看的 —— 不给出口，登录就成了逛的前置条件。
    -->
    <text class="browse" @tap="goBrowse">{{ $t("login.browseFirst") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.agree {
  margin-top: 40rpx;
  text-align: center;
}
.agree__text,
.agree__link {
  font-size: 24rpx;
  line-height: 1.6;
}
.agree__text {
  color: var(--sh-sub);
}
.agree__link {
  color: var(--sh-primary-text);
}
.browse {
  display: block;
  margin-top: 32rpx;
  text-align: center;
  font-size: 26rpx;
  color: var(--sh-sub);
}

.switch {
  margin: 32rpx 0 8rpx;
  padding: 16rpx;
  text-align: center;
}
.switch__text {
  font-size: 26rpx;
  color: var(--sh-primary-text);
}

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
  color: var(--sh-primary-text);
}
.otp-row__send.is-off {
  color: var(--sh-sub);
}

.quick {
  margin-top: 72rpx;
}
.quick + .quick {
  margin-top: 20rpx;
}
.divider {
  text-align: center;
  margin: 40rpx 0 0;
  font-size: 24rpx;
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
/* 登录页的输入框是**刻意的另一个形态**：surface 底（不是 faint）、lg 圆角、
   更大的内边距 —— 整屏只有一两个控件，它要占住视觉重心。

   **不做成 `.field__input--lg` 进库**：只有这一个页面用，进库等于把页面样式搬了个家。
   但它此前叫 `.field`，squat 在两端共用表单族的名字上 —— 找 `.field__*` 的人
   会以为这是那一族的基类。改名即可。 */
.login__field {
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
