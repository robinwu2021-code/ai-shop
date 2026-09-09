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
    /**
     * 单行形态：**给页面里的一小块用**（表单里的标签选择器、可选货、可用券）。
     *
     * 为什么要这一档：那些块拉挂时，整块字段会因为 `v-if="list.length"` 直接消失 ——
     * 商家看到的不是坏掉的选择器，是「这个功能不存在」，他不会想到重试。
     * 但把默认那一档摆进去也不对：**量出来是三行居中 + 一颗大按钮**，
     * 立在本该是一排小 chip 的位置上，读起来像「整页出了大事」。
     * 所以这一档收成一行：一句灰字 + 一个文字链，与字段内容同一个体量。
     */
    line?: boolean;
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
  { text: "", tip: "", compact: false, bare: false, pending: false, failed: false, failedText: "",
    line: false },
);

// 重试由调用点决定重新拉什么 —— 这里只负责那颗按钮长什么样、摆在哪
defineEmits<{ retry: [] }>();
</script>

<template>
  <!-- pending 时整个不渲染：见 script 里那段，「还不知道」不该长成「确定没有」 -->
  <view
    v-if="!pending"
    class="empty"
    :class="{ 'sh-card': !bare && !line, 'is-compact': compact, 'is-bare': bare, 'is-line': line }"
  >
    <!-- 单行形态只做出错这一态：块级的「确定为空」由调用点自己说
         （「还没有标签」这类话是页面特有的，收不进来） -->
    <template v-if="line">
      <text class="sh-hint empty__line-t">{{ failedText || $t("common.loadFailed") }}</text>
      <text class="sh-link" @tap="$emit('retry')">{{ $t("common.retry") }}</text>
    </template>
    <template v-else-if="failed">
      <text class="txt-body">{{ failedText || $t("common.loadFailed") }}</text>
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
      <!-- 有第二行时第一行换 `.txt-body`（28rpx / 墨色），没有就还是 `.sh-muted`。
           **不这么分档等于没拆**：`.sh-muted` 的字号走 `--sh-fs-sub`，而 b 端在
           App.vue 里把它调到了 24rpx —— 正好等于 `.sh-hint`；两者颜色又都是
           `--sh-sub`。于是两行同字号、同色、同字重，读起来是一句折了行的话，
           而拆开的全部意义就是「一眼看出哪句是结论、哪句是下一步」。
           这是量出来的：截图上两行长得一样，computed 才说得清为什么。
           走类不写数 —— 第一版在这儿硬写了 `font-size: 28rpx`，被「件不自己写字号」
           那道闸拦下了，而闸是对的：字阶里本来就有这一档。 -->
      <text :class="tip ? 'txt-body' : 'sh-muted'"><slot>{{ text }}</slot></text>
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
/* 单行形态：左对齐、无留白、与字段内容同高。默认那一档是 72rpx 上下留白
   加居中，摆在表单里会把一个字段撑成一屏的主角 */
.empty.is-line {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
  padding: 8rpx 0;
  text-align: start;
}
.empty__line-t {
  flex: 0 1 auto;
}

.empty__act {
  margin-top: 28rpx;
  /*
   * 按内容宽，不占满一行。
   *
   * `.sh-btn` 是 `display: block` —— 页面底部的主操作本来就该通栏，那是对的。
   * 但它站在一张**本来就没内容**的卡片正中间时，一条通栏的实心主色条把
   * 「这儿没东西」说成了「这儿有件大事要办」。量出来是 303px，与卡片同宽。
   *
   * 已有的 5 处引导型空态（cards / order-confirm / order…）也一直是这个样子，
   * 一起改过来 —— 出错态要把「重试」铺到 65 页上，这条杠会被放大 65 倍。
   */
  display: flex;
  justify-content: center;
}
/*
 * ⚠️ **不要在这里写 `.empty__act > *`**。
 *
 * 插槽内容归**调用点**的作用域：小程序产物里它带的是页面的 `data-v-ecf8f08b`，
 * 而这个件的规则钉的是 `data-v-981c29c1` —— 选择器一次都不匹配。
 * H5 上同理（Vue 的 scoped 对插槽内容也给父作用域 id，要穿透得用 `:slotted()`）。
 * 2026-09-09 先写了一条 `.empty__act > * { flex: 0 0 auto }`，
 * 两端都是死规则 —— 而它看起来在做事：**按钮确实是内容宽的**，
 * 那是上面 `display: flex` 让它成了 flex item、按内容定宽，与那条无关。
 * 量出来才看得见：那颗按钮的 computed `flex` 是 `0 1 auto`（默认值），不是 `0 0 auto`。
 */

</style>
