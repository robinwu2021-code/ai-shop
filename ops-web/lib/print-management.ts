import type { PrintTarget, PrintReconcileRequest } from "./api/print-types";
export function scopedTarget(input:PrintTarget):PrintTarget {
 const t={tenantNo:input.tenantNo.trim(),entityNo:input.entityNo.trim(),storeNo:input.storeNo.trim()};
 if(!t.tenantNo||t.tenantNo.length>32||!t.entityNo||t.entityNo.length>64||!t.storeNo||t.storeNo.length>64)throw Error("target");return t;
}
export function reconcileRequest(target:PrintTarget,sourceKind:string,from:string,to:string,dryRun:boolean,reason:string):PrintReconcileRequest {
 const start=Date.parse(from),end=Date.parse(to);
 if(!["SUB_ORDER","REFUND","RECHARGE"].includes(sourceKind.trim())||!reason.trim()||!Number.isFinite(start)||!Number.isFinite(end)||start>=end)throw Error("range");
 return {...scopedTarget(target),sourceKind:sourceKind.trim(),from:new Date(start).toISOString(),to:new Date(end).toISOString(),dryRun,reason:reason.trim()};
}
