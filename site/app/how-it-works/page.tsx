import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";

// 正文见 content/how-it-works/index.md
export const metadata: Metadata = metadataFor("how-it-works");

export default function Page() {
  return <ContentPage path="how-it-works" />;
}
