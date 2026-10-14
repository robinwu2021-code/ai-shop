import { SiteHeader } from "@/components/layout/site-header";
import { SiteFooter } from "@/components/layout/site-footer";
import { ContentSection } from "@/components/content/section";
import { loadPage } from "@/lib/content";

/**
 * 所有页面共用的外壳 —— 页面文件只负责说「我是哪份 md」。
 *
 * 这也是内容与呈现分开之后的收益：新增一页 = 一个 md + 一个三行的路由文件，
 * 不再需要每页拼一遍 header / main / footer。
 */
export function ContentPage({ path }: { path: string }) {
  const page = loadPage(path);
  return (
    <>
      <SiteHeader />
      <main id="top">
        {page.sections.map((s, i) => (
          <ContentSection key={`${s.type}-${i}`} s={s} first={i === 0} />
        ))}
      </main>
      <SiteFooter />
    </>
  );
}

/**
 * 每页的 <title> 与 description 同样来自 md 的 frontmatter。
 *
 * `canonical` 用 md 里的 slug：静态导出下同一页可能有带斜杠与不带斜杠两个地址，
 * 不指认哪个是正版，搜索引擎会把权重拆成两半。
 */
export function metadataFor(path: string) {
  const { title, description, slug } = loadPage(path);
  return { title, description, alternates: { canonical: slug } };
}
