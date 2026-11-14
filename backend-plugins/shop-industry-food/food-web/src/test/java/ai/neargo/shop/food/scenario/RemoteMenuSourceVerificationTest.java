package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Captured;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.ItemOptions;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.MenuSourceContracts.Query;
import ai.neargo.shop.food.dto.MenuSourceContracts.Resource;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.foodclient.RemoteConfigRevisionService;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.ConfigRevisionInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

class RemoteMenuSourceVerificationTest {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-26T08:00:00Z");
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final Query query = new Query("E", "A", "M", "R1", Kind.SKU, "S1", "ITEM", null, Mode.CURRENT);

    ConfigRevisionContentVO document() {
        var item =
                new MenuItemInput(
                        "ITEM",
                        "G",
                        0,
                        true,
                        "EXPLICIT",
                        List.of("S1"),
                        "REPLACE",
                        List.of(),
                        List.of());
        var definition =
                new MenuConfigResultVO(
                        new MenuVO("M", "A", "Menu", "TIME", "UTC", 0, null, "DRAFT", 0L, 0L),
                        new MenuScheduleVO(null, null, List.of(), List.of()),
                        new MenuCompositionVO(
                                List.of(
                                        new MenuCategoryInput(
                                                "CAT", "Category", 0, List.of(item)))),
                        List.of(new MenuContextInput("POS", "DINE_IN", null, null)));
        var captured =
                new Captured(
                        "E",
                        "A",
                        definition,
                        List.of(
                                new DishVO(
                                        "G",
                                        "SINGLE",
                                        0,
                                        new DishPresentation(
                                                null, List.of(), List.of(), List.of(), List.of()),
                                        new MeasurementPolicy("EA", "COUNT", 0, null),
                                        0,
                                        0)),
                        List.of(new ItemOptions("ITEM", List.of())),
                        List.of(),
                        List.of());
        String text =
                PublicationDocuments.freeze(
                        json,
                        new MenuPublicationSnapshot(
                                4,
                                "MENU",
                                "M",
                                0,
                                captured,
                                List.of(new SkuIdentityFact("S1", "G", "E", "A", 0)),
                                List.of(
                                        new GoodsFact(
                                                "G",
                                                "E",
                                                "A",
                                                "Goods",
                                                null,
                                                true,
                                                "APPROVED",
                                                0,
                                                List.of(new SkuFact("S1", "One", 0)))),
                                null));
        return new ConfigRevisionContentVO(
                new ConfigRevisionVO(
                        "R1",
                        "MENU",
                        "M",
                        1,
                        NOW.minusDays(1),
                        PublicationDocuments.hash(json, text),
                        "EFFECTIVE"),
                text);
    }

    Proof proof(
            Query q,
            ConfigRevisionContentVO source,
            Resource resource,
            OffsetDateTime at,
            OffsetDateTime until) {
        var p = new Proof(1, q, source, resource, at, until, null);
        return new Proof(1, q, source, resource, at, until, MenuSourcePolicy.receipt(json, p));
    }

    RemoteConfigRevisionService remote(ConfigRevisionInternalApi api) {
        return new RemoteConfigRevisionService(api, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void originalTypedProofAndHistoricalModeSurviveOneTransportCall() {
        var api = mock(ConfigRevisionInternalApi.class);
        var source = document();
        var resource = MenuSourcePolicy.project(json, query, source);
        var p = proof(query, source, resource, NOW, NOW.plusSeconds(30));
        when(api.verifyMenuResource(query)).thenReturn(p);
        assertThat(remote(api).verifyMenuResource(query)).isEqualTo(p);
        verify(api).verifyMenuResource(query);
        verifyNoMoreInteractions(api);
        var historical =
                new Query("E", "A", "M", "R1", Kind.SKU, "S1", "ITEM", null, Mode.HISTORICAL);
        var h = proof(historical, source, resource, NOW, NOW.plusSeconds(30));
        when(api.verifyMenuResource(historical)).thenReturn(h);
        assertThat(remote(api).verifyMenuResource(historical).query().mode())
                .isEqualTo(Mode.HISTORICAL);
    }

    @Test
    void remoteChecksRequestResourceDocumentScopeHashModeAndReceiptInsteadOfTrustingAnOkFlag() {
        var api = mock(ConfigRevisionInternalApi.class);
        var source = document();
        var resource = MenuSourcePolicy.project(json, query, source);
        var valid = proof(query, source, resource, NOW, NOW.plusSeconds(30));
        var foreign =
                new Query("OTHER", "A", "M", "R1", Kind.SKU, "S1", "ITEM", null, Mode.CURRENT);
        var historical =
                new Query("E", "A", "M", "R1", Kind.SKU, "S1", "ITEM", null, Mode.HISTORICAL);
        var forgedMeta =
                new ConfigRevisionVO(
                        "R1", "MENU", "M", 1, NOW.minusDays(1), "0".repeat(64), "EFFECTIVE");
        for (var invalid :
                List.of(
                        proof(foreign, source, resource, NOW, NOW.plusSeconds(30)),
                        proof(historical, source, resource, NOW, NOW.plusSeconds(30)),
                        proof(
                                query,
                                source,
                                new Resource(Kind.SKU, "S1", "OTHER", null),
                                NOW,
                                NOW.plusSeconds(30)),
                        proof(
                                query,
                                new ConfigRevisionContentVO(
                                        forgedMeta, source.definitionSnapshot()),
                                resource,
                                NOW,
                                NOW.plusSeconds(30)),
                        new Proof(
                                1,
                                query,
                                source,
                                resource,
                                NOW,
                                NOW.plusSeconds(30),
                                "0".repeat(64)),
                        proof(query, source, resource, NOW.minusSeconds(30), NOW),
                        proof(query, source, resource, NOW.plusSeconds(1), NOW.plusSeconds(30)),
                        proof(query, source, resource, NOW, NOW.plusSeconds(31)))) {
            when(api.verifyMenuResource(query)).thenReturn(invalid);
            assertThatThrownBy(() -> remote(api).verifyMenuResource(query))
                    .isInstanceOf(IllegalStateException.class);
        }
        // Even a recomputed receipt cannot turn foreign original menu content into a scoped proof.
        var body =
                source.definitionSnapshot().replace("\"entityNo\":\"E\"", "\"entityNo\":\"OTHER\"");
        var metadata =
                new ConfigRevisionVO(
                        "R1",
                        "MENU",
                        "M",
                        1,
                        NOW.minusDays(1),
                        PublicationDocuments.hash(json, body),
                        "EFFECTIVE");
        when(api.verifyMenuResource(query))
                .thenReturn(
                        proof(
                                query,
                                new ConfigRevisionContentVO(metadata, body),
                                resource,
                                NOW,
                                NOW.plusSeconds(30)));
        assertThatThrownBy(() -> remote(api).verifyMenuResource(query))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownOutcomeIsNotRetriedAndOldProviderRemainsClosed() {
        var api = mock(ConfigRevisionInternalApi.class);
        when(api.verifyMenuResource(any()))
                .thenThrow(
                        new ServiceCallException(CallOutcome.TIMEOUT, ServiceName.FOOD, 0, "safe"));
        assertThatThrownBy(() -> remote(api).verifyMenuResource(query))
                .isInstanceOf(ServiceCallException.class);
        verify(api).verifyMenuResource(query);
        verifyNoMoreInteractions(api);
        var old = mock(ConfigRevisionService.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> old.verifyMenuResource(query))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
