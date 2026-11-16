"use client";

// 运营端品牌标识：方形底 + 一个字（或配置的图片）。侧栏与登录卡片共用，名称与标识来自 brand.json。
import { useBrand } from "@/lib/stores/brand";
import { cn } from "@/lib/utils";

export function BrandMark({ className }: { className?: string }) {
  const brand = useBrand();
  if (brand.logoUrl) {
    // 用 <img> 而非 next/image：静态导出，地址来自配置文件
    return <img src={brand.logoUrl} alt="" className={cn("size-8 shrink-0 rounded-field object-contain", className)} />;
  }
  return (
    <span aria-hidden className={cn("flex size-8 shrink-0 items-center justify-center rounded-field bg-primary txt-strong text-primary-foreground", className)}>
      {brand.mark}
    </span>
  );
}
