"use client";

// 开放对接的钥匙（P-18.4）。**本模块唯一的写口。**
//
// 为什么它此前不存在，而不存在等于开放接口没做完：三个 `/open/v1` 端点
// 早就写完了、签发与吊销的服务层也在，缺的只是控制器与这一屏。
// 于是唯一发得出钥匙的办法是直接往 `inv_open_credential` 里插 ——
// 而那正是 `inventory-write-ownership` 守卫拦的事。
//
// **这一屏与另外三页不同的地方只有一处，但它决定了整屏的形状**：
// secret 只在签发那一刻明文出现一次。所以签发之后不能只弹一句"成功"，
// 必须把那串字摆出来、给一个复制按钮、并且明说关掉就没了。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import type { InvCredential, InvCredentialIssued } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import type { FormValues } from "@/lib/form-validate";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useCan } from "@/lib/use-can";
import type { InventoryCopy } from "./copy";

export function CredentialsTab({ c }: { c: InventoryCopy }) {
  // 与台账那一页同一个道理：输入框与生效值分开，不然每敲一个字打一次接口
  const [entityInput, setEntityInput] = useState("");
  const [entityNo, setEntityNo] = useState("");
  const [drawer, setDrawer] = useState(false);
  const [form, setForm] = useState<FormValues>({ name: "", scopes: "read", expiresAt: "" });
  const [issued, setIssued] = useState<InvCredentialIssued | null>(null);
  const [copied, setCopied] = useState(false);

  const qc = useQueryClient();

  /*

   * **签发与吊销要按权限藏，看列表不用。**

   *

   * 这一屏的可见性判 `inventory:credential:read`，而两个写动作判的是

   * `inventory:credential:grant` —— 两者的持有人并不重合：GOODS_OPS 与 AUDITOR

   * 有前者没有后者。不藏的结果是他们看得见「签发新钥匙」、点下去一片 403，

   * 而看的人只会认为功能坏了。

   *

   * （2026-08-29 前这两个码分别是 `product:sku:read` 与 `merchant:mode:update` ——

   * 借来的。后者尤其错位：它的意思是「改商家经营模式」，而 BD 持有它，

   * 于是 BD **事实上能发钥匙却看不见这一页**。见 Perms 的进销存那一段。）

   *

   * 藏掉之后他们仍看得到**有哪些钥匙发出去过** —— 那正是审计要看的东西。

   */

  const can = useCan();

  const canIssue = can("inventory:credential:grant");
  const { confirm, dialog } = useConfirm();

  const list = useQuery({
    queryKey: ["inv-creds", entityNo],
    queryFn: () => api.listInvCredentials({ entityNo }),
    enabled: !!entityNo,
  });

  const issue = useMutation({
    mutationFn: () =>
      api.issueInvCredential({
        entityNo,
        name: String(form.name ?? ""),
        scopes: String(form.scopes ?? "read"),
        // 空串要送 null 而不是 ""：后端那一列是「空 = 不过期」
        expiresAt: form.expiresAt ? `${form.expiresAt}T00:00:00` : null,
      }),
    onSuccess: (r) => {
      setDrawer(false);
      setCopied(false);
      setIssued(r);                       // **先摆出来，再刷新列表**
      void qc.invalidateQueries({ queryKey: ["inv-creds", entityNo] });
    },
  });

  const fields: FieldDef[] = [
    { key: "name", label: c.invCredName, placeholder: c.invCredNamePh, required: true },
    {
      key: "scopes", label: c.invCredScopes, type: "select",
      options: [
        { value: "read", label: c.invCredScopeRead },
        { value: "read,stock:sync", label: c.invCredScopeSync },
      ],
    },
    // 字段本身没有「说明」这一档（ValidatableField 里没有 hint），
    // 那句「留空 = 不过期」放在页面顶上的 Notice 里，见下方 invCredExpiresHint
    { key: "expiresAt", label: c.invCredExpires, type: "date" },
  ];

  const columns: Column<InvCredential>[] = [
    {
      header: c.invCredName,
      cell: (r) => (
        <div>
          <div>{r.name}</div>
          <div className="text-xs text-muted-foreground tabular-nums">{r.appKey}</div>
        </div>
      ),
    },
    { header: c.invColScopes, cell: (r) => <span className="text-xs">{r.scopes}</span> },
    {
      header: c.invColStatus,
      cell: (r) =>
        r.status === "ACTIVE"
          ? <Badge>{c.invCredActive}</Badge>
          : <Badge tone="muted">{c.invCredRevoked}</Badge>,
    },
    {
      header: c.invColLastUsed,
      // **空着不写「—」**：「从未使用」是一条结论（这把钥匙发出去没人接），
      // 而破折号只是「没有值」，两者在这一列上完全不是一回事
      cell: (r) => r.lastUsedAt
        ? <span className="tabular-nums">{fmtTime(r.lastUsedAt)}</span>
        : <span className="text-muted-foreground">{c.invCredNever}</span>,
    },
    {
      header: c.invColExpires,
      cell: (r) => r.expiresAt
        ? <span className="tabular-nums">{fmtTime(r.expiresAt)}</span>
        : <span className="text-muted-foreground">{c.invCredNoExpiry}</span>,
    },
    {
      header: "",
      cell: (r) =>
        r.status === "ACTIVE" && canIssue ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() =>
              confirm({
                title: c.invCredRevokeTitle,
                desc: c.invCredRevokeBody,
                danger: true,
                confirmText: c.invCredRevoke,
                // 传 action：吊销不可撤销，点完到生效之间要看得见「正在处理」
                action: async () => {
                  await api.revokeInvCredential(r.credentialId);
                  await qc.invalidateQueries({ queryKey: ["inv-creds", entityNo] });
                },
              })
            }
          >
            {c.invCredRevoke}
          </Button>
        ) : null,
    },
  ];

  return (
    <div className="space-y-4">
      <Notice>{c.invCredNotice}</Notice>
      <Notice tone="muted">{c.invCredExpiresHint}</Notice>

      <Toolbar>
        <Input
          className="w-64"
          placeholder={c.invCredEntityPh}
          value={entityInput}
          onChange={(e) => setEntityInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && setEntityNo(entityInput.trim())}
        />
        <Button variant="secondary" onClick={() => setEntityNo(entityInput.trim())}>
          {c.invCredEntity}
        </Button>
        <div className="flex-1" />
        {canIssue && (
          <Button disabled={!entityNo} onClick={() => setDrawer(true)}>
            {c.invCredIssue}
          </Button>
        )}
      </Toolbar>

      {entityNo && (
        <DataTable
          columns={columns}
          rows={list.data ?? []}
          loading={list.isLoading}
          empty={c.invCredEmpty}
          rowKey={(r) => r.credentialId}
        />
      )}

      <FormDrawer
        open={drawer}
        onOpenChange={setDrawer}
        titleNew={c.invCredIssue}
        titleEdit={c.invCredIssue}
        isEdit={false}
        fields={fields}
        value={form}
        onChange={setForm}
        onSubmit={() => issue.mutate()}
        submitting={issue.isPending}
      />

      {/*
        签发结果。**它不是一句「成功」** —— secret 这辈子只出现这一次，
        所以这一块要把整串摆出来、给复制按钮、并明说关掉就没了。
        用户点「我已经存好了」才关：点叉关掉太容易，而代价是只能吊销重发。
      */}
      {issued && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-4 space-y-3">
          <div className="font-medium">{c.invCredIssuedTitle}</div>
          <div className="text-sm text-muted-foreground">{c.invCredSecretOnce}</div>
          <div className="space-y-1 font-mono text-sm">
            <div className="break-all">App Key: {issued.appKey}</div>
            <div className="break-all">App Secret: {issued.appSecret}</div>
          </div>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="secondary"
              onClick={() => {
                void navigator.clipboard?.writeText(
                  `AppKey: ${issued.appKey}\nAppSecret: ${issued.appSecret}`,
                );
                setCopied(true);
              }}
            >
              {copied ? c.invCredCopied : c.invCredCopy}
            </Button>
            <Button size="sm" onClick={() => setIssued(null)}>{c.invCredDone}</Button>
          </div>
        </div>
      )}

      {dialog}
    </div>
  );
}
