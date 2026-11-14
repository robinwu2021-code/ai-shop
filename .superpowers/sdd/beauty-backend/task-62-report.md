# Task62 BM05-S0 current authorized business person

Status: **DONE_WITH_CONCERNS**. The authorized S0 product and verification are complete. Root independent review, contract-ledger reconciliation and integration remain separate. This does not enable service start or complete BM05.

Sole worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`; branch `codex/beauty-foundation`; unchanged task HEAD/base `2b558d4eac7cf311813bbfba6e82374281b839a9`. No stage, commit, push, merge, install, deployment, new dependency, migration, role-permission grant or foreign container/VM operation was performed. Root-owned uncommitted main/brief/ledger/schema/generator work is excluded from implementation ownership.

## Result and scope

The frozen `PersonnelOperatorFactsPort` contract accepts only the five reviewed service actions and the authenticated operator access query. It returns a millisecond UTC observation with `LINKED` or authorized `UNLINKED`; linked observations contain only personnel/assignment references, owner revisions and the half-open validity interval. A null `validTo` remains indefinite validity and is an explicit required JSON property.

The merchant provider uses the real canonical `MerchantOperatorAccessPortImpl` outside caller transactions. It checks exact current identity, resolves current authorized account, then reads one read-only REPEATABLE_READ snapshot using the unchanged access SQL and the new account-link/person/assignment/store/entity projection. It checks the same allowed account inside the snapshot and reruns canonical authorization/current identity afterward. Duplicate effective employments and malformed mapped intervals fail as dependency errors. The optional Clock follows the existing owner constructor pattern; tests supply a fixed Clock and no new global bean exists.

The new query uses binary exact account/personnel identity and tenant/entity/store joins through the whole parent chain, active/deleted/effectiveness filters and LIMIT 2. No existing SQL statement changed. The internal controller uses token-first `BoundDelegatedCalls`, complete route/target/scope binding, delegated-context cleanup and real internal HTTP status codes. The beauty adapter verifies current authenticated namespace, request HMAC/echo, typed result shape, versions and intervals through the actual five-second InternalHttp client. It maps only the exact permitted bound error/digest/status combinations; malformed success, missing transport and bare error responses become 80107, never UNLINKED.

The five permission codes and exact Chinese/English/Arabic labels were added without changing ROLE_PERMS or seeds. Only the three named generated permission documents were regenerated. There is no service task/start controller in this slice.

## Authorized deviations and execution boundaries

- Root approved two shared protocol helpers plus existing `BoundInternalProtocolTest`: the original envelope could neither serialize nor decode the frozen LinkState enum. Serialization now uses `Enum.name()`; decoding requires an exact named constant before conversion. Numeric, unknown and case-altered values are rejected. Ordinary enums roundtrip; a separate overridden-toString fixture proves canonical serialization uses the name. Global Jackson configuration was not changed, and existing fixed canonical digest fixtures pass unchanged.
- Maven 3.9.16 concatenated literal absolute module text with the temporary aggregate directory. Root approved canonical-checked relative module text targeting the exact current backend and beauty trees. No repository POM changed. A separate root-approved temporary compilation POM additionally included the existing food aggregator for affected food/host compilation.
- Minimal fail-closed owner/remote scaffolds were introduced after tests to make genuine native behavior RED runnable. Their source/class hashes and scaffold errors are retained; missing contracts/setup failures are not claimed as behavioral RED.
- The native runner acquired the existing shared lock before any container or ablation and released only its own acquisition. All seven native runs used cached `mysql:9.7.2 --pull never`, own UUID labels/names, loopback-only random ports, bounded readiness, memory/disk prechecks and a bounded container. Passwords were generated per run and passed through environment only; logs/XML replace that generated password before archival.
- The fixture is explicitly **structural pre-V532 authentication tables plus exact frozen V532/V533**, executed by ScriptUtils on actual MySQL 9.7.2. It is not historical full-platform Flyway replay or a deployed application proof. Bounded local HTTP protocol tests use the real InternalHttp transport; native identity/permission/relationship tests use actual canonical owner SQL, not permissive permission providers. Wiring-only mocks do not supply permission behavior.

## Verification and chronology

Counts below are **tests / assertion failures / errors / skipped** from fresh actual Surefire XML. Successful runner exit for a RED phase means the runner proved an expected assertion failure; its Maven exit remains 1 in that phase's commands.json.

| Stage and evidence directory | Bound protocol | Native owner | Controller protocol | Role regression | Remote HTTP | Maven / runner exit |
|---|---:|---:|---:|---:|---:|---:|
| Initial scaffold RED `red-cafe2e1b-0915-4181-b616-92d304a17f9f` | 7/0/0/0 | 12/4/6/0 | 3/0/0/0 | 3/0/0/0 | 6/2/2/0 | 1 / 0 |
| Initial GREEN `green-35be1c64-fffc-45ea-b75d-eb85248cdc23` | 7/0/0/0 | 12/0/0/0 | 3/0/0/0 | 3/0/0/0 | 6/0/0/0 | 0 / 0 |
| First expiry ablation `ablation-94f4de6f-ad32-419f-998a-d6afab8e5477` | 7/0/0/0 | 12/1/1/0 | 3/0/0/0 | 3/0/0/0 | 7/0/0/0 | 1 / 0 |
| Formatted expiry provenance recapture `ablation-21d7e638-1666-4aed-a476-5ed9b1b6f398` | 7/0/0/0 | 12/1/1/0 | 3/0/0/0 | 3/0/0/0 | 7/0/0/0 | 1 / 0 |
| Exact restored GREEN `restored-e89c2635-fef4-43cd-9bfd-a31c4069e487` | 7/0/0/0 | 12/0/0/0 | 3/0/0/0 | 3/0/0/0 | 7/0/0/0 | 0 / 0 |
| Binary parent-scope RED `red-08d71328-4c85-4807-98ba-df1fbd01e79f` | 7/0/0/0 | 13/1/0/0 | 3/0/0/0 | 3/0/0/0 | 7/0/0/0 | 1 / 0 |
| **Final current GREEN** `green-6be8d5a5-6f11-4ee4-af00-95a63598ce62` | **7/0/0/0** | **13/0/0/0** | **3/0/0/0** | **3/0/0/0** | **7/0/0/0** | **0 / 0** |

The initial scaffold failures include the explicit expiry assertDoesNotThrow assertion and denial/remote exact-code assertions; scaffold-related errors are separately visible above. The owner implementation then passed all initial 31 tests. Provider assembly and post-snapshot real role-revocation assertions were strengthened before final restoration, bringing that stage to 32 tests.

The meaningful expiry ablation removed only the entire new effective-assignment upper-bound conjunct while retaining its lower bound. `expiredAssignmentAndStoppedPersonReturnUnlinked` failed its own explicit assertion on a real expired row. The independent interval constructor defense also caused the finite-boundary method's reported error; that error is not counted as the intended assertion RED. The exact testcase parser cannot attribute a later testcase failure to a successful named testcase; a Node regression proves that boundary.

The first ablation proved the assertion but did not snapshot the ablated class before finally recompilation. A rejected late capture is truthfully labelled `late-restored-capture`; it is not ablated-class evidence. The runner was corrected and the same finite ablation repeated only to close that provenance gap. In the complete recapture, exactly **two** of 95 provenance entries changed: MerchantMappers.java and its nested PersonnelAccountMapper.class. Finally restored compilation returned **all 95 source/class entries byte-for-byte** to their pre-ablation hashes, followed by the successful restored suite.

After that proof, root requested binary parent-chain scope hardening. The new real-SQL case-variant test first produced one assertion failure and no errors: a lower-case person entity was attached by a case-insensitive join. The fix affects only the new query; all nine person/assignment/store/entity case variants then passed in the final 33-test suite. The earlier expiry ablation is retained as the exact prior-version expiry-guard proof, not mislabelled as the final binary-join source version. Root explicitly accepted this chronology without an unnecessary third ablation; the upper-bound conjunct is unchanged and present exactly once in the final query.

Additional verification:

| Command/check | Actual result |
|---|---|
| Bound enum assertion RED, `enum-red.log` | 7 tests, 1 assertion failure, 0 errors/skips; Maven exit 1 |
| Shared protocol GREEN, `enum-green.xml` | 7/0/0/0 |
| `node --test scripts/tests/personnel-operator-runner.test.mjs` | Final 5 pass, 0 fail/skip, exit 0 |
| `node node_modules/vitest/vitest.mjs run --root packages/shared tests/biz-perm-label.test.ts` | Initial real five missing-label assertion failures observed; final 17 pass, exit 0 |
| Default current backend+beauty reactor compile/test | Final native command compiles/tests actual current default-retail modules; exit 0 |
| Current backend+beauty+food aggregate, `-Pfood-embedded test-compile -DskipTests` | Actual food-api/web/core, host and beauty-channel compile; exit 0, no install |
| Three existing permission generators | Each exit 0; exactly the three named outputs |
| `git diff --check` | Exit 0 |
| Final preservation/provenance/cleanup audit | Exit 0: 32 original SQL statements, ROLE_PERMS and five known-* files byte-exact; final tested 95 source/class hashes unchanged after food compile |

Preparation included a missing-runner module failure, the literal-absolute-module Maven model failure, and local compile/enum-fixture corrections; none is counted as behavioral RED. The first attempted pnpm invocation unexpectedly produced an untracked package lock; it was removed immediately, no dependency change was relied on, and cached direct Vitest was used afterward. The final audit helper was corrected to exclude the aggregate POM from Surefire XML and to respect the shared base suite's intentional lack of an auth dependency; final current classpath assertions pass.

## Commands and evidence manifest

All evidence is under `.superpowers/sdd/beauty-backend/task-62-evidence/`; `artifact-sha256-index.json` indexes its artifacts and the final report. `owned-files.json` gives exact owned paths and final fingerprints. Root-owned main/ledger/brief/schema/generator paths are excluded. Whole new Java files were formatted using cached google-java-format 1.25.2 AOSP before final verification. Existing insertions were formatted narrowly; broad formatter expansion into legacy Mapper/Map.ofEntries content was removed by reconstructing original bytes plus only the owned insertions.

The exact finite native invocation in each phase is archived in that directory's `commands.json`, including actual exit codes and the temporary POM path:

```text
node scripts/verify-personnel-operator.mjs --phase=red|green|ablation|restored
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f <owned-temporary-aggregate-pom>
  -pl :shop-app,:beauty-channel -am --fail-at-end test
  -Dtest=PersonnelOperatorFactsIT,PersonnelOperatorProtocolTest,RemotePersonnelOperatorFactsPortTest,BizRoleSeedTest,BoundInternalProtocolTest
  -Dsurefire.failIfNoSpecifiedTests=false
  -Dpersonnel.operator.test.admin-url=<owned-loopback-jdbc>
  -Dpersonnel.operator.test.source-root=<canonical-current-worktree>
```

Ablation's finally command is the same current combined aggregate with `test-compile -DskipTests`; its exit is 0 in both ablation stages. Password is exclusively `PERSONNEL_OPERATOR_TEST_PASSWORD` in child environment, never a Maven property/argument or retained credential file. Exact affected-food compilation arguments and canonical three-module targets are in `food-compile-command.json`; raw output is `food-current-reactor-compile.log` and sources are `food-source-provenance.json`.

Key evidence:

- `initial-native-gate.json`, `initial-native-gate-v2.json`, and root gate artifact: actual file-read/hash review before first native execution.
- `baseline-source-hashes.json`; per-phase `phase-provenance.json`; complete recapture `before-ablation-provenance.json` and `finally-restored-provenance.json`; `final-source-class/source-class-inventory.json`: actual source/class identities and SHA256s, including nested mapper and test classes.
- Per-phase five XML reports, summary.json, command exits, resource limits/UUID and cleanup.json; final `final-test-methods.json` records all 33 actual method names/counts.
- `final-classpath-provenance.json`: actual final XML classpaths and emitted code-source values. Tests compare canonical exact current shop-base, shop-merchant and beauty-channel origins; no stale local business JAR satisfies those checks.
- `final-tested-bytes-still-current.json`: all 95 final tested source/class hashes remain identical after affected food compilation.
- `final-audit.json`: legacy SQL/ROLE_PERMS/ratchets, frozen task HEAD and seven own UUID cleanups. `warning-ledger.json` retains observed warning lines.
- Final label/runner logs, generated-permissions.log, food/preflight cleanup records, bounded audit/source-inventory/food-compile helper scripts and formatter commands.

Frozen migration pins, checked before every native run and unchanged at final audit:

| File | Bytes | SHA256 |
|---|---:|---|
| V532__shared_personnel_foundation.sql | 8333 | 299c7ed3dc22f7b5a0510949956e685c702d74b88a606b5a92004ca1cb7d579b |
| V533__shared_resource_identity.sql | 2540 | 2b41485b9b032ffa00b74b5cec31bfaf774a9024f465e3e2d5ec149759c43aff |

## Warnings, concerns and cleanup

Raw logs retain javac annotation-processor discovery, inherited deprecation/unchecked notices (including OpsService's deprecated setStaffRole), Mockito/ByteBuddy dynamic-agent and JVM CDS warnings. The final remote suite deliberately emits one missing-InternalHttp context-start warning and one EOF transport warning to prove fail-closed behavior. No warning was suppressed. No Flyway compatibility proof is inferred from this ScriptUtils fixture. Existing M1/M2/M3 debt and known-* ratchets remain unchanged.

The permission domain generator's one unclassified namespace is inherited `ops:*` in existing §4.1, unchanged vs HEAD. Its earlier attribution to the new service codes was incorrect; no generator scope change was needed. S0's five assignable permissions intentionally have no service endpoint or seeded role grant in this slice.

All seven native own UUID containers, their anonymous volumes and UUID reactor directories were removed by the runner. Cleanup checked the exact ownership label before removal; the shared native lock is absent. The separate current-food compilation directory and owned preflight aggregate were also removed. No application server, foreign container, VM, database or live JAR was altered. Formal unified deployment and later start fencing remain deferred to their separate authorized tasks.
