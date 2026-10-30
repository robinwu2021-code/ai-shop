### Spec Compliance

- ✅ SPEC verdict: Spec compliant for Task60A-A9, including all three appended root rulings. Reviewed BASE `ddd3f8b073dc66a40931ecff6aa7a1405bd21b41` against the frozen 18-product package, SHA256 `942ffd1b5d94cecce6a5c9b2d3042de13d8d64ed9faf7f80c6bbddcfda739803`. The nineteenth allowed path, `docs/technical/reference/三端权限矩阵-按业务域.md`, was actually regenerated and remains byte-identical; no missing-change finding applies.
- ✅ Four exact REQUIRED registrations have the approved codes, with no production authorization edits: `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java:128`. The real directory scan delegates to the same method-local JDK AST helper tested by fixtures (`:652`, `:660`); method annotation lists prevent neighboring-method/comment inheritance, both annotation orders and multiline forms are covered, fully qualified PreAuthorize is recognized, syntax diagnostics fail explicitly, and compiler resources close. CLASS_BASE/MAPPING and the original four legacy checks remain intact in the package.
- ✅ Only the existing request mapping and field JSDoc change: `b-app/scripts/gen-openapi.mjs:509`, `b-app/src/api/requests.ts:116`. The generated OpenAPI addition is the required two-string-field schema and required JSON body; operation/auth/response are retained. Tests parse the actual generated YAML and assert body, exact field sets, descriptions, and existing CustomerReference semantics: `b-app/tests/member-code-spec.test.ts:7`, `:17`, `:31`.
- ✅ The registry adds exactly 17 anchored method/path rules with the authorized 4 points and 13 entitlement mappings, including read8/verify2/recover2/freeze1: `scripts/perm-endpoint-map.mjs:27`. Source-backed positives and wrong-method/lookalike/suffix negatives use the real scanner, constants and codeOf: `packages/shared/tests/ops-perm-matrix.test.ts:113`. The JSON diff contains only the eight FINANCE/SUPER_ADMIN additions; generated Markdown role cleanup and the six extra derived outputs match the explicit root approvals.
- ⚠️ Cannot verify from the diff alone: unchanged runtime owner checks, complete permissions-source authority, generator provenance, and all isolated snapshot input/output hashes. The root evidence gate supplies independently checked hashes for 19 products, 25 readonly sources, 106 evidence files, 13 two-generation outputs and the 7792→7793 snapshot boundary. This review treats that gate as evidence only, not as a substitute for judging the changed code. No deployment, whole-branch, BM04/BM05-completion, or rights-runtime acceptance is inferred.

### Strengths

- `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java:684`: the authorization check now operates on each method's actual annotations; the behavior fixtures exercise the same helper used by real source scanning instead of a parallel test parser.
- `b-app/tests/member-code-spec.test.ts:17`: assertions bind client-visible generated YAML to the required body and exact two-field schema, while `:31` protects the existing response contract.
- `packages/shared/tests/ops-perm-matrix.test.ts:113`: each positive ties the target registration to both the actual controller annotation and actual permission constant value; negative cases constrain registry breadth.
- `.superpowers/sdd/beauty-backend/task-60a-a9-report.md:70`: the report accurately distinguishes the earlier Java deletion/byte-restoration stage from the later final whole-class AST run, and distinguishes full Ops 37/37 from the subsequent selected 31/31 with six old tests unselected.

### Issues

#### Critical (Must Fix)

- None.

#### Important (Should Fix)

- None.

#### Minor (Nice to Have)

- `.superpowers/sdd/beauty-backend/task-60a-a9-evidence/extra-generation-1-3.log:2` (also `:6`, `:9`): the glossary generator emits inherited `MODULE_TYPELESS_PACKAGE_JSON` warnings while loading unchanged locale TypeScript. This leaves avoidable noise in otherwise useful generation evidence. Track a separate generator/module-loading cleanup, preserving runtime semantics; do not widen A9 to change package metadata or suppress warnings blindly. This is nonblocking and not a new A9 product regression.

### Evidence Checks

- Read the full brief, all appended rulings, implementer report, and complete frozen diff. Initial tool output truncation required bounded rereads of the affected package sections; no changed source file was separately reread. Verified package SHA above and implementer report SHA `6acede08a37734c48ec75480ced7147b52b78429c0746683dc73fddf147f4f6d`; independently counted 18 diff paths.
- Inspected `java-final-green.xml:2` and `:65` onward: actual 10 tests, zero failures/errors/skips, including all four legacy methods and six behavioral cases. Inspected `java-mutation-proof.json`, `spec-mutation-proof.json`, and `registry-mutation-proof.json`: each records byte-exact restoration at its stated stage, with the Java-stage hash correctly distinct from final AST source.
- Inspected `spec-initial-red-valid.log:10`: genuine undefined requestBody assertion failure; `spec-final-green.log:6`: 3 passed. Inspected `registry-mutation-red.log:10`: missing accounts rule yields null rather than finance:settle:read; `registry-restored-finite-green.log:6`: 31 passed/6 unselected. `ops-registry-full-green.log:6` records the earlier full 37 passed. No suite reruns were performed.
- Inspected `ops-semantic-proof.json` and the actual JSON diff: only FINANCE/SUPER_ADMIN each gain the four points routes, with no removals. Inspected `snapshot-2-global-check.log:1`: all 25 generator outputs current. Full manifest/provenance/snapshot checks remain attributable to the supplied root evidence gate, not newly rerun here.
- Outside-diff named code risks: none required an additional source check; the changed logic and fixtures plus preserved real-run evidence answer the concrete method-boundary and registry-breadth risks. No broader code crawl, checkout mutation, git command, generator execution, or test rerun was performed.

### Assessment

**QUALITY verdict / Task quality:** Approved, with the nonblocking inherited warning note above.

**Reasoning:** The approved registrations and request contract are narrow, source-aligned changes. The guard repair uses the real method AST, retains existing scanning patterns, and has behavioral and real-directory evidence; no blocking correctness, scope, or maintainability defect was found in this task package.
