<script setup lang="ts">
function openDoc(doc: "terms" | "privacy") {
  uni.navigateTo({ url: `/pages/legal/index?doc=${doc}` });
}
</script>

<template>
  <view class="sh-hint">
    {{ $t('phoneLogin.agreementPrefix') }}
    <text class="sh-link" @tap="openDoc('terms')">{{ $t('legal.terms') }}</text>
    ·
    <text class="sh-link" @tap="openDoc('privacy')">{{ $t('legal.privacy') }}</text>
  </view>
</template>
