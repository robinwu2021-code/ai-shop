import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";

// 正文见 content/capabilities/index.md
export const metadata: Metadata = metadataFor("capabilities");

export default function Page() {
  return <ContentPage path="capabilities" />;
}
