"use client";
import { useCan } from "@/lib/use-can";
import { usePointsText } from "@/lib/points-i18n";
import { TabHeader } from "@/components/ui/tab-header";
import { Notice } from "@/components/ui/notice";
import { PrintMonitor } from "./monitor";

export default function PrintPage() {
  const can = useCan();
  const { t } = usePointsText();
  return <div className="space-y-4">
    <TabHeader tabs={[{ key: "print", label: t("打印监控与对账", "Print monitoring and reconciliation") }]} value="print" onChange={() => {}} />
    {!can("ops:print:read") ? <Notice>{t("没有打印监控读取权限。", "Print monitoring read permission required.")}</Notice> : <PrintMonitor canResolve={can("ops:print:resolve")} />}
  </div>;
}
