# Task60A-A5c fresh spec and quality review — bm04

Reviewed checkout: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.
BASE: `28eb4b5ef18cbbacdaee4d9e1eb6200e18489f2a`; this is the supplied uncommitted five-product-file snapshot, not a commit.

## Spec Compliance

**PASS for the bounded implementation, with the historical generation-evidence limitation below. No blocking implementation deviation found.**

- The reviewed diff contains exactly the approved lineage module, regression test, generator prose line, and two existing generated products. I independently recomputed current byte counts and SHA256 for all five; each matches the product manifest and root gate. The diff, manifest and implementation report identities also match the supplied hashes. The generator change is solely the live detail-count sentence; no resolver, parser, A5, SQL, Java, package, known-failure, DOMAINS or TOPICS change appears in the five-file diff. Broader checkout/index/HEAD scope relies on the supplied independent root gate and boundary evidence; I ran no git commands.
- `scripts/lib/schema-lineage.mjs:251` documents independent marketing and checkout issuance and the remaining revision/hash/caller requirements. `:268` records exactly the four observed quote_no members. The former global quote_no owner is removed. Exact mappings at `:335`, `:341` and `:347` preserve only marketing revision → marketing quote, checkout confirmation → checkout quote, and operation.current_quote_no → checkout quote.quote_no; all three retain tenant_no equality. There is no global current_quote_no alias or second resolver.
- Existing `resolveReference` at `scripts/lib/schema-lineage.mjs:456` still checks real source, target and scope columns, exact source mappings before collision suppression, and rejects self references. Relations and badges use that resolver. The actual shared classifier at `:479` rejects an additional quote_no member through the exact-members rule; `columnBadge` at `:494` retains UK priority.
- Four new actual-schema cases at `packages/shared/tests/schema-lineage.test.ts:225`, `:232`, `:245` and `:256` independently state the exact family edges, target columns and tenant scope, forbid root/self/cross-family edges, restrict the alias to its source, and exercise fifth-member resolution, relations, FK absence and actual shared classification. They also preserve UK priority. The expected relationships are explicit literals rather than a comparison of the registry with itself; existing cases are not weakened by this diff. The current A5 test hash remains `2756d20054045dcc8cd6443130fa53d223ac4d84a602f402f4eda411180e06ea`.
- Focused source inspection confirms independent issuance at GroupServiceImpl.java:377 and CheckoutServiceImpl.java:268, revision copying at GroupServiceImpl.java:423, operation assignment at CheckoutServiceImpl.java:285 and quote creation at :289, and confirmation copying at :430. V539:51/69 defines the checkout root and tenant+quote unique key. Existing request/revision/hash/operation/caller checks at CheckoutServiceImpl.java:376 remain authoritative; the diagram relation does not replace them.
- I read the existing execution logs: initial EPERM is a startup failure, not semantic RED; the real old-resolver run is 4 failed/67 passed, implemented GREEN is 71 passed, forced old-global ablation is 3 failed/68 passed, and restored final GREEN is 71 passed. The mutation explicitly overrides qualified precedence for quote_no, so it exercises the regression. The unchanged alias correctly remains green in this mutation. Current implementation bytes equal the retained pre-ablation bytes exactly.
- Both actual CLI generation logs show exit 0 and 363 tables/603 relations/16 domains/20 SVG. I independently compared the two direct manifests: all 21 identities match each other and current bytes. All 24 final existing-output entries match current bytes. I also parsed current HTML and Markdown: their unique quote-reference descriptions are exactly the three required tenant-scoped relations, with no root or cross-family reference. HTML repeats descriptions in its existing views; this is three distinct relationships, not three total text occurrences. Its quote rows preserve marketing root UK/revision FK, checkout root UK/confirmation UK, and current_quote_no FK. Generated Markdown at :549/:598/:600 states the correct scope. The db-ord.svg diff adds only the two intra-checkout arrows and labels.

## Quality

**PASS within the approved scope. No new Critical, Important or Minor defect identified.**

The fix uses the existing source-qualified registry and shared classification/resolution paths. It is small, preserves established validation and badge semantics, and includes meaningful positive, negative and mutation evidence. Generation totals and the 71-pass result do not establish a global semantic audit of every legacy relationship.

### Inherited Important — ADDRESSED

The A5b global quote_no owner produced concrete false checkout-to-marketing references. Removal of that global owner, the exact memberships at `scripts/lib/schema-lineage.mjs:268`, scoped family mappings at `:335`/`:341`, and exact alias at `:347` implement the bounded correction. The false references are absent from the generated cross-domain list at `docs/technical/reference/数据库-ER图.md:221`; the actual correct scoped references appear at :549/:598/:600. Tests at `packages/shared/tests/schema-lineage.test.ts:225` through :267 and the inspected RED/ablation evidence cover the previous defect and fifth-member leakage. This finding is addressed in the reviewed bytes; formal closure still follows root acceptance and exact commit.

### Inherited Minor — ADDRESSED

`scripts/gen-erd.mjs:287` removes the stale live “54 张表” number while retaining the full fields/types/indexes/relations sentence. The actual generated sentence at `docs/technical/reference/数据库-ER图.md:473` matches. This is the only generator-source change and is not a handwritten product-only fix.

### New findings

Critical: none identified. Important: none identified. Minor: none identified. No product fix requested.

## Cannot Verify / bounds

The first-generation direct manifest omitted `topic-member-promotion.svg`, `topic-money.svg` and `topic-points.svg`. Consequently, there are **21 direct first/second hash comparisons, not 24**. Their first-run unchanged status is supported by the supplied tool-transcript statement, not first-run topic hashes. Current topic bytes match the final manifest; their equality to BASE was independently checked by the root gate, not recomputed here through git. The implementation report accurately discloses this limitation. I assess it as historical verification incompleteness, not a confirmed current correctness defect: current products and content match the supplied identities, the two direct manifests agree, and current topic BASE equality is independently gated. I do not claim full direct first-generation hash coverage. The first manifest wrapper's quoted-path collection failure is likewise distinct from the successful first actual generation.

This review used file inspection, hashing, comparison and focused parsing of already-generated HTML/Markdown. I did not rerun tests, generators, suites, native commands, Docker, database operations or deployments; no source, product, root-document, index or HEAD writes occurred. My only write is this `/tmp` report. Historical execution and ordering/provenance rely on the inspected logs and supplied gate. SVG markup was inspected; no browser/pixel review was performed. Unrelated legacy registry entries, omitted visual domains, historical populated data, dynamic business behavior, all P1–P4 work, deployment and whole-branch acceptance remain unverified.

## Evidence identity

- Supplied full five-file diff: `/tmp/task60a-a5c-review-package-ffc22a27-c129-4843-8821-373529d4ebce.diff`, 60935 bytes; SHA256 `012e75a40dbe9963f9a8b851bf8a87d0947d33aee965094633c94782b7428e3d`.
- Product manifest SHA256: `658c4dd480f4e02fbafbb850a3e0fc1b15f53e79a87226b12f9c1fbb6926e463`.
- Implementation report SHA256: `acbe0c382c9e9f84feedd3e68def0530b88fe946db6d8ddfa3cc2f5772ac89b3`.
- Current lineage module/restored implementation SHA256: `ff101d151bcc656f6a463b1f0eaca3ad86f0b2cf5e549579b73ef9f07c0bec46`.
- Current ignored HTML SHA256: `0d59b20503261f071cbb758da548248ecaffd3ecbb2a1308ec49ba3309d9b500`.
