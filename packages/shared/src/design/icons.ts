// 图标源（24×24 线性图标，stroke 统一 1.8）。
// 作为 CSS mask 使用，所以 SVG 里的颜色无意义 —— 用 currentColor 只是占位，
// 实际颜色由 sh-icon 的 background-color 决定（见该组件注释）。
//
// 全部内联：不引图标库，避免为 4 个图标拖进一个几百 KB 的包，也避免小程序的分包与域名问题。

const wrap = (path: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#000" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`;

const wrapFilled = (path: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#000">${path}</svg>`;

export const ICONS = {
  home: wrap('<path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V20h13V9.5"/>'),
  homeFilled: wrapFilled(
    '<path d="M12 2.6 2.4 10.4a1 1 0 0 0 .63 1.78H4.5V20a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5v-7.82h1.47a1 1 0 0 0 .63-1.78z"/>',
  ),
  grid: wrap(
    '<rect x="3.5" y="3.5" width="7" height="7" rx="2"/><rect x="13.5" y="3.5" width="7" height="7" rx="2"/><rect x="3.5" y="13.5" width="7" height="7" rx="2"/><rect x="13.5" y="13.5" width="7" height="7" rx="2"/>',
  ),
  gridFilled: wrapFilled(
    '<rect x="3" y="3" width="8" height="8" rx="2.4"/><rect x="13" y="3" width="8" height="8" rx="2.4"/><rect x="3" y="13" width="8" height="8" rx="2.4"/><rect x="13" y="13" width="8" height="8" rx="2.4"/>',
  ),
  cart: wrap(
    '<path d="M3 4h2.2l2.3 10.5h9.6L19.5 7H6.2"/><circle cx="9.5" cy="19" r="1.4"/><circle cx="17" cy="19" r="1.4"/>',
  ),
  cartFilled: wrapFilled(
    '<path d="M2.2 3a.9.9 0 0 0 0 1.8h2L6.6 15.4a1.4 1.4 0 0 0 1.37 1.1h9.36a1.4 1.4 0 0 0 1.37-1.1l1.75-8a.9.9 0 0 0-.88-1.1H6.06l-.5-2.3A1.4 1.4 0 0 0 4.2 3z"/><circle cx="9.3" cy="19.4" r="1.6"/><circle cx="17.2" cy="19.4" r="1.6"/>',
  ),
  user: wrap('<circle cx="12" cy="8" r="3.6"/><path d="M4.8 20.2c1-3.6 3.8-5.6 7.2-5.6s6.2 2 7.2 5.6"/>'),
  userFilled: wrapFilled(
    '<circle cx="12" cy="7.8" r="4.2"/><path d="M12 14c-4 0-7.2 2.5-8 6.2a1.2 1.2 0 0 0 1.18 1.4h13.64A1.2 1.2 0 0 0 20 20.2c-.8-3.7-4-6.2-8-6.2z"/>',
  ),
  store: wrap(
    '<path d="M4 9.5V20h16V9.5"/><path d="M3 4.5h18l-1.2 3.7a2.6 2.6 0 0 1-2.48 1.8 2.6 2.6 0 0 1-2.48-1.8 2.6 2.6 0 0 1-2.48 1.8 2.6 2.6 0 0 1-2.48-1.8 2.6 2.6 0 0 1-2.48 1.8A2.6 2.6 0 0 1 4.2 8.2z"/>',
  ),
  storeFilled: wrapFilled(
    '<path d="M2.9 3.6a.9.9 0 0 0-.86 1.17l1.1 3.5A3.5 3.5 0 0 0 6.3 10.7a3.5 3.5 0 0 0 2.85-1.47A3.5 3.5 0 0 0 12 10.7a3.5 3.5 0 0 0 2.85-1.47A3.5 3.5 0 0 0 17.7 10.7a3.5 3.5 0 0 0 3.16-2.43l1.1-3.5a.9.9 0 0 0-.86-1.17z"/><path d="M5 11.9V20a1.4 1.4 0 0 0 1.4 1.4h11.2A1.4 1.4 0 0 0 19 20v-8.1a5 5 0 0 1-1.3.2 5 5 0 0 1-2.85-.88 5 5 0 0 1-5.7 0A5 5 0 0 1 6.3 12.1a5 5 0 0 1-1.3-.2z"/>',
  ),
  plus: wrap('<path d="M12 5.5v13M5.5 12h13"/>'),
  // 拖动手柄：两列点。比「三横线」更像「能抓住的东西」，也不会与菜单图标混
  grip: wrap(
    '<circle cx="9" cy="6" r="1.1"/><circle cx="15" cy="6" r="1.1"/>'
    + '<circle cx="9" cy="12" r="1.1"/><circle cx="15" cy="12" r="1.1"/>'
    + '<circle cx="9" cy="18" r="1.1"/><circle cx="15" cy="18" r="1.1"/>',
  ),
  // 调整：滑杆。不用铅笔 —— 这里改的是「用哪几档」，不是写字
  sliders: wrap(
    '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/>'
    + '<circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
  ),
  // 移除：叉。不用垃圾桶 —— 规格没被删掉，只是这一类不用它了
  /* 勾。**库里此前没有它** —— 于是 10 处「已选中」用的都是文字 ✓，
     而文字符号跟着系统字形变，正是本文件存在的理由（见顶部）。 */
  /* 减号。与 plus 成对 —— 步进器只用到 plus 的话，「−」还是得拿字符凑，
     而一加一减挨在一起时，字符与图标的线宽差一眼就看出来 */
  minus: wrap('<path d="M5.5 12h13"/>'),
  check: wrap('<path d="M4.5 12.5 9.5 17.5 19.5 6.5"/>'),
  close: wrap('<path d="M6.5 6.5 17.5 17.5M17.5 6.5 6.5 17.5"/>'),
  search: wrap('<circle cx="11" cy="11" r="6.4"/><path d="m16 16 4.2 4.2"/>'),
  pin: wrap('<path d="M12 21s6.5-6 6.5-10.5a6.5 6.5 0 1 0-13 0C5.5 15 12 21 12 21Z"/><circle cx="12" cy="10.5" r="2.4"/>'),
  chevronRight: wrap('<path d="m9.5 5.5 6.5 6.5-6.5 6.5"/>'),
  /* 上下两个是 chevronRight 转置/翻转来的，顶点与线宽都跟着它 ——
     手画会差几个像素，同一屏里三个方向的箭头粗细不一样就很显眼。
     **它们不进 sh-icon 的 DIRECTIONAL 名单**：阿语要镜像的是左右，不是上下。 */
  chevronUp: wrap('<path d="m5.5 14.5 6.5-6.5 6.5 6.5"/>'),
  chevronDown: wrap('<path d="m5.5 9.5 6.5 6.5 6.5-6.5"/>'),
  share: wrap(
    '<path d="M12 16V4"/><path d="m8 8 4-4 4 4"/><path d="M5 13v5.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V13"/>',
  ),
} as const;

export type IconName = keyof typeof ICONS;
