"use client";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useCan } from "@/lib/use-can";
import { useCopy } from "@/lib/use-copy";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { HelpNote } from "@/components/ui/help-note";
import { MALL_COPY } from "./copy";
import { PURCHASE_WORKSPACE_COPY } from "./purchase-workspace-copy";
import { OrdersTab } from "./orders-tab";
import { EntityOrdersTab } from "./entity-orders-tab";

type Kind = "ENTITY" | "STORE";
export function PurchaseWorkspace({ initialKind = "STORE" }: { initialKind?: Kind }) {
  const allow = useCan(), sp = useSearchParams(), c = useCopy(PURCHASE_WORKSPACE_COPY), mall = useCopy(MALL_COPY);
  const permitted = { ENTITY: allow("merchant:merchant:read"), STORE: allow("commerce:mall:order:read") };
  const [kind, setKind] = useState<Kind>(() => sp.get("kind") === "ENTITY" ? "ENTITY" : sp.get("kind") === "STORE" ? "STORE" : initialKind);
  const active = permitted[kind] ? kind : permitted.ENTITY ? "ENTITY" : "STORE";
  const initialScope = sp.get(active === "ENTITY" ? "entity" : "store") ?? "";
  const [draft, setDraft] = useState(initialScope);
  const [scope, setScope] = useState({ kind: active, no: initialScope });
  const [locked, setLocked] = useState(false);
  if (!permitted.ENTITY && !permitted.STORE) return <p role="status">{c.denied}</p>;
  const current = scope.kind === active ? scope.no : "";
  const directOrder = sp.get(active === "ENTITY" ? "entity" : "store") === current ? sp.get("order") : null;
  return <section className="space-y-3" aria-label={c.title}>
    <HelpNote>{c.hint}</HelpNote>
    <form className="flex flex-wrap items-center gap-2" onSubmit={e => { e.preventDefault(); if (!locked) setScope({ kind: active, no: draft.trim() }); }}>
      <Select aria-label={c.kind} value={active} disabled={locked} className="w-auto" onChange={e => {
        if (locked) return;
        const next = e.target.value as Kind;
        if (!permitted[next]) return;
        setKind(next); setDraft(""); setScope({ kind: next, no: "" });
      }}>
        {permitted.ENTITY && <option value="ENTITY">{c.entity}</option>}
        {permitted.STORE && <option value="STORE">{c.store}</option>}
      </Select>
      <Input aria-label={c.scope} value={draft} disabled={locked} maxLength={64} className="max-w-xs" placeholder={active === "ENTITY" ? c.entityPlaceholder : c.storePlaceholder} onChange={e => setDraft(e.target.value)} />
      <Button type="submit" disabled={locked || !draft.trim()}>{c.query}</Button>
    </form>
    {locked && <p role="status" className="txt-caption text-muted-foreground">{c.locked}</p>}
    {!current ? <p className="txt-caption text-muted-foreground">{c.first}</p> : active === "ENTITY"
      ? <EntityOrdersTab key={`ENTITY:${current}`} entityNo={current} initialOrderNo={directOrder} onContextLockChange={setLocked} />
      : <OrdersTab key={`STORE:${current}`} c={mall} scopedStoreNo={current} initialOrderNo={directOrder} onDetailOpenChange={setLocked} />}
  </section>;
}
