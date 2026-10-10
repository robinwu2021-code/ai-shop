<script setup lang="ts">
/**
 * 个人资料（C-AC-08）—— 头像、昵称、手机号、登录密码四行。
 *
 * <p><b>为什么要有这一页：</b>在它之前，「我的」页头部那张卡登录后<b>点了没反应</b>，
 * 而每个人的名字都是建户时给的占位名（线上 23/23）。
 * 也就是说这个平台上<b>没有任何人改过自己的昵称</b> —— 不是没人想改，是没有入口。
 *
 * <p>四行的顺序是按「能不能改」排的，不是按重要性：头像与昵称人人能改，
 * 手机号是绑定（一次性），密码则<b>要求先有手机号</b>。
 * 把密码放最后，它上面那行正好就是它的前置条件。
 */
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { prompt } from "@ai-shop/ui/prompt";
import { MAX_AVATAR_BYTES, pickImages } from "@shared/ports/media";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";

const { t } = useI18n();
const user = useUserStore();

/** 设过密码没有 / 现在能不能设。取不到时按「不能设」显示 —— 见 load() */
const hasPassword = ref(false);
const canSetPassword = ref(false);
/**
 * 密码状态取回来了没有。
 *
 * <p><b>与「不能设」分开</b>：没分开的话，接口抖一下用户看到的是
 * 「先绑手机号才能设密码」—— 而他的手机号明明绑着。
 * 那句话会把他送去一个已经做完的动作，且他无从知道真实原因。
 */
const passwordReady = ref(false);
/** 正在传头像 —— 挡住连点，也让用户知道这事在进行 */
const uploading = ref(false);

async function load() {
  try {
    const s = await api.passwordState();
    hasPassword.value = s.hasPassword;
    canSetPassword.value = s.canSet;
    passwordReady.value = true;
  } catch {
    passwordReady.value = false;
  }
}

onShow(() => {
  // 从绑手机号那条路回来时 canSet 会变 —— 每次进来重新问一次，别缓存
  void user.loadProfile();
  void load();
});

/** 头像是个能喂给 <image> 的地址吗。存量里还有 emoji（替身库里就是「🙂」） */
function isImageUrl(v?: string): boolean {
  return !!v && (v.startsWith("http") || v.startsWith("/"));
}

async function changeAvatar() {
  if (uploading.value) return;
  let tempPath: string;
  let size: number;
  try {
    const [picked] = await pickImages(1);
    if (!picked) return;
    tempPath = picked.tempPath;
    size = picked.size;
  } catch {
    // 取消也走 fail（见 pickImages 的注释）—— 不提示，用户知道自己点了取消
    return;
  }
  /*
   * 在端上先挡一次超大图。**不是为了省服务端的事** ——
   * 是因为后端拒的时候只能回「参数有误」，而用户要的是「这张太大，换一张」。
   * 上限与后端同一个常量族（MAX_AVATAR_BYTES / MpAvatarController.MAX_BYTES）。
   */
  if (size > MAX_AVATAR_BYTES) {
    uni.showToast({ title: String(t("profile.avatarTooBig")), icon: "none" });
    return;
  }
  uploading.value = true;
  try {
    await api.uploadAvatar(tempPath);
    // 改完重新拉一次 profile —— 与 phone-gate 同一个写法。
    // 端点确实回了新的 User，但 store 只有这一条写入口，
    // 多开一个直写口迟早会与 loadProfile 的处理分叉
    await user.loadProfile();
    uni.showToast({ title: String(t("profile.saved")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("profile.failed")), icon: "none" });
  } finally {
    uploading.value = false;
  }
}

async function changeNickname() {
  /*
   * 初值**只在已经设过时才填**。
   *
   * 还是占位名的时候填进去，用户得先把它删掉才能写自己的名字 ——
   * 而那串字不是他写的。（`prompt` 的 value 与 hint 分开正是为了这件事，
   * 见 ui/prompt.ts 开头记的三处事故。）
   */
  const input = await prompt({
    title: String(t("profile.nickname")),
    value: user.user?.nicknameSet ? user.user.nickname : "",
    placeholder: String(t("profile.nicknamePlaceholder")),
    maxlength: 20,
  });
  if (input === null) return;
  const next = input.trim();
  // 空白在这里就挡住：后端也会拒（它不再静默忽略），但那趟往返没必要
  if (!next) {
    uni.showToast({ title: String(t("profile.nicknameEmpty")), icon: "none" });
    return;
  }
  try {
    await api.updateProfile({ nickname: next });
    await user.loadProfile();
    uni.showToast({ title: String(t("profile.saved")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("profile.failed")), icon: "none" });
  }
}

async function changePassword() {
  if (!canSetPassword.value) {
    // 整行本来就不可点，这里是兜底。说清缺的是什么，而不是「不可用」
    uni.showToast({ title: String(t("profile.passwordNeedPhone")), icon: "none" });
    return;
  }
  const input = await prompt({
    title: String(hasPassword.value ? t("profile.passwordEdit") : t("profile.passwordSet")),
    // 这句话必须在这儿：店主在 C 端改了密码，他的商家端登录密码跟着变。
    // 不说的话他下次登不进 B 端会以为是故障（两端共用 usr_identity 的同一行）
    hint: String(t("profile.passwordSharedHint")),
    placeholder: String(t("profile.passwordPlaceholder")),
    password: true,
    maxlength: 32,
  });
  if (input === null) return;
  try {
    await api.setPassword(input);
    hasPassword.value = true;
    uni.showToast({ title: String(t("profile.saved")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("profile.failed")), icon: "none" });
  }
}

function gotoBindPhone() {
  uni.navigateTo({ url: "/pages/login/index?bind=1" });
}
</script>

<template>
  <sh-scaffold title-key="profile.title">
    <view class="sh-cells">
      <!-- 头像 -->
      <view class="sh-cell sh-row sh-row--between" @tap="changeAvatar">
        <text class="txt-body">{{ $t("profile.avatar") }}</text>
        <view class="sh-row pf__right">
          <image
            v-if="isImageUrl(user.user?.avatar)"
            class="pf__avatar"
            :src="user.user?.avatar"
            mode="aspectFill"
          />
          <!--
            存量里还有 emoji（替身库是「🙂」），以及空。两种都按文字渲染 ——
            把一个 emoji 喂给 <image> 的结果是一个碎图标，而不是一张头像。
          -->
          <text v-else class="pf__avatar pf__avatar--text">{{ user.user?.avatar || "🙂" }}</text>
          <sh-go />
        </view>
      </view>

      <!-- 昵称 -->
      <view class="sh-cell sh-row sh-row--between" @tap="changeNickname">
        <text class="txt-body">{{ $t("profile.nickname") }}</text>
        <view class="sh-row pf__right">
          <!--
            还没设过时显示的是「去设置」而不是那个占位名。
            显示占位名的话它看起来就是个真名字 —— 那正是以前没人改昵称的原因。
          -->
          <text v-if="user.user?.nicknameSet" class="txt-caption">{{ user.user?.nickname }}</text>
          <text v-else class="txt-caption txt-primary">{{ $t("profile.nicknameUnset") }}</text>
          <sh-go />
        </view>
      </view>

      <!-- 手机号：这一行同时是下面那行的前置条件 -->
      <view class="sh-cell sh-row sh-row--between" @tap="!user.user?.phone && gotoBindPhone()">
        <text class="txt-body">{{ $t("profile.phone") }}</text>
        <view class="sh-row pf__right">
          <text v-if="user.user?.phone" class="txt-caption">{{ user.user.phone }}</text>
          <text v-else class="txt-caption txt-primary">{{ $t("me.bindPhone") }}</text>
        </view>
      </view>

      <!-- 登录密码 -->
      <view
        class="sh-cell sh-row sh-row--between"
        :class="{ 'pf__cell--off': passwordReady && !canSetPassword }"
        @tap="changePassword"
      >
        <view class="sh-fill">
          <text class="txt-body">{{ $t("profile.password") }}</text>
          <!--
            说明只在「不能设」时出现。一直挂着的话它就是一行每次进来都要
            看一眼、而每次都与自己无关的字。
          -->
          <text v-if="passwordReady && !canSetPassword" class="txt-caption pf__hint">
            {{ $t("profile.passwordNeedPhone") }}
          </text>
        </view>
        <view class="sh-row pf__right">
          <text class="txt-caption">
            {{ hasPassword ? $t("profile.passwordEdit") : $t("profile.passwordSet") }}
          </text>
          <sh-go />
        </view>
      </view>
    </view>

    <!--
      这句话放在页面底部而不是密码那一行里：它解释的是「为什么只有一个密码」，
      属于背景，不是操作提示。挤进那一行会把一个一眼能读完的列表变成一段话。
    -->
    <text class="sh-hint">{{ $t("profile.passwordSharedHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.pf__right {
  gap: 8rpx;
}

.pf__avatar {
  width: 72rpx;
  height: 72rpx;
  border-radius: 50%;
}

/* emoji / 空值走这一支：圆底衬一个字，与真头像占同样大小，列表不会跳 */
.pf__avatar--text {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 40rpx;
  background: var(--sh-faint);
}

.pf__cell--off {
  opacity: 0.5;
}

.pf__hint {
  display: block;
  margin-top: 4rpx;
}
</style>
