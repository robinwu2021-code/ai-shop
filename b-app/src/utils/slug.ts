import { pinyin } from "pinyin-pro";

/**
 * 门店代码的建议值：按店名生成，中文转拼音（V357，TDD-店铺码与分享 §3.6）。
 *
 * **这只是一个建议**，店主可以改也可以清掉 —— 代码会出现在对外链接
 * `hxmall.top/s/<代码>` 与他的名片上，不该由程序替他定。
 *
 * **为什么拼音在端上转**：后端的 maven 一律离线跑，而 `~/.m2` 里没有任何拼音库
 * （pinyin4j / TinyPinyin 都没有），加这个依赖装不上。端上 npm 能装，
 * 而且「给个建议让人改」本来就该在前端。
 *
 * **为什么只在 b-app 装 pinyin-pro**：字典有 1MB 左右。B 端是 App（包几十 MB，
 * 多这一点无所谓），而 c-app 要进小程序的 2MB 包 —— 放进 packages/shared
 * 就会连带压到那边。
 */
export function slugSuggest(name: string): string {
  if (!name || !name.trim()) {
    return "";
  }
  /*
   * `nonZh: "consecutive"` 让非中文片段保持整块。
   * 不加它的话 "Hongxuan Store" 会被逐字拆成 "H-o-n-g-x-u-a-n"。
   */
  const raw = pinyin(name, { toneType: "none", type: "array", nonZh: "consecutive" }).join("-");
  const slug = raw
    .toLowerCase()
    // 标点、空格、以及没认出来的字一律当分隔符。`虹选鲜果·福田店` 里那个间隔号走这条
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 32)
    // 截断本身可能又切出一个尾部连字符（`...-fu-` ），后端的格式判据不收首尾连字符
    .replace(/-+$/g, "");
  /*
   * 短于 3 位就返回空，让店主自己填 —— 后端下界是 3。
   * 返一个通不过校验的建议，等于让他点一次保存才知道不行。
   */
  return slug.length >= 3 ? slug : "";
}
