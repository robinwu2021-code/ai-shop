# TDD-hxmall-site（hxmall.top 官网）

状态：**技术选型有效；视觉方案与文案已随两项决策更新（2026-08-19）**

> 1. **视觉**：§3.2「光谱切黑」依赖虹橙→虹紫渐变，该品牌色已作废。
>    现行真源是 [视觉设计方案-全项目.md](./design/视觉设计方案-全项目.md)：白底 + 红 `#e1251b` + 弧线母题，**不用渐变**。
>    深色只保留「商家的颜色」一屏（墨底 `#17181a`），用作节奏对比，不是全站基调。
> 2. **定位**：已定 **社区 LBS 邻里电商**，首页结构随之从 §3.4 的五屏扩到七屏。
>
> 首页设计稿（静态、可点、含皮肤演示）：[site/design/home.html](../../site/design/home.html)。
> §3.1 技术栈、§3.3 目录结构、§3.5 响应式、§3.6 配置项、§4 测试、§5 风险**不变**。
关联需求：[品牌工程与官网方案.md](design/品牌工程与官网方案.md) §6（官网方案）· §7 P2（官网上线）
创建日期：2026-08-19

---

## 1. 需求摘要

为 HX Mall（虹选 · 好物，hxmall.top）建设对外官网：支持 PC 与移动端，
React + Tailwind CSS 最新版本。视觉要求：**科技感、海报品质、色彩高端、有质感**。
承载四个职能：品牌展示（首屏海报）、App 下载承接、商家入驻转化、法务页（隐私政策 / 用户协议，上架强制项）。

验收标准：

1. 首页在 375px～2560px 全宽度范围内无横向滚动、排版不破
2. 隐私政策 / 用户协议公网可访问（应用商店审核用 URL）
3. Lighthouse 移动端 Performance ≥ 90（静态站没理由更低）
4. 品牌色、字标写法与《品牌工程与官网方案》§3/§4 完全一致，色值全部来自 token，无硬编码
5. OG 卡片 / favicon / 微信分享缩略图齐备

---

## 2. 当前架构分析

- **可复用**：`ops-web` 已验证 Next.js 16 + React 19 + Tailwind v4 + radix 的组合；
  `packages/shared/src/design/tokens.ts` 有 8 套皮肤 × 明暗 token（官网「虹」演示直接读它）
- **影响范围**：零。新建 `site/` workspace，不碰任何现有端（《品牌工程与官网方案》§6.1 已论证不塞进 ops-web）
- **品牌输入**：logo = `hx` 字母标（h 肩为虹橙 `#FF5A1F` → 虹紫 `#7A4CE0` 渐变），缩减标 = `h` + 点；
  字标 `虹选 · 好物`；slogan 候选「好物在身边」；墨 `#14161A`

## 3. 方案设计

### 3.1 技术栈选型

**已定：Next.js App Router + `output: "export"` 纯静态导出**（2026-08-19 拍板）。

选它的理由不是「Next 强」，是**在这个仓库里它的边际成本接近零**：
[ops-web/next.config.ts](../../ops-web/next.config.ts) 已经在跑同一套组合（`output:"export"` + `images.unoptimized` + nginx 托管），
官网是复制一条已验证的链路，不是开新路。

#### 版本（2026-08-19 从 npm registry 实查，非估计）

| 依赖 | 版本 | 说明 |
|---|---|---|
| `next` | **16.3.1** | App Router；`engines.node >= 20.9`（本机 26 ✓） |
| `react` / `react-dom` | **19.2.8** | — |
| `@types/react` / `@types/react-dom` | 19.2.18 / 19.2.4 | — |
| `typescript` | **5.9.3** | ⚠️ **刻意不取 latest（7.0.2）**，理由见下 |
| `tailwindcss` + `@tailwindcss/postcss` | **4.3.3** | CSS-first `@theme`，品牌色单点定义 |
| `lucide-react` | 1.33.0 | 与 ops-web 同一套图标 |
| `motion` | 13.1.0 | 入场与滚动动效；`prefers-reduced-motion` 必须降级 |
| `sharp` | 0.35.3 | **构建期图片预处理** —— 静态导出关掉了 `next/image` 优化，这一步得自己做 |
| `subset-font` | 2.5.0 | 中文字体子集化。不用 `fontmin`：它上次发布是 2025-08，`subset-font` 是 2026-04，且直接绑 harfbuzz、API 适合塞进构建脚本 |
| `vitest` | 4.1.11 | 与 ops-web 同 |
| `@testing-library/react` / `jest-dom` / `jsdom` / `@vitejs/plugin-react` | 16.3.2 / 7.0.1 / 30.0.1 / 6.0.5 | — |
| `eslint` + `eslint-config-next` | 10.8.1 + 16.3.1 | — |
| `@next/bundle-analyzer` | 16.3.1 | 可选，用来守住 JS 预算（见 §3.7） |
| `babel-plugin-react-compiler` | 1.0.0 | 可选。官网交互极少，先不开 |

#### 为什么 TypeScript 停在 5.9.3 而不是 7.0.2

`typescript-eslint@8.67.0`（含 `@typescript-eslint/parser`）的 peer 是 **`typescript: >=4.8.4 <6.1.0`**。
取 TS 7 等于把整条 lint 链踢出局 —— 官网这点代码不值得为此裸奔或另找 linter。
且 `ops-web` 在 5.x，两个 workspace 的 `tsconfig` 行为保持一致，比抢一个大版本号更有价值。
**这是唯一一处「最新版」被否掉的地方，其余全部取当前 latest。**

#### 被否掉的方案

| 方案 | 优点 | 为什么否 |
|---|---|---|
| Vite + React SPA | 最轻 | SPA 拿不到静态 HTML，官网的核心诉求正是被搜到、被分享 |
| Astro | 静态站性能极致（零 JS 默认 + island） | **技术上它更贴身**，但仓库已有 uni-app / Next / Spring Boot 三套栈，第四套的长期成本是「没人记得怎么改它」 |
| 手写 HTML | 零构建 | 5～7 页共用页眉页脚 = 复制五份；且拿不到 `tokens.ts` 的真值，品牌色会出现第二套真相 |

#### 这套栈的两个已知代价（不藏着）

1. **`next/image` 优化在静态导出下失效**，必须 `images.unoptimized: true`。图片压缩、响应式尺寸、AVIF/WebP 由 `scripts/optimize-images.mjs`（sharp）在构建前做掉。
2. **有一层抹不掉的 JS 地板** —— App Router + React 19 即使页面全静态，首屏也要下载并执行框架运行时（约百 KB 级 gzip）。
   不加 `"use client"` 的组件不进 bundle，所以只有「商家的颜色」那一屏为交互付费；但运行时本身省不掉。
   **这不是主要瓶颈**：中文站的头号杀手是字体（全量思源黑体 3–8MB），比它大一到两个数量级。

### 3.2 视觉方案

**真源是 [视觉设计方案-全项目.md](./design/视觉设计方案-全项目.md)，本节不重复定义，只写落到官网的部分。**
原「光谱切黑」方向作废——它依赖的虹橙→虹紫渐变已随品牌色一起被否掉，且新规范明确禁止渐变。

- **基调**：白底 + 主色红 `#e1251b` + 墨 `#17181a`。展示大字拉丁走 Instrument Serif，中文落思源黑体 **Regular**（不加粗——中文大字一上 Bold 就变促销横幅）
- **深色只用一屏**：「商家的颜色」用墨底 `#17181a`，作节奏对比，不是全站基调
- **产品即证明**：那一屏做九套皮肤实时切换，`import` `packages/shared/src/design/tokens.ts` 的真值，**不复制色值**
- **不放示意图**：真机图未产出前用带说明的槽位，避免评审把示意图当成已实现的界面

已落地的静态设计稿（可点、含皮肤切换）：**[site/design/home.html](../../site/design/home.html)**。
React 移植是搬运不是翻译——稿子里的自定义属性已按 Tailwind v4 `@theme` 的 `--color-*` / `--font-*` 命名。

### 3.3 模块设计

```
site/                          ← 新 workspace（根 package.json workspaces 增加一项）
  design/home.html             静态设计稿（已产出，不参与构建，移植完保留作对照）
  app/
    layout.tsx                 全局布局 + 元数据 + 字体
    page.tsx                   首页（七屏，见 §3.4）
    download/page.tsx          下载承接页
    merchant/page.tsx          商家入驻
    privacy/page.tsx           隐私政策（上架强制）
    terms/page.tsx             用户协议（上架强制）
    sitemap.ts  robots.ts      构建期生成，加页面不会忘
    opengraph-image.tsx        OG 卡片，构建期生成（静态导出没有请求时生成）
  components/
    brand/Logo.tsx             HX 方章 + 字标（方章/圆章/反白/纯字标，props 切换）
    home/Hero.tsx              首屏
    home/NearbyRadius.tsx      三档服务半径同心圆
    home/Steps.tsx             挑 → 下单 → 楼下自提
    home/SkinShowcase.tsx      九皮肤切换演示 ← **本站唯一的 "use client"**
    home/Market.tsx            邻里集市
    home/MerchantCta.tsx       商家入驻
    ui/                        按钮/容器等基础件
  lib/site.config.ts           站点常量（域名、ICP 号占位、下载链接、联系方式）← 零硬编码收口处
  scripts/
    optimize-images.mjs        sharp：压缩 + 响应式尺寸 + AVIF/WebP（补上被关掉的 next/image）
    subset-fonts.mjs           subset-font：用页面真实文案子集化中文字体
  styles/globals.css           @theme 品牌 token
  public/brand/                logo svg/png、OG 图、favicon
```

**`"use client"` 只允许出现在 `SkinShowcase.tsx`。** 这是硬约束不是建议：
每多一个客户端组件，那层 JS 地板就厚一层，而官网除了换色演示没有任何交互。
由 §4 的一条测试断言守着（扫 `components/` 下 `"use client"` 出现次数 = 1）。

### 3.4 首页结构（一屏一件事）

按已定的 **社区 LBS 邻里电商** 定位重排，七屏，与设计稿逐屏对应：

1. 首屏：「楼下的好东西，都在虹选。」+ 双入口（下载 / 先看附近）
2. **附近的店**：三档服务半径同心圆——这是产品真实的地理覆盖模型
3. 怎么买：挑 → 下单 → 楼下自提
4. **商家的颜色**（墨底）：九色实时切换演示机
5. 邻里集市：八个品类，每条落在 LBS 事实上
6. 商家入驻：五条能力，全部对应已实现的功能
7. 下载：三入口 + 小程序码
8. 页脚：`© 2026 深圳虹选科技有限公司 · 粤ICP备XXXXXXXX号-X` + 法务链接

### 3.5 响应式策略

- 移动优先；断点用 Tailwind 默认 `sm/md/lg/xl`，主栅格只在 `lg` 处切一次（竖排→横排）
- 展示大字用 `clamp()`，不用断点跳变；触控目标 **≥ 44px**（设计稿里菜单按钮曾是 42px，差 2px，已修）
- 皮肤演示在移动端从「并排色卡」降级为「横滑色卡」

### 3.6 配置项（P4 零硬编码）

| 配置 | 位置 |
|---|---|
| 品牌色（红 / 墨 / 面板 / 次要） | `styles/globals.css` 的 `@theme`，值与 `brand/tokens.json` 一致，由测试断言 |
| ICP 备案号、下载链接、小程序码、联系邮箱、公司全称 | `lib/site.config.ts` |
| 皮肤演示色值 | 直接 `import` `packages/shared/src/design/tokens.ts`，不复制 |
| 子路径部署前缀 | `NEXT_PUBLIC_BASE_PATH`（与 ops-web 同一套写法） |

### 3.7 SEO 与性能预算

静态导出下 SEO 能力是完整的，逐项落点：

| 项 | 落点 |
|---|---|
| 每页 title / description / canonical | `export const metadata`（或 `generateMetadata`） |
| OG / Twitter 卡片 | `opengraph-image.tsx`，**构建期**生成（静态导出没有请求时生成） |
| sitemap.xml / robots.txt | `app/sitemap.ts` / `app/robots.ts` |
| 结构化数据 | 首页 `Organization` + `WebSite` JSON-LD；商家入驻页 `FAQPage` |
| 微信分享缩略图 | `public/brand/` 下固定 300×300，`site.config.ts` 引用 |

**静态导出下不可用**：ISR、middleware、请求时动态 OG。都不需要。

性能预算（CI 里守，超了就红）：

| 指标 | 阈值 | 为什么是这个数 |
|---|---|---|
| 首页首屏 JS（gzip） | **≤ 160 KB** | **实测基线 129.9 KB**（T1 脚手架，零客户端组件，5 个 chunk）。这就是 App Router + React 19 的地板，省不掉。留 30 KB 给 `SkinShowcase` 一个组件；超了说明有人多加了 `"use client"` |
| 中文字体子集**单档** | **≤ 130 KB** | 全量 14 MB。795 字实测 400 档 103.8 KB / 600 档 105.6 KB（2026-08-20） |
| 单张图片 | **≤ 200 KB** | `optimize-images.mjs` 输出 AVIF 优先 |
| Lighthouse 移动端 Performance | **≥ 90** | 静态站没理由更低；100 会被 JS 地板卡住，不作为目标 |

## 4. 测试策略

- 单元：`Logo.tsx` 各形态渲染；`SkinShowcase` 读到的皮肤数 = `SKINS.length`；`site.config` 无空占位泄漏到生产构建
- 一致性：`@theme` 品牌色值 === `brand/tokens.json` 对应值（防两套真相）
- **约束断言**：`components/` 下 `"use client"` 出现次数 **= 1**（见 §3.3）
- 构建：`next build`（静态导出）+ `typecheck` 作为 CI 检查
- 手工清单：375 / 768 / 1440 / 2560 四档宽度截屏；`prefers-reduced-motion` 降级；**微信内置浏览器**打开

## 5. 风险与注意事项

1. **备案未过则 80/443 被劫持**（团队已验证），上线前 hxmall.top 打不开是资质问题不是代码问题；开发期用局域网/隧道预览
2. `.top` 域名信任分与微信分享校验风险 → 《品牌工程与官网方案》§6.3 的 `.com` 建议仍待拍板
3. **中文字体子集化漏字**：子集是按页面真实文案算的，改一句文案就可能缺字。上线前必须重跑 `subset-fonts.mjs`，且要有人肉眼过一遍首屏
4. **TypeScript 锁在 5.x**：等 `typescript-eslint` 放开 TS 7 的 peer 之后再升，别单独抢版本号（见 §3.1）
5. **`next/image` 优化失效**是静态导出的固有代价，别指望框架——图片没过 `optimize-images.mjs` 就不许进 `public/`

## 6. 实现任务

- [x] **T1 `site/` workspace 脚手架** —— Next 16.3.1 + React 19.2.8 + Tailwind 4.3.3 + TS 5.9.3，`output:"export"` 跑通（2026-08-19）
      · 产物 `site/out/`，首页 + 404 共 3 个静态页 · 首屏 JS gzip **129.9 KB**、CSS **4.1 KB**、字体两个 woff2 共 34.4 KB
      · 已落首屏（七屏之第 1 屏）作为链路验证：`@theme` token、next/font、Logo 几何、静态导出四条链都通
      · ⚠️ **副作用**：`npm install` 把 hoist 的公共依赖去重到了新版本 —— ops-web 的 `next` 从 16.2.4 → 16.3.1、
        `typescript` 5.7.3 → 5.9.3、`tailwindcss` 4.0.6 → 4.3.3（它的 `^` 范围本来就允许）。
        已复验 `ops-web` 的 `typecheck` 与 `build` 均通过
- [x] **T2 `@theme` 接品牌 token + `Logo.tsx` + 一致性测试**（2026-08-19）
      · `lib/tokens.test.ts` 断言 `@theme` === `brand/tokens.json`；`lib/constraints.test.ts` 守 `"use client"` 白名单、零硬编码域名/色值、`site.config` 空占位清单
      · `logo.test.tsx` 断言几何参数（圆角 26.4 = 0.275×96、弧线描边 4.8 = 0.05×96），不是断言像素
- [x] **T3 首页七屏**（2026-08-19）· `SkinShowcase` 是唯一客户端组件，只花 **2.4 KB gzip**
      · 汉堡菜单用 checkbox + peer 纯 CSS，不为它开第二个客户端组件
      · 皮肤演示 `import` `@shared/design/tokens`，`Record<SkinId,…>` 保证产品加皮肤时这里 `tsc` 会红
- [x] **T8 部署**（2026-08-19）· 官网接管 `www.hxmall.top/`，C 端移到 `/c/`；见 [部署 README](../../deploy/tencent/README.md)
- [ ] T4 download / merchant / privacy / terms 四页
- [ ] T5 SEO：metadata / sitemap / robots / OG / JSON-LD / favicon / 微信分享图
- [x] **T6 字体子集**（2026-08-20）：`subset-fonts.mjs` 已接进 `prebuild`。
      795 字 × 两档字重（400/600）各 ~105 KB；字符集从 `content/` 与 tsx 实算（剥注释、
      跳过 content 内部文档），产物进仓库、源字体不进 —— 构建机不必装 fontTools。
      `lib/fonts.test.ts` 兜底：源码里的字不在覆盖清单里就红。
      ⚠️ **预算从「单文件 ≤120 KB」改成「单档 ≤130 KB」**：120 KB 是三页站的估算，
      现在 13 页 795 字，单档实测 103.8 KB。变量字体保留 wght 轴反而要 216 KB（CJK 插值数据太贵），
      所以出两个定重文件而不是一个变量文件。
- [ ] `optimize-images.mjs`（真机图产出后再接）
- [ ] T7 测试 + 四档宽度走查 + Lighthouse 达标

---
确认记录：（待用户确认）
