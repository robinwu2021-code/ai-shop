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
import { isPhone } from "@shared/utils/validate";

const props = defineProps<{
  visible: boolean;
  /**
   * 他在别处已经填过的号码（下单页 = 所选地址上的收货电话；地址表单 = 刚输进手机号栏的）。
   * 带进验证码那一栏，**他只需要再填验证码** —— 同一个号不让人输第二遍。
   *
   * 只是预填、不是绑定：地址上的号码没验证过，谁都能填别人的号，账号归属必须过验证码或微信一键。
   */
  suggest?: string;
}>();
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
  () => props.visible,
  async (on) => {
    if (!on) return;
    conflict.value = false;
    // 不用 phoneStr()：它声明在下面，而 immediate 的 watch 在 setup 里同步跑 —— 那时还在 TDZ
    const typed = String(phone.value ?? "").trim();
    if (!typed && props.suggest && isPhone(props.suggest)) phone.value = props.suggest;
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
  <!--
    形态归 `sh-sheet`：遮罩、面板、圆角、把手、关闭、安全区、max-height 全在它那儿。
    此前这一份自己画了一遍 —— 圆角 32rpx（库件是 44）、没有 max-height（内容一多
    就把上半截顶出视口）、`left/right` 是物理属性（阿语下不跟着翻）。
  -->
  <sh-sheet
    :visible="visible"
    :title="String($t('phoneGate.title'))"
    :hint="String($t('phoneGate.why'))"
    @close="emit('close')"
  >
    <view v-if="conflict" class="sh-notice sh-notice--warning conflict">
      <text class="txt-sub txt-ink">{{ $t("phoneGate.conflict") }}</text>
    </view>

    <!-- 一键：拿到的是 code，换号在后端。
         **这是全仓唯一保留的 `<button>`** —— 微信只认 button 上的 open-type 拿手机号 -->
    <button
      v-if="capable"
      class="sh-btn onetap"
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
      <view class="sh-row">
        <input v-model="code" class="field__input form__input form__input--code" type="number" maxlength="6"
               :placeholder="String($t('phoneGate.codePlaceholder'))" />
        <view class="sh-btn sh-btn--soft sh-btn--sm sh-center form__send" :class="{ 'is-off': sending }" @tap="sendCode">
          {{ $t("phoneGate.sendCode") }}
        </view>
      </view>
      <!-- `form__submit` 只是给测试的稳定抓手（与旁边的 form__send 同一套命名）：
           这一处此前是原生 <button>，测试按标签选它；换成 .sh-btn 之后选择器落空，
           而报错指向 trigger 那一行，看不出根因是「这里已经不是 button 了」。 -->
      <view class="sh-btn form__submit" :class="{ 'is-disabled': busy }" @tap="onSubmit">
        {{ $t("phoneGate.submit") }}
      </view>
    </view>

    <text v-if="capable" class="sh-muted switch" @tap="capable = false">
      {{ $t("phoneGate.useCode") }}
    </text>
    <text class="sh-muted cancel" @tap="emit('close')">{{ $t("phoneGate.later") }}</text>
  </sh-sheet>
</template>

<style scoped>
/* 换号冲突的提示块：警示色 tint 底，与 deposit 的「还差多少」同一个做法 */
.conflict {
  margin-top: 24rpx;
}
.onetap {
  margin-top: 32rpx;
}
.form {
  margin-top: 32rpx;
}
/* 形态归 .field__input（88rpx / 24rpx 圆角 / faint 底，与两端所有输入框同一档）；
   这里只留这张弹层里项与项之间的缝 */
.form__input {
  margin-bottom: 20rpx;
}
.form__input--code {
  flex: 1;
}
/* 形态归 .sh-btn--soft + --sm（tint 胶囊，26rpx）；只把高度对齐旁边那个输入框。
   居中用 .sh-center 而不是 line-height：后者既是行距又是盒高，两件事挤在一个数上 */
.form__send {
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
