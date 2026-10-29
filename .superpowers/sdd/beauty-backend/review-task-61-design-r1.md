# Task61 design R1 narrow re-review

## Spec compliance

- ✅ **I1 ADDRESSED.** `.superpowers/sdd/beauty-backend/bm05-design.md:177–182` now freezes `validFrom<=at AND (validTo IS NULL OR at<validTo)`, nullable indefinite `validTo`, one shared SQL/observation instant, and matching owner/remote interval validation. This matches the authoritative V532/owner semantics established in the initial review.
- ✅ `.superpowers/sdd/beauty-backend/bm05-design.md:188–189` requires real SQL NULL-ended LINKED coverage, finite half-open boundaries, bound HTTP explicit-null round-trip and malformed/missing interval rejection. Legitimate absence remains UNLINKED; malformed evidence remains dependency unavailable. The distinction between omitted and explicit-null `validTo` must survive wire conversion as explicitly required at lines177/180.
- ✅ `.superpowers/sdd/beauty-backend/bm05-design.md:197` updates the existing expiry ablation to remove the entire null-aware upper-bound conjunct while retaining the lower bound; exact restoration/recompile remains required. It continues to exercise real expired employment rather than setup/classpath failure.

## Quality and fix-created issues

- **Quality: Approved for this design-only gate.** The correction is confined to S0 interval semantics, associated acceptance tests and ablation; it introduces no schema/product change, permission expansion or later-slice scope change.
- **Critical:** None. **Important:** None remaining in this narrow review. **Minor:** None.
- `.superpowers/sdd/beauty-backend/task-61-design-report.md:76–91` explicitly labels tests as future acceptance requirements, preserves the original report and supersedes the design hash without claiming runtime coverage.

## Checks and cannot-verify boundaries

- Read `/tmp/task61-design-r1.diff` and the appended report against the same Task61 brief and original I1. No broad source audit, repeated hash audit, build, test, generator, container/native execution, product edit, subagent or Git write. Root supplied the exact two-file scope and current hashes; this review only writes this report.
- Cannot verify future SQL behavior, JSON presence enforcement/null serialization, remote error classification, test execution or ablation outcome from a document-only change. S0 must prove these during implementation; there is no product completion claim here.
- The initial review's remaining boundaries are unchanged: root true-source reconciliation precedes S0 release; the separate architectural R1 Pay ruling precedes S1; performed-service dispute accounting remains a later closed contract gate; formal deployment remains after all P1–P4 code/test/review/compile.
