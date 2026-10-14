import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";

// 正文见 content/download/index.md
export const metadata: Metadata = metadataFor("download");

export default function Page() {
  return <ContentPage path="download" />;
}
