"use client";

// 商家履约配置（方案 v4，**只读**）。挂在商家档案抽屉里、人员授权块之前。
//
// 为什么运营要看得到：履约投诉（「说好自提到了没货」「快递一直不发」）的第一问
// 永远是「这家店到底开了哪几路」——在此之前答案只在商家自己的手机上。
// 这一块把 门店 × 送货方式 摆成矩阵，一眼看清。
//
// **刻意没有任何写操作**：怎么送是商家的经营决策，写入口在 B 端；
// 平台的干预是锁路（P2 的 ops_locked），那是处置动作，有单独的权限码与审计。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCopy } from "@/lib/use-copy";
import { Badge } from "@/components/ui/badge";
import { Field } from "@/components/ui/drawer";
import { MERCHANTS_COPY } from "./copy";

export function FulfillmentBlock({ merchantNo }: { merchantNo: string }) {
  const c = useCopy(MERCHANTS_COPY);
  const { data = [], isLoading } = useQuery({
    queryKey: ["merchant-fulfillment", merchantNo],
    queryFn: () => api.merchantFulfillment(merchantNo),
  });

  const channelName = (ch: string) => (c as Record<string, string>)[`ch${ch}`] ?? ch;

  if (isLoading) return <Field label={c.fieldFulfillment}>…</Field>;

  return (
    <Field label={c.fieldFulfillment}>
      {data.length === 0 ? (
        // 空 = 该主体还没迁移到 channel 模型（或没有门店）。说清楚，别让人当成「全关了」
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
              <div className="mt-1 flex flex-wrap gap-1">
                {s.channels.map((ch) => (
                  <Badge
                    key={ch.channel}
                    tone={ch.denied ? "warning" : ch.enabled ? "success" : "muted"}
                  >
                    {channelName(ch.channel)}
                    {ch.denied ? ` · ${c.chDenied}` : ch.enabled ? "" : ` · ${c.chOff}`}
                  </Badge>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </Field>
  );
}
