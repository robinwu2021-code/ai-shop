<script setup lang="ts">
/**
 * 公告：这一屏只做一件事 —— 说一句话给买家，立刻生效。
 *
 * <p><b>为什么独立成页</b>：它是店铺设置里唯一的日频操作（早上到货、下午售罄），
 * 此前和地址、营业时间挤在一页共用一个保存按钮 ——
 * 改一句话要连带提交整份门面，而店主并不知道那一按会写回什么。
 * 这里走独立写入口（/biz/store/announcement），不可能误伤其它字段。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";

const { t } = useI18n();
const merchant = useMerchantStore();

const text = ref("");
const until = ref<number | null>(null);
const recentAll = ref<string[]>([]);
/** 服务端那份（判「改没改」）。没改动时发布键是灰的 */
const savedText = ref("");
const savedUntil = ref<number | null>(null);
const loaded = ref(false);
const dirty = computed(() =>
  loaded.value && (text.value !== savedText.value || until.value !== savedUntil.value));

/**
 * 有效期三档。**存失效时刻**（epoch 毫秒）而不是「几天」——
 * 存天数的话，同一条公告在不同时刻保存会得到不同的到期时间。
 * 到期由服务端读时判断，不跑定时任务：这件事经不起漏一次，
 * 「昨天到货」挂一周比没有公告更伤信任。
 */
const TTL_OPTIONS = [
  { key: "today", ms: () => endOfToday() },
  { key: "d3", ms: () => Date.now() + 3 * 24 * 3600 * 1000 },
  { key: "forever", ms: () => null },
] as const;
type TtlKey = (typeof TTL_OPTIONS)[number]["key"];

/** 今天 23:59:59 —— 「今天有效」说的是今天结束，不是「24 小时后」 */
function endOfToday() {
  const d = new Date();
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

const ttlKey = ref<TtlKey>("forever");
function pickTtl(k: TtlKey) {
  ttlKey.value = k;
  const opt = TTL_OPTIONS.find((o) => o.key === k);
  until.value = opt ? opt.ms() : null;
}

/** 到期时刻的人话。只在设了有效期时出现 —— 「长期」没有到期这回事 */
const untilText = computed(() => {
  const at = until.value;
  if (!at) return "";
  const d = new Date(at);
  const sameDay = d.toDateString() === new Date().toDateString();
  const hh = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  return t("store.ttl.until", {
    s: sameDay ? `${t("store.ttl.todayAt")} ${hh}` : `${d.getMonth() + 1}/${d.getDate()} ${hh}`,
  });
});

/** 常用里不重复列正在编辑的这条 */
const recent = computed(() => recentAll.value.filter((x) => x && x !== text.value));

async function load() {
  try {
    const s = await api.mStore();
    text.value = s.announcement ?? "";
    until.value = s.announcementUntil ?? null;
    recentAll.value = s.announcementRecent ?? [];
    savedText.value = text.value;
    savedUntil.value = until.value;
    const at = until.value;
    ttlKey.value = !at ? "forever"
      : new Date(at).toDateString() === new Date().toDateString() ? "today" : "d3";
    loaded.value = true;
  } catch {
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
  }
}

const saving = ref(false);
async function publish() {
  if (!dirty.value || saving.value) return;
  saving.value = true;
  try {
    const saved = await api.mSaveAnnouncement({
      announcement: text.value,
      announcementUntil: until.value,
    });
    text.value = saved.announcement ?? text.value;
    recentAll.value = saved.announcementRecent ?? recentAll.value;
    savedText.value = text.value;
    savedUntil.value = until.value;
    uni.showToast({ title: t("store.noticeSaved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="store.noticeTitle" :denied="!merchant.can('biz:store')">
    <view class="sh-card">
      <textarea
        v-model="text"
        class="field__area"
        :placeholder="$t('store.announcementPh')"
        maxlength="60"
      />

      <view class="ttl">
        <text
          v-for="o in TTL_OPTIONS"
          :key="o.key"
          class="ttl__i"
          :class="{ 'is-on': ttlKey === o.key }"
          @tap="pickTtl(o.key)"
        >{{ $t(`store.ttl.${o.key}`) }}</text>
        <text v-if="untilText" class="ttl__at">{{ untilText }}</text>
      </view>

      <view class="sh-btn go" :class="{ 'is-off': !dirty || saving }" @tap="publish">
        {{ saving ? "…" : $t("store.noticePublish") }}
      </view>
    </view>

    <!-- 常用：店主的公告是在几句话之间轮换，不是每次都写新的。点一下换上，再点发布 -->
    <view v-if="recent.length" class="sh-card mt">
      <text class="field__label">{{ $t("store.noticeRecent") }}</text>
      <view class="recent">
        <text v-for="(r, i) in recent" :key="i" class="recent__i" @tap="text = r">{{ r }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  margin-top: 24rpx;
}
/* 有效期三档：分段小胶囊，选中靠主色底 —— 不做成按钮，它是「同一件事的三种时长」 */
.ttl {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
}
.ttl__i {
  padding: 10rpx 20rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 24rpx;
}
.ttl__i.is-on {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.ttl__at {
  margin-left: auto;
  font-size: 24rpx;
  color: var(--sh-sub);
}
/* 发布：没有改动时灰着 —— 按下去什么都不会发生的按钮不该长得能按 */
.go {
  margin-top: 32rpx;
}
.go.is-off {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
.field__label {
  display: block;
  font-size: 26rpx;
  color: var(--sh-sub);
  margin-bottom: 12rpx;
}
/* 常用是内容不是标签：不截断、允许换行 */
.recent {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.recent__i {
  padding: 16rpx 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  font-size: 26rpx;
  color: var(--sh-ink);
  line-height: 1.5;
}
</style>
