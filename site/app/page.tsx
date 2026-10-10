import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";
import { site } from "@/lib/site.config";

// 正文见 content/home/index.md

/**
 * 首页标题用 `absolute` —— 否则会被 layout 的模板拼成
 * 「虹选 · 好物 — … · 虹选 · 好物」，品牌名出现两次。
 */
const meta = metadataFor("home");
export const metadata: Metadata = {
  ...meta,
  title: { absolute: String(meta.title) },
};

/** 结构化数据：让搜索结果里出现品牌卡片，而不是一条普通蓝链 */
const JSON_LD = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "Organization",
      name: site.name,
      alternateName: [site.nameCompact, site.nameEn],
      url: site.url,
      email: site.contact.email,
      legalName: site.legal.company,
      parentOrganization: { "@type": "Organization", name: site.legal.parentBrand },
    },
    {
      "@type": "WebSite",
      name: site.name,
      url: site.url,
      inLanguage: "zh-CN",
      description: meta.description,
    },
  ],
};

export default function Page() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(JSON_LD) }}
      />
      <ContentPage path="home" />
    </>
  );
}
