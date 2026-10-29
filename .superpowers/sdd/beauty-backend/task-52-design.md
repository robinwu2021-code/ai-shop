# Task52 approved implementation design

Five beauty-web public controllers preserve baseline methods/DTOs, with explicit additive POST recovery. Reuse BizContext/StoreRequestScopeValidator/SecurityUtils; actual services perform live permissions and ownership. Typed BeautyBookingInternalApi delegates current tenant/realm/kind/actor; exact LoginUser factories and finally restoration after token. Four typed remote services. Neutral ResourceBookingInternalApi transports actor/scope/full Binding/factAt/replacement; CustomerIdentityInternalApi preserves EntitlementContext. BEAUTY_APPOINTMENT reverse source API verifies full Binding. Explicit 403/409/503 and remote field/identity validation; no RPC in transaction.

Topology: embedded unique local source; standalone HOST web+channel without core remote source; SERVER core local source and remote owner/customer. Lazy provider wiring avoids eager cycle. Disabled/ops/noAPI no public booking routes/jobs. Task53 must exercise true channel-only owner and actual reverse HTTP.

RED/GREEN: public routes/auth; TCP MCH/USR/C and token/no-owner, ownership, UNKNOWN, outage/malformed, complete binding and context restoration; topology/cycle tests. No UI/BM04/06 or frozen V1–4 changes.
