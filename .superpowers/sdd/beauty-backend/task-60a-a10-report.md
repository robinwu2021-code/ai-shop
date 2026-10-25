# Task60A-A10 fixture implementation and verification

Status: **products frozen for independent review**. BASE `01185dcd5ebaddd4376d445ece0070386d8b6068`; worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`. Tier 0, governed by main TDD §2.35 and the A10 brief. No production contract changed; no stage, commit, push, merge, deployment, native database suite, or deployment child owners were run.

Final ordinary result: **3 tests / 0 failures / 0 errors / 0 skipped**. Explicit `beauty-embedded test-compile -DskipTests` also exited 0. This closes helper/unit/compile work only.

## Exact product manifest

| Product | Change | Final SHA256 |
|---|---|---|
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyBm04EmbeddedHttpIT.java` | Existing fixture changed | `74f732a478ea0dbba21a79b5758529af37b27a05544056ae63fa0b3d575bdf13` |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyBm04ConfirmationFixtureTest.java` | New ordinary test | `ec2f25bf0172e922e20749d7844754a2eed9195c91fd7b0b1a91784dc0258ae8` |

[product-manifest.json](./task-60a-a10-evidence/product-manifest.json) records exactly these two paths, BASE/final hashes and sizes. [complete-BASE-owned.diff](./task-60a-a10-evidence/complete-BASE-owned.diff) includes the full existing-file diff and the complete untracked new test. [complete-BASE-worktree-tracked.diff](./task-60a-a10-evidence/complete-BASE-worktree-tracked.diff) separately includes root-owned brief/progress edits observed during implementation; those are not implementer products. No root ledger or main TDD was edited by this implementer.

## Behavior implemented

The outer class exposes package-private static `confirmationBody(JsonNode)` and `assertWrongRealmConfirmation(int, JsonNode)`. The body contains exactly quoteNo as String, quoteRevision as long, and quoteHash as String from the prepared quote. It excludes operation evidence from the request. The assertion accepts only HTTP 401 and outer application code 10401.

The shared real lifecycle now prepares one valid body and passes that same map to the merchant wrong-realm request and the subsequent real consumer confirmation. The merchant request uses a fresh idempotency key, the underlying raw HTTP response, and its parsed outer ApiResponse; generic `denied(..., Map.of())` is no longer used as confirmation authorization proof. The same merchant token successfully reads its legitimate work order immediately before and after the wrong-realm request. The consumer then confirms, places the order, opens/looks up payment, and follows the existing successful purchase/binding/bill flow in fixture code.

A separately named `assertMalformedConfirmationRejected` submits the actual malformed empty body with a valid consumer session. Its assertions use the current contract: HTTP **200** and BAD_REQUEST **10400**. This path is distinct from the strict 401/10401 wrong-realm path.

Before and after the wrong-realm request, read-only HOST JDBC observations require zero `ord_checkout_confirmation` rows for MAIN/BEAUTY/operation and exactly one corresponding `ord_checkout_operation` still in QUOTED with confirmation_no/order_no/sub_order_no all NULL. The before/after map must remain equal for those observations plus ord_order, ord_sub_order, stl_payment_operation, stl_payment_order_guard, stl_payment, pts_ledger_entry, ent_ledger and sv_entry counts. The latter are database-wide totals intentionally scoped to the deployment driver's fresh isolated owned HOST database, so unexpected unlinked inserts cannot hide behind missing operation pointers. No new seed or SQL write was added. Existing count helper now binds zero or more String arguments, preserving its existing one-argument callers.

## Current source facts, read before SQL implementation

[owner-contract-source-evidence.json](./task-60a-a10-evidence/owner-contract-source-evidence.json) records source paths, line excerpts, and SHA256 hashes:

- SecurityConfig supplies the routing TokenStore to ConsumerTokenAuthFilter. RealmRoutingTokenStore resolves a valid merchant session by token prefix; AbstractTokenAuthFilter only authenticates a matching consumer realm. A valid merchant session is ignored, without the absent-session expired marker.
- CheckoutAccessPolicy.requireCurrentBuyer calls SecurityUtils.requireUser before checkout writes. Missing consumer authentication raises UnauthorizedException; GlobalExceptionHandler.onUnauthorized returns HTTP401 and code10401 unless TOKEN_EXPIRED_ATTR is set. Current source therefore supports the requested strict expectation; no production authorization conflict was found.
- MpCheckoutController's ConfirmBody requires quoteNo and quoteHash, and accepts quoteRevision. Its @Valid request handling reaches GlobalExceptionHandler.onInvalid for an empty body. That handler has no ResponseStatus and returns BAD_REQUEST10400 in the envelope; ErrorCode separately defines expired10402 and forbidden10403.
- Actual migrations V539/V540 define checkout phase/pointers/operation and confirmation keys, stl_payment_operation and stl_payment_order_guard. Baseline V1 defines real order/suborder/payment tables. V425/V487/V498 define actual pts_ledger_entry, ent_ledger and sv_entry. PaymentLedgerServiceImpl writes StlPayment as its payment ledger. No guessed ledger table or payload-bound SQL was used.
- The BM04 driver creates a fresh random HOST database and selects it via BEAUTY_BM04_HOST_JDBC for the fixture; the same fixture handles the later embedded/standalone deployment shapes.

These are source facts and compiled assertions, **not live auth or SQL execution evidence**.

## Ordinary test and TDD evidence

The new ordinary class has exactly the required three methods and never constructs Fixture or enables the deployment property:

1. `validConfirmationUsesPreparedQuoteEvidence`: compares all and only three emitted keys/values against independent literals, using a nondefault quote number/hash and revision 9876543210L plus an operation object that must not enter the body.
2. `wrongRealmAssertionRejectsMalformedBodyFailure`: requires AssertionError for actual-contract HTTP200/code10400 and also HTTP400/code10400.
3. `wrongRealmAssertionRequiresExactAuthorityFailure`: asserts that HTTP401/code10401 does not throw, and rejects expired401/10402, 200/10401, forbidden403/10403 and 401/10403, 400/10401, 500/10500, and 200/0.

| Phase | Result | Evidence |
|---|---|---|
| Behavior RED, compiling minimal helper shape | 3 tests / 3 assertion failures / 0 errors / 0 skips; empty body helper and extracted old permissive denial predicate | initial-red.log, initial-red.xml, initial-red-result.json, initial-red source snapshots |
| Strict body and assertion GREEN | 3/0/0/0; Maven exit 0 | first-green.log/.xml, first-green-result.json |
| Finite helper mutation: accept any HTTP400 before strict checks | 3 tests / 2 assertion failures / 0 errors / 0 skips; both authority rejection methods caught it; Maven exit 1 | mutation-red.log/.xml, mutation-red-result.json, mutated source snapshot |
| Exact restored final GREEN | 3/0/0/0; all three actual methods; Maven exit 0 | final-green.log/.xml, final-green-result.json |
| Explicit embedded fixture test-compile | BUILD SUCCESS, exit 0 | test-compile.log, test-compile-result.json |

[mutation-proof.json](./task-60a-a10-evidence/mutation-proof.json) proves pre-mutation/restored fixture SHA256 both equal `74f732a478ea0dbba21a79b5758529af37b27a05544056ae63fa0b3d575bdf13`; mutated SHA was `b49cb246670919dc56d1c0c210acafbc5a92123114fdd0019fef16914002e324`. Final GREEN and compile source hashes match the frozen products. XML was deleted before each ordinary run and freshly copied after it. This was a test-helper mutation only; no production authorization ablation was performed.

## Exact command scope and formatting

All Maven invocations used the assigned worktree explicitly, JDK21 at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, offline mode and local repository `/tmp/codex-beauty-bm01-m2`:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend/pom.xml -pl shop-app -am -Pbeauty-embedded test \
  -Dtest=BeautyBm04ConfirmationFixtureTest -Dsurefire.failIfNoSpecifiedTests=false

JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend/pom.xml -pl shop-app -am -Pbeauty-embedded test-compile -DskipTests
```

Per-phase result JSON stores exact argv, cwd, JDK, exit status, elapsed time and source SHA. [run-scoped-verification.py](./task-60a-a10-evidence/run-scoped-verification.py) is the finite evidence runner. No unchanged broad suite, default retail suite, A7/A8/A9 verification, standalone deployment or native suite was rerun.

AOSP google-java-format1.25.2 was applied to the new file and changed ranges before final tests. Two unrelated lifecycle count statements expanded by partial formatting were restored exactly to BASE before final GREEN and compile. [format-commands.json](./task-60a-a10-evidence/format-commands.json), format.log, and [final-static-checks.json](./task-60a-a10-evidence/final-static-checks.json) record formatting and successful final checks: new-file AOSP dry-run, git diff --check, new lines <=120, no tabs or wildcard imports. Existing unchanged formatting was preserved.

## Deferred validation and concerns

**LIVE AUTH / DATABASE SIDE EFFECTS / EMBEDDED-AND-STANDALONE HTTP TOPOLOGY / PRODUCTION AUTH ABLATION ARE STILL UNEXECUTED.** They remain Task60B final unified deployment work. Helper tests and test-compile do not close those runtime ACs or establish BM04/M1 deployment completion. The database-wide count comparison assumes the driver's owned isolated HOST database; this fixture must not be reused against a concurrent shared business database.

No implementation concern remains from the current owner/schema facts. Products are frozen; independent specification/quality review and any integration action belong to root. Evidence [manifest.json](./task-60a-a10-evidence/manifest.json) lists the saved artifact hashes.
