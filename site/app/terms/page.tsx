import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";

// 正文见 content/terms/index.md
export const metadata: Metadata = metadataFor("terms");

export default function Page() {
  return <ContentPage path="terms" />;
}
