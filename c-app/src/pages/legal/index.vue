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
      <text class="txt-caption doc__meta">{{ $t("legal.updatedAt", { d: doc.updatedAt }) }}</text>
      <view v-for="s in doc.sections" :key="s.heading" class="sec">
        <text class="txt-body sec__h sh-mb-sm">{{ s.heading }}</text>
        <text v-for="(p, i) in s.body" :key="i" class="txt-sub sec__p">{{ p }}</text>
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
}
.sec {
  margin-bottom: 40rpx;
}
/* 下间距走间距档（sm = 16rpx）——「标题自己带一个 margin」正是
   那条判据要拦的：间距是版面的事，不该长在标题身上 */
.sec__h {
  display: block;
}
.sec__p {
  display: block;
  margin-bottom: 16rpx;
}
</style>
