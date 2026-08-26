<script setup lang="ts">
// 数字格：几个大数一排，每个下面一句小标签。
//
// **五个页面各写了一份**（2026-08-26 扫出来）：`coupon-issues` 与 `member-reach`
// 的 `.trio` **逐字节相同**、`customers` 的 `.quad`、`sku-identity` 的 `.nums`、
// `activities` 的 `.effect`。
//
// **档位是先定后收的**，不是收的时候顺手挑的：五份里出现过 44/700、40/700、
// 40/600、32/600 四种。判据来自字阶而不是投票 —— 「700 只给价格」，
// 而这些是发放数 / 触达数 / 会员数 / 命中数 / 核销数，一个都不是价格；
// 44 与 32 也不在七档上。**40rpx / 600**，标签 24rpx。
//
// **语义色收成四档**，页面不再各写各的：
//   ok(success) / warn(warning) / bad(danger) / primary(primary-text)
// `customers` 原本用 `--sh-primary` 给「沉睡」上色 —— 主色当文字色压在页面底上
// 不足 AA（design-tokens 守卫点名的那一类），这里改走 `--sh-primary-text`，
// 红还是那个红，只是深一档、读得清。
//
// `boxed` 是**可点筛选**那一种（`customers` 的四层会员数即入口）：格子带底色，
// 选中换成主色 tint。不给 `boxed` 就是纯展示，没有底色也不响应点击 ——
// **点了没反应的控件比没有控件更糟**，所以这两件事由同一个开关管。
//
// gap 取 12rpx：五页本来就一致。它不在间距档（8/16/28/40/64）上，
// 但库里 `sh-tabs` / `sh-uploader` 的 gap 也是 12 —— **间距档目前没有守卫**，
// 这件事记在覆盖清单的待决里，不在这个组件里单独解决。
export interface StatItem {
  /** 可点时回传的标识；纯展示可以不给 */
  key?: string;
  value: string | number;
  label: string;
  /** 语义色。不给＝墨色 */
  tone?: "ok" | "warn" | "bad" | "primary";
}

const props = withDefaults(
  defineProps<{
    items: readonly StatItem[];
    /** 每格带底色且可点（筛选入口）。默认纯展示 */
    boxed?: boolean;
    /** boxed 时选中的那一格（对 item.key） */
    active?: string;
  }>(),
  { boxed: false, active: "" },
);

const emit = defineEmits<{ (e: "pick", key: string): void }>();

function tap(it: StatItem) {
  if (!props.boxed || !it.key) return;
  emit("pick", it.key);
}
</script>

<template>
  <view class="st" :style="{ gridTemplateColumns: `repeat(${items.length}, 1fr)` }">
    <view
      v-for="(it, i) in items"
      :key="it.key || i"
      class="st__i"
      :class="{ 'st__i--box': boxed, 'is-on': boxed && !!it.key && active === it.key }"
      @tap="tap(it)"
    >
      <text class="st__n sh-num" :class="it.tone ? `st__n--${it.tone}` : ''">{{ it.value }}</text>
      <text class="st__l">{{ it.label }}</text>
    </view>
  </view>
</template>

<style scoped>
.st {
  display: grid;
  gap: 12rpx;
}
.st__i {
  text-align: center;
}
/* 可点那一种：格子要有「能点」的样子 */
.st__i--box {
  background: var(--sh-surface);
  border-radius: 24rpx;
  padding: 20rpx 8rpx;
}
.st__i--box.is-on {
  background: var(--sh-primary-tint);
}
.st__n {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  line-height: 1.2;
  color: var(--sh-ink);
}
.st__n--ok {
  color: var(--sh-success);
}
.st__n--warn {
  color: var(--sh-warning);
}
.st__n--bad {
  color: var(--sh-danger);
}
/* 主色当文字用走 primary-text（深一档）—— 主色是为「压白字的按钮底」调的，
   压在页面底上不足 AA。没有任何症状，只是弱视用户读不清 */
.st__n--primary {
  color: var(--sh-primary-text);
}
.st__l {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
</style>
