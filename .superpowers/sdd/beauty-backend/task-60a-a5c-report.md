# Task60A-A5c implementation report

Status: DONE_WITH_CONCERNS — implementation and bounded checks pass; evidence collection caveats are explicit below. Independent root spec/quality review and exact commit remain required before closing the inherited A5b Important finding.

## Scope and source facts

档位0; binding requirements: `task-60a-a5c-brief.md`, main TDD §2.29, local CLAUDE/dev standards. Sole checkout `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`, BASE/unchanged HEAD `28eb4b5ef18cbbacdaee4d9e1eb6200e18489f2a`.

The root supplied and confirmed actual readSchema quote_no members are mkt_quote, mkt_quote_revision, ord_checkout_quote and ord_checkout_confirmation. GroupServiceImpl.java:377 creates marketing BizKey.QUOTE and :423 copies it to revision. CheckoutServiceImpl.java:268 generates CQ, :285 writes operation.currentQuoteNo, :289 creates checkout quote and :430 copies it to confirmation. V539:51/69 defines the checkout root and tenant+quote uniqueness. CheckoutServiceImpl.java:376–390 retains authoritative request/operation/caller/revision/hash checks. This slice does not modify those business sources or replace their validation with a diagram edge.

## Delivered behavior

Removed the global marketing quote_no owner. Exact four-member collision classification now admits only tenant-scoped mkt_quote_revision.quote_no → mkt_quote.quote_no and ord_checkout_confirmation.quote_no → ord_checkout_quote.quote_no. Neither root resolves itself or the other family. A fifth table with quote_no is rejected by the actual shared classifier and has no resolved relation/FK badge; existing UK priority remains intact.

Added only the exact source mapping ord_checkout_operation.current_quote_no → ord_checkout_quote.quote_no with tenant scope. An unrelated source with current_quote_no gains no alias edge. Existing resolver, source/target/scope checks, shared relations/badge path and all existing tests remain unchanged. The sole gen-erd source edit removes the live “54 张表” count and retains the fields/types/indexes/relations sentence.

## Actual RED / GREEN and ablation

All test runs used existing Vitest v4.1.11, from `packages/shared`, command:

`../../node_modules/.bin/vitest run tests/entity-alignment.test.ts tests/schema-lineage.test.ts --cache false`

| Evidence under /tmp/task60a-a5c-evidence | Actual result | Process exit |
|---|---|---|
| 00-sandbox-startup-error.log | Sandbox EPERM writing Vitest temporary config; no tests executed; NOT semantic RED | 1 |
| 01-old-resolver-red.log | Actual old resolver: 4 failed / 67 passed, 71 total; entity suite passed | 1 |
| 02-implemented-green.log | Minimal mappings: 71 passed, both files passed | 0 |
| 03-old-global-ablation-red.log | Old global quote_no forced ahead of qualified/collision handling: 3 failed / 68 passed | 1 |
| 04-restored-final-green.log | Exact restored implementation: 71 passed, both files passed | 0 |

The first RED exposes both checkout→marketing false edges, lost marketing tenant scope, missing checkout confirmation target, missing current_quote_no alias and fifth-table leakage. The single mutation changes only quote_no owner selection to the old marketing global semantics, bypassing qualified precedence so it is a real regression. It leaves the new current_quote_no mapping intact; therefore the alias test correctly stays green in this ablation. No A5b unrelated mutations were repeated.

Before and restored module SHA256: `ff101d151bcc656f6a463b1f0eaca3ad86f0b2cf5e549579b73ef9f07c0bec46`; mutated SHA256: `782889aea4154e91074d95c4395ace5237893cb27c06bcc3eda2358b2986f134`. `ablation-restoration.json` records exact byte equality. `implementation-before-ablation.mjs` retains original bytes. A5 entity test remains frozen at `2756d20054045dcc8cd6443130fa53d223ac4d84a602f402f4eda411180e06ea`.

## Generation and actual output checks

`05-pre-generation-freeze.json` records HEAD, branch, status and four source hashes before generation. Exactly two actual `npm run gen:erd` executions followed, both exit 0 (`06-generation-1.log`, `07-generation-2.log`), each reporting 363 tables, 603 relations, 16 domains, 20 SVG. No other generator ran. These numbers are generation totals, not a semantic audit of all existing relationships.

`generation-1-output-manifest.json` and `generation-2-output-manifest.json` directly compare all 19 tracked db-*.svg, generated Markdown and ignored HTML: 21 identities match exactly; source freeze also matches (`08-idempotence.json`). `all-existing-output-manifest.json` records all 24 existing outputs, including three tracked topic SVGs and the ignored HTML. The three topic SVGs were absent from the diff-stat immediately after generation 1 and remain exactly equal to BASE after generation 2.

Evidence caveats: the first generation itself succeeded, but its following manifest wrapper initially exited 1 because Git quoted the Chinese path. Collection was corrected using `git ls-files -z` without rerunning generation. The first direct hash manifest omitted the three unchanged topic SVGs; their first-run identity is supported by the immediate post-run diff-stat (tool transcript), with final hashes checked against BASE, rather than a first-run hash snapshot. This limitation does not change product bytes; no third generation was performed.

`09-generated-content-check.json` uses Python HTMLParser on actual HTML, plus actual Markdown code spans. Each contains exactly these three quote relation descriptions, including their tenant equality:

- mkt_quote_revision.quote_no → mkt_quote.quote_no
- ord_checkout_confirmation.quote_no → ord_checkout_quote.quote_no
- ord_checkout_operation.current_quote_no → ord_checkout_quote.quote_no

No checkout→marketing or root/self/cross-family quote edge appears. Actual HTML badges: marketing root UK, marketing revision FK, checkout root UK, checkout confirmation UK (priority retained despite its real reference), operation.current_quote_no FK. The Markdown live 54 count is absent and the full detail sentence remains. No eager generator import, browser/pixel claim or handwritten product edit was used.

## Exact product manifest and BASE diff

Only five owned tracked product files changed:

| File | Reason |
|---|---|
| scripts/lib/schema-lineage.mjs | Exact quote collision membership and three scoped source mappings; no resolver rewrite |
| packages/shared/tests/schema-lineage.test.ts | Four independent actual-schema regression cases; no existing case weakened |
| scripts/gen-erd.mjs | Only live detail-count prose line |
| docs/technical/diagrams/db-ord.svg | Actual generator adds the two correct intra-checkout edges and labels |
| docs/technical/reference/数据库-ER图.md | Removes two false checkout→marketing references; adds collision caveat and three scoped descriptions; live54 removed |

`product-manifest.json` records old/new bytes and SHA256 for each. `product-base.diff` is the full owned product diff against the exact BASE. `full-base.diff` is the full checkout BASE diff, additionally containing the root's preexisting progress.md edit, which I did not touch. `10-final-boundary.json` records final status, unchanged HEAD, frozen A5 SHA and `git diff --check BASE -- <five product paths>` exit 0 with empty output.

Ignored HTML SHA256: 0d59b20503261f071cbb758da548248ecaffd3ecbb2a1308ec49ba3309d9b500, 863775 bytes.

## Self-review and bounds

Reviewed the full five-file product diff and generated semantic evidence: no SQL/Java/parser/A5 registry/known/package/DOMAINS/TOPICS/root-doc edits; source scope is exactly three files and generated scope exactly two existing files. No new visual domain, global alias, second resolver, install, subagent, native/business compile/database operation, staging, commit, push, merge or deployment occurred. The root-owned progress.md change is excluded from the product manifest. The report/evidence are owned support artifacts.

This closes the implementation of the two bounded inherited findings only. Other legacy registry relations, omitted detail domains, historical populated rows, runtime behavior, all P1–P4 tasks and deployment are not proved. Whole-branch acceptance and formal closure await the root's fresh review/commit.
