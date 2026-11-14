# Review fixes implementation plan

Spec: ../specs/2026-10-18-review问题修复方案.md
Execution: inline, preserve existing changes; no deployment during implementation.

- [x] A: snapshot current-run reports, capture git/command provenance; Python regression tests.
- [x] B: typed refund outcomes and verified terminal receipts through gateway, pay/SPI and both refund flows; narrow channel tests.
- [x] C: shared original-payment capacity states and safe release after compensation, late-success freeze; targeted pay/source scenario tests.
- [x] D: replace message-to-trade Service dependency with batch SPI port; narrow architecture/permission tests.
- [x] E: bounded incremental acceptance availability projection with watermarks and rebuild; expiry policy/store tests.
- [x] F: synchronize effective requirements, schema/API inventory and AC evidence; final related-module verification.

Constraints: unknown stays reserved; no cash history deletion; no legacy points engine; all writes transactional; external funds outside DB transactions. Each failure is traced before editing. Small test scope, 5s slow threshold, progress at 15s; command watchdogs only for abnormal execution, no 30-minute turn limit.

Final evidence and remaining production gates: ../../technical/Review修复-执行记录-2026-10-18.md. Full baseline cleanup and deployment are not marked complete.
