# Task65 scoped R1 re-review

## Finding verdicts

**I1 — An existing UNKNOWN financial observation permanently occupies the manual source's unique identity. — ADDRESSED (design).**

The original review required a precise owner-verified adoption/migration rule, protection against old financial writers, preservation of genuine funded evidence and a real legacy-path upgrade test. The revised `.superpowers/sdd/beauty-backend/bm05-m2-counter-source-design.md:174`–182 now defines all four:

- Line 174 replaces the blanket immutable/default-funded/no-migration rule with a single constrained transition, preserving the physical row and the existing source/operation unique identities. Separate manual epoch/time/failure fields prevent confusing old financial tickets with manual readiness.
- Lines 176–178 name the claim/adopt methods and ticket, eligibility predicates, owner facts validation outside locks, version/epoch recheck, exact canonical coordinates and USER buyer match, plus serialized concurrent-adopter behavior. Only empty UNKNOWN placeholders with no financial pairs, verified evidence, bindings or allocation guards can move. Ineligible funded evidence is checked before mutation and remains unchanged. Identity and quantity namespaces are retained rather than duplicated or cleared.
- Line 180 names the conditional Mapper statement and proposed V9-only update guard. Together with the explicit pre-classification funded-kind checks at line 182, this covers upgraded financial code and a pre-upgrade worker whose SQL lacks the new discriminator. A late incompatible update is rejected; a byte-identical/version-only update cannot advance manual readiness and instead causes CAS retry. The original V8 migration remains untouched.
- Line 252 adds B2a tests that reproduce the actual old EXISTING→financial UNKNOWN path before applying V9, then adopt/bind/display the same original. It includes delayed old financial completion, exact old UPDATE, and refusal to adopt rows with actual funded evidence. This directly covers the original finding, rather than testing only fresh manual fixtures.

This is approval of the specified remedy. The trigger, conditional SQL and upgrade race tests have not been implemented or executed in this design task.

**I2 — The specified manual identity validator cannot consume the promised MANUAL_EXISTING/M1 input. — ADDRESSED (design).**

The original review required an owner-facts input path, exact submit/read/recovery call points, historical context/party matching and tests that do not fabricate a counter quote. The revised design at lines 162–166 closes that contract:

- Line 162 adds `manualParty(BtyWorkOrderContext, ManualSourceFacts):CustomerParty` alongside the real-counter-quote overload. It accepts M1 CONSUMER_SESSION only for USER and merchant-recorded counter authority for USER/GUEST, with original operation/order/suborder/scope checks. It explicitly separates identity from ADMITTED readiness and prohibits wrapping M1 facts in a counter quote.
- Line 164 defines exact USER/context and historical GUEST/context matching. The claimed-GUEST-context→USER-source case requires canonical merchant-scoped member facts and the matching local claim receipt; the returned source party and context are not relabeled. The existing command claim relation is frozen and revalidated. Current C read entitlement remains separately member-owned.
- Line 166 identifies `submitForWorkOrder`, `getSubmission`, `requestSubmissionRecovery`, `recoverPending` and the pre-bind recheck, with the private `verifyManualParty` method. It uses original source facts and persisted V3 identity during recovery without retaining raw guest credentials. Existing V2 funded validation and permanent command hashes are preserved; a historical V2 manual attempt is not silently rewritten into V3.
- Line 253 adds B2b cases for actual M1 USER/C facts with a throwing readCounterQuote boundary, counter USER/GUEST, claim/context mismatch and restart recovery without the guest token. These assertions cover the former type/authority gap.

**Minor M1 — Make the committed-order retry/recovery lock contract explicit. — ADDRESSED (design; assessed separately).**

The new paragraph at line 140 makes CREATED retries return the original result immediately under the operation root, after live authority and exact original key/payload checks, without taking quote/confirmation/intent/existing-parent locks. A stale prelock hint must repeat this check. Recovery uses a nonlocking READ_COMMITTED parent-existence read and explicitly forbids lockParent/lockChildren while holding counter operation/intent roots. Inconsistent markers remain pending/conflict for reconciliation, and only genuinely new rows enter finalization's parent/child stage. Line 246 adds a deterministic CREATED retry versus actual receipt/progress test that asserts absence of parent-lock SQL and no compensation. This resolves the original nonblocking precision concern; it does not retroactively assert that the original design had a proven deadlock.

## New breakage in the fix diff

**None found. Critical: 0. Important: 0. Minor: 0.**

The fix keeps the changes inside the previously declared observation/submission/Mapper/V9 and counter placement/recovery owners. It does not add a new member witness, financial authority, source identity, stock ledger, public route or service/SPI inheritance. The new guard protects only local observation shape; genuine manual provenance still comes from the owner facts preflight. The amended finite runner explicitly includes B2a/B2b at line 270.

One concrete interaction was checked against unchanged source: the active-submission overlap condition in `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java:219`–230 currently names only EXISTING, while the old attempt can remain SOURCE_PENDING (`:859`–860). The implementation must adapt this same-original-source path for MANUAL_EXISTING so the required B2a legacy-submit→upgrade→new-submit flow succeeds. This is already a concrete behavior required by revised design line 166 and the real-path B2a assertion at line 252, within an explicitly modified owner; it does not require a new business decision or justify another design blocker. The test must actually retain the old submission rather than deleting it as fixture cleanup.

## Out-of-scope observations

None. Unchanged design sections were not re-reviewed. Root-owned public requirements reconciliation is outside the artifact fix and is not treated as unauthorized product work.

## Verification and limits

- Reviewed the original I1/I2/M1 findings against their full retained report text, the appended author report section “Task65 scoped R1 design correction (round 1/5)” and the complete immutable R1 fix package. Read the diff once in bounded sections; did not reread the historical 108-source audit table.
- Package: `/tmp/task65-r1-review-package-d0b9925b-decd-4dc9-b6dc-75009ce95cc0.diff`; verified SHA256 `3cb47b2020c6ccd183fbbe7475f35c2a682ad6de4faa7801739f32cc189b5dc2`. Fix base is `/tmp/task65-before-review-183b70d0-ce82-4566-9bb4-bf72256e65ef`; Git HEAD remains the supplied `df39678f30f530388c3c7dbe4e5d0d6557d7755b` (not re-queried).
- The only additional source inspection followed the named old-active-submission compatibility risk: the current submission gate, resume/pending lifecycle and related Mapper symbol locations. Previous bounded source findings were reused rather than re-auditing the checkout.
- The author's R1 report at lines 149–159 accurately distinguishes proposed behavior and future assertions from checks actually performed. Root separately reported source-pin/hash/product-diff verification; this reviewer did not rerun that broad inventory.
- **Cannot verify at design time:** actual trigger syntax/application, mapper affected-row semantics, cross-version race results, constructor/reactor compilation, or successful business tests. The newly named focused tests and required native bounds remain implementation acceptance obligations. Their existence in the plan is not executed evidence.
- No tests, builds, native commands, Docker, deployments, git mutations, product edits or subagents. Only this requested re-review report was written.

## Scoped verdict

**Fix round: All findings addressed, no new Critical/Important breakage.**

**Scoped spec compliance: Approved. Scoped design quality: Approved.** I1 and I2 are closed at the design gate; Minor M1 is separately closed. This verdict authorizes no claim that M2 behavior is already implemented or runtime-verified.
