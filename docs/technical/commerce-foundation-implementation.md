# Commerce foundation implementation — T01

Status: T01 implemented, awaiting independent review. T02 not started by this implementer.

## Delivered boundary

`backend/commerce` is an independent parent plus api/core/persistence/starter reactor. The parent has no host parent, imported host properties, or runtime dependencies. The host adds the aggregate and four version-managed coordinates only; comparison of before/after effective dependency management excluding the new commerce coordinates was equal.

The API contains 100 public contract types and one module descriptor. These include six typed facades, feature-specific commands/results, neutral context/store/money/error records, nine replaceable external ports, stable payment/refund/inventory/event/notice references, and explicit UNKNOWN outcomes. Interfaces contain no fake business implementations. Actor context is a trusted-adapter input; client binding is expressly prohibited in port/facade documentation.

Core has one module descriptor and **zero business classes**. Its JPMS descriptor requires only API. Persistence and starter are intentionally empty T01 placeholders; their jar packaging emits the expected empty-jar warning. No migrations, transaction implementation, auto-configuration, business behavior, or host bridge is claimed.

## Effective version lock

Read the current host effective POM using Java 21: Spring Boot 4.0.7, Spring Framework 7.0.8, JUnit Jupiter 6.0.3, compiler plugin 3.14.1, surefire 3.5.6, dependency plugin 3.9.0, Flyway 11.14.1, MyBatis-Plus 3.5.16. Commerce locks the Boot BOM and build plugins; database/framework runtime dependencies remain absent until the relevant task. The commerce POM does not inherit Boot starter-parent, preventing inherited runtime dependencies. The Java baseline is release 21.

## RED → GREEN evidence

All Maven commands explicitly used:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

1. RED: `mvn -f backend/commerce/pom.xml -pl commerce-api,commerce-core -am test` — API tests failed with 2 failures, 0 errors because six production facades and neutral contracts did not exist. Host fixture compilation is captured as an assertion, not an uncontrolled test compilation error. Log: `/tmp/commerce-t01-red.log`.
2. RED: `mvn -f backend/commerce/pom.xml -pl commerce-core -am -Dtest=CoreDependencyTest -Dsurefire.failIfNoSpecifiedTests=false test` — core boundary failed with 1 failure, 0 errors because API production bytecode did not exist. Log: `/tmp/commerce-t01-core-red.log`.
3. GREEN: exact plan command above — 4 tests, 0 failures/errors/skips. Log: `/tmp/commerce-t01-targeted.log`.
4. GREEN: `mvn -f backend/commerce/pom.xml clean verify` — all five reactor projects succeeded; 4 tests passed. Log: `/tmp/commerce-t01-verify.log`.
5. GREEN: copy only `backend/commerce` (excluding target directories) to `/tmp/commerce-t01-standalone`, then `mvn -f /tmp/commerce-t01-standalone/pom.xml verify` — independent reactor succeeded, same 4 tests. No repository parent exists at that location. Log: `/tmp/commerce-t01-standalone.log`.
6. GREEN: `mvn -f backend/pom.xml -pl commerce/commerce-api,commerce/commerce-core -am validate` — host aggregation resolved and selected all three intended projects. Log: `/tmp/commerce-t01-host-validate.log`.
7. GREEN: `mvn -f backend/pom.xml -N help:effective-pom -Doutput=/tmp/commerce-host-effective-after.xml -q`; XML comparison against `/tmp/commerce-host-effective.xml` confirmed existing managed dependency entries unchanged.
8. GREEN: `git diff --check`.

`PortableApiContractTest` compiles two distinct host port implementations against actual production contracts and invokes their behavior; it also checks the six facades expose typed interface methods. `CoreDependencyTest` checks the actual Maven-resolved runtime classpath (API empty, core only API), rejects empty API output, and invokes JDK jdeps on each production output **separately** to inspect class references. API may reference only JDK/API; core may also reference core. Scanning separately avoids jdeps collapsing the two same-named `classes` directories. A nonzero actual class-reference count is required.

## Rulings and limits

- Ruling: T01 plan permits signatures to be filled in later, while the approved execution request asks for fully typed future signatures now. Implemented future command/result/port contracts now, with no business implementations. Cost if wrong: later task review may require source-compatible extensions or an explicitly reviewed API revision.
- Ruling: add minimal JPMS descriptors to encode the production dependency boundary even while core has no business classes. Tests run on the classpath so compiler/XML test tooling does not broaden production modules. Cost if wrong: later framework/module-path integration may require descriptor changes; classpath consumers are unaffected.
- Ruling: independent commerce parent owns its dependency version lock despite the host's general no-child-version convention; this follows the explicit portability requirement. Cost if wrong: a repository documentation/version guard may need to recognize the independent reactor boundary rather than forcing host parent inheritance.
- No whole-host business suite, MySQL, payments, inventory, notifications, production database, frontend, deployment, or T02 acceptance was run. The 4 tests establish T01 contract/build boundaries, not free initialization, payment, fulfillment, expiry admission, or reminder behavior. Fresh authorization/session implementation and fail-closed assembly belong to later tasks.
- Expected intermediate failure: initial JPMS test compilation could not read `java.compiler`; resolved by test-only classpath configuration. No production dependency on compiler/testing libraries was added.
- Maven's first offline metadata attempt failed because help-plugin 3.5.1 was not locally available; online authorized resolution succeeded. Workspace/cache writes required automatic sandbox escalation and were approved. No remaining environment blocker.
- Copied design/spec/schema/diagram inputs are deliberately uncommitted. No push, deployment, production mutation, or unrelated workspace changes.

Independent review is handled by the parent agent before proceeding to T02.
