<script setup lang="ts">
// 登录与手机号。账号是主系统的 —— 同一个微信在「社区好物」与这里是同一个人。
//
// 两段，按他缺什么显示哪一段：
//   1. 还没登录：微信登录（小程序里其实打开就静默登录过了，走到这里多半是那一步没成）
//   2. 登录了、还没手机号：绑手机号 —— 能一键就一键，否则验证码
// **没有「手机号 + 验证码直接登录」**：主系统 C 端不给匿名发码（MpUserController#sendOtp），
// 验证码只用来给已登录的账号绑号。H5 只是调试用的，那里登不进来是预期的。
import { computed, onUnmounted, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { useUserStore } from "@/stores/user";
import { ROUTES } from "@/shared/routes";

/** 号已绑在别的账号上（主系统的错误码）：要说清楚，不然他会反复重试 */
const PHONE_TAKEN = 10409;
/** 一键取号失败（多半是小程序没认证）：退到验证码 */
const WX_PHONE_FAILED = 70027;

const user = useUserStore();
const redirect = ref("");
const capable = ref(false);
const phone = ref("");
const code = ref("");
const busy = ref(false);
const left = ref(0);
let timer: ReturnType<typeof setInterval> | undefined;

onLoad(async (q) => {
  redirect.value = q?.redirect ? decodeURIComponent(String(q.redirect)) : "";
  if (user.isLogin && !user.hasPhone) await user.loadProfile().catch(() => null);
  capable.value = await api.phoneCapable().then((r) => r.capable).catch(() => false);
});
onUnmounted(() => timer && clearInterval(timer));

const step = computed(() => (!user.isLogin ? "login" : !user.hasPhone ? "phone" : "done"));
const phoneOk = computed(() => /^1\d{10}$/.test(phone.value.trim()));

async function wxLogin() {
  busy.value = true;
  const ok = await user.silentLogin(true).catch(() => false);
  busy.value = false;
  if (!ok) {
    toast("微信登录没成，稍后再试");
    return;
  }
  if (user.hasPhone) back();
}

async function sendCode() {
  if (!phoneOk.value || left.value > 0) return;
  try {
    await api.sendOtp(phone.value.trim());
    left.value = 60;
    timer = setInterval(() => {
      left.value -= 1;
      if (left.value <= 0 && timer) clearInterval(timer);
    }, 1000);
  } catch (e) {
    toast(errMsg(e));
  }
}

async function bind(run: () => Promise<unknown>) {
  busy.value = true;
  try {
    await run();
    await user.loadProfile();
    back();
  } catch (e) {
    const c = (e as { code?: number }).code;
    if (c === PHONE_TAKEN) toast("这个号已绑在另一个账号上，换一个号");
    else if (c === WX_PHONE_FAILED) {
      capable.value = false;
      toast("一键获取没成，用验证码绑");
    } else toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

function bindByCode() {
  if (!phoneOk.value || !/^\d{4,6}$/.test(code.value.trim())) {
    toast("手机号与验证码都要填");
    return;
  }
  void bind(() => api.bindPhone(phone.value.trim(), code.value.trim()));
}

function onWxPhone(e: { detail?: { code?: string } }) {
  const c = e.detail?.code;
  if (c) void bind(() => api.bindPhoneByWx(c));
}

/**
 * 回到来的那一页。**有上一页就返回**：询价填了一半被要登录，reLaunch 会把那一页连同填的东西冲掉。
 * 栈里只有这一页时（直接打开、或冷启动恢复）才按 redirect 重开。
 */
function back() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack();
    return;
  }
  uni.reLaunch({ url: redirect.value || ROUTES.home });
}
</script>

<template>
  <sh-scaffold title-key="title.login">
    <view v-if="step === 'login'" class="sh-card">
      <text class="txt-title">登录后询价、看报价</text>
      <text class="txt-caption sh-muted block">与「社区好物」是同一个微信账号</text>
      <!-- #ifdef MP-WEIXIN -->
      <view class="sh-btn gap" :class="{ 'is-disabled': busy }" @tap="wxLogin">微信登录</view>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN -->
      <view class="sh-notice sh-notice--muted gap">
        <text class="txt-sub">请在微信里打开「元器件」小程序登录</text>
      </view>
      <!-- #endif -->
    </view>

    <view v-else-if="step === 'phone'" class="sh-card">
      <text class="txt-title">绑定手机号</text>
      <text class="txt-caption sh-muted block">询价与成为供应商都要 —— 平台用这个号联系你，不给别人看</text>

      <!-- #ifdef MP-WEIXIN -->
      <button v-if="capable" class="sh-btn gap" open-type="getPhoneNumber" @getphonenumber="onWxPhone">
        微信一键获取手机号
      </button>
      <!-- #endif -->

      <input v-model="phone" class="field__input gap" type="number" maxlength="11" placeholder="手机号" />
      <view class="sh-row pair">
        <input v-model="code" class="field__input grow" type="number" maxlength="6" placeholder="验证码" />
        <view class="sh-btn sh-btn--soft sh-btn--md" :class="{ 'is-disabled': !phoneOk || left > 0 }" @tap="sendCode">
          {{ left > 0 ? $t("login.resend", { s: left }) : "发验证码" }}
        </view>
      </view>
    </view>

    <view v-else class="sh-card">
      <text class="txt-title">已登录</text>
      <text class="txt-caption sh-muted block">手机号 {{ user.user?.phone }}</text>
    </view>

    <sh-actionbar v-if="step === 'phone'">
      <view class="sh-btn" :class="{ 'is-disabled': busy }" @tap="bindByCode">{{ busy ? "绑定中…" : "绑定" }}</view>
    </sh-actionbar>
    <sh-actionbar v-else-if="step === 'done'">
      <view class="sh-btn" @tap="back">返回</view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.block {
  display: block;
  margin-top: 8rpx;
}
.gap {
  margin-top: 28rpx;
}
.pair {
  gap: 16rpx;
  margin-top: 16rpx;
}
.grow {
  flex: 1;
  margin-top: 0;
}
</style>
