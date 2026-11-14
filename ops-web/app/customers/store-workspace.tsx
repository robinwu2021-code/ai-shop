"use client";
import { useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCan } from "@/lib/use-can";
import { useCopy } from "@/lib/use-copy";
import { Button } from "@/components/ui/button";
import { HelpNote } from "@/components/ui/help-note";
import { StoresTab } from "@/app/merchants/stores-tab";
import { ApplyTab } from "@/app/merchants/apply-tab";
import { MERCHANTS_COPY } from "@/app/merchants/copy";
import { CUSTOMERS_COPY } from "./copy";
export function StoreWorkspace(){
  const c=useCopy(CUSTOMERS_COPY),merchant=useCopy(MERCHANTS_COPY),can=useCan(),sp=useSearchParams();
  const [review,setReview]=useState(sp.get("view")==="review"&&can("merchant:apply:audit"));
  return <div className="space-y-3"><HelpNote>{c.reviewHint}</HelpNote>
    <div className="flex flex-wrap gap-2"><Button variant={review?"outline":"default"} onClick={()=>setReview(false)}>{c.stores}</Button>{can("merchant:apply:audit")&&<Button variant={review?"default":"outline"} onClick={()=>setReview(true)}>{c.review}</Button>}</div>
    {review?<ApplyTab c={merchant} canAudit={can("merchant:apply:audit")} canReadSubscription={can("commerce:subscription:read")}/>:<StoresTab c={merchant} detailActions={store=><div className="flex flex-wrap gap-2">
      {can("merchant:merchant:ban")&&can("commerce:mall:assisted:purchase")&&<Button size="sm" variant="outline" asChild><Link href={`/mall?tab=orders&kind=ENTITY&entity=${encodeURIComponent(store.merchantNo)}`}>{c.assistEntity}</Link></Button>}
      {can("commerce:mall:assisted:purchase")&&<Button size="sm" variant="outline" asChild><Link href={`/mall?tab=assisted&store=${encodeURIComponent(store.storeNo)}`}>{c.assistStore}</Link></Button>}
      {can("member:member:read")&&<Button size="sm" variant="outline" asChild><Link href={`/customers?tab=members&storeNo=${encodeURIComponent(store.storeNo)}`}>{c.members}</Link></Button>}
    </div>}/>}
  </div>;
}
