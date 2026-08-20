<script setup lang="ts">
// 商家消息中心（TDD-通知与消息推送 §二期）。
// 收的是「必须有人看见」的经营事件：新订单、售后申请、新评价/差评。
// 布局与 C 端同源 —— 消息中心不该有两套交互记忆。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { datetime } from "@shared/utils/format";
import type { Message, MessageType } from "@shared/types";

const TABS: { key: string; type: MessageType | null }[] = [
  { key: "all", type: null },
  { key: "TRADE", type: "TRADE" },
  { key: "SYSTEM", type: "SYSTEM" },
];

const tab = ref("all");
const messages = ref<Message[]>([]);
const loaded = ref(false);

const shown = computed(() => {
  const def = TABS.find((t) => t.key === tab.value);
  return def?.type ? messages.value.filter((m) => m.type === def.type) : messages.value;
});
const unread = computed(() => messages.value.filter((m) => !m.read).length);

async function load() {
  messages.value = await api.mMessageList();
  loaded.value = true;
}

async function open(m: Message) {
  if (!m.read) messages.value = await api.mMessageRead(m.messageNo);
  if (m.link) uni.navigateTo({ url: m.link });
}

async function readAll() {
  if (!unread.value) return;
  messages.value = await api.mMessageReadAll();
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="message.title">
    <view class="head">
      <sh-tabs
        class="tabs"
        :items="TABS.map((t) => ({ key: t.key, label: String($t(`message.tab.${t.key}`)) }))"
        :active="tab"
        @change="(k: string) => (tab = k as typeof tab)"
      ></sh-tabs>
      <text v-if="unread" class="readall" @tap="readAll">{{ $t("message.readAll") }}</text>
    </view>

    <view v-for="m in shown" :key="m.messageNo" class="msg" @tap="open(m)">
      <view class="msg__dot" :class="{ 'is-unread': !m.read }" />
      <view class="msg__main">
        <view class="msg__top">
          <text class="msg__title" :class="{ 'is-unread': !m.read }">{{ m.title }}</text>
          <text class="msg__at sh-num">{{ datetime(m.at) }}</text>
        </view>
        <text class="msg__body">{{ m.body }}</text>
        <text v-if="m.link" class="msg__more">{{ $t("message.view") }}</text>
      </view>
    </view>

    <sh-empty bare v-if="loaded && !shown.length" :text='$t("message.empty")'></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.tabs {
  flex: 1;
  min-width: 0;
}
.readall {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.msg {
  display: flex;
  gap: 16rpx;
  background: var(--sh-surface);
  border-radius: 32rpx;
  padding: 28rpx;
  margin-bottom: 16rpx;
}
.msg__dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 9999px;
  background: transparent;
  margin-top: 12rpx;
  flex-shrink: 0;
}
.msg__dot.is-unread {
  background: var(--sh-danger);
}
.msg__main {
  flex: 1;
  min-width: 0;
}
.msg__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20rpx;
}
.msg__title {
  font-size: 28rpx;
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg__at {
  font-size: 24rpx;
  color: var(--sh-sub);
  flex-shrink: 0;
}
.msg__body {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
  margin-top: 12rpx;
}
.msg__more {
  display: block;
  font-size: 24rpx;
  color: var(--sh-primary-text);
  margin-top: 14rpx;
}
</style>
