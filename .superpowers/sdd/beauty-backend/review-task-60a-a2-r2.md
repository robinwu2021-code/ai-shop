**I2 — The persisted report bypasses output redaction — ADDRESSED.** `scripts/verify-checkout-payment.mjs:327` separates credential discovery from replacement. Captured stdout and stderr are discovered at `:493`–`:494`; `:436`–`:449` reads and discovers every eligible owned artifact before the rewrite loop at `:451`. Earlier bare values therefore use credentials discovered from later artifacts or either stream. `:466`–`:473` rereads and verifies every persisted artifact against that completed set before any provenance certificate or sanitation-success log at `:475`–`:480`. The original cross-artifact false-absence defect no longer exists in this delta.

### New Breakage in the Fix Diff

- **Critical/Important/Minor:** None found.
- **Failure/output check:** `scripts/verify-checkout-payment.mjs:448` aborts before writes or certification on incomplete artifact discovery; `:497`–`:501` withholds captured streams until discovery is complete. Write or reread failures are accumulated and rejected before certification at `:473`; subsequent native-success logging is unreachable on those failures. Completed discovery also supplies replacement for captured output on nonzero/signal and sanitation-failure paths. The delta preserves warning text, selected-artifact ownership/watermark boundaries, ambiguous-dump failure, and outer own-UUID/tmp/lock cleanup.

### Out-of-Scope Observations

- None newly identified. I1 was closed in R1 and is outside this scoped review. Existing environment warning debt and formal deployment after all P1–P4 code remain deferred.

### Checks

- Read the supplied immutable-R1→R2 review diff once, the brief, remaining verbatim I2 finding and R1 review, appended R2 report, manifests, root gates, and recorded R2 evidence. No git command, suite rerun, native operation, deployment, helper/subagent, index mutation or checkout edit was performed. No focused probe was needed: the recorded runs directly answer the identified ordering and failure risks.
- Independently verified review-package size 26530 bytes and SHA256 `fec2dae31ed4ac7d8a4d9498a832a85bf9acb8dcf94cf06b7db3e5bb38d9eeaa`. Current runner is 31797 bytes / SHA256 `ee953b49d799fca5dbecf919e1721d8c04d7ad086e1c48878995969d2333883c`; test is 36261 bytes / SHA256 `581d44230251d231439b245f6f70595aafb3425add8bb42656bc6d30f928bf90`. Both match the R2 owned manifest. Both diff artifacts and every file listed in R2 `evidence-sha256.json` independently match their recorded hashes.
- Confirmed the appended report names the exact safety command `node --test scripts/tests/checkout-payment-runner.test.mjs` and the covering tests. Recorded `ordering-red.txt` has 19 tests / 16 pass / 3 fail, with actual discovery-order, premature-write, and premature-certificate assertion failures. Recorded `red.txt` has 19 / 16 / 3, with the first failure specifically asserting that a persisted artifact still contains the fixture value. These are behavior failures, not setup/export/compiler failures. Recorded `green.txt` has 19 tests / 19 pass / 0 fail / 0 cancelled / 0 skipped.
- `scripts/tests/checkout-payment-runner.test.mjs:515` covers both artifact orders, earlier-bare/later-marker artifacts, both captured streams, multiple values/repeats, successful/nonzero/signal outcomes, and thrown-process cases with markers actually available in artifacts. It checks persisted contents, displayed output, SHA/structural sidecars, all discovery reads before the first write, all verification reads before the first certificate, and own cleanup. Tests at `:568` and `:581` cover unreadable discovery withholding all writes/certification/output and late persisted-read failure withholding all certificates/success claims. The recorded GREEN also retains the original 16 passing tests, including warning preservation and foreign-resource boundaries.
- The supplied delta changes only main-body lifecycle and the controlled test helper/tests; tokenizer, selector, pre-main logic, provisioning and JVM/process topology are unchanged. Per root ruling, no R2 native execution was required or performed. Historical R1 native 1/0/0/0 and cleanup remain explicitly earlier-code evidence, not a final-R2-source native claim.

### Verdict

**Fix round: All findings addressed, no new Critical/Important breakage.** Remaining I2 is addressed; no new fix-delta finding was identified.
