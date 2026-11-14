"use client";

import type { ComponentProps } from "react";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { cn } from "@/lib/utils";

/** Admission details need more room than the shared compact drawers. */
export function AdmissionDetailDrawer(props: ComponentProps<typeof Drawer>) {
  return <Drawer {...props}
    width="w-[720px]"
    className="h-dvh"
    headerClassName="shrink-0 gap-4 border-b bg-card [&>div]:min-w-0 [&_h2]:leading-snug [&_h2]:[overflow-wrap:anywhere] [&_p]:break-all"
    bodyClassName="min-h-0 p-5 sm:p-7"
  />;
}

export function AdmissionDetailSection({ first, className, ...props }: ComponentProps<typeof DrawerSection>) {
  return <DrawerSection {...props} first={first}
    className={cn("[&>div:first-child]:mb-4 [&_h3]:text-foreground", !first && "mt-8 pt-6", className)}
  />;
}

export function AdmissionDetailGrid(props: ComponentProps<typeof FieldGrid>) {
  return <FieldGrid {...props} className={cn("grid-cols-1 gap-x-8 gap-y-5 sm:grid-cols-2", props.className)} />;
}

export function AdmissionDetailField({ wide, className, ...props }: ComponentProps<typeof Field> & { wide?: boolean }) {
  return <Field {...props} className={cn("mb-0 min-w-0 [overflow-wrap:anywhere]", wide && "sm:col-span-2", className)} />;
}
