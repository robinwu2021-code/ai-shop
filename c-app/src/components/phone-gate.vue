<script setup lang="ts">
/*
 * 「这一步需要手机号」的弹层。
 *
 * <p>**两种形态，同一个出口**：能一键就一键，不能就验证码，拿到号码后都调后端绑定，
 * 成功后关闭并让调用方继续原来的动作（下单 / 领券 / 参团）。
 *
 * <p>为什么不在启动时问：还没产生任何关系就要手机号，是最典型的劝退。
 * 只在**真正需要的那一刻**弹 —— 履约要联系买家，那时候要号是讲得通的。
 */
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ (e: "done"): void; (e: "close"): void }>();

const { t } = useI18n();
const user = useUserStore();

/** 一键授权可不可用 —— **由后端说了算**，端上判不出来（取决于小程序认证状态） */
const capable = ref(false);
const phone = ref("");
const code = ref("");
const sending = ref(false);
const busy = ref(false);
const conflict = ref(false);

watch(
  () => props.show,
  async (on) => {
    if (!on) return;
    conflict.value = false;
    try {
      capable.value = (await api.phoneCapable()).capable;
    } catch {
      // 问不到就当不可用：验证码那条路一直在，宁可多一步也不要卡住
      capable.value = false;
    }
  },
  { immediate: true },
);

/** 微信一键 —— 拿到的是 code，换手机号在后端做（端上碰不到号码） */
async function onWxPhone(e: { detail?: { code?: string; errMsg?: string } }) {
  const c = e.detail?.code;
  if (!c) return; // 用户点了拒绝：什么都不做，让他用下面的验证码
  await bind(() => api.bindPhoneByWx(c));
}

/*
 * **手机号要以字符串发出去。**
 *
 * `<input type="number">` 上的 `v-model` 会被 Vue **自动转成数字**，
 * 于是请求体是 `{"phone":13500135001}` 而不是 `"13500135001"` ——
 * 后端签名要的是 String。用 number 类型的输入框是为了在手机上弹数字键盘，
 * 那个是对的；错的是把它的值原样当号码用。
 */
const phoneStr = () => String(phone.value ?? "").trim();
const codeStr = () => String(code.value ?? "").trim();

async function sendCode() {
  if (!phone.value) {
    uni.showToast({ title: String(t("phoneGate.needPhone")), icon: "none" });
    return;
  }
  sending.value = true;
  try {
    await api.sendOtp(phoneStr());
    uni.showToast({ title: String(t("phoneGate.sent")), icon: "none" });
  } finally {
    sending.value = false;
  }
}

async function onSubmit() {
  // `<view>` 没有 disabled 属性，防重复提交要在这儿拦一次 ——
  // 换掉原生 button 时最容易漏的就是这一行（另外四处原本就有）
  if (busy.value) return;
  if (!phone.value || !code.value) {
    uni.showToast({ title: String(t("phoneGate.needBoth")), icon: "none" });
    return;
  }
  await bind(() => api.bindPhone(phoneStr(), codeStr()));
}

async function bind(run: () => Promise<unknown>) {
  busy.value = true;
  conflict.value = false;
  try {
    await run();
    await user.loadProfile();
    emit("done");
  } catch (err) {
    const code2 = (err as { code?: number }).code;
    if (code2 === 10409) {
      /*
       * **不要说「手机号已被占用」。** 用户会以为是别人抢了他的号，
       * 而多半是他自己以前在 H5 注册过。说清楚「用它登录可以继续，
       * 但这个微信里的浏览记录与购物车不会带过去」，他才知道下一步该怎么选。
       */
      conflict.value = true;
    } else if (code2 === 70027) {
      // 一键通道没给出号码：切回验证码，并说明白，别让他以为是自己点错了
      capable.value = false;
      uni.showToast({ title: String(t("phoneGate.wxFailed")), icon: "none" });
    } else {
      uni.showToast({ title: (err as Error).message, icon: "none" });
    }
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <view v-if="show" class="mask" @tap="emit('close')">
    <view class="sheet" @tap.stop>
      <text class="txt-title sheet__title">{{ $t("phoneGate.title") }}</text>
      <text class="sh-muted sheet__why">{{ $t("phoneGate.why") }}</text>

      <view v-if="conflict" class="conflict">
        <text class="txt-sub conflict__text">{{ $t("phoneGate.conflict") }}</text>
      </view>

      <!-- 一键：拿到的是 code，换号在后端 -->
      <button
        v-if="capable"
        class="sh-btn primary"
        open-type="getPhoneNumber"
        :disabled="busy"
        @getphonenumber="onWxPhone"
      >
        {{ $t("phoneGate.oneTap") }}
      </button>

      <!-- 回落：手机号 + 验证码。一键可用时它仍然在，只是收起成一行小字入口 -->
      <view v-if="!capable" class="form">
        <input v-model="phone" class="field__input form__input" type="number" maxlength="11"
               :placeholder="String($t('phoneGate.phonePlaceholder'))" />
        <view class="form__row">
          <input v-model="code" class="field__input form__input form__input--code" type="number" maxlength="6"
                 :placeholder="String($t('phoneGate.codePlaceholder'))" />
          <view class="sh-btn sh-btn--soft sh-btn--sm form__send" :class="{ 'is-off': sending }" @tap="sendCode">
            {{ $t("phoneGate.sendCode") }}
          </view>
        </view>
        <view class="sh-btn primary" :class="{ 'is-disabled': busy }" @tap="onSubmit">
          {{ $t("phoneGate.submit") }}
        </view>
      </view>

      <text v-if="capable" class="sh-muted switch" @tap="capable = false">
        {{ $t("phoneGate.useCode") }}
      </text>
      <text class="sh-muted cancel" @tap="emit('close')">{{ $t("phoneGate.later") }}</text>
    </view>
  </view>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: var(--sh-scrim);
  z-index: var(--sh-z-sheet);
}
.sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 48rpx 40rpx 64rpx;
  border-radius: 32rpx 32rpx 0 0;
  background: var(--sh-surface);
}
.sheet__title {
  display: block;
  color: var(--sh-ink);
}
.sheet__why {
  display: block;
  margin-top: 12rpx;
}
.conflict {
  margin-top: 24rpx;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-warning-tint);
}
.conflict__text {
  color: var(--sh-ink);
}
.form {
  margin-top: 32rpx;
}
/* 形态归 .field__input（88rpx / 24rpx 圆角 / faint 底，与两端所有输入框同一档）；
   这里只留这张弹层里项与项之间的缝 */
.form__input {
  margin-bottom: 20rpx;
}
.form__row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.form__input--code {
  flex: 1;
}
/* 形态归 .sh-btn--soft + --sm（tint 胶囊，26rpx）；只把高度对齐旁边那个输入框 */
/* 居中用 flex 而不是 line-height：后者既是「行距」又是「盒高」，
   判据分不出来，而字阶那一档的行距本来该由 .sh-btn--sm 给 */
.form__send {
  display: flex;
  align-items: center;
  height: 88rpx;
  padding: 0 24rpx;
  margin-bottom: 20rpx;
}
.form__send.is-off {
  opacity: 0.5;
}
.switch,
.cancel {
  display: block;
  margin-top: 28rpx;
  text-align: center;
}
</style>
