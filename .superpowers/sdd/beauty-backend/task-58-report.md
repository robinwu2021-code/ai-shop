# Task58 design report

Status: DESIGN_READY — design and literal verified on disk; implementation not released.

## Scope and base

- Assigned worktree: /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex
- Branch: codex/beauty-foundation
- DESIGN_BASE: eb9ec7734334214fe04d5b7d4e6f2593cc2cafd2
- Accepted Task57 product: 741b38178f01a6f4159fc618d57a45d75a3601e0
- Requirements authority: .superpowers/sdd/beauty-backend/task-58-brief.md
- Design artifact: .superpowers/sdd/beauty-backend/task-58-design.md
- Report artifact: .superpowers/sdd/beauty-backend/task-58-report.md

Only these two Markdown files are owned in this phase. No product/test/SQL/native/build/stage/commit actions
have been performed. No shared/original workspace file was changed. No helper/subagent was used.

## Read-only audit completed

The design audit table gives actual file:line evidence for:
- Task55C/55D trusted current B, USER/guest claim receipt, frozen scope and original OPEN draft;
- the delivered WorkOrderService open/get/getMy surface and actual Task56/57 eight-method CheckoutPort;
- PREPARE actor namespace, recovery limits, true current C checkout and current cashier receipt;
- sourceSnapshot RR, financial canonical receipts, UNKNOWN fields, per-checkout-operation source pair;
- V539 unique canonical order/subOrder identity and immutable original checkout operation;
- beauty source guards, task/binding/submission indexes, mapper predicates and transaction ownership;
- provider/Bean graph, actual shared owner bridges and fixture limitations;
- beauty V1–V7/ready7/27 and platform migration maximum V540 with frozen V539/V540 hashes;
- current native selectors/toolchain/resources and the scoped prospective owned paths.

Commands used in this phase were read-only inspection (git status/rev-parse, rg, nl/sed/cat,
Python text/hash inspection). No test/build/native SQL result is claimed by this report.

## Design decisions

1. Task58 allocates observed quantities within beauty, under its real local guard. It does not establish
   shared atomic consumption, an execution permit, or work start. canStart remains false and BM05 is closed.
2. A canonical source observation watermark is required. Existing source_qty >= allocated_qty CHECK prevents
   lowering a historical guard after a refund below held quantity; preserving the guard alone would allow
   a late old observation to be reused. V8 separates highest observed owner pair, last complete verified facts,
   and a local query epoch. A higher local epoch with a lower owner revision remains BLOCKED. A superseded
   response cannot restore READY, but a fully verified complete higher revision advances highest and BLOCKED
   under the same observation lock; this prevents delayed current lower facts from reviving refunded capacity.
   A superseded trusted owner UNKNOWN also preserves/raises highest and atomically increments epoch to invalidate
   all in-flight tickets, even without a last pair; recovery requires a new post-invalidation read. Foreign/malformed
   responses and transport failures do not get that authority. No V8 column or literal change is needed.
3. sourceRevision belongs to ord_checkout_operation. Canonical subOrder uniqueness, checkout_caller=BEAUTY,
   checkout_operation_no and original buyer/store/order identity are frozen and checked before comparing pairs.
   Before prepare resolves, the intent holds the original local operation identity and derived original owner key;
   it must not invent a canonical subOrder or trust a caller-supplied source alias.
4. Source reads deliberately claim a fresh epoch and set UNKNOWN before owner IO. Slow/failing reads temporarily
   suspend binding availability but preserve highest and last verified facts/history. GET may update this
   observation projection; it never automatically binds, confirms, places, pays, or starts work.
5. The owner source hash excludes response observedAt. A same-revision different-hash response is an owner
   contract inconsistency. READY can return only on a trusted matching highest pair or a newer revision.
6. Another current B can query original operation IDs but cannot recreate original PREPARE proof. A missing
   operation number plus NOT_FOUND in another B's namespace is not definitive absence. Recovery can require
   original actor and original prepare request; no C token or short customer reference is stored/replayed.
7. Bill amounts come from one trustworthy source response: deduplicated successful gross receipt minus net
   received can describe refunded money. UNKNOWN due=0 is nullable/untrusted. Currency totals are incomplete
   when a source is unknown or cannot be attributed wholly to this work order; there is no quantity prorating,
   cross-currency summation, due-minus-net collection authority, or new funds ledger.
8. Transactions contain only local DB work. Owner IO occurs after commit and before a separate fenced apply.
   Binding lock order is observation -> sorted line guards -> work order -> sorted tasks -> submission.

The design specifies concrete DTOs/service/helper/Mapper/Bean ownership, exact recovery phases, lock predicates,
upgrade checks, AC selectors, and a monotonic-revision ablation. No generic scheduler/background retry is proposed.

## Candidate migration (literal only)

- Proposed migration: V8__beauty_source_observation.sql
- Proposed beauty schema ready version/table count: 8 / 28
- New table: bty_source_observation (nonmonetary source facts and watermarks)
- Future source: docs/technical/design/schema/beauty-source-observation-schema.json
- Future generator: scripts/gen-beauty-source-observation-schema.mjs
- Future output: backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql
- Candidate exact UTF-8 bytes: 4582, LF, final LF included
- Candidate SHA-256: c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343

The SQL appears ONLY as a literal in the design. No SQL file or schema source/generator file has been created.
Root must independently extract/read/hash and pin the actual generated bytes before execution. This candidate
hash is not release permission. No platform V541 or edit to beauty V1–V7/platform V539/V540 is proposed.

## Verification status

- Design document final content audit: completed, including parent counterexamples for superseded higher facts
  and trusted owner UNKNOWN (with/without last pair); exact DTO/Mapper/Bean contracts and named AC are recorded.
- Literal extraction after filesystem write: one ~~~sql block, 4582 bytes, SHA-256 matches the candidate above.
- Both artifacts: UTF-8/LF/final LF, no trailing whitespace; design 830 lines at final content audit.
- Owned-path check: the two files exist and are ignored by .gitignore:42 (.superpowers/); no force-add performed.
  Ordinary git status shows only root-owned progress.md modified by another actor. This implementer did not touch it.
  The two design/report writes are the only mutations performed by this implementer.
- Product compilation: NOT RUN.
- Unit/integration/native tests: NOT RUN.
- Business RED/GREEN/ablation: NOT RUN; design lists feasible selectors, not results.
- Native MySQL/container/VM operation: NOT RUN.
- SQL execution/migration: NOT RUN.
- Stage/commit: NOT PERFORMED.

Future execution is limited to the brief's JDK21/offline Maven and cached MySQL 9.7.2, DISK64M,
colima-hxkey-tests / hxkey-tests profile, one own UUID serial run, at least 512 MiB, own-resource finally cleanup.
No historical passing test is relabeled as Task58 evidence.

## Open items

No unresolved business question prevents design completion. Root technical review/release and actual SQL pin
are outstanding. Main technical review points are monotonic source observation across refunds/late responses,
canonical operation identity before first prepare resolution, original B recovery availability, and incomplete
money attribution for a source shared across work orders. Each has a concrete contract and named selector.

Next step is root independent technical review. Implementation must wait for root IMPLEMENTATION_BASE.
