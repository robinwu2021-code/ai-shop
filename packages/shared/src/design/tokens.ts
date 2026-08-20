// 主题 token「目录」：皮肤(色) × 明暗(风格) 两个正交维度。
// 变量实际值定义在 App.vue 全局样式；UnoCSS 主题色指向 var(--sh-*)（uno.config.ts）。
// 换肤 = 改根节点 data-skin/data-theme（H5/App）或 .sh-root 上的 skin-*/mode-* 类（小程序）
//       → 全局即时生效，零重载。

export type SkinId =
  /** 品牌皮肤（虹选红）。B 端默认；色值真源 brand/spec.html */
  | "brand"
  | "fresh"
  | "promo"
  | "blue"
  | "mono"
  | "crimson"
  | "amber"
  | "teal"
  | "violet";
export type ModeId = "light" | "dark" | "auto";

export interface SkinDef {
  id: SkinId;
  /** 选择器上的预览色（= light 下主色） */
  color: string;
}

/**
 * 皮肤主色**按明暗分别取值**。
 *
 * 为什么不能只给一个值：`mono`（极简黑）在浅色模式是近黑 `#18181B`，
 * 但同一个值放到深色底 `#0C0E12` 上，对比度只有 **1.09** —— 主按钮和背景糊在一起，
 * 等于按钮消失了。这不是审美问题，是功能失效。
 *
 * 其余皮肤同理但没那么极端：浅色模式要压深才能让白字达 WCAG AA（4.5:1），
 * 而压深后的值在深色底上又偏暗，所以深色模式用回更亮的那一档。
 *
 * 所有取值由 `design-tokens.test.ts` 的对比度断言守着 —— 加皮肤时不达标会直接测试失败。
 */
export interface SkinColor {
  /** 浅色模式主色 */
  light: string;
  /** 深色模式主色 */
  dark: string;
}

/**
 * 面感（中性面的色温）。皮肤不只是主色 —— 背景与文字也要配套，
 * 否则暖色主色配冷灰底会「脏」。
 *
 * · `pure`    白底（页面近白 #F5F6F8 + 卡片纯白），清爽、接近微信原生观感
 * · `neutral` 灰白底（默认），卡片浮在浅灰上，层次感强
 * · `warm`    暖白底，配琥珀/赤金这类暖色主色
 * · `cool`    冷白底，配青碧/蓝这类冷色主色
 */
export type SurfaceTone = "pure" | "neutral" | "warm" | "cool";

/** 一套中性面：背景 / 卡片 / 浮层 / 主文字 / 次文字 / 弱色块 / 分隔线 */
export interface Surface {
  bg: string;
  surface: string;
  elev: string;
  ink: string;
  sub: string;
  faint: string;
  line: string;
}

/**
 * 面感色表。**白底方案保留**（`pure`）—— 有些商家就是要「白纸一张」的观感。
 * 深色档统一走近黑，只在色温上跟着倾斜一点点，不做花哨。
 */
export const SURFACES: Record<SurfaceTone, { light: Surface; dark: Surface }> = {
  // ⚠️ 纯白底组的 bg **不是纯白**：页面底与卡片同为 #FFFFFF 时，
  // 卡片、列表行、空态之间没有任何边界，整屏糊成一片白（设计语言不用描边、不用阴影，
  // 分层全靠面色，所以面色一旦相同，层次就彻底没有了）。
  //
  // **两个数由对比度定，不是挑出来的**（design-tokens.test.ts 有断言守着）：
  //
  // 1. `surface ↔ bg ≥ 1.17` —— 取「微信档」：微信是页面 #EDEDED + 卡片白 = 1.171。
  //    此前 #F5F6F8 只有 **1.08**，白卡浮在近白底上等于没有边界，一屏卡片糊成一片
  //    （真机截图上卡片边界要靠猜）。往深里再压会丢掉「白纸一张」的观感，
  //    而微信这个档是国内商家最熟悉的参照，故就此打住。
  // 2. `sub 文字 on surface ≥ 4.5` —— WCAG AA 正文下限。此前 #7B808A 在白底上只有
  //    **3.96，不达标**：次要信息（单号、时间、店名）本就该弱一档，但弱到读不清
  //    就不是层次而是缺陷了。
  //
  // 深色档反过来：底不动，把 surface/elev/faint/line 一起提亮到同样的 1.17。
  pure: {
    light: { bg: "#ECEDEF", surface: "#FFFFFF", elev: "#FFFFFF", ink: "#14161A", sub: "#63676E", faint: "#E4E5E8", line: "#DCDEE2" },
    dark: { bg: "#000000", surface: "#161719", elev: "#1D2023", ink: "#F3F4F6", sub: "#9AA1AC", faint: "#23262B", line: "#2E3036" },
  },
  neutral: {
    light: { bg: "#ECEDEF", surface: "#FFFFFF", elev: "#FFFFFF", ink: "#14161A", sub: "#63676E", faint: "#E7E8EB", line: "#E0E2E5" },
    dark: { bg: "#0C0E12", surface: "#1C1F27", elev: "#242933", ink: "#F3F4F6", sub: "#9AA1AC", faint: "#292E38", line: "#303641" },
  },
  warm: {
    light: { bg: "#EFECE7", surface: "#FFFFFF", elev: "#FFFFFF", ink: "#1A1712", sub: "#71695E", faint: "#E8E4DB", line: "#E1DACE" },
    dark: { bg: "#12100D", surface: "#242019", elev: "#2D2720", ink: "#F5F2EC", sub: "#A79C8B", faint: "#2F2920", line: "#3A3329" },
  },
  cool: {
    light: { bg: "#E9EDF1", surface: "#FFFFFF", elev: "#FFFFFF", ink: "#121619", sub: "#626C75", faint: "#E1E7ED", line: "#D7DFE5" },
    dark: { bg: "#0A0E12", surface: "#182027", elev: "#202933", ink: "#F1F4F6", sub: "#93A0AC", faint: "#212A34", line: "#28343F" },
  },
};

/**
 * 配色分两组，解决的是两种不同诉求：
 *
 * · `pure` **白底组** —— 底色恒为近白（#F5F6F8），各皮肤只换**主色与字色**。
 *   要的是「白纸一张」的干净观感（微信原生就是这个路子：页面近白、卡片纯白），
 *   换皮肤不改变页面的「白」。底色不取纯白是因为卡片也是白的 —— 两者同色就没有边界了。
 * · `full` **整套配色组** —— 底色、字色、主色一起换。
 *   暖色主色配暖白底才不「脏」，要的是成套的氛围。
 *
 * 两组并存，不是二选一：同一个商家可能白天要白底清爽，做活动时要整套暖调。
 */
export type SkinGroup = "pure" | "full";

/** 皮肤 = 分组 + 主色（+ 白底组自带字色） */
export interface SkinPalette extends SkinColor {
  group: SkinGroup;
  tone: SurfaceTone;
  /**
   * 白底组专用：该皮肤的**字色**。
   * 这一组「只改字体颜色」—— 底不变，靠主色与墨色的冷暖区分气质。
   * 整套组不填，字色随面感走。
   */
  ink?: { light: string; dark: string };
  /**
   * 品牌锁定：主色不因对比度指标被改动。
   * `fresh` 是**微信绿**，与微信生态的观感一致性优先于 3:1 的组件边界指标 ——
   * 但压在它上面的**文字仍必须达 4.5:1**（改用墨色即可，不必牺牲绿色本身）。
   */
  brandLocked?: boolean;
  /**
   * **主色当文字用时的那一档**（`--sh-primary-text`）。不填就由生成器算。
   *
   * <p>为什么需要它：主色是为「压白字的按钮底」调的，而同一个红拿去当**文字**
   * 压在页面底上就不够了 —— 实测 `#E1251B` 压 `#ECEDEF` 只有 **4.00**，
   * 而 AA 要 4.5。这件事没有任何症状：颜色看着是对的品牌红，只是弱视用户读不清，
   * 而 B 端满屏都是这种用法（「＋新建商品」「获取验证码」「从相册选」）。
   *
   * <p>**八套皮肤里有六套中招**（blue 3.92 / promo 3.86 / amber 3.87 / teal 3.85 …），
   * 所以它不是品牌的特例，是整套设计系统缺的一档。
   *
   * <p>只有 brand 手填：`#B31710` 是 `brand/spec.html` §01 点名的「深红 Deep」，
   * 品牌书里写死的值优先于算出来的最小值（算出来是 `#D02219`，4.57 —— 刚好过线，
   * 底色再动一点就又掉下去）。其余皮肤没有品牌书，交给生成器按对比度算。
   */
  deep?: { light: string; dark: string };
}

// 名称与说明走 i18n（skin.* / mode.*），此处只放与语言无关的色值
export const SKINS: SkinDef[] = [
  /*
   * 品牌皮肤（虹选红）。排首位 —— 它是**品牌本身**，其余八套是「换肤」这个功能。
   * 色值真源：brand/tokens.json 与 brand/spec.html（主色 #E1251B、深色档 #FF5A4D）。
   * 深色档不是"把主色调亮一点"的美学选择：主色压深底只有 3.72，深色模式下
   * 主按钮会糊进背景；#FF5A4D 压深底 5.66 才够。
   */
  { id: "brand", color: "#E1251B" },
  { id: "fresh", color: "#00B578" },
  { id: "promo", color: "#D24600" },
  { id: "blue", color: "#2C69FF" },
  { id: "mono", color: "#18181B" },
  { id: "crimson", color: "#D6303A" },
  { id: "amber", color: "#A46911" },
  { id: "teal", color: "#008484" },
  { id: "violet", color: "#7A4CE0" },
];

export const MODES: ModeId[] = ["light", "dark", "auto"];

/**
 * 默认皮肤的**兜底值**。各端可以有自己的默认：B 端经 `configureShell({ defaultSkin })`
 * 注入 `brand`（品牌红），C 端不注入就用这里的 `fresh`。
 *
 * <p>为什么不把这个常量直接改成 `brand`：那会把 C 端一起换掉，而 C 端换不换是
 * 另一个决定。默认值是「端的选择」，不是「色板的属性」——放在色板里就没法分端了。
 */
export const DEFAULT_SKIN: SkinId = "fresh";
export const DEFAULT_MODE: ModeId = "light";

/**
 * 原生栏（tabBar / 导航栏）用的色值。
 * 这两个栏由客户端渲染，**不吃 CSS 变量**，只能在换肤时用 uni.setTabBarStyle /
 * uni.setNavigationBarColor 运行时改写 —— 所以同一份色板必须在 JS 侧再暴露一次。
 * 值必须与 App.vue 里的 CSS 变量保持一致（改一处要改两处，由规范测试比对）。
 */
export const SKIN_HEX: Record<SkinId, SkinPalette> = {
  /**
   * brand = **虹选红**。真源 `brand/spec.html` §01，色值与对比度都是实测值：
   * 主色 `#E1251B` 压白字 4.69 ✓ AA；深色档 `#FF5A4D` 压深底 5.66 ✓、压墨字 5.77 ✓。
   *
   * <p><b>两档不可互换</b>（规范里点名的三条分工之一）：主色压深底只有 3.72，
   * 深色模式用它主按钮会糊进背景；而 `#FF5A4D` 压白字只有 3.08，浅底上用它不可读。
   * 判断依据是底色，不是喜好。
   *
   * <p>`ink` 取规范的墨 `#17181A`（白底 17.77）。不 brandLocked ——
   * 品牌红本身就达标，不需要豁免。
   */
  brand: {
    group: "pure", tone: "pure",
    light: "#E1251B", dark: "#FF5A4D",
    ink: { light: "#17181A", dark: "#F3F4F6" },
    // 「深红 Deep」，spec §01 三条分工的第一条：灰底上的红字用它，不用主色
    deep: { light: "#B31710", dark: "#FF5A4D" },
  },
  // ---- 纯白底组：底恒为白，只换主色与字色 ----
  /**
   * fresh = **微信绿 `#00B578`，不压深**。
   * 与微信生态的观感一致性比「白字达标」更重要 —— 而白字不达标这件事
   * 换前景色就解决了：绿底配墨字 6.80，配白字只有 2.66。
   */
  fresh: {
    group: "pure", tone: "pure", brandLocked: true,
    light: "#00B578", dark: "#00B578",
    ink: { light: "#14161A", dark: "#F3F4F6" },
  },
  // mono 两端必须反过来取，否则深色模式下主按钮不可见
  mono: {
    group: "pure", tone: "pure",
    light: "#18181B", dark: "#F2F3F5",
    ink: { light: "#18181B", dark: "#F2F3F5" },
  },
  blue: {
    group: "pure", tone: "pure",
    light: "#2C69FF", dark: "#5B8CFF",
    // 冷墨：字色带一点蓝，与蓝主色同族，白底上更整
    ink: { light: "#121619", dark: "#F1F4F6" },
  },
  crimson: {
    group: "pure", tone: "pure",
    light: "#D6303A", dark: "#E8555E",
    // 暖墨：字色带一点红棕
    ink: { light: "#1A1214", dark: "#F5F0F0" },
  },

  // ---- 整套配色组：底色 + 字色 + 主色一起换 ----
  promo: { group: "full", tone: "warm", light: "#D24600", dark: "#FF6010" },
  amber: { group: "full", tone: "warm", light: "#A46911", dark: "#E8961B" },
  teal: { group: "full", tone: "cool", light: "#008484", dark: "#00A3A3" },
  violet: { group: "full", tone: "cool", light: "#7A4CE0", dark: "#9A78E8" },
};

/** 按组取皮肤 —— 面板分两行展示，各自一个小标题 */
export function skinsOf(group: SkinGroup): SkinId[] {
  return (Object.keys(SKIN_HEX) as SkinId[]).filter((id) => SKIN_HEX[id].group === group);
}

export const MODE_HEX: Record<"light" | "dark", { surface: string; ink: string; sub: string }> = {
  light: { surface: "#FFFFFF", ink: "#16171D", sub: "#8A8D97" },
  dark: { surface: "#161A22", ink: "#F2F4F7", sub: "#97A0AF" },
};

/** 圆角五档 —— 组件层只许用这五个（uno.config.ts 同源，规范测试拦截）。
 *  扁平色块风格偏大圆角：靠圆角 + 色块分层，不靠描边。 */
export const radius = {
  sm: "16rpx",
  md: "24rpx",
  lg: "32rpx",
  xl: "44rpx",
  full: "9999px",
} as const;

/** 间距阶（西式留白：整体比国内电商更松） */
export const spacing = {
  xs: "8rpx",
  sm: "16rpx",
  md: "28rpx",
  lg: "40rpx",
  xl: "64rpx",
} as const;
