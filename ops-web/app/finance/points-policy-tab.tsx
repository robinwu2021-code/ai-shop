"use client";

// 积分的端开关 —— 接 `/ops/points/client-policy`。
//
// 三件事：哪个端不发放、哪个端不核销、当面付能不能抵扣。
//
// ⚠️ **存的是禁用名单，不是允许名单。** `X-Client` 头今天还没有哪个端全量在发 ——
// 用允许名单的话，没带头的请求一律落到「不在名单里」，开关一上线就把全站积分
// 静默关掉了。禁用名单下一条策略只约束**自报家门的那些端**。
//
// ⚠️ **这不是合规硬闸。** 端标识来自客户端、天然可伪造。它能做到的是
// 「让自报家门的那个端不发/不用积分」，做不到的是「保证某个端一定拿不到积分」。
// 要后者得在端侧和签名上做文章 —— 这句必须写在界面上，否则会有人拿它当合规凭据。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { ClientPointsPolicy } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type { FinanceCopy } from "./copy";

/** 与后端 `PayScenes` 逐字一致。多一个少一个都不会报错，只会静默不生效 */
const CLIENTS = ["MP_WECHAT", "MP_ALIPAY", "IOS", "ANDROID", "H5"] as const;

export function PointsPolicyTab({ c, canEdit }: { c: FinanceCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const list = useQuery({
    queryKey: ["points-client-policy"],
    queryFn: () => api.pointsClientPolicy(),
  });
  const [draft, setDraft] = useState<ClientPointsPolicy | null>(null);
  useEffect(() => {
    if (list.data) setDraft(list.data);
  }, [list.data]);

  const save = useMutation({
    mutationFn: (v: ClientPointsPolicy) => api.savePointsClientPolicy(v),
    onSuccess: (v) => qc.setQueryData(["points-client-policy"], v),
  });

  if (!draft) return <div className="text-[13px] text-muted-foreground">{c.ppLoading}</div>;

  function toggle(list: string[], v: string) {
    return list.includes(v) ? list.filter((x) => x !== v) : [...list, v];
  }

  /**
   * 「能核销不能发放」是**合法组合**：某个端停止发分（比如 iOS 的虚拟支付规则），
   * 但用户已有的分照样能在那儿花掉。所以这里**只提示、不禁止** ——
   * 硬禁会挡住一个真实需要的配置，而运营没有别的地方能表达它。
   */
  const oddPairs = CLIENTS.filter(
    (v) => draft!.earnDeny.includes(v) && !draft!.redeemDeny.includes(v),
  );

  return (
    <>
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold">{c.ppTitle}</h3>
      </div>
      <Notice className="mb-3" tone="warning">{c.ppNotSecurityGate}</Notice>

      <div className="space-y-4 rounded-card border border-border p-4">
        <Row
          label={c.ppEarnDeny} hint={c.ppEarnHint} clients={CLIENTS}
          picked={draft.earnDeny} disabled={!canEdit}
          onToggle={(v) => setDraft({ ...draft, earnDeny: toggle(draft.earnDeny, v) })}
        />
        <Row
          label={c.ppRedeemDeny} hint={c.ppRedeemHint} clients={CLIENTS}
          picked={draft.redeemDeny} disabled={!canEdit}
          onToggle={(v) => setDraft({ ...draft, redeemDeny: toggle(draft.redeemDeny, v) })}
        />

        <label className="flex items-start gap-2 text-[13px]">
          <input
            type="checkbox" className="focus-ring mt-1" disabled={!canEdit}
            checked={draft.offlineRedeem}
            onChange={(e) => setDraft({ ...draft, offlineRedeem: e.target.checked })}
          />
          <span>
            <span className="font-semibold">{c.ppOfflineRedeem}</span>
            <span className="block text-[12px] text-muted-foreground">{c.ppOfflineHint}</span>
          </span>
        </label>

        {oddPairs.length > 0 && (
          <Notice tone="warning">
            {c.ppOddPair.replace("{clients}", oddPairs.join("、"))}
          </Notice>
        )}

        {canEdit && (
          <Button disabled={save.isPending} onClick={() => save.mutate(draft)}>
            {save.isPending ? c.ppSaving : c.ppSave}
          </Button>
        )}
      </div>
    </>
  );
}

function Row({ label, hint, clients, picked, disabled, onToggle }: {
  label: string; hint: string; clients: readonly string[];
  picked: string[]; disabled: boolean; onToggle: (v: string) => void;
}) {
  return (
    <div>
      <div className="text-[13px] font-semibold">{label}</div>
      <div className="mb-2 text-[12px] text-muted-foreground">{hint}</div>
      <div className="flex flex-wrap gap-1.5">
        {clients.map((v) => (
          <button
            key={v} type="button" disabled={disabled} onClick={() => onToggle(v)}
            className={`focus-ring rounded-chip border px-2.5 py-1 text-[12px] ${
              picked.includes(v)
                ? "border-destructive bg-destructive-tint text-destructive-text"
                : "border-border hover:bg-muted"
            }`}
          >
            {v}
          </button>
        ))}
      </div>
    </div>
  );
}
