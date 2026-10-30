package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl;
import ai.neargo.shop.merchant.port.PersonnelOperatorFactsPortImpl;
import ai.neargo.shop.portal.internal.InternalPersonnelOperatorController;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.LinkState;
import ai.neargo.shop.spi.personnel.PersonnelOperatorInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Route/token/context behavior exercises the actual bound controller, including real JSON decoding.
 */
class PersonnelOperatorProtocolTest {
    private static final String TOKEN = "local-task62-test-key";
    private final DelegatedIdentity identity =
            new DelegatedIdentity("MAIN", "MERCHANT", "USR", "200");
    private final PersonnelOperatorFactsPort.Query query =
            new PersonnelOperatorFactsPort.Query(
                    new MerchantOperatorAccessPort.Query(
                            "MAIN", "E", "S", "200", "MERCHANT", "USR", "biz:service:task:self"));

    private InternalCall<PersonnelOperatorFactsPort.Query> call(
            String endpoint, String target, DelegatedIdentity actor) {
        return new InternalCall<>(
                new InternalRequestBinding(1, endpoint, "CURRENT", actor, "E", "S", target), query);
    }

    @Test
    void tokenThenRouteThenCurrentScopeAndFinallyContext() throws Exception {
        var seen = new AtomicInteger();
        var before = SecurityContextHolder.getContext();
        PersonnelOperatorFactsPort owner =
                q -> {
                    assertThat(DelegatedIdentityContext.current()).isEqualTo(identity);
                    assertThat(q).isEqualTo(query);
                    seen.incrementAndGet();
                    return new PersonnelOperatorFactsPort.Observation(
                            LinkState.UNLINKED, Instant.EPOCH, null);
                };
        var controller = new InternalPersonnelOperatorController(owner, TOKEN);
        var bound = call(PersonnelOperatorInternalApi.ENDPOINT, "200", identity);
        assertThat(controller.current(bound, "wrong").getStatusCode().value()).isEqualTo(401);
        assertThat(seen.get()).isZero();
        assertThat(
                        controller
                                .current(call("OTHER", "200", identity), TOKEN)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(seen.get()).isZero();
        var response = controller.current(bound, TOKEN);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(1);
        assertThat(SecurityContextHolder.getContext()).isSameAs(before);
        var json = JsonMapper.builder().build().writeValueAsString(response.getBody());
        assertThat(json).contains("UNLINKED").doesNotContain("accountNo", "contact", "role");
        var throwing =
                new InternalPersonnelOperatorController(
                        q -> {
                            throw new IllegalStateException("private");
                        },
                        TOKEN);
        assertThat(throwing.current(bound, TOKEN).getStatusCode().value()).isEqualTo(503);
        assertThat(SecurityContextHolder.getContext()).isSameAs(before);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(
                        post(PersonnelOperatorInternalApi.PATH)
                                .header("X-Internal-Token", TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post(PersonnelOperatorInternalApi.PATH)
                                .header("X-Internal-Token", TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongRealmTargetAndPayloadAreRejected() {
        var seen = new AtomicInteger();
        var controller =
                new InternalPersonnelOperatorController(
                        q -> {
                            seen.incrementAndGet();
                            return new PersonnelOperatorFactsPort.Observation(
                                    LinkState.UNLINKED, Instant.EPOCH, null);
                        },
                        TOKEN);
        assertThat(
                        controller
                                .current(
                                        call(
                                                PersonnelOperatorInternalApi.ENDPOINT,
                                                "wrong",
                                                identity),
                                        TOKEN)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        var other = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "200");
        assertThat(
                        controller
                                .current(
                                        call(PersonnelOperatorInternalApi.ENDPOINT, "200", other),
                                        TOKEN)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        var consumer =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                PersonnelOperatorInternalApi.ENDPOINT,
                                "CURRENT",
                                new DelegatedIdentity("MAIN", "CONSUMER", "USR", "200"),
                                null,
                                null,
                                "200"),
                        query);
        assertThat(controller.current(consumer, TOKEN).getStatusCode().value()).isEqualTo(400);
        var wrongStore =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                PersonnelOperatorInternalApi.ENDPOINT,
                                "CURRENT",
                                identity,
                                "E",
                                "OTHER",
                                "200"),
                        query);
        assertThat(controller.current(wrongStore, TOKEN).getStatusCode().value()).isEqualTo(400);
        assertThat(seen.get()).isZero();
        assertThatThrownBy(
                        () ->
                                new PersonnelOperatorFactsPort.Query(
                                        new MerchantOperatorAccessPort.Query(
                                                "MAIN",
                                                "E",
                                                "S",
                                                "200",
                                                "MERCHANT",
                                                "USR",
                                                "biz:booking:manage")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embeddedDefaultDisabledOpsHaveOneOrNoProvider() {
        // These are bean-assembly dependencies only; no permission/provider behavior is
        // substituted.
        var accessMapper = mock(MerchantMappers.OperatorAccessMapper.class);
        var links = mock(MerchantMappers.PersonnelAccountMapper.class);
        for (String mode :
                new String[] {"default-retail", "beauty-embedded", "beauty-disabled", "ops"}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(PersonnelOperatorFactsPortImpl.class)
                    .withBean(
                            MerchantOperatorAccessPort.class,
                            () -> new MerchantOperatorAccessPortImpl(accessMapper))
                    .withBean(MerchantMappers.OperatorAccessMapper.class, () -> accessMapper)
                    .withBean(MerchantMappers.PersonnelAccountMapper.class, () -> links)
                    .withBean(
                            "transactionManager",
                            PlatformTransactionManager.class,
                            () -> mock(PlatformTransactionManager.class))
                    .withPropertyValues("shop.beauty.enabled=" + mode.equals("beauty-embedded"))
                    .run(
                            c -> {
                                assertThat(c.getBeansOfType(PersonnelOperatorFactsPort.class))
                                        .hasSize(1);
                                assertThat(c.getBean(PersonnelOperatorFactsPort.class))
                                        .isExactlyInstanceOf(PersonnelOperatorFactsPortImpl.class);
                            });
        }
        new ApplicationContextRunner()
                .run(c -> assertThat(c.getBeansOfType(PersonnelOperatorFactsPort.class)).isEmpty());
    }
}
