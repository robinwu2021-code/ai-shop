"use client";

// 商家履约配置（方案 v4，只读矩阵 + P2 锁路）。挂在商家档案抽屉里、人员授权块之前。
//
// 锁路**用锁不用删**：商家配置原样保留，锁着时买家侧不可选、商家侧置灰；解锁只能运营。
// 投诉处置的第一入口，所以动作就放在矩阵格子旁，不另开页面。
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCopy } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/drawer";
import { MERCHANTS_COPY } from "./copy";

export function FulfillmentBlock({ merchantNo }: { merchantNo: string }) {
  const c = useCopy(MERCHANTS_COPY);
  const allow = useCan();
  const canLock = allow("merchant:fulfillment:update");
  const { confirm, dialog } = useConfirm();
  const qc = useQueryClient();
  const { data = [], isLoading } = useQuery({
    queryKey: ["merchant-fulfillment", merchantNo],
    queryFn: () => api.merchantFulfillment(merchantNo),
  });
  const lockMut = useMutation({
    mutationFn: (v: { storeNo: string; channel: string; locked: boolean; reason?: string }) =>
      api.lockChannel(v.storeNo, v.channel, v.locked, v.reason),
    onSuccess: (_, v) => {
      qc.invalidateQueries({ queryKey: ["merchant-fulfillment", merchantNo] });
      notify.success(v.locked ? c.toastChannelLocked : c.toastChannelUnlocked);
    },
  });

  const channelName = (ch: string) => (c as Record<string, string>)[`ch${ch}`] ?? ch;

  if (isLoading) return <Field label={c.fieldFulfillment}>…</Field>;

  return (
    <Field label={c.fieldFulfillment}>
      {data.length === 0 ? (
        <span className="text-muted-foreground">{c.fulfillLegacy}</span>
      ) : (
        <div className="space-y-2">
          {data.map((s) => (
            <div key={s.storeNo}>
              <div className="text-sm">
                {s.storeName ?? s.storeNo}
                {s.storeStatus !== "ACTIVE" && (
                  <Badge tone="muted" className="ms-1">{s.storeStatus}</Badge>
                )}
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-1">
                {s.channels.map((ch) => (
                  <span key={ch.channel} className="inline-flex items-center gap-0.5">
                    <Badge tone={ch.locked ? "danger" : ch.denied ? "warning" : ch.enabled ? "success" : "muted"}>
                      {channelName(ch.channel)}
                      {ch.locked ? ` · ${c.chLocked}` : ch.denied ? ` · ${c.chDenied}` : ch.enabled ? "" : ` · ${c.chOff}`}
                      {ch.scopeMode === "SUBSET" ? ` · ${c.chSubset.replace("{n}", String(ch.areaNos?.length ?? 0))}` : ""}
                    </Badge>
                    {canLock && !ch.denied && (
                      <Button
                        size="sm"
                        variant="ghost"
                        className="h-6 px-1 txt-caption"
                        onClick={() =>
                          ch.locked
                            ? confirm({
                                title: c.unlockTitle.replace("{ch}", channelName(ch.channel)),
                                action: () => lockMut.mutateAsync({ storeNo: s.storeNo, channel: ch.channel, locked: false }),
                              })
                            : confirm({
                                title: c.lockTitle.replace("{ch}", channelName(ch.channel)),
                                desc: c.lockDesc,
                                danger: true,
                                requireReason: true,
                                action: (reason) => lockMut.mutateAsync({ storeNo: s.storeNo, channel: ch.channel, locked: true, reason }),
                              })
                        }
                      >
                        {ch.locked ? c.btnUnlock : c.btnLock}
                      </Button>
                    )}
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
      {dialog}
    </Field>
  );
}
