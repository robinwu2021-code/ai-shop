"use client";
import { useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useReportingIdentity } from "@/lib/use-reporting-identity";
import { useCan } from "@/lib/use-can";
import { usePaging } from "@/lib/use-paging";
import { useCopy } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { TextFilter } from "@/components/ui/filter-select";
import { HelpNote } from "@/components/ui/help-note";
import { StatCard, StatRow } from "@/components/ui/misc";
import { PagedTable } from "@/components/ui/paged-table";
import { Toolbar } from "@/components/ui/toolbar";
import type { CustomerRegistration, CustomerStore, CustomerBrand, PlatformMember, CustomerMembership, CustomerMemberSource } from "@/lib/api/contracts/customer";
import { CUSTOMERS_COPY, type CustomersCopy } from "./copy";

function sourceName(c:CustomersCopy,type:string|null){return type?(c as Record<string,string>)[`source_${type}`]??type:c.missing;}
function storeCols(c:CustomersCopy,canRead:boolean):Column<CustomerStore>[] {return [
  {header:c.storeName,cell:r=>canRead?<Link className="focus-ring underline" href={`/customers?tab=stores&merchantNo=${encodeURIComponent(r.entityNo)}`}>{r.name??r.storeNo}</Link>:r.name??r.storeNo},
  {header:c.storeNo,cell:r=>r.storeNo},{header:c.entity,cell:r=>r.entityNo},{header:c.brand,cell:r=>r.brandName??r.brandNo??c.missing},
];}
export function RegistrationsTab(){
  const identity=useReportingIdentity();
  const c=useCopy(CUSTOMERS_COPY),can=useCan(),paging=usePaging(),[keyword,setKeyword]=useState(""),[selected,setSelected]=useState<CustomerRegistration|null>(null);
  const q=useQuery({queryKey:[...identity,"customer-registrations",keyword,paging.page,paging.size],queryFn:()=>api.customerRegistrations({keyword:keyword||undefined,page:paging.page,size:paging.size})});
  const stores=useQuery({queryKey:[...identity,"customer-registration-stores",selected?.userNo],queryFn:()=>api.customerRegistrationStores(selected!.userNo),enabled:!!selected});
  const cols:Column<CustomerRegistration>[]=[{header:c.userNo,cell:r=>r.userNo},{header:c.nickname,cell:r=>r.nickname??c.missing},{header:c.status,cell:r=>r.status},{header:c.market,cell:r=>r.market??c.missing},{header:c.registeredAt,cell:r=>fmtTime(r.registeredAt)},{header:c.entities,numeric:true,cell:r=>r.entityCount},{header:c.storeCount,numeric:true,cell:r=>r.storeCount},{header:c.detail,cell:r=><Button size="sm" variant="outline" onClick={()=>setSelected(r)}>{c.detail}</Button>}];
  return <><HelpNote>{c.userHint}</HelpNote><Toolbar search={keyword} onSearch={v=>{setKeyword(v);paging.setPage(1);}} searchPlaceholder={c.searchUsers}/>
    <StatRow><StatCard label={c.totalUsers} value={q.isSuccess?q.data.total:null} loading={q.isLoading}/><StatCard label={c.withoutStores} value={q.isSuccess?q.data.withoutStores:null} loading={q.isLoading}/><StatCard label={c.newUsers} value={q.isSuccess?q.data.newLast30Days:null} loading={q.isLoading}/></StatRow>
    <PagedTable query={{...q,data:q.data?.page}} {...pagingProps(paging)} columns={cols} rowKey={r=>r.userNo} empty={c.empty}/>
    <Card><CardHeader><CardTitle>{c.trend}</CardTitle></CardHeader><CardContent><DataTable columns={[{header:c.trendDay,cell:r=>r.date},{header:c.count,numeric:true,cell:r=>r.count}]} rows={q.data?.trend} loading={q.isLoading} error={q.error} onRetry={()=>q.refetch()} rowKey={r=>r.date} empty={c.empty}/></CardContent></Card>
    <Drawer open={!!selected} onOpenChange={open=>{if(!open)setSelected(null);}} title={selected?.nickname??selected?.userNo??c.users} desc={selected?.userNo}><DataTable columns={storeCols(c,can("merchant:merchant:read"))} rows={stores.data} loading={stores.isLoading} error={stores.error} onRetry={()=>stores.refetch()} rowKey={r=>r.storeNo} empty={c.empty}/></Drawer>
  </>;
}
function pagingProps(p:ReturnType<typeof usePaging>){return {page:p.page,size:p.size,onPage:p.setPage,onSize:p.setSize};}
export function BrandsTab(){
  const identity=useReportingIdentity();
  const c=useCopy(CUSTOMERS_COPY),can=useCan(),paging=usePaging(),[keyword,setKeyword]=useState(""),[selected,setSelected]=useState<CustomerBrand|null>(null);
  const q=useQuery({queryKey:[...identity,"customer-brands",keyword,paging.page,paging.size],queryFn:()=>api.customerBrands({keyword:keyword||undefined,page:paging.page,size:paging.size})});
  const cols:Column<CustomerBrand>[]=[{header:c.brand,cell:r=><span>{r.name}<br/><small>{r.brandNo}</small></span>},{header:c.management,cell:r=>r.managementName??r.managementNo},{header:c.status,cell:r=>r.status},{header:c.storeCount,numeric:true,cell:r=>r.stores.length},{header:c.detail,cell:r=><Button size="sm" variant="outline" onClick={()=>setSelected(r)}>{c.detail}</Button>}];
  return <><HelpNote>{c.chainHint}</HelpNote><Toolbar search={keyword} onSearch={v=>{setKeyword(v);paging.setPage(1);}} searchPlaceholder={c.searchBrands}/><StatRow><StatCard label={c.brandCount} value={q.isSuccess?q.data.total:null} loading={q.isLoading}/><StatCard label={c.currentStores} value={q.isSuccess?new Set(q.data.records.flatMap(b=>b.stores.map(s=>s.storeNo))).size:null} loading={q.isLoading}/></StatRow>
    <PagedTable query={q} {...pagingProps(paging)} columns={cols} rowKey={r=>r.brandNo} empty={c.empty}/>
    <Drawer open={!!selected} onOpenChange={open=>{if(!open)setSelected(null);}} title={selected?.name??c.chains} desc={selected?.brandNo}><FieldGrid><Field label={c.management}>{selected?.managementName??selected?.managementNo}</Field><Field label={c.status}>{selected?.status}</Field></FieldGrid><DataTable columns={storeCols(c,can("merchant:merchant:read"))} rows={selected?.stores} error={q.error} onRetry={()=>q.refetch()} rowKey={r=>r.storeNo} empty={c.empty}/></Drawer>
  </>;
}
export function PlatformMembersTab(){
  const identity=useReportingIdentity();
  const c=useCopy(CUSTOMERS_COPY),can=useCan(),sp=useSearchParams(),paging=usePaging(),[keyword,setKeyword]=useState(""),[storeNo,setStoreNo]=useState(sp.get("storeNo")??""),[brandNo,setBrandNo]=useState(sp.get("brandNo")??""),[selected,setSelected]=useState<string|null>(null);
  const validKeyword=!keyword||/^(?:[0-9]{4}|[A-Za-z][A-Za-z0-9_-]{2,63})$/.test(keyword);
  const q=useQuery({queryKey:[...identity,"platform-members",keyword,storeNo,brandNo,paging.page,paging.size],queryFn:()=>api.platformMembers({keyword:keyword||undefined,storeNo:storeNo||undefined,brandNo:brandNo||undefined,page:paging.page,size:paging.size}),enabled:validKeyword});
  const detail=useQuery({queryKey:[...identity,"platform-member",selected],queryFn:()=>api.platformMember(selected!),enabled:!!selected});
  const cols:Column<PlatformMember>[]=[{header:c.memberNo,cell:r=><Button size="sm" variant="outline" onClick={()=>setSelected(r.personNo)}>{r.personNo}</Button>},{header:c.phone,cell:r=>r.phoneTail?`···${r.phoneTail}`:c.missing},{header:c.sourceStore,cell:r=>r.sourceStoreName??r.sourceStoreNo??c.unknownSource},{header:c.source,cell:r=>sourceName(c,r.sourceType)},{header:c.joinedAt,cell:r=>fmtTime(r.joinedAt)},{header:c.membershipCount,numeric:true,cell:r=>r.membershipCount},{header:c.status,cell:r=>r.status}];
  const membershipCols:Column<CustomerMembership>[]=[{header:c.relation,cell:r=>r.memberNo},{header:c.entity,cell:r=>r.entityName??r.entityNo},{header:c.sourceStore,cell:r=>r.sourceStoreName??r.sourceStoreNo??c.unknownSource},{header:c.joinedAt,cell:r=>fmtTime(r.joinedAt)},{header:c.status,cell:r=>r.status}];
  const historyCols:Column<CustomerMemberSource>[]=[{header:c.relation,cell:r=>r.memberNo},{header:c.sourceType,cell:r=>sourceName(c,r.sourceType)},{header:c.sourceStore,cell:r=>r.storeName??r.storeNo??c.unknownSource},{header:c.sourceTime,cell:r=>fmtTime(r.occurredAt)},{header:c.firstSource,cell:r=>r.first?c.yes:c.no}];
  return <><HelpNote>{c.memberHint}</HelpNote><Toolbar search={keyword} onSearch={v=>{setKeyword(v.trim());paging.setPage(1);}} searchPlaceholder={c.searchMembers}><TextFilter value={storeNo} onChange={v=>{setStoreNo(v);paging.setPage(1);}} placeholder={c.storeFilter} aria-label={c.storeFilter}/><TextFilter value={brandNo} onChange={v=>{setBrandNo(v);paging.setPage(1);}} placeholder={c.brandFilter} aria-label={c.brandFilter}/></Toolbar>
    {!validKeyword?<p role="status">{c.searchMembers}</p>:<PagedTable query={q} {...pagingProps(paging)} columns={cols} rowKey={r=>r.personNo} empty={c.empty}/>}
    <Drawer open={!!selected} onOpenChange={open=>{if(!open)setSelected(null);}} title={selected??c.members}>
      {detail.error&&<p role="alert">{String(detail.error)} <Button variant="outline" onClick={()=>detail.refetch()}>{c.retry}</Button></p>}
      {detail.data&&<><FieldGrid><Field label={c.userNo}>{detail.data.person.userNo??c.noAccount}</Field><Field label={c.sourceStore}>{detail.data.person.sourceStoreName??detail.data.person.sourceStoreNo??c.unknownSource}</Field></FieldGrid><DrawerSection title={c.relationships}><DataTable columns={membershipCols} rows={detail.data.memberships} error={detail.error} onRetry={()=>detail.refetch()} rowKey={r=>r.memberNo} empty={c.empty}/></DrawerSection><DrawerSection title={c.linkedStores}><DataTable columns={storeCols(c,can("merchant:merchant:read"))} rows={detail.data.stores} error={detail.error} onRetry={()=>detail.refetch()} rowKey={r=>r.storeNo} empty={c.empty}/></DrawerSection><DrawerSection title={c.history}><DataTable columns={historyCols} rows={detail.data.sources} error={detail.error} onRetry={()=>detail.refetch()} rowKey={r=>r.sourceNo} empty={c.empty}/></DrawerSection></>}
    </Drawer>
  </>;
}
