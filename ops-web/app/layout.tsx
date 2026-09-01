import type { Metadata } from "next";
import { IBM_Plex_Sans } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { AppShell } from "@/components/layout/app-shell";
import { IS_MOCK } from "@/lib/api";

// 字族：IBM Plex Sans（拉丁）+ IBM Plex Sans SC（中文，见下）。
// IBM Plex 是为技术界面设计的中性无衬线，数字辨识度高（0/O、1/l 分得开），
// 适合处理金额与单号的密集台账；消费端那种圆润字体（C 端用）在这里偏"软"。
const plex = IBM_Plex_Sans({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-plex",
  display: "swap",
});
// ⚠️ 中文走 CDN 而非 next/font：**CJK 家族在 next/font 的字体数据里没有登记**
// （IBM Plex Sans / Arabic 都在，IBM Plex Sans SC 查无此项）。自托管路径取不到汉字字形，
// 用 latin 子集时 document.fonts.check(...,'商家') 实测为 false —— 字体加了等于没加，
// 且肉眼看不出来。已知代价：受限网络/内网部署下会静默回退到 PingFang SC / 微软雅黑。

export const metadata: Metadata = {
  title: "邻里购 · 平台运营端",
  description: "社区电商平台运营管理后台（ops-web）",
  // 图标产物由 brand/build.py 生成到 public/，勿手改单个文件
  icons: {
    icon: [
      { url: "/favicon.svg", type: "image/svg+xml" },
      { url: "/favicon-32.png", sizes: "32x32" },
    ],
    apple: "/apple-touch-icon.png",
  },
};

// 首帧前应用持久化主题色 + 语言/方向（避免闪烁）。key 对齐 lib/stores/{theme,locale}.ts。
const THEME_INIT = `try{var t=JSON.parse(localStorage.getItem('shop-ops-theme')||'{}');var k=t&&t.state&&t.state.themeKey;if(k)document.documentElement.dataset.theme=k;if(t&&t.state&&t.state.dark)document.documentElement.classList.add('dark');}catch(e){}
try{var l=JSON.parse(localStorage.getItem('shop-ops-locale')||'{}');var lo=(l&&l.state&&l.state.locale)||'zh';var d=document.documentElement;d.lang=lo==='zh'?'zh-CN':lo==='en'?'en-US':lo;d.dir='ltr';}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh" className={plex.variable} suppressHydrationWarning>
      <head>
        {/* 中文字体：IBM Plex Sans SC（见上方注释说明为何不走 next/font）*/}
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+SC:wght@400;500;600;700&display=swap"
        />
        {/*
          * **这份产物连的是真后端还是 mock，从外面要看得出来。**
          *
          * 2026-09-01：线上运营端跑了两天 mock 而没人知道 ——
          * 部署文档里写着 `NEXT_PUBLIC_USE_MOCK=0`，那次构建漏了它，
          * 而漏掉的表现是「登录提示无权限」：请求根本没发给后端，
          * 后端日志里一条记录都没有，于是查了半天判权、角色、权限点，
          * 全都是好的。
          *
          * 默认值是 mock（`!== "0"`），所以**漏配等于回到 mock** ——
          * 这种默认最需要一个能从外面探到的标记：
          * 部署后 `curl -s <url> | grep x-api-mode` 一眼就能看出来。
          */}
        <meta name="x-api-mode" content={IS_MOCK ? "mock" : "http"} />
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT }} />
      </head>
      <body>
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
