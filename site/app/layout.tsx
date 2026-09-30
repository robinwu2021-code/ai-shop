import type { Metadata } from "next";
import { Figtree, Instrument_Serif } from "next/font/google";
import "./globals.css";
import { site } from "@/lib/site.config";

// 拉丁字族。中文**不走 next/font** —— CJK 家族在它的字体数据里查无此项，
// 用 latin 子集时汉字字形取不到，加了等于没加且肉眼看不出来（ops-web 实测过）。
// 中文自托管子集字体是 T6 的事，在那之前 globals.css 里回退到系统黑体。
const figtree = Figtree({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-figtree",
  display: "swap",
});

// 展示大字用的衬线。只在标题出现，正文不用。
const instrument = Instrument_Serif({
  subsets: ["latin"],
  weight: "400",
  variable: "--font-instrument",
  display: "swap",
});

// 官网面向商家（2026-08-20 拍板）。标题与关键词都按「要开店的人会搜什么」来写，
// 顾客侧的检索由 /download/ 自己的 metadata 承接。
const TITLE = `${site.name} — 社区门店的线上经营系统`;

export const metadata: Metadata = {
  metadataBase: new URL(site.url),
  title: {
    default: TITLE,
    template: `%s · ${site.name}`,
  },
  description: site.description,
  keywords: [
    "商家入驻",
    "开店",
    "社区团购系统",
    "多门店管理",
    "自有小程序",
    "邻里电商",
    "虹选",
    "HX MALL",
  ],
  openGraph: {
    type: "website",
    locale: "zh_CN",
    url: site.url,
    siteName: site.name,
    title: TITLE,
    description: site.description,
  },
  // 静态导出没有请求时生成，OG 图与 favicon 走构建期产物（T5 补）
  robots: { index: true, follow: true },
};

/**
 * 老分享链接的退路。
 *
 * 官网接管 `/` 之前，C 端 H5 在这里，分享出去的链接形如
 * `https://www.hxmall.top/#/pages/goods/detail?id=…`。**hash 不会发给服务器**，
 * nginx 没法重定向 —— 只能在浏览器里判。这些链接已经在用户的聊天记录、
 * 朋友圈和包装贴纸上了，打不开就是真丢单。
 *
 * 放在 <head> 内联执行：要赶在首屏渲染前跳走，否则用户会先看到一眼官网再闪一下。
 * 它不是组件，不进客户端 bundle，也不触发 "use client" 约束。
 */
const LEGACY_HASH_REDIRECT = `if(location.pathname==="/"&&location.hash.indexOf("#/pages/")===0){location.replace("/c/"+location.hash)}`;

/**
 * 安卓包的版本号与地址，**页面加载后从版本清单取**。
 *
 * <p>在这之前，「最新版是哪个」写死在三处：这个站的 site.config、服务器 env、
 * 以及人的记性。每处都要手工跟，于是每处都会掉队 —— 2026-09-30 查出 env 那处
 * 停在 0.4.98，而这里已经是 0.5.21，差了二十多个版本。而掉队时下载照样 200、
 * 照样装得上，只是功能旧，**没有任何信号**。
 *
 * <p>清单（{@code /dl/latest.json}）由发版脚本写，是唯一真源。这样发一版 APK
 * 不用再改这个站、不用重新构建部署它。
 *
 * <p><b>取不到就什么都不做</b> —— 构建时插进去的那个值仍在页面上，
 * 只是可能旧一版。比让按钮变空白好。
 *
 * <p>内联脚本而不是客户端组件：官网除了换色演示没有交互，
 * 首屏 JS 有预算（constraints.test.ts 盯着 "use client" 只许有一个）。
 * 这段几百字节，不进 React bundle。
 */
const LATEST_APK = `(function(){try{fetch(${JSON.stringify(site.download.manifest)},{cache:"no-store"})
.then(function(r){return r.json()}).then(function(j){if(!j||!j.version)return;
document.querySelectorAll("[data-apk-version]").forEach(function(e){e.textContent=j.version});
if(j.url)document.querySelectorAll("a[data-apk-latest]").forEach(function(a){a.href=j.url})})
.catch(function(){})}catch(e){}})()`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" className={`${figtree.variable} ${instrument.variable}`}>
      <head>
        <script dangerouslySetInnerHTML={{ __html: LEGACY_HASH_REDIRECT }} />
      </head>
      <body>
        {children}
        <script dangerouslySetInnerHTML={{ __html: LATEST_APK }} />
      </body>
    </html>
  );
}
