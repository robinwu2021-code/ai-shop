import type { NextConfig } from "next";

// hxmall.top 官网 —— 纯静态导出，由 nginx 托管，无 Node 运行时。
// 与 ops-web 同一套写法（见 ops-web/next.config.ts），部署链路复用现成的 scp 流程。
//
// ⚠️ `images.unoptimized` 不是偷懒：`output:"export"` 下 next/image 的优化服务根本不存在，
// 不写这一行构建直接报错。被关掉的那部分由 scripts/optimize-images.mjs（sharp）在
// prebuild 阶段补上 —— 图片没过那一步就不许进 public/。见 TDD §3.1。
const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH || "";

const nextConfig: NextConfig = {
  output: "export",
  images: { unoptimized: true },
  // 皮肤演示直接读 packages/shared 的 TS 源（那里是色值真源，复制一份就会有第二套真相）。
  // workspace 包没有构建产物，必须让 Next 自己转译。
  transpilePackages: ["@ai-shop/shared"],
  // 每个路由导出成 `目录/index.html`，nginx 不用配 try_files 就能直出
  trailingSlash: true,
  ...(BASE_PATH ? { basePath: BASE_PATH, assetPrefix: BASE_PATH } : {}),
};

export default nextConfig;
