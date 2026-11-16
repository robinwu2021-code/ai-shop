"use client";

// 顶栏系统切换：平台运营 ⇄ 支付运营（TDD-ops-统一登录与系统切换 §3.4）。
// 两端同源、共用一个令牌，跳过去即已登录。「支付运营」是否出现由支付端 /me/access 决定 ——
// 支付权限只在支付端配置，这里不登记 pay:* 权限码。查询失败只是不显示，不影响平台运营。
import { useQuery } from "@tanstack/react-query";
import { ArrowUpRight, ChevronDown } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { useLocaleStore } from "@/lib/stores/locale";
import { useBrand } from "@/lib/stores/brand";
import { paymentOpsHref } from "@/lib/auth/ops-bridge";
import { BrandMark } from "@/components/brand-mark";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const ACCESS_URL = process.env.NEXT_PUBLIC_NEARPAY_ACCESS_URL || "/oms-api/pay-ops/v2/me/access";

/** 支付端有效权限非空才算「能进」；信封有无都接受（{code,data} 或裸对象）。 */
export async function fetchPaymentAccess(token: string, fetcher: typeof fetch = fetch): Promise<boolean> {
  const res = await fetcher(ACCESS_URL, { headers: { Authorization: `Bearer ${token}`, Accept: "application/json" }, cache: "no-store" });
  if (!res.ok) return false;
  const body = await res.json().catch(() => null) as { data?: { permissions?: unknown }; permissions?: unknown; code?: number } | null;
  if (body?.code !== undefined && body.code !== 0) return false;
  const perms = body?.data?.permissions ?? body?.permissions;
  return Array.isArray(perms) && perms.length > 0;
}

export function SystemSwitcher() {
  const token = useAuth((s) => s.token);
  const locale = useLocaleStore((s) => s.locale);
  const { t } = useI18n();
  const brand = useBrand();
  const access = useQuery({
    queryKey: ["payment-access", token],
    queryFn: () => fetchPaymentAccess(token),
    enabled: !!token,
    staleTime: Infinity,
    retry: false,
  });
  // 只有一个系统可去时不画控件：一个点开只有自己的切换器比没有更糟
  if (!access.data) return null;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label={t("nav.switchSystem")}
        className="focus-ring inline-flex h-8 items-center gap-1.5 rounded-field px-2 txt-body text-muted-foreground hover:bg-accent hover:text-foreground data-[state=open]:bg-accent"
      >
        <span className="max-w-40 truncate">{brand.name}</span>
        <ChevronDown className="size-3.5" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel>{t("nav.switchSystem")}</DropdownMenuLabel>
        <DropdownMenuItem disabled className="gap-2.5">
          <BrandMark className="size-6 txt-caption" />
          <span className="flex-1">{brand.name}</span>
          <span className="txt-caption text-muted-foreground">{t("nav.currentSystem")}</span>
        </DropdownMenuItem>
        <DropdownMenuItem className="gap-2.5" onSelect={() => window.location.assign(paymentOpsHref(window.location.origin, locale))}>
          <span aria-hidden className="flex size-6 items-center justify-center rounded-field bg-secondary txt-caption">P</span>
          <span className="flex-1">{t("nav.paymentOps")}</span>
          <ArrowUpRight className="size-3.5 text-muted-foreground" />
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
