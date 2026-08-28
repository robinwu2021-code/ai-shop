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
    <view class="head sh-row">
      <sh-tabs
        class="sh-fill"
        :items="TABS.map((t) => ({ key: t.key, label: String($t(`message.tab.${t.key}`)) }))"
        :active="tab"
        @change="(k: string) => (tab = k as typeof tab)"
      ></sh-tabs>
      <text v-if="unread" class="txt-caption readall" @tap="readAll">{{ $t("message.readAll") }}</text>
    </view>

    <view v-for="m in shown" :key="m.messageNo" class="sh-card msg" @tap="open(m)">
      <view class="msg__dot" :class="{ 'is-unread': !m.read }" />
      <view class="sh-fill">
        <view class="msg__top sh-row sh-row--between sh-row--baseline">
          <text class="txt-body msg__title" :class="{ 'is-unread': !m.read }">{{ m.title }}</text>
          <text class="txt-caption msg__at sh-num">{{ datetime(m.at) }}</text>
        </view>
        <text class="txt-caption msg__body">{{ m.body }}</text>
        <sh-go v-if="m.link" class="msg__more" :text="String($t('message.view'))"></sh-go>
      </view>
    </view>

    <sh-empty bare v-if="loaded && !shown.length" :text='$t("message.empty")'></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.head {
  margin-bottom: 16rpx;
}

.readall {
  flex-shrink: 0;
  color: var(--sh-primary-text);
}
/* 面色 / 圆角 / 内边距全交给 `.sh-card` —— 此前这三行是把它照抄了一遍。
   内边距因此从 28rpx 变成 B 端的密度档 24rpx（`--sh-pad-card`），差 2px：
   **这正是密度变量存在的意义** —— 各页各写一个数，调密度时就得逐页找。 */
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

.msg__top {
  gap: 20rpx;
}
.msg__title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg__at {
  flex-shrink: 0;
}
.msg__body {
  display: block;
  margin-top: 12rpx;
}
/* 字号与颜色由 `sh-go` 给 */
.msg__more {
  display: flex;
  margin-top: 16rpx;
}
</style>
