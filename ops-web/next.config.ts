import type { NextConfig } from "next";

// 子路径部署：构建期注入 NEXT_PUBLIC_BASE_PATH（如 /ai-shop/ops-web）→ basePath/assetPrefix。
// 静态导出，由 nginx 托管；/ops 反代到后端（同源，无 CORS）。
const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH || "";

const nextConfig: NextConfig = {
  output: "export",
  images: { unoptimized: true },
  trailingSlash: true,
  ...(BASE_PATH ? { basePath: BASE_PATH, assetPrefix: BASE_PATH } : {}),
};

export default nextConfig;
