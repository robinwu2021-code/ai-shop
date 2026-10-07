// 压缩包导入商品图的**纯逻辑**：把解压出来的文件路径分成主图/详情/文字，并按序号排。
//
// 解压本身（plus.zip）是 App-only，在 b-app 的 #ifdef APP-PLUS 里做；
// 这里只处理「解出来之后那堆路径怎么归类、怎么排序」——纯字符串逻辑，能单测。
// 分类规则就是给商家的契约（PRD §五），改这里等于改契约。

/** 归类结果。main 首张即封面（cover）；txt 可空（没带文字文件）。 */
export interface ZipMedia {
  main: string[];
  detail: string[];
  txt?: string;
}

// 目录名**包含**关键词即可（不是精确相等）：真实压缩包里详情目录叫「详情页」、
// 主图目录可能叫「主图片」，精确匹配会漏。段内不含 / 保证只认目录名这一段。
const MAIN_DIR = /(^|\/)[^/]*(主图|main)[^/]*\//i;
const DETAIL_DIR = /(^|\/)[^/]*(详情|detail)[^/]*\//i;
const IMAGE = /\.(jpe?g|png|webp|gif)$/i;

/**
 * 按文件名里的数字排序 —— `2.jpg` 在 `10.jpg` 前面。
 *
 * **不能用字符串序**：字符串序下 "10" < "2"，商家编号到两位数时顺序就乱了，
 * 而详情长图的顺序就是展示顺序，乱一张整页读起来都不对。
 * 没有数字的排在最后、同号按原名——给个确定的兜底，别让顺序变成随机。
 */
export function sortByNumber(names: string[]): string[] {
  const num = (s: string): number => {
    const base = s.slice(s.lastIndexOf("/") + 1);
    const m = base.match(/(\d+)/);
    return m?.[1] ? parseInt(m[1], 10) : Number.MAX_SAFE_INTEGER;
  };
  return [...names].sort((a, b) => num(a) - num(b) || a.localeCompare(b));
}

/**
 * 把解压出的路径分成主图/详情/文字。
 *
 * - 目录名 `主图/`（或 `main/`）→ 主图；`详情/`（`detail/`）→ 详情，大小写不敏感；
 * - 根目录任一 `.txt` → 文字（多个取排序后第一个，别让"随便哪个"变成不确定）；
 * - **没有任何已知目录时**：所有图当主图（PRD 容错，商家懒得分目录也能用）；
 * - 有目录、但某张图不在任何目录里：并入主图（宽容，不丢图）；
 * - 非图非 txt：忽略（zip 里常夹 .DS_Store、缩略图缓存）。
 */
export function classifyZip(paths: string[]): ZipMedia {
  const main: string[] = [];
  const detail: string[] = [];
  const loose: string[] = [];
  const txts: string[] = [];

  for (const p of paths) {
    if (/\.txt$/i.test(p)) {
      // 只认根目录的 txt —— 子目录里的 txt 多半是别的东西
      if (!p.replace(/^\.?\//, "").includes("/")) txts.push(p);
      continue;
    }
    if (!IMAGE.test(p)) continue;
    if (MAIN_DIR.test(p)) main.push(p);
    else if (DETAIL_DIR.test(p)) detail.push(p);
    else loose.push(p);
  }

  // 整个包没有目录结构（main、detail 都空）→ 散图全当主图；
  // 有目录时，散图并进主图（无目录的图默认是主图，不是详情）
  if (main.length === 0 && detail.length === 0) {
    main.push(...loose);
  } else {
    main.push(...loose);
  }

  return {
    main: sortByNumber(main),
    detail: sortByNumber(detail),
    txt: txts.length ? sortByNumber(txts)[0] : undefined,
  };
}

// ---------------------------------------------------------------- 交给模型分类（TDD-商品压缩包导入）

/** 压缩包文件的标准去向：主图 / 详情 / 商品文案 / 不导入 */
export type ZipTarget = "MAIN" | "DETAIL" | "TEXT" | "IGNORE";

/** 一个文件的去向。`order` 在同一去向内从 1 起；TEXT / IGNORE 为 0 */
export interface ZipPick {
  /** 包里的相对路径（原样） */
  path: string;
  /** 去向：MAIN 主图 / DETAIL 详情 / TEXT 文案 / IGNORE 不导入 */
  target: ZipTarget;
  /** 同一去向内的顺序，从 1 起；TEXT / IGNORE 为 0 */
  order: number;
}

/**
 * 去掉解压出来的系统垃圾：macOS 的 `__MACOSX/` 与 `._*` 资源叉、`.DS_Store`、Windows 的 `Thumbs.db`。
 * 先滤掉再交给模型 —— 省 token，也不给它犯错的机会。
 */
export function dropJunk(paths: string[]): string[] {
  return paths.filter((p) => {
    const base = p.slice(p.lastIndexOf("/") + 1);
    return !/(^|\/)__MACOSX\//.test(p) && !base.startsWith("._")
      && base !== ".DS_Store" && base.toLowerCase() !== "thumbs.db";
  });
}

/**
 * 规则的分法，**每个文件一条**：随请求交给服务端，模型那一条不合格时用它。
 * 规则只有一份（`classifyZip`），服务端不再用 Java 写一遍。
 */
export function ruleHint(media: ZipMedia, all: string[]): ZipPick[] {
  const at = (list: string[], p: string) => list.indexOf(p) + 1;
  return all.map((path): ZipPick => {
    if (at(media.main, path)) return { path, target: "MAIN", order: at(media.main, path) };
    if (at(media.detail, path)) return { path, target: "DETAIL", order: at(media.detail, path) };
    if (path === media.txt) return { path, target: "TEXT", order: 0 };
    return { path, target: "IGNORE", order: 0 };
  });
}

/** 分法 → 要导的主图、详情（各按 order）与文案（第一份 TEXT） */
export function listsOf(items: ZipPick[]): { main: string[]; detail: string[]; txt?: string } {
  const of = (t: ZipTarget) => items.filter((i) => i.target === t)
    .sort((a, b) => a.order - b.order).map((i) => i.path);
  return { main: of("MAIN"), detail: of("DETAIL"), txt: items.find((i) => i.target === "TEXT")?.path };
}
