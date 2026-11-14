"use client";

// 页头只展示当前视图；完整功能入口按菜单分组收进显式切换菜单。
import * as React from "react";
import { Check, ChevronDown } from "lucide-react";
import { useI18n } from "@/lib/i18n";
import { cn } from "@/lib/utils";
import {
  DropdownMenu, DropdownMenuTrigger, DropdownMenuContent,
  DropdownMenuGroup, DropdownMenuLabel, DropdownMenuItem,
} from "@/components/ui/dropdown-menu";

export function TabHeader({ tabs, value, onChange, desc, action }: {
  tabs: { key: string; label: string; group?: string }[];
  value: string;
  onChange: (k: string) => void;
  desc?: string;
  action?: React.ReactNode;
}) {
  const { t } = useI18n();
  const current = tabs.find((x) => x.key === value);
  const groups: { label?: string; tabs: typeof tabs }[] = [];
  for (const tab of tabs) {
    const existing = tab.group ? groups.find((g) => g.label === tab.group) : groups.at(-1);
    if (existing && existing.label === tab.group) existing.tabs.push(tab);
    else groups.push({ label: tab.group, tabs: [tab] });
  }
  return (
    <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
      <div className="flex min-w-0 flex-wrap items-center gap-3">
        <h1 className="min-w-0 truncate txt-title">{current?.label ?? ""}</h1>
        {desc && tabs.length <= 1 && <p className="txt-caption text-muted-foreground">{desc}</p>}
        {tabs.length > 1 && <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button type="button" className="focus-ring inline-flex shrink-0 items-center gap-1 rounded-field px-2 py-1 txt-caption text-muted-foreground hover:bg-accent hover:text-foreground">
              {t("common.switchView")}<ChevronDown className="size-3.5" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="max-h-[var(--radix-dropdown-menu-content-available-height)] w-64 overflow-y-auto">
            {groups.map((group, i) => <DropdownMenuGroup key={group.label ?? `ungrouped-${i}`} className="py-1">
              {group.label && <DropdownMenuLabel>{group.label}</DropdownMenuLabel>}
              {group.tabs.map((tab) => <DropdownMenuItem key={tab.key} onSelect={() => onChange(tab.key)}
                aria-current={tab.key === value ? "page" : undefined}
                className={cn(group.label && "ms-2", tab.key === value && "bg-accent font-medium text-primary-ink")}>
                <span className="min-w-0 flex-1">{tab.label}</span>
                {tab.key === value && <Check className="size-4 shrink-0" />}
              </DropdownMenuItem>)}
            </DropdownMenuGroup>)}
          </DropdownMenuContent>
        </DropdownMenu>}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}
