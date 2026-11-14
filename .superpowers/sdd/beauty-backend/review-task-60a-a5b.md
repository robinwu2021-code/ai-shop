# A5b fresh spec and quality review — bm04

Reviewed checkout: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.
Implementation base: `74a071a1bf3b345db3522ced15497a6ba74148fd`; reviewed the uncommitted snapshot, not a commit.

## Spec Compliance

**PASS within the approved A5b scope. No blocking spec deviation found.**

- The product is exactly the three approved source files and seven changed existing generated outputs. I read the supplied full diff in bounded passes, the brief, implementation report, semantic audit and exact membership attachment. I independently recomputed current bytes/SHA256 for all ten manifest products; all match. The diff, manifest and report hashes also match the supplied identities below. DOMAINS/TOPICS are unchanged in the diff; no SQL, Java, parser, entity-alignment, package, known-failure or other generator product changes appear in it. The broader checkout/index/HEAD boundary is additionally covered by the supplied root evidence gate.
- `scripts/lib/schema-lineage.mjs:431` has one resolver shared by relations and badges. It checks the source column, actual target column and both sides of every scope pair, rejects self references and unresolved targets, gives exact source-table overrides priority, and suppresses global collision fallbacks. Its import of `ddl.mjs` reads the existing AUDIT constant; that module performs schema reads only inside functions. The new module contains no top-level schema read or generation/write action.
- Exact collision memberships match the audit attachment. The four voucher mappings retain two points-only and two tenant-scoped stored-value-only edges. Plan, legacy source-order, grant, payment actor, tier and catalog mappings retain the specified source restrictions and scope. Receipt/source-event/entry/line/refund execution, points plan, staff-log actor, ent_usage source-order and ent_instance grant do not gain unconditional edges. Unknown audited members are suppressed by resolution and reported by the shared classifier.
- Eleven ordinary identities and three role identities retain the expected targets, real target columns and tenant scope. Buyer maps to user_no across the four actual sources; confirmation and migration return retain their proven targets. The module preserves existing unrelated effective owner table/column mappings and collision explanations rather than attempting a whole-registry rewrite.
- `packages/shared/tests/schema-lineage.test.ts:224` onward states independent expected actual edges, targets, scopes and negative source members. It does not compare the imported map with itself. Missing source/target/scope fixtures check actual relations and badges, including preserved UK priority. The nine original LINEAGE endpoint cases, classification, owner-target, stale-collision and Python table-set comparison remain. The old generator parity import is removed; tests import the pure module instead. The Python check still compares table sets, as before; it is not new proof of column-level parity.
- `scripts/gen-erd.mjs:184`, `:349` and the relation-description render paths consume the shared implementation. Markdown and HTML display real source/target columns and scope predicates. I checked the actual generated diff and current ignored HTML for tier version scope, catalog entity scope, normalized actor target and the SERVICE_USE/MIGRATION_BASELINE/PRF- caveats. Scope descriptions also cover omitted sv/ent/pay detail domains without adding visual domains. The Markdown collision-cell newline/pipe fix is narrowly output formatting.
- Evidence supports the specified workflow: baseline 14 pass/2 fail; old-behavior extraction plus independent cases 27 pass/30 fail; separately documented three erroneous UK test expectations; final combined 67 pass; three semantic mutations fail 1/1/9 cases and are exactly restored. I read the relevant existing log summaries and restoration hashes. The final 67-pass run predates only the approved generator formatting hunk, not a resolver/test/A5 change. The final two actual generation logs report 363 tables, 603 relations, 16 domains and 20 SVGs. The supplied gate verifies 24 first-generation output hashes against the second generation, including the ignored HTML. No fresh 67-pass run is claimed here.

## Quality

**PASS for the changed A5b behavior. No introduced Critical or Important defect identified.**

The implementation centralizes classification and resolution without adding another DDL parser or runtime join framework. The negative cases and scope mutation provide meaningful protection against the specific plausible false edges this change addresses. The generated changes are explained by the accepted relationships and refresh of already-present DDL tables. Existing broader registry defects remain; consequently, this verdict is not a claim that all 603 generated relations have been semantically audited.

### Critical

None identified in the reviewed scope.

### Important — inherited, deferred outside the A5b gate

**Retained global `quote_no` owner emits concrete false checkout-to-marketing edges.**

- Source: `scripts/lib/schema-lineage.mjs:95` retains `quote_no: { table: "mkt_quote" }`. This mapping was already present in both prior maps; A5b copies its effective semantics unchanged.
- Actual generated evidence: `docs/technical/reference/数据库-ER图.md:221` includes both `ord_checkout_quote.quote_no → mkt_quote.quote_no` and `ord_checkout_confirmation.quote_no → mkt_quote.quote_no`.
- Business evidence: `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:268` generates an independent `number("CQ")`; `:288`–`:304` stores it in OrdCheckoutQuote, and `:430` copies that checkout quote to the confirmation. `backend/shop-app/src/main/resources/db/migration/V539__checkout_source_foundation.sql:51` defines the checkout quote root, with `(tenant_no,quote_no)` uniqueness at `:69`. These source facts contradict a marketing-quote owner for the checkout root and its confirmation. A developer following the generated edge would join unrelated identities.
- Concrete follow-up fix: audit actual quote_no memberships/producers, classify the independent quote namespaces, preserve proven marketing references with exact source mappings, add the tenant-scoped checkout confirmation → ord_checkout_quote relationship, and suppress root-to-root/cross-family edges. Consider current_quote_no only after its exact semantics are included in that bounded audit. Add independent negative/positive tests and regenerate through the same manifest/idempotence workflow.
- Scope decision: this is a real Important documentation/lineage defect, not hypothetical uncertainty. It is inherited global-owner behavior explicitly deferred by the A5b brief and already acknowledged by the implementation report. It does not invalidate the scoped A5b correction. Root has acknowledged it and will carry a bounded follow-up before whole-branch acceptance; it must not be erased by the 67-pass result.

### Minor — inherited, deferred

**Stale generated prose still says there are 54 detail tables.** `scripts/gen-erd.mjs:287` produces the incorrect sentence at `docs/technical/reference/数据库-ER图.md:473`, despite current domain lists containing many more tables. This line is unchanged and the brief explicitly defers old hard-coded prose counts. Concrete follow-up fix: derive the detail-card count from configured DOMAINS and parsed tables, or remove the number, then regenerate. No handwritten generated-file correction is appropriate.

No new in-scope Minor finding identified.

## Evidence identity

- Full diff: `/tmp/task60a-a5b-review-package-9c6ce011-492e-4ed6-8c9d-988eb3a896cc.diff`, 230414 bytes; SHA256 `509a217f4859ac934a08f59a304302d86e1772b426bfcbacc428512cdde87cd8`.
- Product manifest SHA256: `27c6aeda70bb28dd5af1fbe5c6f2f4e56772a42ce3d85b1488cf66059bb6a074`.
- Implementation report SHA256: `1075886580eaf5896283943747575df927b7402253c4863176e4f61166d9f239`.
- Pure module SHA256: `4071d9e6c2c9e26d02c18c81c1391223754e8644af45f2f6defabc3cbcc6c9e8`.
- Generator SHA256: `3aed77acfd0b42da8621a139d3b90bd5327a93239a561bcd2f7dea7d79359e72`.
- Test SHA256: `3515082fb2a3f59f71225dc759e118199d46bf61750b2e178dc1349a97675d98`.
- Ignored HTML SHA256 matches manifest: `4c5aab64865f1e5f5793eb6fbd46f3a5d33929f892d248cb15b23d25305c0e93`.

## Cannot Verify / bounds

This review used read-only file inspection and hashing, plus existing execution evidence. I did not rerun tests, generators, suites, native commands or Docker, and did not run git commands or mutate source/docs/index/HEAD. My only write is this `/tmp` review report. Thus historical RED/GREEN execution, mutation restoration timing, and generation provenance rely on the inspected evidence and independently supplied root gate; current product bytes were independently matched here. SVG markup was reviewed; no browser rendering or pixel-level layout review was performed.

The accepted parser's composite uniqueness representation and UK priority remain unchanged. This review proves neither historical populated-row integrity nor native/database behavior, and performs no comprehensive audit of unrelated legacy owners. Missing sv/ent/pay detail diagrams, other omitted domains, full P1–P4 completion, deployment and whole-branch acceptance remain outside this verdict.
