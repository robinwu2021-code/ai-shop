package ai.neargo.shop.arch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BizEndpointSourceTest {
    @Test void permissionAnnotationsBelongToMethodsRegardlessOfOrder() throws Exception {
        var endpoints = BizEndpointSource.parse("""
            class Controller {
                @GetMapping("/biz/before") @PreAuthorize("@perm.canBiz('biz:stock')")
                public void before() { }
                @PreAuthorize("@perm.canBiz('biz:stock')")
                @org.springframework.web.bind.annotation.PatchMapping(path={"/biz/after", "/biz/alias"})
                public void after() { }
                @PostMapping("/biz/unprotected") public void unprotected() { }
            }
            """).endpoints();
        assertThat(endpoints).hasSize(4);
        assertThat(endpoints.stream().filter(e -> e.method().bizGuard()).map(BizEndpointSource.Endpoint::path))
                .containsExactly("/biz/before", "/biz/after", "/biz/alias");
        assertThat(endpoints.getLast().method().bizGuard()).isFalse();
    }

    @Test void commentsStringsAndAdjacentMethodsCannotSupplyAuthorization() throws Exception {
        var endpoint = BizEndpointSource.parse("""
            class Controller {
                // @PreAuthorize("@perm.canBiz('biz:stock')")
                @PostMapping("/biz/unguarded") public void write() {
                    String decoy = "@PreAuthorize(@perm.canBiz('biz:stock'))";
                }
            }
            """).endpoints().getFirst();
        assertThat(endpoint.method().bizGuard()).isFalse();
    }

    @Test void classMappingsAndMethodOverridesUseActualEffectiveAnnotation() throws Exception {
        var endpoints = BizEndpointSource.parse("""
            @RequestMapping("/biz") @PreAuthorize("@perm.canBiz('biz:stock')")
            class Controller {
                @GetMapping("/protected") public void read() { }
                @GetMapping("/override") @PreAuthorize("isAuthenticated()") public void other() { }
            }
            """).endpoints();
        assertThat(endpoints.getFirst().path()).isEqualTo("/biz/protected");
        assertThat(endpoints.getFirst().method().bizGuard()).isTrue();
        assertThat(endpoints.getLast().method().bizGuard()).isFalse();
    }

    @Test void unresolvedMappingsFailInsteadOfSilentlyDroppingAnEndpoint() {
        assertThatThrownBy(() -> BizEndpointSource.parse("class C { @PostMapping(UNKNOWN) public void m(){} }"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("explicit paths");
    }

    @Test void resourceDecisionRequiresActualActorAndScopeCallNotAComment() throws Exception {
        String source = """
            @PreAuthorize("@managementAccess.authenticated()") class Controller {
                @GetMapping("/biz/resource") public Object read(String store) {
                    return owner.read(access.actor(), store);
                }
            }
            """;
        var method = BizEndpointSource.parse(source).endpoints().getFirst().method();
        List<String> proof = List.of("owner.read(access.actor(),store)", "access.actor()");
        assertThat(BizResourceAuthorization.authenticated(method)).isTrue();
        assertThat(BizResourceAuthorization.methodMatches(method, proof)).isTrue();
        var withoutScope = BizEndpointSource.parse(source.replace("return owner.read(access.actor(), store);",
                "/* owner.read(access.actor(),store) */ return null;")).endpoints().getFirst().method();
        assertThat(BizResourceAuthorization.methodMatches(withoutScope, proof)).isFalse();
        var withoutAuth = BizEndpointSource.parse(source.replace("@PreAuthorize(\"@managementAccess.authenticated()\")", ""))
                .endpoints().getFirst().method();
        assertThat(BizResourceAuthorization.authenticated(withoutAuth)).isFalse();
    }

    @Test void anUnregisteredSiblingHandlerCannotReuseAResourcePathDecision() throws Exception {
        var endpoints = BizEndpointSource.parse("""
            class BizBrandController {
                @DeleteMapping("/biz/management/brands") public void unsafe() { }
            }
            """).endpoints();
        assertThat(BizResourceAuthorization.covered(endpoints.getFirst())).isFalse();
        assertThatThrownBy(() -> BizResourceAuthorization.verify(Path.of("../.."), endpoints))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Unregistered resource handler");
    }

    @Test void quotedSpelDecoysAndUnprotectedOrBranchesCannotAuthorize() throws Exception {
        for (String expression : List.of("true || '@perm.canBiz(' != null", "@perm.canBiz('biz:stock') or true")) {
            assertThat(BizEndpointSource.authorizationRequires(expression, "perm", java.util.Set.of("canBiz", "canAnyBiz"))).isFalse();
        }
        assertThat(BizEndpointSource.authorizationRequires("true || '@managementAccess.authenticated()' != null",
                "managementAccess", java.util.Set.of("authenticated"))).isFalse();
        assertThat(BizEndpointSource.authorizationRequires("isAuthenticated() and @perm.canBiz('biz:stock')",
                "perm", java.util.Set.of("canBiz"))).isTrue();
    }

    @Test void anUncalledNestedClassCannotProvideResourceAuthorization() throws Exception {
        var method = BizEndpointSource.parse("""
            class Controller {
                @GetMapping("/biz/resource") public Object read(String store) {
                    class Uncalled { void guard() { owner.read(access.actor(), store); } }
                    return null;
                }
            }
            """).endpoints().getFirst().method();
        assertThat(BizResourceAuthorization.methodMatches(method, List.of("owner.read(access.actor(),store)", "access.actor()"))).isFalse();
    }

    @Test void removingRealOwnerResourceGuardInvalidatesItsEvidence() throws Exception {
        var evidence = BizResourceAuthorization.EVIDENCE.get("IssuerAuthorityServiceImpl#scope");
        String source = Files.readString(Path.of("../..").resolve(evidence.source()));
        var original = BizEndpointSource.parse(source).methods().stream().filter(m -> m.name().equals("scope")).findFirst().orElseThrow();
        assertThat(BizResourceAuthorization.methodMatches(original, evidence.calls())).isTrue();
        String mutated = source.replace("scopes.require(actor,management,type,no,permission);", "");
        assertThat(mutated).isNotEqualTo(source);
        var missing = BizEndpointSource.parse(mutated).methods().stream().filter(m -> m.name().equals("scope")).findFirst().orElseThrow();
        assertThat(BizResourceAuthorization.methodMatches(missing, evidence.calls())).isFalse();
    }
}
