import type { MetadataRoute } from "next";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { loadPage } from "@/lib/content";
import { site } from "@/lib/site.config";

/**
 * sitemap.xml —— **从 content 目录列出来**，不手维护一张 URL 清单。
 *
 * 手维护的后果是可预见的：加一页忘了加进来，搜索引擎就当它不存在；
 * 删一页忘了删掉，抓到 404 又扣一次信任。
 *
 * `priority` 按站点地图的转化目标给：首页与六个类型页是入口，法务页垫底。
 */
export const dynamic = "force-static";

const CONTENT = join(process.cwd(), "content");

function scenarioSlugs() {
  return readdirSync(join(CONTENT, "scenarios"))
    .filter((f) => f.endsWith(".md") && f !== "index.md")
    .map((f) => `scenarios/${f.replace(/\.md$/, "")}`);
}

export default function sitemap(): MetadataRoute.Sitemap {
  const paths = [
    "home",
    "scenarios",
    ...scenarioSlugs(),
    "how-it-works",
    "pricing",
    "capabilities",
    "mini-program",
    "download",
    "privacy",
    "terms",
  ];

  return paths.map((p) => {
    const { slug } = loadPage(p);
    const priority = slug === "/" ? 1 : slug.startsWith("/scenarios") ? 0.8 : slug === "/privacy/" || slug === "/terms/" ? 0.2 : 0.6;
    return { url: `${site.url}${slug}`, changeFrequency: "monthly" as const, priority };
  });
}
