import type { PrintApi } from "../contracts/print";
import { currentAuth } from "@/lib/auth";
import { can } from "@/lib/permissions";
import { scopedTarget } from "@/lib/print-management";
import type { PrintTarget } from "../print-types";
function unavailable(q:PrintTarget,perm:string):never {
 scopedTarget(q); const a=currentAuth();
 if(!a||q.tenantNo!==a.tenantNo||!can(a.perms,perm))throw Error("Print access denied");
 // No mock source facts or actor entity/store grants exist. Fail closed rather than simulate audited maintenance.
 throw Error("Printing requires the enabled backend and authorized target; mock execution is unavailable");
}
export const printMock:PrintApi={
 printHealth:async q=>unavailable(q,"ops:print:read"),
 printIncidents:async q=>unavailable(q,"ops:print:read"),
 reconcilePrint:async q=>unavailable(q,"ops:print:resolve"),
 printOperation:async(_,q)=>unavailable(q,"ops:print:read"),
};
