<script setup lang="ts">
// 空态。
//
// 抽出来的理由不是「代码重复」，是**观感会漂移**：这套壳原本在 27 个页面里各写一份
//（B 端 13、C 端 14），结构完全相同，只有 padding 在 60/80/100rpx 之间随手取值，
// 于是同一个 App 里空态高矮不一。写法一样却各存一份的东西，迟早各走各的。
//
// 只收一个文案：图标与插画留到有真实素材时再说 —— 现在放 emoji 会跟着系统字形变，
// 且与「扁平色块」的设计语言冲突。
// 两种形态（都来自现有页面的真实用法，不是我加的花样）：
//   默认  —— 独占一屏的空列表，带卡片底色
//   bare  —— 嵌在某个分组/卡片**内部**的空态，只有一行灰字，再套一层底色会出现「卡中卡」
// **两种空态是同一件事，此前当成了两件。**
// 除了这一行灰字，全站还有 7 处「引导型空态」——说明 + 可选的补充 + 一个按钮，
// 居中、大留白（`cards` / `merchants` / `orders`×2 / `cart` / `order-confirm` /
// `community`）。它们各写一份，名字有 `.empty` 与 `.state` 两种，留白四种
// （72 24 / 80 40 / 96 48 / 120 40），`community` 连按钮都自己画了一个。
// 判据当年撤掉「空态」那一条时的理由是「那不是 sh-empty 那种一行灰字」——
// 说得对，但结论应该是**把这一档补进来**，不是让它在外面各长各的。
//
// ── 三种「没东西可看」是三件事 ────────────────────────────────
//
// 一个拉数据的页面有四种态：加载中 / 有数据 / 确定为空 / 出错。
// 而 2026-09-08 逐页量下来：**88 页拉数据，只有 6 页区分了出错与空**，
// 机制就在这一行 —— 全仓 `.catch(() => [])` 55 处、`.catch(() => null)` 41 处：
// **代码在主动把失败变成「空」**。不是忘了处理，是处理成了另一件事。
//
// 后果：网络不通时，「暂无收货地址」和「你确实还没填过地址」长得一模一样。
// 前者该给一个「重试」，后者该给一个「去添加」——给反了比不给更糟。
//
// 所以这个件收三种态，而不是让 88 个页面各写一遍：
//   pending  还不知道 → **什么都不渲染**（不是转圈：这一档留给骨架屏，眼下不做）
//   failed   没取到   → 「没能加载出来」+ 一句原因 + 重试
//   都不是   确定为空 → 调用点给的 text / tip / #action
//
// 文案走 `common.loadFailed` / `loadFailedTip` / `retry` —— **词条本来就在**
//（c 端三语齐全，b 端 2026-09-08 补齐），只是没人用。
withDefaults(
  defineProps<{
    text?: string;
    /** 补充一句：为什么空、下一步能做什么。只有一句话时不必给 */
    tip?: string;
    compact?: boolean;
    bare?: boolean;
    /** 首屏还没到过 —— 整个件不渲染，避免「先闪一下暂无」 */
    pending?: boolean;
    /** 这次没取到（与「确定为空」是两件事） */
    failed?: boolean;
    /**
     * 出错那一行的文案。**留给「说清什么没加载出来」** ——
     * `community` 手写这一形状时用的是「没能加载附近的自提点」，
     * 比通用的「没能加载出来」有用得多。不传就用通用的。
     * 下面那句原因（多半是网络不通）与「重试」两个字不留口子：
     * 它们在任何一页上都是同一句话，各写一份只会各自漂。
     */
    failedText?: string;
  }>(),
  { text: "", tip: "", compact: false, bare: false, pending: false, failed: false, failedText: "" },
);

// 重试由调用点决定重新拉什么 —— 这里只负责那颗按钮长什么样、摆在哪
defineEmits<{ retry: [] }>();
</script>

<template>
  <!-- pending 时整个不渲染：见 script 里那段，「还不知道」不该长成「确定没有」 -->
  <view
    v-if="!pending"
    class="empty"
    :class="{ 'sh-card': !bare, 'is-compact': compact, 'is-bare': bare }"
  >
    <template v-if="failed">
      <text class="sh-muted">{{ failedText || $t("common.loadFailed") }}</text>
      <text class="sh-hint txt-quiet">{{ $t("common.loadFailedTip") }}</text>
      <view class="empty__act">
        <!-- 与现有 5 处引导型空态同款（`sh-btn sh-btn--sm`）。
             想过用 `--soft` 压一档，但出错时重试是**这一屏唯一能做的事**，
             它就是主操作；而与别处不同的按钮只会让人多想一下。 -->
        <view class="sh-btn sh-btn--sm" @tap="$emit('retry')">
          {{ $t("common.retry") }}
        </view>
      </view>
    </template>
    <template v-else>
      <text class="sh-muted"><slot>{{ text }}</slot></text>
      <text v-if="tip" class="sh-hint txt-quiet">{{ tip }}</text>
      <!-- 引导型空态的那个按钮。**具名插槽而不是 props**：动作是什么、叫什么、
           点了去哪，都是调用点的事；这里只负责它与上面那行字的距离。 -->
      <view v-if="$slots.action" class="empty__act"><slot name="action"></slot></view>
    </template>
  </view>
</template>

<style scoped>
/* 空态的上下留白。**这个数原本是随手取的**（见下方 is-compact/is-bare 的来历），
   而它出现在一个「本来就没东西可看」的状态里，留白越大越像页面坏了。
   走变量：默认保持 C 端原样，B 端调紧。 */
.empty {
  text-align: center;
  padding: var(--sh-pad-empty, 72rpx) 24rpx;
}
/*
 * 页内小块的空态（某个分组下暂无内容、或嵌在卡片里），不必占满一屏。
 *
 * **两种形态取同一个内边距**：它们的区别在「有没有卡片底」，不在松紧 ——
 * 此前一个 40rpx 一个 60rpx，是两次各自随手取的。
 *
 * 走 `--sh-pad-empty` 的一半而不是再写死一个数：那个变量是**两端的密度旋钮**
 *（C 端 72、B 端 48），而此前只有默认形态跟着它走 —— 旋钮拧一下，
 * 三种形态里两种纹丝不动。现在 C 端 36 / B 端 24，都还在 4rpx 网格上。
 */
.empty.is-compact,
.empty.is-bare {
  padding: calc(var(--sh-pad-empty, 72rpx) / 2) 24rpx;
}
/* 动作与说明之间的距离。**只在这儿定一次** —— 此前七处各给各的
   （0 / 24 / 28 / 32rpx），于是同一种空态在不同页面上按钮高低不一 */
.empty__act {
  margin-top: 28rpx;
}
</style>
