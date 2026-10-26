# Task68B shared contract prerequisite — independent bounded design review

Date: 2026-10-26. Static design/source review only.

## Verdict and readiness

**Spec: PASS. Architecture: APPROVED. Shared-contract/private-import prerequisite: ADDRESSED. Frozen alias-membership prerequisite: ADDRESSED.**

Critical: 0. Important: 0. Minor actionable findings: 0. No new blocking breakage found in this overlay.

Root may publish the updated exact inventory/paired metadata and release the sequential B1 protocol/codec and B2 owner integration work. There is no remaining design ruling or user preference required by the reviewed scope. This approves the future implementation contract, not implemented behavior or runtime verification. Task68A and the original selection, display, old-recovery, admission and fence approvals remain preserved; this review does not reopen them.

## Inputs and scope

Sole workspace: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.

Read the shared-contract brief first, its complete two-new-document diff and pins, the addendum/report, relevant original approved signatures/hash/codec sections, and Task68B's identity carry-forward. Checked direct actual SPI types, architecture rule, Router, digest precedent, scope predicates and the ten owner identity bindings. No unrelated pricing, Task68A implementation, future P3 or whole-repository audit was performed.

Verified frozen inputs:

| Input | SHA-256 |
|---|---|
| task-68b-shared-contract-addendum.md | `7005f0483528a88476892cbaa40e097a16f33fbcdbe2082aaf113a3ed59c12e5` |
| task-68b-shared-contract-report.md | `6320b849b248a5e7a109fd20b6c3a5d0060280fa6d8ddcd60bfa772d8eaf7a54` |

Below, overlay references mean `.superpowers/sdd/beauty-backend/task-68b-shared-contract-addendum.md`. `B` means `backend/shop-base/src/main/java/ai/neargo/shop`; `C` means `backend/shop-core/src/main/java/ai/neargo/shop`; `A` means `backend/shop-app/src/main/java/ai/neargo/shop`. Paths are relative to the sole workspace.

## Direct feasibility and closure evidence

### 1. Shared API removes the actual new private-domain edges

Overlay lines15–43 define one final pure SPI helper with seven static signatures: canonicalGroups, normalizeChoices, resolveChoices, seal, sealDigest, requireSeal and requireSelection. Its permitted dependencies are JDK/Jackson/common errors/SPI; it owns neither owner IO nor trade economics. The planned marketing helper delegates protocol functions, promotion consumes the SPI resolver, and trade consumes SPI validation. Marketing therefore has no reason to import CheckoutDigests; promotion/trade have no reason to import CounterCampaignRules.

This matches the real rule at `backend/shop-app/src/test/java/ai/neargo/shop/arch/ArchitectureTest.java:134`: domain-private package pairs are evaluated at156–161 and new violations rejected at169–178. Trade/marketing/promotion are in DOMAINS at52–64; the proposed `ai.neargo.shop.spi.marketing` path is outside those private domains. Existing `A/portal/port/CampaignPortRouter.java:33` through46 already composes marketing and promotion. The overlay's caller graph at96–107 retains that composition boundary and does not add an architecture-baseline exemption or a private locking call from promotion.

The method types are concrete. `B/spi/marketing/CampaignPort.java:263` defines MerchantAmount with long amount, int quantity, store, GoodsLine list and rewards flag; GoodsLine is at283, Discount at326, MerchantDiscount at366, AppliedActivity at424. Original approved addendum lines32–41 already define the four planned counter records used by the new helper. `B/spi/trade/ManualServiceAdmissionPort.java:437` implements typed USER/GUEST validation with nullable USER revision and mandatory positive GUEST revision. No extra carrier or dependency is needed to express the signatures.

### 2. One canonical encoding, with correct error ownership

Overlay lines47–68 fully expand the positional seal tuple, including nullable party/rule/choice fields, canonical groups, sorted raw requested-choice map and selected rule order. The nested tuple fields follow the existing record order. Explicit null positions, UTF-8, lowercase SHA-256 and ORDER_MAP_ENTRIES_BY_KEYS agree with original approved addendum line112 and actual `C/trade/order/service/create/CheckoutDigests.java:16` through31. The original required positional expansion is being made explicit before any deployed schema1 bytes exist; no new hash tag, old hash rewrite or arbitrary JSON-text digest is introduced.

Injected Jackson/SHA usage is already supported in shop-base: `B/spi/entitlement/LegacyCouponHistoryFacts.java:8` through15 is an existing example. The overlay correctly reuses the available dependency rather than importing the trade class or changing global mapper settings. The digest algorithm is one protocol implementation, not three copied domain implementations.

Overlay lines37–41 distinguish invalid client choices from internal canonical/seal failures, and distinguish stored CONFLICT, dependency failures and stale final-owner facts. The no-strip-change reference rule matches `B/spi/trade/CheckoutPort.java:506` through512. Overflow and malformed canonical facts fail closed. The codec remains responsible for translating stored validation errors and raw JSON property/type/duplicate-key checks at overlay line70; typed record validation or a SHA digest alone is not treated as authorization.

### 3. SQL alias membership is established at the correct owner boundary

The identified source mismatch is real: `C/marketing/port/CampaignPortImpl.java:274` obtains entity-scoped rows through SQL, then line293 Java-compares the raw store. The new evaluator cannot reuse that best-only ordinary helper for full counter candidates or aliases. The existing mapper is `C/marketing/campaign/mapper/CampaignMappers.java:14`, a BaseMapper; the actual owner already has both that mapper and injected Jackson at `CampaignPortImpl.java:43` through48.

Overlay lines74–78 specify two bounded uncached current reads under the reviewed entity fence: tenant/entity heads and the same heads restricted by SQL store equality, followed by primary-key membership plus existing null/Java-isBlank whole-entity handling. This is implementable through the existing mapper and avoids both Java equality after a SQL match and an incorrect SQL TRIM substitute for Java isBlank. The comparable promotion probes already use owner mapper SQL (`C/promotion/service/impl/ActivityPricingServiceImpl.java:55` through80; enrollment entity filtering at533–538), while its old live helper Java-compares store at831. The overlay expressly requires the new counter probe to follow SQL membership without reusing these private ordinary-policy filters. Existing parent/link and objective-effect requirements remain binding.

The ten-binding limit is accurate: `C/marketing/campaign/mapper/CounterCampaignFenceMapper.java:53` through64 enumerates seven tenant and three entity columns, not store columns. `CounterCampaignFence.java:204` through222 requires exactly those ten bindings and a common actual column collation. Overlay line74 does not incorrectly generalize that proof to store; the store SQL predicate uses its own effective column semantics under the entity fence. No application case/accent/trailing-space normalization is proposed.

### 4. Existing one-group seal carries the required frozen relation

Overlay lines80–92 establish a complete, unambiguous correlation: sole canonical group and choice; canonical applied/share entity and store; exact selected raw activity reference; sole raw selected rule retaining tenant/entity/store/value/version fields. Owner evaluation attests membership under the actual SQL predicates, and canonical selected activity/share coordinates carry the discount into the actual order group. This mapping is necessary: `B/spi/marketing/CampaignPort.java:342` through352 computes group discount/platform money using exact merchant/store equality. Copying raw SQL aliases into those coordinates could otherwise lose the amount; the overlay explicitly prevents that.

The raw rule facts remain hash-covered, including null-versus-blank store and scope aliases. Client keys and activity references remain byte-exact; a client case variant is not promoted into an alias by the owner collation. Zero-or-one selected rule and exact activity identity make another correlation field unnecessary in this one-group lane. NONE/empty AUTO, explicit selection, rule shape, frozen threshold-null-as-zero, amount cap and canonical shape checks are specified at66–68. No multi-group support is implicitly introduced.

Consumed validation at92 uses the frozen original group/choice/economic/consent/order relation and raw facts already accepted by the owner; it neither reinterprets the database collation nor queries current marketing. A standalone digest remains insufficient: the original quote/plan authority, consumed confirmation, actual typed party/allocations/paid shares and unchanged tenant MAIN checks on trade records remain mandatory. This closes the alias-to-admission mismatch without weakening order scope or adding a live post-sale eligibility dependency. Original old-zero/recovery behavior remains intact.

### 5. Compatibility, inventory and verification scope are reconciled

Overlay lines109–121 add exactly one product source to the existing draft (23 total: 15 modifications and 8 creations); previously planned helpers/codec/owners consume it. The report lines5–9 matches the same API and scope. No schema/migration/mapper/test-framework/HTTP field addition is needed for this overlay. Root remains responsible for republishing the final sequential B1/B2 exact inventories and paired metadata before release.

Overlay line113 preserves the 18-argument economic constructor and conditional-null omission, old ordinary/M1/M2a JSON, CounterOrderPlan shape and hashes, quote-only display envelope, and display exclusion from authoritative comparison. The finite existing test-source extensions at117–119 cover shared choice errors, canonical/digest/raw JSON negatives, aliases retaining raw facts plus canonical money, genuine USER/GUEST order-to-cash-to-source paths, foreign scope/client reference controls, post-sale owner mutation and old-byte compatibility. The tests are not substitutes for actual owner IO or stock evidence. Serializer baseline generation and all execution stay in unified phase B; source authoring/compilation remains phase A.

## Limits and disposition

Only this review artifact was written. No product/design input, index or commit was changed. No build, test, serializer, SQL, native integration, application, network or runtime probe was executed; no subagents were used.

This is static readiness approval for the named future B prerequisites. It does not claim the existing code already implements the new SPI helper, alias-aware counter evaluator or codec, and it does not transfer Task68A runtime status to B. Implementation review and the later unified architecture/compatibility/real-flow execution remain required under the existing phase policy.
