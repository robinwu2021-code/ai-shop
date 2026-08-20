import type { Metadata } from "next";
import { ContentPage, metadataFor } from "@/components/content/page";

// 正文见 content/mini-program/index.md
export const metadata: Metadata = metadataFor("mini-program");

export default function Page() {
  return <ContentPage path="mini-program" />;
}
