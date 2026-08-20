import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";

// 正文见 content/pricing/index.md
export const metadata: Metadata = metadataFor("pricing");

export default function Page() {
  return <ContentPage path="pricing" />;
}
