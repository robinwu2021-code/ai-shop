# Task60A-A1 + A1b implementation report

Status: **DONE — joint implementation and required verification complete; independent spec/quality review and exact commit remain with root.** No deployment/native/BM05/other-60A completion claim.

IMPLEMENTATION_BASE/HEAD `58744b8313e6fbf9693f0088467b6d9a04ceeac4`; branch `codex/beauty-foundation`; worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`. Task59 accepted product `c9d9518798ee74f93d7a1f899d356c4760d57b43`. Authority: original task-60a-a1-brief plus root-approved task-60a-a1b-brief. One writer, no child/helper/reviewer agent; no stage/commit/push.

## Delivered scope and behavior

Original A1: same-module JobLockConfiguration move store.config → job.config, two imports, normal Ops PathVariable import, beauty business-domain registration and conditional ArchUnit class-presence test; HOST/SERVER unique lock-provider/executor assertions and three explicit api-disabled combinations. HOST thin Import remains unconditional; standalone beauty/job/api outer conditions and defaults remain unchanged. Both @Bean methods from first @Bean onward are byte-identical to old source (`a1b/lock-bean-body-proof.txt`): signatures, Bean names, usingDbTime and DB lock semantics retained.

A1b: renamed the internal AppointmentPreparationService helper to AppointmentPreparation, changing only its type/constructor/nested-type identifiers in seven production Java logical files. Bean method name appointmentPreparationService is retained. No methods, JSON, state, transaction, permission or business decisions changed. The two method-design JSON occurrences and two regenerated documents are exact identifier substitutions too (`a1b/identifier-only-proof.json`). No interface/ServiceImpl or forwarding layer was added.

Joint ownership is exactly 17 logical files / 19 paths (two deleted→added migrations, 15 modified paths). No POM, config key, SQL, known-ratchet, role, API160 source, generator source, root brief/progress/design/TDD/ADR/pin/review or other slice was edited by this task. Root-authored documentation changes visible in this shared worktree are excluded. Extra BookingServerWiringTest/BeautyBm04AssemblyTest and original JobHandlerEndpointTest were executed read-only; all three remain byte-identical to BASE.

## Historical RED and authorized scope amendment

Original test-only RED: HOST 34 tests / 2 failures / 0 errors / 0 skips. Existing guards precisely reported unregistered beauty/store and Ops FQN PathVariable. Original lock/route/token regressions and new singleton/disabled assertions already passed; no artificial package-name mirror failure is claimed. A1 minimal product repair then left one real failure: concrete AppointmentPreparationService violated unchanged serviceMustBeInterface after beauty registration. This was correctly handed to root as BLOCKED without broadening ownership or weakening rules. Root A1b explicitly authorized the helper rename and joined acceptance.

Original BLOCKED report is preserved byte-for-byte at `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/.superpowers/sdd/beauty-backend/task-60a-a1-evidence/a1b/history/task-60a-a1-report.md` (SHA `dc15a8181045bec207d3af48c1160dd39d2cc47622bf50caef91ebaefd36f445`); original 8-path manifest at `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/.superpowers/sdd/beauty-backend/task-60a-a1-evidence/a1b/history/owned-manifest.json` (SHA `6bae15fa4eac85de96381ffe73aa9c59e1bde1b46bd5cbbf398f2a0622f66ea3`). All earlier commands/logs/failing XML/old snapshots are also preserved under `a1b/history/`; original evidence files remain unchanged. Root 10-file frozen baseline and source copies are in `a1b/baseline.json` / `a1b/source-baseline/`.

## Commands and results

All Maven commands ran from the worktree above with JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home (21.0.12.1), offline cache /tmp/codex-beauty-bm01-m2. Exact argv, exit/timestamps, raw output and fresh XML/testcases/SHA are in each run directory result.json/output.log/XML. Only approved worktree store/app/server targets plus A1b core target were cleaned; no -am, whole-cache deletion, remote, native, Docker or deployment. Store/core install -DskipTests is build preparation, never test acceptance. Preflight successful sanitized PID/process/path checks found no target occupancy; no processes were stopped.

| Run directory relative to evidence | Exit | XML tests/failures/errors/skips | Meaning |
|---|---:|---|---|
| `red-store` | 0 | skipTests, no XML | build only |
| `red-host` | 1 | 34/2/0/0 | original behavior RED |
| `red-server` | 0 | 6/0/0/0 | PASS |
| `green-store` | 0 | skipTests, no XML | build only |
| `green-host` | 1 | 34/1/0/0 | extra violation → root A1b |
| `green-server` | 0 | 6/0/0/0 | PASS |
| `a1b/joint-core` | 0 | skipTests, no XML | build only |
| `a1b/joint-host` | 0 | 34/0/0/0 | PASS |
| `a1b/joint-server` | 0 | 11/0/0/0 | PASS |
| `a1b/ablation-selector` | 1 | 1/1/0/0 | expected ablation RED |
| `a1b/restored-selector` | 0 | 1/0/0/0 | PASS |

Exact commands (reused where the table names repeated store/HOST/SERVER runs):

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/shop-store-mybatis/pom.xml clean install -DskipTests
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -Pbeauty-embedded clean test -Dbeauty.arch.required=true -Dtest=ArchitectureTest,JobTargetProfileTest,JobHandlerEndpointTest
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-server clean test -Dtest=BeautyJobTargetTest
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/beauty-core/pom.xml clean install -DskipTests
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-server clean test -Dtest=BeautyJobTargetTest,BookingServerWiringTest,BeautyBm04AssemblyTest
JAVA_HOME="$JAVA_HOME" mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -Pbeauty-embedded clean test -Dbeauty.arch.required=true '-Dtest=ArchitectureTest#topLevelPackagesMustBeRegistered'
python3 scripts/gen-beauty-java-method-design.py
python3 scripts/gen-beauty-java-method-design.py --check
```

Generation and --check both exit 0: controllers42/services28/serviceMethods101/dtos171/mappers25/customMapperMethods47/ownedHttp95/sharedHttp11. The two generated outputs have no other byte changes; API160/project source was untouched. Evidence: a1b/generation.log and generation-check.json/log.

## Final AC mapping

| Requirement | Test/evidence and result |
|---|---|
| Same-module lock move and identical DB semantics | store-jar.json + a1b/lock-bean-body-proof.txt; PASS |
| HOST unique provider/executor, lock without worker, single routes, token/JSON contract | JobTargetProfileTest 7/7; JobHandlerEndpointTest 10/10 unchanged; PASS |
| SERVER unique provider/executor and api-disabled false/true, true/false, false/false | BeautyJobTargetTest 6/6, including 3 parameterized cases; no DataSource/registry/endpoint/provider/executor when disabled; PASS |
| Existing nonApi, shared-DB acquisition and conflict retained | BeautyJobTargetTest existing methods unchanged; PASS |
| Beauty in single DOMAINS, all existing domain/Service/Port/Mapper/Web/known rules retained | ArchitectureTest 17/17 including corrected helper naming; PASS |
| Embedded business class actually scanned, default retail compile boundary retained | embeddedBeautyClassesArePresentWhenRequested executed with required=true, metadata name WorkOrderServiceImpl present; no static beauty-core import; 0 skips; PASS |
| Ops FQN removed via normal import | noFullyQualifiedReferences true behavior RED → GREEN; PASS |
| Internal helper rename without business change or Bean rename | exact baseline identifier-only proof across all 10 incremental files; preserved appointmentPreparationService method; PASS |
| Real renamed-core wiring | BookingServerWiringTest 2/2 and BeautyBm04AssemblyTest 3/3 read-only; PASS |
| Generated method truth and outputs match | generation and --check exit0; only two helper identifiers changed per artifact; PASS |
| Fresh store/core JAR and real HOST/SERVER classpath | see artifact section below; PASS |
| Exact DOMAINS ablation and restoration | 1 expected failure naming only beauty, then 1 pass; original/full-GREEN bytes restored; PASS |

HOST joint full GREEN is 34/34, SERVER joint expanded GREEN is 11/11; every executed acceptance XML is fresh and nonzero with 0 failures/errors/skips. The deliberate RED rows retain their actual failures and are not labeled successful acceptance. The conditional presence test uses JUnit explicit enablement, no silent early return; default nonembedded condition-disabled execution was not claimed or rerun.

## Exact ablation/restoration and source identity

After full GREEN, removed only the eight ASCII bytes "beauty" from the single DOMAINS array entry (trailing comma/comment and all other bytes retained). Same HOST command changed only -Dtest to ArchitectureTest#topLevelPackagesMustBeRegistered. Fresh XML: 1 test, 1 failure, 0 errors/skips, exact offender ["beauty"]. No other product/source mutation. Restored the saved original bytes, then the identical selector passed 1/1, 0 failures/errors/skips.

Architecture source before/full-GREEN SHA `b9e91551940992fd1a0533190b7a51a531478ae518b79eca379d8dafc19d93fe`; ablated SHA `c6d038309600ca2c8549528a1d48aadec2e19d62d3a2b933209dba5f4e4d9cc4`; restored SHA `b9e91551940992fd1a0533190b7a51a531478ae518b79eca379d8dafc19d93fe`. See a1b/ablation-mutation.json and restoration.json. All 19 final path hashes equal the joint full-GREEN source snapshot (full-green-source-hashes.json vs final-source-hashes.json, final-verification.json). No unaffected HOST job/endpoint or SERVER suite was rerun after ablation; full-suite GREEN refers to the archived earlier execution on exactly these restored source bytes.

## Artifact hygiene and actual classpath

Store target/cache JAR SHA `8158e9047bd08665e38db3202cedea21e1831a7c4be6b38928420d1a0339c71e`; bytes equal, new job.config.JobLockConfiguration count1, old store.config class count0.

Core target/cache JAR SHA `a1e3833c2d9d6bab437f45da7ade9249d45b0902477a1230917bc3c149d79dd7`; bytes equal, new AppointmentPreparation top-level count1, old AppointmentPreparationService including inner classes count0. The new ReceptionPlanExpired inner class is present under the new helper name.

Joint HOST, joint SERVER and both selector runs record java.class.path and surefire.test.class.path in result.json: each resolves these exact refreshed cache JARs and SHAs. Original store JAR is unchanged through A1b; no unrelated module clean/install was used.

## Manifest, complete diff and verification record

Joint manifest `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/.superpowers/sdd/beauty-backend/task-60a-a1-evidence/a1b/owned-manifest.json` — SHA256 `14709f28e48a3bda2de0915ff9d4675d794cdb23b90dff1afaa06f7dc8064098`. Complete diff `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/.superpowers/sdd/beauty-backend/task-60a-a1-evidence/a1b/full-owned.diff` — SHA256 `64731df9f82274d0fcad3a5cd819da6a8dd16a9da9953ab4eff0353bec8fe97e`. The diff includes full contents of both untracked new migration files, and explicit deleted old paths; no stage required.

Manifest entries include absolute/relative path, added/deleted/modified status, base/final source hashes, and both exact migration relations. Original 8-path manifest is historical and intentionally not overwritten. Final report replaces only the authorized task report. a1b/checksums.json indexes the new evidence files; historical checksums and raw failures remain intact.

## Formatting, warnings, risks and deviations

Original A1 migrated lock file used cached Google Java Format1.25.2 AOSP; only changed test blocks retained formatter output after removing unrelated legacy formatting spillover. A1b obeyed the no-whole-helper-format rule: every byte except exact type identifiers is preserved across seven Java files, including helper business bodies and Bean method name. Changed Java lines <=120 (both moves compared to mapped old paths) and git diff --check passed; evidence source-style-verification.json.

Warnings remain in raw logs and a1b/warnings.txt: intentional unknown-handler negative test; existing duplicate mapper definitions; MyBatis ID-init timing/resources; Flyway verified-H2-version gap; test-config logistics/phone/ship keys, OTP rate limit, generated development security and DevSeeder; macOS DNS native fallback; annotation processor discovery, existing deprecation/unchecked notices (core BeautyCatalogIT, HOST legacy tests/DevSeeder); Mockito self-attach/dynamic-agent/CDS notices. Expanded SERVER adds the expected Mockito/JVM notices from its existing wiring test. No warning was suppressed or repaired opportunistically. These ordinary H2/in-process tests and compilation do not establish native database or deployment readiness.

The only scope deviation was root-approved A1b after the real domain-registration failure, now resolved and jointly verified. No unresolved implementation defect or further scope expansion remains in this handoff. Independent spec/quality review and precise commit remain pending with root; no A2/other60A, native/Docker, remote action or actual deployment was started.
