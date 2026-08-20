import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";

// 正文见 content/scenarios/index.md
export const metadata: Metadata = metadataFor("scenarios");

export default function Page() {
  return <ContentPage path="scenarios" />;
}
