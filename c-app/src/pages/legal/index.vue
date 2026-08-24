<script setup lang="ts">
/*
 * 端内法律文本页（用户协议 / 隐私政策）。
 *
 * <p>做成站内页而不是 `web-view` 打官网：`web-view` 要把域名配进「业务域名」，
 * 而那需要认证 + 上传校验文件 —— 现在做不到，而协议链接是**提审必查项**。
 * 站内页没有这个前置，而且断网也看得到。
 */
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { LEGAL_DOCS, PRIVACY, type LegalDoc } from "@/legal/documents";

const doc = ref<LegalDoc | null>(null);

onLoad((q) => {
  doc.value = LEGAL_DOCS[(q?.doc as string) || "privacy"] ?? PRIVACY;
});
</script>

<template>
  <sh-scaffold :title-key="doc?.titleKey || 'legal.privacy'">
    <view v-if="doc" class="doc">
      <text class="doc__meta">{{ $t("legal.updatedAt", { d: doc.updatedAt }) }}</text>
      <view v-for="s in doc.sections" :key="s.heading" class="sec">
        <text class="sec__h">{{ s.heading }}</text>
        <text v-for="(p, i) in s.body" :key="i" class="sec__p">{{ p }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.doc {
  padding-bottom: 64rpx;
}
.doc__meta {
  display: block;
  margin-bottom: 32rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.sec {
  margin-bottom: 40rpx;
}
.sec__h {
  display: block;
  margin-bottom: 16rpx;
  font-size: 30rpx;
  color: var(--sh-ink);
}
.sec__p {
  display: block;
  margin-bottom: 16rpx;
  font-size: 26rpx;
  line-height: 1.7;
  color: var(--sh-sub);
}
</style>
