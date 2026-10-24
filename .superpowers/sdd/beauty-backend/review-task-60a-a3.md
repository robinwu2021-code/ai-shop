### Spec Compliance

- ✅ Spec compliant for bounded Task60A-A3. All three owned product files have their requested changes: winner-neutral race assertions and directed two-result coverage in `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java:799`, the four exact aliases at `scripts/verify-beauty-bm04-wire.mjs:34`, and the selector positive/negative cases at `scripts/tests/beauty-bm04-wire-runner.test.mjs:94`.
- ✅ `ServiceSubmissionIT.java:818` retains each request index and the complete successful VO or actual BizException; `:912` requires the correct success/failure cardinality and actual CONFLICT code. `:931` derives the action from the successful request, and `:940` scopes the sole permanent row by entity/store/workOrder/namespace and either legal action. The three existing races retain the unbiased two-arrival boundary; `:810` adds both explicitly directed legal outcomes without replacing those races.
- ✅ `ServiceSubmissionIT.java:954` validates the original SOURCE_SUBMITTED log identity and exact schema-1 safe snapshot. `:980` checks frozen task/request association, quantity, selection/addition provenance, and actual prepared lines. `:1030` through `:1092` preserve complete before/after source/task/log facts and frozen command bytes, compare the winner's complete stable VO with numeric BigDecimal equality, allow only the approved top-level revision exception, require the replay to equal the full current VO, and retry the changed loser as CONFLICT.
- ✅ `ServiceSubmissionIT.java:860`, `:899`, `:1094`, and `:1107` provide bounded arrival/acceptance waits, bounded future gets, worker SecurityContext/ThreadLocal cleanup, callback reset, executor interruption, and bounded termination. Assertions still reject failure of the intended first request even though its finally releases the held request.
- ✅ `scripts/verify-beauty-bm04-wire.mjs:34` adds exactly the four named methods and preserves the original two methods, whole-class selection, and existing selector grammar. `scripts/tests/beauty-bm04-wire-runner.test.mjs:101` checks exact acceptance and rejects unknown suffixes, incomplete prefixes, and all three other modes through both direct validation and an empty-PATH CLI.
- ✅ The six incidental AOSP wraps are pure formatting in the reviewed diff, within the brief's explicit final exception: no additional behavior or scope change found. Production Java, SQL, configuration, deployment logic, and unrelated selectors are absent from this three-file change.
- ⚠️ Cannot verify whole-branch architecture or formal deployment from this task diff. Required release proof remains the separate final all-P1–P4 integration/deployment gate; this approval supplies no such proof. The final validation claim is precisely five affected cases, plus two restored directed cases, not a final single-run 29-case GREEN.

### Strengths

- `ServiceSubmissionIT.java:845` and `:936` independently construct expected namespace/payload hashes from the request rather than reading the expected values from the observed row. One focused outside-diff check addressed the concrete risk of an incorrect copied hashing contract: `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java:159` through `:173` matches the action, namespace ingredients, and JSON payload hashing used by the test.
- `ServiceSubmissionIT.java:954`, `:1041`, and `:1084` distinguish immutable acceptance-log/frozen facts from the live VO. The current projection is checked in full; the first projection's exception is limited to the specifically approved revision, with real quantity differences still checked.
- `.superpowers/sdd/beauty-backend/task-60a-a3-evidence/focused-green/ServiceSubmissionIT.xml:2` contains actual 5/0/0/0 results, with both directed parameters at `:66` and `:104` and all three old methods at `:141`, `:178`, and `:216`. `restored-green/ServiceSubmissionIT.xml:2` contains actual 2/0/0/0 results with both parameters. Parsed the actual XML and verified all eight XML/archive-manifest hashes against the verification index, rather than relying on its counts alone.
- `red/ServiceSubmissionIT.xml:217` fails only the directed ADD case at the original fixed-action assertion; `action-red/ServiceSubmissionIT.xml:104` reproduces that failure. `receipt-red/ServiceSubmissionIT.xml:66` and `:122` fail the immutable-acceptance comparison; `quantity-red/ServiceSubmissionIT.xml:66` and `:141` fail the real tasks[0].qty comparison. Mutation diffs alter the intended observation/assertion only; all three restoration JSON proofs record byte equality and the same final source SHA.
- `.superpowers/sdd/beauty-backend/task-60a-a3-evidence/node-final-green.log` ends with 8 pass, 0 fail/cancelled/skipped. Historical setup/query/decimal failures remain separately recorded; no skipped or setup-only run is counted as business RED/GREEN.
- Checked current byte counts/SHA256 for all three product files against the supplied root package, current compiled-class hash against the index, brief SHA, report SHA, and package SHA. All match. Final Java SHA256: `93b9983b746a6ec063a9c115302346314705b1e429553b43b0b7c9fdfdfae6ee`; compiled class: `0087331cd7cdf66b15bc0170e4f28e92bedfa994ecf14d8c7c8e9fb764e4d3cb`.
- Review check: read-only, no git commands, test reruns, native/Docker/deployment actions, helpers, or checkout mutations. Tool output truncated the initial combined diff display mid-helper; recovered the cut-off helper from the same supplied diff package only. No changed product source file was separately reread; source hashing did not print source contents.

### Issues

#### Critical (Must Fix)

- None found in this task scope.

#### Important (Should Fix)

- None found in this task scope.

#### Minor (Nice to Have)

- `ServiceSubmissionIT.java:924`: in the unchanged-payload race, the second successful response is retained but compared with the chosen successful response only for submission number and action. The later complete stable-field comparison covers the chosen response and a subsequent replay. This satisfies the task's sole acceptance/immutable-row invariant, but an incorrect quantity/status/quote in the other initial response could escape. Optionally apply the same complete stable-field comparator to every successful response, allowing only the approved revision difference.
- `.superpowers/sdd/beauty-backend/task-60a-a3-evidence/focused-green/maven.log:59`, `:65`, `:76`, and `:95`: final passing output still contains the javac annotation-processing notice, the existing BeautyCatalogIT deprecated-API notice, Flyway's unverified MySQL 9.7 compatibility warning, and slow MyBatis Sequence initialization. The restored run retains the same categories (`restored-green/maven.log:59`, `:65`, `:76`, `:95`). These are observable quality concerns, not pristine output. The diff does not introduce compiler/runtime configuration changes or the deprecated API use; retain their A11 follow-up ownership, investigate and resolve there without suppressing warnings or broadening this test-only task. The compatibility warning limits environment-validation confidence but does not invalidate these concrete assertion/mutation results.

### Assessment

**Task quality:** Approved

**Reasoning:** The change fixes the action-biased test using actual successful-request identity, independently expected hashes, complete immutable acceptance/frozen/source assertions, explicit loser conflicts, and both reproducible legal outcomes while preserving unbiased races. Recorded five-case GREEN and targeted mutation/restoration evidence match the reviewed bytes; the two minor findings do not block bounded A3 acceptance or release the separately deferred integration/deployment gates.
