<script setup lang="ts">
/*
 * 分享海报（TDD-C 端裂变与商家招募 §3.2 B3 / §7.3）。
 *
 * **为什么要它**：朋友圈只吃图片。没有海报，社区场景里最大的那个入口（朋友圈与群聊里
 * 转图）就完全走不了 —— 而全仓此前 canvas 零命中，这一条路一直是断的。
 *
 * **码是店铺码，不带邀请人**（§7.3 拍板）：`wxacode.getUnlimited` 生成的是永久码且
 * 每个 appid 总量有限，所以店铺码是一店一码、生成一次落库复用。把 inviterNo 编进 scene
 * 等于「每个用户一张永久码」，用户一多就烧穿额度，而烧穿之后新入驻的商家再也拿不到码。
 * 所以海报承担「朋友圈曝光 → 进店」，归因到店；邀请归因走小程序内转发那条路。
 *
 * **降级是主路径的一部分**，不是异常处理：
 * · 拿不到码 → 画一张不带码的海报（店名与价格仍然有用）；
 * · 画不出来 → 明说「生成失败」并给关闭，不留一个转圈的壳。
 */
import { getCurrentInstance, nextTick, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money } from "@shared/utils/format";
import { thumb } from "@shared/utils/media-thumb";
import type { Goods } from "@shared/types";
import { canNativeShare, showShareImage } from "@shared/ports/share";

/**
 * 两种海报：**商品**（商品图 · 名称 · 价格 · 门店名 · 门店码）与**门店**（门户里生成：店名 · 公告 · 门店码）。
 *
 * <p>`store` 给了就画门店名、用<b>门店码</b>（TDD-C端门店化与门店门户 s09）：此前画的是主体名、
 * 码是主体的店铺码 —— 同一主体四家店的海报扫出来都进默认店。没给（旧调用方）退回主体名与主体码。
 */
const props = defineProps<{
  goods?: Goods;
  store?: { storeNo: string; storeName: string; announcement?: string };
}>();

/** 店码接口回的门店名 —— 商品页只知道门店号，名字从这里来 */
let codeStoreName = "";
/** 海报上写的店名：门店名优先 */
const shopName = () => props.store?.storeName || codeStoreName || props.goods?.merchant?.name || "";

const { t } = useI18n();
/*
 * **canvas 的两个 API 在自定义组件里都要传组件实例**，否则按 canvas-id 找不到节点：
 * 表现正是「面板开着、一直停在正在生成」—— 不报错，回调永远不来。
 * 这是 canvas 放进组件里最常见的坑。
 */
const instance = getCurrentInstance();

const visible = ref(false);
/** 画好的图：小程序是临时文件路径，H5 是 dataURL。空 = 还没画出来 */
const imagePath = ref("");
const drawing = ref(false);
const failed = ref(false);

/** 画布尺寸（px）。3:4 竖版 —— 朋友圈里竖图占的视觉面积最大 */
const W = 300;
const H = 400;

async function open() {
  visible.value = true;
  if (imagePath.value || drawing.value) return;
  drawing.value = true;
  failed.value = false;
  // 画布要先渲染出来才能取到上下文
  await nextTick();
  try {
    await draw();
  } catch {
    failed.value = true;
  } finally {
    drawing.value = false;
  }
}

function close() {
  visible.value = false;
}

/**
 * 画。**顺序即层次**：白底 → 商品图 → 店名 → 标题 → 价格 → 码 → 一行提示。
 *
 * <p>两端用两套上下文，**不是偷懒**：uni 的 H5 CanvasContext 是指令队列
 * （`setFillStyle`/`fillText` 只入队，要 `draw()` 才执行），而那个 `draw` 的回调
 * 在自定义组件里实测不回来 —— 于是要么永远停在「正在生成」，要么等超时后导出一张
 * **只有白底**的图（文字与图片还在队列里）。H5 用原生 2d 上下文是同步的，画完即所得。
 *
 * <p>小程序那一侧仍走 uni 的 API：那是它唯一的画法。
 */
async function draw() {
  // #ifdef H5
  await drawH5();
  // #endif
  // #ifndef H5
  await drawMp();
  // #endif
}

/**
 * H5：**自己建一张离屏画布**，不碰 uni 的 `<canvas>` 组件。
 *
 * <p>实测：往 uni 那张画布上用原生 2d 画，导出来仍然是全白 ——
 * 组件内部会按自己的指令队列重绘，把外面画的东西盖掉。而它自己的 `draw()` 回调
 * 在自定义组件里又不回来。两头堵死，所以干脆不跟它抢：
 * `document.createElement("canvas")` 没有任何第三方介入，画完即所得。
 */
async function drawH5() {
  const el = document.createElement("canvas");
  const dpr = 2;
  el.width = W * dpr;
  el.height = H * dpr;
  const ctx = el.getContext("2d");
  if (!ctx) throw new Error("no 2d context");
  ctx.scale(dpr, dpr);

  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, W, H);

  // 先取码：店名可能要从码的接口里拿（商品页只知道门店号）
  const acode = await acodeImage();
  const g = props.goods;
  if (g) {
    const cover = g.cover ? thumb(g.cover, 750) : "";
    const img = await loadImage(cover);
    if (img) {
      ctx.drawImage(img, 0, 0, W, 220);
    }

    ctx.fillStyle = "#8a8f99";
    ctx.font = "12px sans-serif";
    ctx.fillText(cut(shopName(), 18), 16, 248);

    ctx.fillStyle = "#1a1a1a";
    ctx.font = "bold 16px sans-serif";
    ctx.fillText(cut(g.title, 14), 16, 274);

    ctx.fillStyle = "#e4393c";
    ctx.font = "bold 22px sans-serif";
    ctx.fillText(money(g.price), 16, 308);
  } else {
    // 门店海报：浅色头图 + 大号店名，下一行是公告（没有就不写）
    ctx.fillStyle = "#e8f5ef";
    ctx.fillRect(0, 0, W, 220);
    ctx.fillStyle = "#1a1a1a";
    ctx.font = "bold 22px sans-serif";
    ctx.fillText(cut(shopName(), 12), 16, 124);
    if (props.store?.announcement) {
      ctx.fillStyle = "#5c6370";
      ctx.font = "13px sans-serif";
      ctx.fillText(cut(props.store.announcement, 20), 16, 256);
    }
  }

  const acodeImg = acode ? await loadImage(acode) : null;
  if (acodeImg) {
    ctx.drawImage(acodeImg, W - 96, H - 108, 80, 80);
  }

  ctx.fillStyle = "#8a8f99";
  ctx.font = "11px sans-serif";
  ctx.fillText(String(t("poster.scanTip")), 16, H - 40);

  imagePath.value = el.toDataURL("image/png");
}

/**
 * H5 的图片加载。**画不出来就返回 null** —— 少一张图的海报仍然是一张能发出去的海报。
 *
 * <p>`crossOrigin` 要设：不设的话画上去会污染画布，`toDataURL` 直接抛
 * SecurityError，整张海报连白底都导不出来 —— 而那时报的错与图片毫无关系。
 */
function loadImage(src: string): Promise<HTMLImageElement | null> {
  if (!src) return Promise.resolve(null);
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

/** 小程序：uni 的指令队列 + draw 回调 */
async function drawMp() {
  const ctx = uni.createCanvasContext("sh-poster", instance?.proxy);
  ctx.setFillStyle("#ffffff");
  ctx.fillRect(0, 0, W, H);

  const acode = await acodeImage();
  const g = props.goods;
  if (g) {
    const cover = g.cover ? thumb(g.cover, 750) : "";
    const coverPath = await localPath(cover);
    if (coverPath) {
      ctx.drawImage(coverPath, 0, 0, W, 220);
    }

    ctx.setFillStyle("#8a8f99");
    ctx.setFontSize(12);
    ctx.fillText(cut(shopName(), 18), 16, 248);

    ctx.setFillStyle("#1a1a1a");
    ctx.setFontSize(16);
    ctx.fillText(cut(g.title, 14), 16, 274);

    ctx.setFillStyle("#e4393c");
    ctx.setFontSize(22);
    ctx.fillText(money(g.price), 16, 308);
  } else {
    ctx.setFillStyle("#e8f5ef");
    ctx.fillRect(0, 0, W, 220);
    ctx.setFillStyle("#1a1a1a");
    ctx.setFontSize(22);
    ctx.fillText(cut(shopName(), 12), 16, 124);
    if (props.store?.announcement) {
      ctx.setFillStyle("#5c6370");
      ctx.setFontSize(13);
      ctx.fillText(cut(props.store.announcement, 20), 16, 256);
    }
  }

  if (acode) {
    ctx.drawImage(acode, W - 96, H - 108, 80, 80);
  }

  ctx.setFillStyle("#8a8f99");
  ctx.setFontSize(11);
  ctx.fillText(String(t("poster.scanTip")), 16, H - 40);

  await new Promise<void>((resolve) => ctx.draw(false, () => resolve()));
  imagePath.value = await toImage();
}

/** 截断长文案。海报上一行放不下就省略，**不换行** —— 换行会把下面的价格顶下去 */
function cut(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

/** 取商品图的本地路径。小程序的 drawImage 不吃网络地址，要先下载 */
async function localPath(src: string): Promise<string> {
  if (!src) return "";
  // #ifdef H5
  return src;
  // #endif
  // #ifndef H5
  try {
    const info = await uni.getImageInfo({ src });
    return (info as unknown as { path?: string })?.path ?? "";
  } catch {
    return "";
  }
  // #endif
}

/**
 * 店铺码。后端给的是 base64（不含 data: 前缀）。
 *
 * <p>H5 直接拼成 data URL 就能画；小程序的 drawImage 不吃 data URL，
 * 要先写成临时文件 —— 这一步失败就返回空，海报少一个码而已。
 */
async function acodeImage(): Promise<string> {
  const storeNo = props.store?.storeNo;
  const merchantNo = props.goods?.merchant?.merchantNo;
  let base64 = "";
  try {
    // 门店码优先：扫出来进的是这一家店；没有门店号（旧调用方）才退回主体码
    if (storeNo) {
      const r = await api.storeAcode(storeNo);
      codeStoreName = r?.storeName ?? "";
      base64 = r?.imageBase64 ?? "";
    }
    else if (merchantNo) base64 = (await api.merchantAcode(merchantNo))?.imageBase64 ?? "";
  } catch {
    return "";
  }
  if (!base64) return "";
  // #ifdef H5
  return `data:image/png;base64,${base64}`;
  // #endif
  // #ifndef H5
  try {
    const fs = uni.getFileSystemManager();
    // USER_DATA_PATH 在 uni 的类型里没声明，但小程序运行时有；取不到就走 catch
    const file = `${(uni as unknown as { env?: { USER_DATA_PATH?: string } }).env?.USER_DATA_PATH}/sh-acode.png`;
    fs.writeFileSync(file, base64, "base64");
    return file;
  } catch {
    return "";
  }
  // #endif
}

/** 导出（小程序）。H5 在 drawH5 里已经直接 toDataURL 了 */
function toImage(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.canvasToTempFilePath({
      canvasId: "sh-poster",
      success: (r) => resolve(r.tempFilePath),
      fail: () => reject(new Error("canvasToTempFilePath failed")),
    }, instance?.proxy);
  });
}

/**
 * 保存。小程序存相册（要相册授权），H5 存不了 —— 提示长按图片保存。
 *
 * <p>H5 那条不是偷懒：浏览器里没有「写相册」这个能力，
 * 而长按保存是每个人都会的动作，比一个点了没反应的按钮诚实。
 */
function save() {
  // #ifdef H5
  uni.showToast({ title: t("poster.longPress"), icon: "none" });
  // #endif
  // #ifndef H5
  uni.saveImageToPhotosAlbum({
    filePath: imagePath.value,
    success: () => uni.showToast({ title: t("poster.saved"), icon: "none" }),
    fail: () => uni.showToast({ title: t("poster.saveFailed"), icon: "none" }),
  });
  // #endif
}

/**
 * 分享到朋友圈（TDD-C端朋友圈分享修补 AC1）。小程序弹微信原生的图片分享菜单：
 * 发送给朋友 / 分享到朋友圈 / 收藏 / 保存 —— 面板上写着「发朋友圈」，此前实际只能存相册。
 *
 * <p>老版本微信没有 `showShareImageMenu` 时退回保存。**用户在菜单里点取消不是失败**，
 * 不能因此再替他存一张图。
 */
function shareToMoments() {
  showShareImage(imagePath.value, save);
}

/** 小程序才有原生图片分享菜单；H5 只能长按保存 */
const canShareImage = canNativeShare();

defineExpose({ open });
</script>

<template>
  <sh-sheet :visible="visible" :title="String($t('poster.title'))" @close="close">
    <!-- 画布本身不给用户看：它只是用来出图的。离屏放置而不是 v-if，
         因为 createCanvasContext 要求节点真的在文档里 -->
    <canvas canvas-id="sh-poster" class="poster__canvas" :style="{ width: W + 'px', height: H + 'px' }"></canvas>

    <view v-if="drawing" class="poster__state sh-center">
      <text class="txt-sub sh-muted">{{ $t("poster.drawing") }}</text>
    </view>
    <view v-else-if="failed" class="poster__state sh-center">
      <text class="txt-sub sh-muted">{{ $t("poster.failed") }}</text>
    </view>
    <image v-else-if="imagePath" class="poster__img" :src="imagePath" mode="widthFix" show-menu-by-longpress />

    <template v-if="imagePath">
      <view v-if="canShareImage" class="sh-btn poster__save" @tap="shareToMoments">
        {{ $t("poster.toMoments") }}
      </view>
      <view class="sh-btn poster__save" :class="{ 'sh-btn--soft': canShareImage }" @tap="save">
        {{ $t("poster.save") }}
      </view>
    </template>
  </sh-sheet>
</template>

<style scoped>
/* 画布离屏：用户看到的是画完导出的那张图，不是画的过程 */
.poster__canvas {
  position: fixed;
  top: -9999px;
  left: -9999px;
}
.poster__state {
  min-height: 400rpx;
}
.poster__img {
  width: 100%;
  border-radius: 16rpx;
}
.poster__save {
  margin-top: 24rpx;
}
</style>
