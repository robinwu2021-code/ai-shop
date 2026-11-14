# Spec Compliance

- ✅ I1 ADDRESSED at the design level. The scoped repair is spec compliant: `bm05-offline-core-design.md:43` removes successful void-return admission, and `:50`–`:55` require an implemented original-owner disposition contract before ADMITTED.
- ⚠️ This approves the design repair, not implementation, test execution or deployment. The future evidence described in `task-63-design-report.md:33` is explicitly not executed. The prior review's other design conclusions remain unchanged; Task62 is not reopened.

## Strengths

- `bm05-offline-core-design.md:50`–`:53`: the new port binds frozen authority, lifecycle, complete original manifest and deterministic confirmation operation. Full owner-row coverage, exact quantities and original operation markers prevent missing/released rows or a retrospectively attached operation from becoming successful evidence.
- `bm05-offline-core-design.md:53`–`:55`: PLATFORM/INVENTORY lifecycle1/2 proof is concrete; INVENTORY v2 acceptance stays pending until actual allocation, expiry serializes at the reservation owner, and DUAL retains platform authority. Replay observes existing completion without another deduction. Unreachable historical authority returns UNKNOWN instead of re-reserving under current configuration.
- `bm05-offline-core-design.md:54`–`:56`: tracked mutation count checks, original outbound evidence and the actual provider/service/consumer file inventory make the port an implementation obligation rather than a permissive interface shell. Existing owner rows and algorithms are reused; no new stock ledger is introduced.
- `bm05-offline-core-design.md:110`, `:139`, `:148`–`:149`, `:160`, `:162`: the added inventory migration, recovery behavior, six real-provider cases, explicit inventory reactor module/two schemas and pending-admission ablation are consistent with the repaired boundary. The first-file migration hash gate remains required.

## Issues

### Critical

None identified in the scoped repair.

### Important

None identified in the scoped repair. I1 is closed for design release.

### Minor

None raised.

## Assessment

**Task quality: Approved — design only.**

The repair now specifies an implementable stock outcome gate with concrete owners, persistence, recovery and tests. The expanded stock footprint is necessary to support the existing topology/lifecycle combinations and addresses the reported defect without weakening resource, source or financial isolation requirements.

## Review evidence and bounded source checks

- Reviewed the updated I1 report section and immutable delta `/tmp/task63-offline-design-r1-review.diff`; SHA256 verified as `0d22b5c434c39f3ac7a9274157c874130a33e536eb679d789688adbf6994f35b`. The initial combined output truncated part of the delta; only the unseen portion was recovered in bounded chunks. No changed-file reread or old-review replay.
- Named fix risk: the proposed inventory proof might lack actual persistent quantity/outbound evidence or a real consumer/expiry serialization point. Checked `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/impl/ReservationServiceImpl.java:74` (reserve), `:147` (commit/outbound), `:182` (allocation), `:359` (expiry), `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvReservation.java`, `InvReservationLine.java`, and `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/InventoryMirrorConsumer.java:91`/`:109`/`:121`. Existing heads/lines carry original quantities, lifecycle, release/issue progress and outbound linkage; the delta explicitly adds missing manifest/source/operation metadata and tracked mutation checks. No new blocker found.
- Root's verification of all 50 source/migration pins accepted without repeating hashes. No broader financial/resource audit, tests, builds, native operations, installation, generators, git mutations, product edits or subagents. Only this report was written in the designated beauty worktree; the original restaurant checkout was untouched.
