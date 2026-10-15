<script setup lang="ts">
import BizLoginConsent from "./biz-login-consent.vue";
import BizPhoneLoginButton from "./biz-phone-login-button.vue";
withDefaults(defineProps<{ text?: string; tip?: string; label?: string; compact?: boolean }>(), {
  text: "", tip: "", label: "", compact: false,
});
defineEmits<{ ready: [result: { userChanged: boolean }]; cancel: []; error: [error: unknown] }>();
</script>

<template>
  <sh-empty :text="text || $t('phoneLogin.title')" :tip="tip || $t('phoneLogin.tip')" :compact="compact">
    <template #action>
      <biz-phone-login-button :label="label || $t('phoneGate.oneTap')"
        @ready="$emit('ready', $event)" @cancel="$emit('cancel')" @error="$emit('error', $event)" />
      <biz-login-consent />
    </template>
  </sh-empty>
</template>
