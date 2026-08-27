<script setup lang="ts">
// 消息中心。
// 三类分 tab 是因为**用户对它们的期待完全不同**：
// 交易类必须看到（到货了要去取），活动类可以错过。混在一起交易消息会被活动消息淹没。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { datetime } from "@shared/utils/format";
import type { Message, MessageType } from "@shared/types";

const TABS: { key: string; type: MessageType | null }[] = [
  { key: "all", type: null },
  { key: "TRADE", type: "TRADE" },
  { key: "MARKETING", type: "MARKETING" },
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
  messages.value = await api.messageList();
  loaded.value = true;
}

async function open(m: Message) {
  if (!m.read) messages.value = await api.readMessage(m.messageNo);
  if (m.link) uni.navigateTo({ url: m.link });
}

async function readAll() {
  if (!unread.value) return;
  messages.value = await api.readAllMessages();
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

    <view v-for="m in shown" :key="m.messageNo" class="sh-card msg" @tap="open(m)">
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
  margin-bottom: 24rpx;
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
/* 面色 / 圆角 / 内边距交给 `.sh-card` —— 此前这三行是把它照抄了一遍。
   内边距因此从 28rpx 变成 C 端的密度档 32rpx（`--sh-pad-card` 没被 C 端覆盖），
   差 2px：**那正是密度变量存在的意义** —— 各页各写一个数，调密度时就得逐页找。 */
.msg {
  display: flex;
  gap: 16rpx;
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
  font-size: 26rpx;
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg__title.is-unread {
  font-weight: 400;
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
