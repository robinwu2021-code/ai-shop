"use client";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/use-can";
import { useCopy } from "@/lib/use-copy";
import { Button } from "@/components/ui/button";
import { DrawerSection, Field } from "@/components/ui/drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { StoreOperationalReview } from "@/lib/types";
import { STORE_REVIEW_COPY } from "./operational-review-copy";

export function OperationalReview({ storeNo, onChanged }: { storeNo: string; onChanged?: (review: StoreOperationalReview) => void }) {
  const c=useCopy(STORE_REVIEW_COPY); const allow=useCan(); const qc=useQueryClient();
  const {confirm,dialog}=useConfirm();
  const query=useQuery({queryKey:["store-operational-review",storeNo],queryFn:()=>api.getStoreOperationalReview(storeNo),retry:false});
  const mutation=useMutation({
    mutationFn:(command:{submit:true}|{approved:boolean;reason:string}) => "submit" in command
      ? api.submitStoreOperationalReview(storeNo) : api.decideStoreOperationalReview(storeNo,command.approved,command.reason),
    onSuccess:(result:StoreOperationalReview)=>{
      qc.setQueryData(["store-operational-review",storeNo],result);
      onChanged?.(result);
      void qc.invalidateQueries({queryKey:["stores-govern"]});
      void qc.invalidateQueries({queryKey:["store-detail",storeNo]});
    },
  });
  const data=query.data; const canAudit=allow("merchant:apply:audit");
  const label=data?.review==="APPROVED"?c.approved:data?.review==="REJECTED"?c.rejected:data?.review==="PENDING"?c.pending:c.unknown;
  return <DrawerSection title={c.title} desc={c.hint}>
    {query.isError?<p role="alert">{c.failed} <Button size="sm" onClick={()=>void query.refetch()}>{c.retry}</Button></p>:query.isPending?<p>…</p>:data&&<>
      <p role="status">{label}</p>
      {data.source&&<Field label={c.source}>{data.source==="MANUAL"?c.manual:data.source==="STORE"?c.store:c.entity}</Field>}
      {data.orderNo&&<Field label={c.order}>{data.orderNo}</Field>}
      {canAudit&&<div className="mt-2 flex gap-2">
        {(data.status==="ACTIVE"&&data.review===null||data.status==="PENDING_OPEN"&&data.review==="REJECTED")&&<Button size="sm" disabled={mutation.isPending} onClick={()=>void confirm({title:c.submit,desc:c.submitHint,danger:true,action:()=>mutation.mutateAsync({submit:true})})}>{c.submit}</Button>}
        {data.status==="PENDING_OPEN"&&data.review==="PENDING"&&<>
          <Button size="sm" disabled={mutation.isPending} onClick={()=>void confirm({title:c.approve,requireReason:true,reasonHint:c.reasonHint,action:(reason)=>mutation.mutateAsync({approved:true,reason})})}>{c.approve}</Button>
          <Button size="sm" variant="outline" disabled={mutation.isPending} onClick={()=>void confirm({title:c.reject,danger:true,requireReason:true,reasonHint:c.reasonHint,action:(reason)=>mutation.mutateAsync({approved:false,reason})})}>{c.reject}</Button>
        </>}
      </div>}
    </>}
    {dialog}
  </DrawerSection>;
}
