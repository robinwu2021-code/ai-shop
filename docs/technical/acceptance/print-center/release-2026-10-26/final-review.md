# Independent release review

- Candidate: `fbc23ac5fa0d943237e88dc45cf24aad31e570b6`
- Range: `c03077de9..fbc23ac5f`; read-only repository review, no application edits.
- Critical: none.
- Important: none found in the reviewed change set. Suitable to proceed through the normal push gate; this is not a claim that push or full application migration has passed.

## Minor

1. `scripts/verify-print-release-http.mjs:44` (also `:29`): negative checks accept any nonzero or missing response code. A JSON 500/error response therefore passes the purported idempotency-conflict or permission-denial check. Assert the expected HTTP/business error code and persist it in the evidence; specifically distinguish the documented 409 conflict from unrelated failures. Existing positive replay checks and focused backend tests reduce immediate risk, so this is not a release-blocking production defect.

## Review focus results

1. Push: inspected the recorded normal `bf41e9688` attempt; it failed on `BizKeyConventionTest.noInlineBusinessKeyGeneration` and the document correctly says final push remains pending. No remote-success assertion is justified yet. Coordinator must finish normal checks on the actual pushed candidate and verify remote SHA afterwards, without moving HEAD during the hook.
2. Owner facts: recharge uses real plan/recharge services with distinct entitlement contexts, consumes the first immutable owner event, and does not query current balance in the print fact recorder. Refund goes through real after-sale/refund closure; partial request is rejected before any receipt. Extracted committed PDF text independently: recharge 100.00 + 30.00, frozen balance 130.00; refund 209.40, 3 items, TEST channel. Both PDFs explicitly identify simulation/nonphysical output. New BizKey generation affects new facts only, fits VARCHAR(32), and replay still returns the existing stored fact without rewriting identity.
3. Isolation: wallet-only authentication and other-merchant empty summaries use the real isolated backend. Multi-refund fixture is separately labeled and not represented as financial-policy/API coverage. No known-failure files were modified; schema-name collision metadata documents genuinely different fact owners rather than widening a test-failure baseline.
4. Database: opt-in test accepts only a literal 127.0.0.1 root JDBC URL and creates random owned schemas. It runs unmodified complete migration history, records the error, rethrows it, and drops its own schema. Logs confirm both scenarios failed at V1 line 15, SQLState HY000 / 1273, before a 587 baseline exists. No historical migration files changed. Full upgrade preservation remains unverified and correctly blocked in the report.
5. Operations/output: added test-only callback verifier remains under src/test; server binds loopback, disables physical job scheduling/outbox pump, mocks vendor HTTP, and manually drives only simulation/export workers. Existing explicit execution confirmation and UNKNOWN semantics are unchanged. API replay versus browser fault injection and absent real UNKNOWN/hardware coverage are accurately separated in the documentation.

## Verification performed

- Inspected all substantive source changes, generated contract changes, scope documents, evidence JSON, push/focused-test/full-migration logs, source owner implementation, and committed PDF text.
- `git diff --check c03077de9..fbc23ac5f`: passed.
- `node --check scripts/verify-print-release-http.mjs` and `node --check scripts/print-browser-acceptance.mjs`: passed.
- Confirmed focused logs: BizKeyConventionTest 3/3 and BusinessPrintSourceFlowTest 2/2 passed; migration opt-in default skips are not migration success.
- Repository stayed clean at the reviewed SHA. No full backend suite, browser journey, migration, or remote push was rerun by this reviewer.
