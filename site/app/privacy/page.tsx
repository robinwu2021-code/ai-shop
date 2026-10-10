import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";

// 正文见 content/privacy/index.md
export const metadata: Metadata = metadataFor("privacy");

export default function Page() {
  return <ContentPage path="privacy" />;
}
