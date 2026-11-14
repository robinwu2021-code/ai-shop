# BM04 module code integration report — READY_FOR_INDEPENDENT_REVIEW

Code review BASE e90e12aab3acf4e336d66b0c3620ae7d981cbd1a (Task54 before implementation); current reviewed product HEAD817ec6c58478d62f48fa90dda509c01815bbc9b3, documentation HEAD9e01eb6b1ba07b05f7bb28669e6e49228b60eb51. Exact worktree/branch per module brief. No deployment approval is claimed; code module gate pending independent cross-task review.

## Requirements → implementation → task proof

| Requirement | Actual code boundary | Accepted task record (full report and latest independent reviews in this workspace) |
|---|---|---|
| REC identity, registered/claimed guests and same-store current permission | shared MemberGuestRegistrationServiceImpl/MemberCodeResolveServiceImpl; beauty GuestRegistrationServiceImpl and reception preparation | Task54 +55A; latest 54-r1/55a-r1; A7 real CLAIMED→USER+REPLACE 3-case native, revocation ablation then exact restore |
| Appointment check-in/reassignment and direct walk-in, persistent original operation | ReceptionCoordinator/ReceptionReassignment/ReceptionRecovery, WorkOrderCoordinator | 55B/55C-r3/55D-r2; A11 exact attributed fault preserves intent and command, unauthenticated ARRIVAL_PENDING distinct from authenticated success |
| Canonical once-only work order and service scope | WorkOrderPreparation/Coordinator/Query, frozen scope, BeautyMappers | 55C/55D; A3 winner-neutral cross-action races, complete immutable acceptance/frozen facts and current replay |
| Ordinary service real quote/customer-confirm/create, legacy completion blocked | CheckoutServiceImpl/CheckoutOrderWriter/CheckoutAccessPolicy/ManagedServiceCompletionPolicy/StatusCasMapper | Task56-r1; ordinary shared owner, COUNT and same-price quote seal; BM05 remains blocked |
| Payment original operation + authorized offline receipt | CheckoutPaymentServiceImpl/OfflineReceiptServiceImpl; PaymentOperationPort and owner money ledger | Task57-r1/r2, no beauty money ledger; UNKNOWN same original numbers, no repeat funding; latest real owner regressions are task-scoped historical proofs |
| Service submission, binding and per-source bill | ServiceSubmissionServiceImpl/ServiceSourceObservation/ServiceSourceBinding/WorkOrderBillServiceImpl | Task58-r1/r2; operation/guard/workorder/task order and source watermark, quantities scoped to binding revisions; A3 race contract, nullable amounts retained |
| Public/typed internal/remote protocols and machine auth/context cleanup | Biz/Mp beauty controllers, BeautyReceptionInternalApi/BeautySourceInternalApi, BoundDelegatedCalls, Remote service/port adapters | Task59-r1; A10 valid quote body and strict40110401 vs invalid20010400 helper proof; live topology/SQL remains deferred |
| Architecture, SQL/schema/lineage/permissions/i18n/generated contracts | owner lock config, beauty DOMAINS, testguard parsers and generators | A1–A11 individually accepted, including mandatory A5c quote lineage fix and A8b B-api typecheck; A9 final immutable snapshot25 generators clean |

Inventory398 non-doc products has exact source hash/diff path for each, full product diff and stat in /tmp/bm04-module-review-9e01eb6b1/. Forty independent review artifacts registry references both initial findings and final fixes; initial reports with stale PENDING headings are historical and latest main§32/ledger/commits resolve acceptance. Do not treat an earlier failed review as an unclosed finding without its subsequent named fix review. This registry is provenance, not a new assertion that every test ran on finalHEAD.

## Current compilation and preserved finite evidence

- Five beauty modules api/core/web/channel/server joint test-compile -DskipTests exit0, BUILD SUCCESS. Current347 non-target plugin inputs pinned and rechecked unchanged. Log/JSON/input snapshots in bm04-module-code-evidence/. Incremental compiler output is disclosed: this is current reactor compilation, not a clean rebuild or test execution.
- Current default retail backend -pl shop-app -am test-compile -DskipTests exit0, BUILD SUCCESS, no beauty profile. Named risk: A10 new ordinary helper test directly references another test class; default classpath remains compilable. No install/applicationlaunch/deployment.
- Current embedded host test-compile proof from acceptedA10 source; no host production or test Java changed sinceA10, onlyA11 plugin test+scripts. A1 changedlock/server config already independently assembled; current fiveplugin reactor includes final source. Previous standalone shape compile and real-TCP protocol tests remain their actualTask59 scoped results, not fresh runtime proof.
- B242 and actual vue-tsc success fromA8b unchangedB source; A9 adds only JSDoc and generatedrequest schema; no later B behavior edits. No unchanged B suites repeated.
- A9 generated all25 checker exits0 on frozen snapshot, not current live repo; subsequent main rootstatus doc edits may require targeted index update. Current gen-doc-index ran afterA11 acceptance and README was byte-unchanged (manualmainTDD index row is outside generatedsection). Source truth should be reconciled after module conclusion; no unrelated output drift masked.
- A11 actual SQL gate162hits/51files/54known/fresh0/stale0, Node26green; warningnative exact1green/1assertred/1restoredgreen. A10 helper actual3green/mutation2assertred/restored3green, embeddedfixture compile. A7 actual realowner3green/auth1assertred/restored1green. These cases overlap historical suites and are not summed into a misleading all-green total.

## Current warnings and explicitly bounded review debt

A11 warning ledger actually checks its own finite logs: each native7FlywayWARN +1 expected attributed IllegalStateExceptionWARN; ablation deliberately removes lastWARN and causes test failure. Flyway9.7 newerthan verified8.1 and unchanged BeautyCatalogIT deprecation/implicitprocessor discovery remain dependency/compiler debt. Other A7 historic duplicateMapper/mapperLocations/H2/testconfig/MockitoCDS/DNS and A9 NodeMODULE_TYPELESS outputs remain disclosed, not said resolved just because absent fromA11. No blanket suppress/package/dependency changes. Any actual multipleownerBean correctness issue discovered by moduleaudit needs a separate boundedfix; warningexistence alone is not proof that unique owner is absent.

Pending root triage in independent module audit: A3 other initialsuccessful response compares id/action only, chosenresponse and laterreplay fullstable fields are checked; A8 duplicate parser recognizes biz: only, no claimpay/trade coverage. Current recommendations are keep as explicit testcoverage boundaries unless actual integration risk requires targetedfix; independent reviewer may disagree by impact.

## Not delivered / not verified

Full BM05 taskexecution, actual occupations, race-safe startpermit, canStart=true, managed fulfillmentcompletion; BM06 full tasks calendar/frontend; BM07/08/P2/P3/P4 are NOT delivered by this gate. Formal60B embedded/standalone childJVM/current auth/DBeconomic sideeffects/production tokenablation/restarts and actual construct crossindustry runtime remain UNEXECUTED until allauthorizedP1-P4code. No true printing/thirdpartyfinance/device proof is claimed. Native controlledfixture tests or readiness cannot replace them.

## Gate next step

Independent module integration review of five named risks in brief. Productfixes require exactnewbrief and originalimplementer/freshfixloop; no moduleacceptance before findingssettled. Root then reconciles current main§0/§2/§21/§32 and README, marks code-only gate, details BM05 contracts before any new productcoding. Finalwholebranchreview remains at endofP4.
