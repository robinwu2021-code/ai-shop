<script setup lang="ts">
import { onHide, onShow } from "@dcloudio/uni-app";
import { computed, onBeforeUnmount, onMounted, ref, useSlots } from "vue";
import { useUserStore } from "@/stores/user";
import { nativePhoneAuthorization } from "@/ports/phone-login";
import { preparePhoneLogin, submitPhoneAuthorization, phoneLoginState } from "@/shared/phone-login";
import { ROUTES } from "@shared/utils/constants";

const props = withDefaults(defineProps<{
  label: string;
  disabled?: boolean;
  loading?: boolean;
  size?: "default" | "sm" | "md";
  tone?: "primary" | "soft";
}>(), { disabled: false, loading: false, size: "default", tone: "primary" });
const emit = defineEmits<{ ready: [result: { userChanged: boolean }]; cancel: []; authorize: []; error: [error: unknown] }>();
const slots = useSlots();
const user = useUserStore();
const preparing = ref(false);
const submitting = ref(false);
let alive = true;
let generation = 0;
const busy = computed(() => props.loading || preparing.value || submitting.value || phoneLoginState.phase === "submitting");
const nativeGuest = computed(() => nativePhoneAuthorization && user.isLogin && !user.isPhoneLogin);

onMounted(async () => {
  preparing.value = true;
  try { await preparePhoneLogin(); } finally { preparing.value = false; }
});
onHide(() => { alive = false; generation++; });
onShow(() => { alive = true; });
onBeforeUnmount(() => { alive = false; generation++; });

async function onTap() {
  if (props.disabled || busy.value) return;
  if (nativePhoneAuthorization && !user.isLogin) {
    preparing.value = true;
    try {
      if (!(await preparePhoneLogin())) uni.showToast({ title: String("登录准备失败，请重试"), icon: "none" });
    } finally { preparing.value = false; }
    return;
  }
  if (user.isPhoneLogin) emit("ready", { userChanged: false });
  else if (!nativePhoneAuthorization) {
    const pages = getCurrentPages();
    const page = pages[pages.length - 1] as { route?: string; options?: Record<string, string> } | undefined;
    const query = Object.entries(page?.options ?? {}).map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join("&");
    const redirect = page?.route ? `/${page.route}${query ? `?${query}` : ""}` : "";
    uni.navigateTo({ url: `${ROUTES.login}?redirect=${encodeURIComponent(redirect)}` });
  }
  else if (nativeGuest.value) phoneLoginState.phase = "authorizing";
  // Native guests submit only through getphonenumber, never tap + getphonenumber twice.
}
async function onPhone(event: { detail?: { code?: string; errMsg?: string } }) {
  if (!alive || props.disabled || busy.value || user.isPhoneLogin) return;
  const code = event.detail?.code;
  if (!code) { phoneLoginState.phase = "ready"; emit("cancel"); return; }
  const attempt = generation;
  emit("authorize");
  submitting.value = true;
  try {
    const result = await submitPhoneAuthorization(code);
    if (alive && generation === attempt) emit("ready", result);
  } catch (error) {
    if (alive && generation === attempt) { emit("error", error); uni.showToast({ title: String(error instanceof Error ? error.message : "登录失败，请重试"), icon: "none" }); }
  } finally { submitting.value = false; }
}
</script>

<template>
  <button
    class="phone-button"
    :class="{ 'sh-btn': !slots.default, 'phone-button--action': !!slots.default, 'sh-btn--soft': tone === 'soft', 'sh-btn--sm': size === 'sm', 'sh-btn--md': size === 'md', 'is-disabled': disabled || busy }"
    :open-type="nativeGuest ? 'getPhoneNumber' : undefined"
    :disabled="disabled || busy"
    :loading="busy"
    @tap="onTap"
    @getphonenumber="onPhone"
  ><slot>{{ label }}</slot></button>
</template>

<style scoped>
.phone-button {
  box-sizing: border-box;
  margin: 0;
  min-width: 0;
  flex-shrink: 0;
  border: 0;
  white-space: nowrap;
  overflow: visible;
}
.phone-button::after { border: 0; }
.phone-button.phone-button--action {
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  align-items: center;
  justify-content: center;
  padding: 0;
  min-width: 88rpx;
  min-height: 88rpx;
  background: transparent;
  color: inherit;
  line-height: 1.4;
}
</style>
