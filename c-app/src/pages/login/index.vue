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
import type { GrantType } from "@shared/types";
import { api } from "@/api";
import { isPhone } from "@shared/utils/validate";

const { t } = useI18n();
const user = useUserStore();
const phone = ref("");
/**
 * 那一格填的东西。
 *
 * <p>验证码与密码**共用一个 ref**，而切换方式时要清空 ——
 * 不清的话用户填了验证码再切到密码，输入框里那 6 位数字会被当成密码提交，
 * 而他看到的只是「密码错误」。
 */
const otp = ref("");
const loading = ref(false);

/*
 * `withPassword: true` 打开密码登录（C-AC-08）。
 *
 * <p><b>这个开关此前没人打开</b>：后端的密码登录（GRANT_PASSWORD、
 * usr_identity 的 PASSWORD 凭证、按手机号限流防撞库）整条链早就通了，
 * 而这一行调的是不带参的 loginMethods() —— 于是密码方式压根不在返回列表里。
 * 「能设密码」而「设了登不进来」就是一个没人读的配置屏。
 */
const methods = loginMethods({ withPassword: true });
/** 免输入的快捷方式（微信）。小程序上有，H5 上为空数组 */
const quickMethods = computed(() => methods.filter((m) => !m.needsPhone));
/**
 * 要手机号的方式 —— 验证码与密码**都是**。
 *
 * <p>此前这里是 `.find()`，只认一个。那时列表里也只有一个，所以看不出问题；
 * 一旦多出密码方式，`.find()` 会静默只渲染第一个，而页面上没有任何痕迹
 * （这正是 ports/auth 里「页面按它返回什么就渲染什么」要防的那件事）。
 */
const phoneMethods = computed(() => methods.filter((m) => m.needsPhone));
/** 当前选中的那一种。默认第一个 —— 验证码永远在前，它不需要先设过什么 */
const phoneKind = ref<GrantType | "">("");
const phoneMethod = computed(
  () => phoneMethods.value.find((m) => m.id === phoneKind.value) ?? phoneMethods.value[0],
);
/** 另一种（用来渲染「改用…登录」那一行）。只有一种时为 undefined */
const otherMethod = computed(
  () => phoneMethods.value.find((m) => m.id !== phoneMethod.value?.id),
);
/** 当前这一格填的是密码还是验证码 —— 决定输入框类型、文案、要不要「发送」按钮 */
const byPassword = computed(() => phoneMethod.value?.id === "PASSWORD");

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

/**
 * 被 401 踢来登录时带着的来源页（`/pages/xxx/index?a=b`）。登录完要回到它。
 *
 * <p>空的时候走 `navigateBack()` —— 那是用户自己点进登录页的情形，栈是好的。
 */
const redirect = ref("");

onLoad((q) => {
  /*
   * **query 没有就用暂存的**（§3.1）。邀请链接指向的是首页，
   * 而他从首页点到这里时那个参数不会跟过来 —— 不兜这一下，
   * `fissionPort.onRegister` 拿不到邀请人，台账那一行根本不会写，
   * 而注册与下单看起来都正常，只有邀请人永远等不到那张券。
   */
  inviterNo.value = (q?.inviterNo as string) || user.pendingInviter || "";
  merchantNo.value = (q?.merchantNo as string) || "";
  // 被 401 踢来时带的来源页，登录完要回到它。见 goBackAfterLogin
  redirect.value = decodeURIComponent((q?.redirect as string) || "");
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
  if (!isPhone(phone.value)) {
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

/**
 * 登录成功后的去向。
 *
 * <h2>为什么不能只 navigateBack</h2>
 * 401 处理器是用 `reLaunch` 把人送过来的，**页面栈已经清空** ——
 * 那时 `navigateBack()` 是空转：登录成功了，人还停在登录页。
 *
 * <h2>为什么 reLaunch 而不是 navigateTo</h2>
 * 来源页可能是 tab 页（购物车、我的），`navigateTo` 对 tab 页会静默失败。
 *
 * <h2>为什么要校验前缀</h2>
 * 这个值来自 URL 参数。只放行 `/pages/` 开头的站内路径 ——
 * 不校验的话，一条构造过的链接就能让登录后跳到任意地方。
 */
function goBackAfterLogin() {
  const to = redirect.value;
  if (to.startsWith("/pages/")) {
    uni.reLaunch({ url: to });
    return;
  }
  uni.navigateBack();
}

/**
 * 换一种方式（验证码 ↔ 密码）。
 *
 * <p>**切换时清掉那一格**：不清的话用户填的 6 位验证码会被当成密码提交，
 * 而他看到的只是「密码错误」—— 一句与真实原因毫无关系的话。
 */
function switchPhoneKind(to: LoginMethod) {
  phoneKind.value = to.id;
  otp.value = "";
}

async function doLogin(method: LoginMethod) {
  // 只有要手机号的方式才校验手机号 —— 微信登录不需要，此前的写法会拦住它
  if (method.needsPhone && !isPhone(phone.value)) {
    uni.showToast({ title: String(t("login.phoneInvalid")), icon: "none" });
    return;
  }
  if (loading.value) return;
  loading.value = true;
  try {
    const cred = await method.acquire(phone.value, otp.value);
    await user.login({ ...cred, inviterNo: inviterNo.value, merchantNo: merchantNo.value });
    uni.showToast({ title: String(t("login.success")), icon: "none" });
    setTimeout(goBackAfterLogin, 400);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="login.navTitle">
    <view class="head">
      <text class="txt-display">{{ $t("login.title") }}</text>
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
      <text class="txt-sub switch__text txt-primary">{{ $t("login.orPhone") }}</text>
    </view>

    <template v-if="phoneMethod && showPhone">
      <view class="form">
        <input
          v-model="phone"
          class="txt-body login__field"
          type="number"
          :placeholder="$t('login.phone')"
          maxlength="11"
        />
        <!--
          验证码与密码共用这一格，但**不是同一个输入框**：
          v-if 分开写而不是把 type / maxlength 绑成表达式 —— 后者在小程序上
          切 type 时不重建节点，已输入的值会留在里面（而那正好是要清掉的东西）。
        -->
        <view v-if="!byPassword" class="otp-row sh-row">
          <input
            v-model="otp"
            class="txt-body login__field sh-fill"
            type="number"
            :placeholder="$t('login.otp')"
            maxlength="6"
          />
          <text class="txt-sub otp-row__send" :class="left > 0 ? 'txt-quiet' : 'txt-primary'" @tap="sendOtp">
            {{ left > 0 ? $t("login.resend", { s: left }) : $t("login.sendOtp") }}
          </text>
        </view>
        <input
          v-else
          v-model="otp"
          class="txt-body login__field"
          password
          :placeholder="$t('login.passwordPlaceholder')"
          maxlength="32"
        />
      </view>

      <!--
        换一种方式。只有一种时整行不渲染 —— 「切换」在只有一个选项时
        是一行每次都要看一眼、而永远点不出任何变化的字。
      -->
      <view v-if="otherMethod" class="switch2" @tap="switchPhoneKind(otherMethod)">
        <text class="txt-sub switch__text txt-primary">{{ $t(otherMethod.labelKey) }}</text>
      </view>

      <view class="sh-btn submit" :class="{ 'is-loading': loading }" @tap="doLogin(phoneMethod)">
        {{ loading ? $t("login.submitting") : $t("login.submit") }}
      </view>
    </template>

    <!--
      **协议必须能点开。** 原来这一行是纯文本，《用户协议》《隐私政策》点不动 ——
      而它是提审必查项：收集手机号与位置的小程序，用户要能读到那两份东西。
    -->
    <view class="txt-caption agree">
      <text class="agree__text txt-quiet">{{ $t("login.agreePrefix") }}</text>
      <text class="txt-caption agree__link txt-primary" @tap="openDoc('terms')">{{ $t("legal.terms") }}</text>
      <text class="agree__text txt-quiet">{{ $t("login.agreeAnd") }}</text>
      <text class="txt-caption agree__link txt-primary" @tap="openDoc('privacy')">{{ $t("legal.privacy") }}</text>
    </view>

    <!--
      **给一条回去的路。** 到这一页的人多半是被 401 弹过来的，
      而商品、门店、团购本来就是游客可看的 —— 不给出口，登录就成了逛的前置条件。
    -->
    <text class="txt-sub browse" @tap="goBrowse">{{ $t("login.browseFirst") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.agree {
  margin-top: 40rpx;
  text-align: center;
}
.agree__text,

.browse {
  display: block;
  margin-top: 32rpx;
  text-align: center;
}

.switch {
  margin: 32rpx 0 8rpx;
  padding: 16rpx;
  text-align: center;
}

/*
 * 「换一种方式」那一行。比上面那个 .switch 的上边距小一半 ——
 * 它紧跟在表单下面，属于同一组；.switch 隔开的是两种登录入口。
 */
.switch2 {
  margin: 16rpx 0 0;
  padding: 16rpx;
  text-align: center;
}

.otp-row__send {
  flex-shrink: 0;
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
  margin-top: 40rpx;
}
</style>
