// 商品编辑页的**商品图**：封面 + 轮播合成一组「商品图」，外加详情长图。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么单独一个文件
// ─────────────────────────────────────────────────────────────────────────────
// 与 `price-rows.ts` 同一次拆分：`goods-edit/index.vue` 3869 行里，
// 图片这一块自成一体 —— 选图、上传、去重、排序、删除、识别，
// 只有「识别之后往表单里填什么」需要页面参与，所以那一步用回调传进来。
//
// **搬过来的实现一个字没改。**
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { MAX_IMAGE_BYTES, pickImages } from "@shared/ports/media";
import { pick } from "@ai-shop/ui/prompt";
import type { GoodsGuess } from "@/api/contract";

/** 一组图的上限 = 封面 1 + 轮播 6。存的时候两个字段各自的上限没变 */
const PHOTO_LIMIT = 7;

/**
 * 商品图与详情图的全部状态与动作。
 *
 * @param onGuess 识别出结果之后做什么（往标题/类目里填）——
 *                那一步要动表单里别的字段，属于页面的事，不属于这里
 */
export function useGoodsPhotos(onGuess: (guess: GoodsGuess) => Promise<void>) {
  const { t } = useI18n();
  // ── 二、商品图（状态） ──────────────────────────────────────────────────────
  //    cover 与 images 存储上分两个字段，界面上合成一组
  /**
   * 商品主图。拍一张就有，替掉 emoji 占位（E9）。
   *
   * <p>**存储上仍与轮播分开**（契约 `cover` / `images` 两个字段没动），
   * 但界面上已经合成一组「商品图」—— 见 `photos`。
   */
  const cover = ref("");
  /** 详情轮播图。与封面分开：封面进列表卡片，这些进详情页的轮播 */
  const images = ref<string[]>([]);

  /**
   * 界面上的「商品图」：**主图就是第一张**。
   *
   * <p>此前主图与轮播是两个控件，于是商家每传一张图都要先回答
   * 「这张算主图还是轮播」—— 那个问题来自数据表（`cover` 与 `images` 是两列），
   * 不来自他要做的事：他心里只有「这个商品长什么样」。
   * 合并之后第一张即封面，是电商 App 的通行约定，不用教。
   *
   * <p>只合并**界面**：存的时候照旧拆回两个字段，后端与 C 端零改动。
   */
  const photos = computed(() =>
    cover.value
      // **去重**：第一张上传时既写进 cover 又留在 images（存储照旧，C 端轮播里
      // 本来就该有封面那一张）。不去重的话，传一张图界面上冒出两个一模一样的格子。
      ? [cover.value, ...images.value.filter((x) => x !== cover.value)]
      : [...images.value],
  );

  /**
   * 详情图：图文详情正文**下方**按顺序全宽竖排的长图。
   *
   * <p>与轮播图分成两组存、两组传：轮播是顶部可左右滑的方图，详情图是 1:3 的长图。
   * 混在一个数组里的话，端上只能靠宽高比猜哪几张该轮播 —— 猜错就是一张长图
   * 被塞进方形轮播里，而这件事没有任何一处会报错。
   */
  const detailImages = ref<string[]>([]);
  const uploading = ref(false);

  async function addImages() {
    if (uploading.value) return;
    // 余量按**合并后的总数**算：界面上是一组，弹「最多 6 张」而格子有 7 个说不通
    const room = PHOTO_LIMIT - photos.value.length;
    if (room <= 0) {
      uni.showToast({ title: t("goods.imageLimit", { n: PHOTO_LIMIT }), icon: "none" });
      return;
    }
    let picked;
    try {
      picked = await pickImages(room, ["album", "camera"]);
    } catch {
      return; // 用户取消，不是错误
    }
    // 与封面同一道端上闸：超限的图走完整个上传才被服务端拒，那几秒是白等的
    const tooBig = picked.find((p) => p.size > MAX_IMAGE_BYTES);
    if (tooBig) {
      uni.showToast({ title: t("goods.imageTooLarge"), icon: "none" });
      return;
    }
    uploading.value = true;
    try {
      for (const img of picked) {
        const { url } = await api.mUploadImage(img.tempPath);
        images.value = [...images.value, url];
        /*
         * 主图还空着就用第一张详情图补上，并顺手识别。
         *
         * 此前详情图这条路**完全不识别**：从相册一次选好几张详情图的人，
         * 拿不到任何自动填写，还得回头再拍一次主图。
         * 只在主图为空时做，且只做第一张 —— 否则每张都识别一遍，
         * 会连弹好几个提示，还可能互相覆盖。
         */
        if (!cover.value) {
          cover.value = url;
          await recognizeInto(url);
        }
      }
    } catch (e) {
      uni.showToast({ title: (e as Error).message, icon: "none" });
    } finally {
      uploading.value = false;
    }
  }

  /**
   * 删一张商品图。**删掉第一张时下一张顶上来当封面** ——
   * 不能留下「有图但没封面」的状态：那样列表页会退回 emoji 占位，
   * 而商家明明看见这个商品有四张图。
   */
  function removePhoto(i: number) {
    const list = photos.value;
    const url = list[i];
    if (!url) return;
    // 按 url 删，不按下标 —— photos 是去重后的视图，下标与 images 的下标对不上
    images.value = images.value.filter((x) => x !== url);
    if (i === 0) cover.value = list[1] ?? "";
  }

  /**
   * 把第 i 张设为主图（与当前封面对调）。
   *
   * <p>**没做拖拽**：uni 的可拖网格要靠 movable-view 重写整块，
   * 而商家在这里真正要做的只有一件事 —— 换封面。对调一步到位，
   * 也不会把「顺序」这件他并不关心的事塞给他。
   */
  function setCoverAt(i: number) {
    const picked = photos.value[i];
    if (!picked || i <= 0) return;
    // 只改封面指针。images 原样不动 —— 它是 C 端轮播的顺序，
    // 换个封面不该顺带把轮播重排一遍
    cover.value = picked;
  }

  /** 点非首张的图：只给「设为主图」一件事，删除仍走格子右上角的 ✕ */
  async function tapPhoto(i: number) {
    if (i <= 0) return;
    if ((await pick({ items: [String(t("goods.setCover"))] })) === 0) setCoverAt(i);
  }

  /** 详情图上限。比轮播多：长图是「参数页 / 实拍页 / 售后页」这么一张张摞上去的 */
  const DETAIL_IMAGE_LIMIT = 10;

  async function addDetailImages() {
    if (uploading.value) return;
    const room = DETAIL_IMAGE_LIMIT - detailImages.value.length;
    if (room <= 0) {
      uni.showToast({ title: t("goods.imageLimit", { n: DETAIL_IMAGE_LIMIT }), icon: "none" });
      return;
    }
    let picked;
    try {
      picked = await pickImages(room, ["album", "camera"]);
    } catch {
      return; // 用户取消，不是错误
    }
    const tooBig = picked.find((img) => img.size > MAX_IMAGE_BYTES);
    if (tooBig) {
      uni.showToast({ title: t("goods.imageTooLarge"), icon: "none" });
      return;
    }
    uploading.value = true;
    try {
      for (const img of picked) {
        const { url } = await api.mUploadImage(img.tempPath);
        detailImages.value = [...detailImages.value, url];
      }
    } catch (e) {
      uni.showToast({ title: (e as Error).message, icon: "none" });
    } finally {
      uploading.value = false;
    }
  }

  function removeDetailImage(i: number) {
    detailImages.value = detailImages.value.filter((_, idx) => idx !== i);
  }

  /**
   * 详情图**换顺序**。长图是有次序的（封面页 → 参数页 → 售后页），
   * 传错了只能全删重传就太贵了。
   *
   * <p>用两个箭头而不是长按拖拽：拖拽在 uni 的三端各有各的手势冲突
   * （小程序里 movable-view 与页面滚动打架），而这里最多 10 张、
   * 实际多半 2–3 张 —— 点两下就到位。
   */
  function moveDetailImage(i: number, delta: number) {
    const to = i + delta;
    const list = [...detailImages.value];
    if (to < 0 || to >= list.length) return;
    const [row] = list.splice(i, 1);
    if (!row) return;
    list.splice(to, 0, row);
    detailImages.value = list;
  }

  /**
   * 看图填字段。**填不进去的一律变成候选，不丢弃。**
   *
   * 此前有两条静默丢弃的路径，店主都看不到识别到了什么：
   *   · `confidence < 0.6` → 只弹一句「未能识别」就返回，其实模型给了结果
   *   · 目标字段已有值 → 跳过，于是「先手打了标题再拍照」的人永远见不到识别出的类目
   *
   * 现在两种情况都进 `suggest`，在主图下面显示成一行可采用/可忽略的提示 ——
   * 识别结果从「要么替你填、要么消失」变成「永远看得见，填不填你定」。
   */
  async function recognizeInto(url: string) {
    const guess = await api.mRecognizeGoods(url).catch(() => null);
    if (!guess) return;
    await onGuess(guess);
  }

  return {
    cover, images, photos, detailImages, uploading, PHOTO_LIMIT, DETAIL_IMAGE_LIMIT,
    addImages, removePhoto, setCoverAt, tapPhoto,
    addDetailImages, removeDetailImage, moveDetailImage, recognizeInto,
  };
}
