import type { MetadataRoute } from "next";
import { site } from "@/lib/site.config";

/**
 * robots.txt。
 *
 * `/b/` 与 `/c/` 是两个端的应用入口（登录后才有内容），抓进索引只会给出一堆空壳页，
 * 还会把「虹选」这个词的搜索结果稀释掉 —— 招商站要的是官网本身被搜到。
 */
export const dynamic = "force-static";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [{ userAgent: "*", allow: "/", disallow: ["/b/", "/c/", "/ops-web/"] }],
    sitemap: `${site.url}/sitemap.xml`,
  };
}
