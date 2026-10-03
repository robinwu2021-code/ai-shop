import type { Metadata } from "next";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { ContentPage, metadataFor } from "@/components/content/page";

/**
 * 六类店铺各一页，正文见 content/scenarios/<slug>.md。
 *
 * 路由**从目录列出来**而不是手写一张清单：加一个 md 就多一页，
 * 不会出现「内容写了但没有路由」这种查起来很费劲的情况。
 */
export function generateStaticParams() {
  return readdirSync(join(process.cwd(), "content/scenarios"))
    .filter((f) => f.endsWith(".md") && f !== "index.md")
    .map((f) => ({ slug: f.replace(/\.md$/, "") }));
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  return metadataFor(`scenarios/${slug}`);
}

export default async function Page({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  return <ContentPage path={`scenarios/${slug}`} />;
}
