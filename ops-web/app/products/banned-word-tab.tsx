"use client";

// 平台禁售词（商品①）。商家提审商品时前置校验标题。
//
// **此前只有事后驳回**：带违禁词的标题会进审核队列、占一个审核员的时间、再被驳回，
// 而商家隔几天才知道要改哪个字。2026-09-03 线上 194 件卡在审核里，
// 而这条链的入口没有任何前置检查。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/use-can";
import type { BannedWord } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Toolbar } from "@/components/ui/toolbar";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import type { ProductsCopy } from "./copy";

export function BannedWordTab({ c }: { c: ProductsCopy }) {
  const allow = useCan();
  // 读与写分开：加一个词会让存量商品下次提审全被拦，与「看一眼词表」不是一件事
  const canEdit = allow("product:category:update");
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [word, setWord] = useState("");
  const [reason, setReason] = useState("");

  const list = useQuery({ queryKey: ["banned-words"], queryFn: () => api.bannedWords() });

  const add = useMutation({
    mutationFn: () => api.addBannedWord({ word: word.trim(), reason: reason.trim() || undefined }),
    onSuccess: (rows) => {
      qc.setQueryData(["banned-words"], rows);
      setWord(""); setReason("");
      notify.success(c.bwAdded);
    },
  });
  const remove = useMutation({
    mutationFn: (id: number) => api.removeBannedWord(id),
    onSuccess: (rows) => qc.setQueryData(["banned-words"], rows),
  });

  const columns: Column<BannedWord>[] = [
    { header: c.bwWord, cell: (w) => <span className="font-mono">{w.word}</span> },
    // 理由原样进商家收到的报错，所以它在表里也要显眼 —— 空着的那些该补
    { header: c.bwReason,
      cell: (w) => w.reason || <span className="text-[var(--destructive-ink)]">{c.bwNoReason}</span> },
    {
      header: c.bwAction,
      cell: (w) => (canEdit ? (
        <Button size="sm" variant="ghost" disabled={remove.isPending}
          onClick={() => void confirm({
            title: c.bwRemoveTitle.replace("{w}", w.word),
            desc: c.bwRemoveDesc,
            action: () => remove.mutateAsync(w.id),
          })}>{c.bwRemove}</Button>
      ) : <span className="text-muted-foreground">—</span>),
    },
  ];

  return (
    <>
      {dialog}
      <HelpNote title={c.bwHelpTitle}>{c.bwHelp}</HelpNote>
      {!canEdit && <ReadOnlyNotice what={c.bwReadOnly} perm="product:category:update" className="mb-3" />}

      {canEdit && (
        <Toolbar>
          <Input value={word} onChange={(e) => setWord(e.target.value)}
                 placeholder={c.bwWordPh} className="w-48" />
          {/* 理由不是可选的装饰：它会原样出现在商家看到的报错里 */}
          <Input value={reason} onChange={(e) => setReason(e.target.value)}
                 placeholder={c.bwReasonPh} className="w-72" />
          <Button disabled={!word.trim() || add.isPending} onClick={() => add.mutate()}>
            {c.bwAdd}
          </Button>
        </Toolbar>
      )}

      <DataTable rows={list.data} columns={columns} loading={list.isPending}
        error={list.error} onRetry={() => void list.refetch()}
        rowKey={(w) => String(w.id)} empty={c.bwEmpty} />
    </>
  );
}
