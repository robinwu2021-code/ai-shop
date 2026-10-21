package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.CallOutcome;
import tools.jackson.databind.json.JsonMapper;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.foodclient.RemoteConfigRevisionService;
import ai.neargo.shop.food.spi.ConfigRevisionInternalApi;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteConfigRevisionServiceTest {
    @Test void bundleCommandAndSchemaTwoContentUseOriginalTypedTransport() {
        var api = mock(ConfigRevisionInternalApi.class); var service = new RemoteConfigRevisionService(api);
        var req = new PublishConfigReq(3L, NOW, null);
        String document = new BundleSnapshotCompatibilityTest().bundle();
        var json = JsonMapper.builder().findAndAddModules().build();
        var meta = new ConfigRevisionVO("REV", "BUNDLE", "MEAL", 1, NOW,
                PublicationDocuments.hash(json, document), "EFFECTIVE");
        var result = new PublishConfigResultVO(meta);
        when(api.publishBundle(any())).thenReturn(result);
        assertThat(service.publishBundle("E", "S", "MEAL", "actor", req, "key")).isEqualTo(result);
        verify(api).publishBundle(new ConfigRevisionInternalApi.Command("E", "S", "MEAL", "actor", "key", req));
        when(api.getRevision(any())).thenReturn(new ConfigRevisionContentVO(meta, document));
        assertThat(service.getRevision("E", "S", "REV").definitionSnapshot()).isEqualTo(document);
        when(api.resolveRevision(any())).thenReturn(new ConfigRevisionContentVO(meta, document));
        assertThat(service.resolveRevision("E", "S", "BUNDLE", "MEAL", NOW).publication()).isEqualTo(meta);
        when(api.listRevisions(any())).thenReturn(PageData.of(List.of(meta), 1, 1, 20));
        assertThat(service.listRevisions("E", "S", new ListConfigRevisionsQuery("BUNDLE", "MEAL", 1, 20))
                .records()).containsExactly(meta);
        String unknown = document.replace("\"contentSchema\":2", "\"contentSchema\":9");
        var badMeta = new ConfigRevisionVO("REV", "BUNDLE", "MEAL", 1, NOW,
                PublicationDocuments.hash(json, unknown), "EFFECTIVE");
        when(api.getRevision(any())).thenReturn(new ConfigRevisionContentVO(badMeta, unknown));
        assertThatThrownBy(() -> service.getRevision("E", "S", "REV")).isInstanceOf(IllegalStateException.class);
    }

    @Test void bundlePublicationErrorsRemainSingleAttemptAndNeverBecomeSuccess() {
        var api = mock(ConfigRevisionInternalApi.class);
        when(api.publishBundle(any())).thenThrow(new ServiceCallException(
                CallOutcome.TIMEOUT, ServiceName.FOOD, 0, "safe"));
        var req = new PublishConfigReq(0L, NOW, null);
        assertThatThrownBy(() -> new RemoteConfigRevisionService(api)
                .publishBundle("E", "S", "MEAL", "actor", req, "key")).isInstanceOf(ServiceCallException.class);
        verify(api).publishBundle(new ConfigRevisionInternalApi.Command("E", "S", "MEAL", "actor", "key", req));
        verifyNoMoreInteractions(api);
    }
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T08:00:00Z");
    @Test void preservesTrustedCommandAndRejectsWrongPublicationIdentity() {
        var api = mock(ConfigRevisionInternalApi.class); var service = new RemoteConfigRevisionService(api);
        var req = new PublishConfigReq(0L, NOW, null);
        var result = new PublishConfigResultVO(new ConfigRevisionVO("REV", "MODIFIER", "MOD", 1,
                NOW, "a".repeat(64), "EFFECTIVE"));
        when(api.publishModifier(any())).thenReturn(result);
        assertThat(service.publishModifier("E", "S", "MOD", "actor", req, "key")).isEqualTo(result);
        verify(api).publishModifier(new ConfigRevisionInternalApi.Command("E", "S", "MOD", "actor", "key", req));
        verifyNoMoreInteractions(api);
        when(api.publishModifier(any())).thenReturn(new PublishConfigResultVO(new ConfigRevisionVO(
                "REV", "MODIFIER", "OTHER", 1, NOW, "a".repeat(64), "EFFECTIVE")));
        assertThatThrownBy(() -> service.publishModifier("E", "S", "MOD", "actor", req, "key"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsDuplicateHistoryAndAcceptsAnIdentifiedPage() {
        var api = mock(ConfigRevisionInternalApi.class); var service = new RemoteConfigRevisionService(api);
        var query = new ListConfigRevisionsQuery("MODIFIER", "MOD", 1, 20);
        var view = new ConfigRevisionVO("REV", "MODIFIER", "MOD", 1, NOW, "a".repeat(64), "EFFECTIVE");
        when(api.listRevisions(any())).thenReturn(PageData.of(List.of(view,view), 2, 1, 20));
        assertThatThrownBy(() -> service.listRevisions("E", "S", query)).isInstanceOf(IllegalStateException.class);
        when(api.listRevisions(any())).thenReturn(PageData.of(List.of(view), 1, 1, 20));
        assertThat(service.listRevisions("E", "S", query).records()).containsExactly(view);
    }
    @Test void validatesFrozenContentHashAndTimeResolvedIdentity() {
        var api = mock(ConfigRevisionInternalApi.class); var service = new RemoteConfigRevisionService(api);
        var json = JsonMapper.builder().findAndAddModules().build();
        var definition = new ModifierConfigVO("MOD", "ADDON", 0, "DRAFT",
                new ModifierConfigVO.Data("珍珠", "SKU", null, List.of()));
        String document = PublicationDocuments.freeze(json, new PublicationSnapshot(1, "MODIFIER", "MOD", 0,
                definition, null, List.of(), List.of(new SkuIdentityFact("SKU", "G", "E", "S", 0)), List.of(), null));
        var meta = new ConfigRevisionVO("REV", "MODIFIER", "MOD", 1, NOW,
                PublicationDocuments.hash(json, document), "EFFECTIVE");
        var body = new ConfigRevisionContentVO(meta, document);
        when(api.getRevision(any())).thenReturn(body);
        assertThat(service.getRevision("E", "S", "REV")).isEqualTo(body);
        when(api.resolveRevision(any())).thenReturn(body);
        assertThat(service.resolveRevision("E", "S", "MODIFIER", "MOD", NOW)).isEqualTo(body);
        assertThatThrownBy(() -> service.resolveRevision("E", "S", "MODIFIER", "MOD", NOW.minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        when(api.getRevision(any())).thenReturn(new ConfigRevisionContentVO(meta, document.replace("珍珠", "changed")));
        assertThatThrownBy(() -> service.getRevision("E", "S", "REV")).isInstanceOf(IllegalStateException.class);
    }

    @Test void businessAndInfrastructureFailuresNeverRetry() {
        for (int status : new int[]{400,403,404,409,503,0}) {
            var api = mock(ConfigRevisionInternalApi.class);
            when(api.listRevisions(any())).thenThrow(new ServiceCallException(
                    status == 0 ? CallOutcome.TIMEOUT : CallOutcome.REMOTE_ERROR, ServiceName.FOOD, status, "safe"));
            var query = new ListConfigRevisionsQuery("MODIFIER", "MOD", 1, 20);
            var expected = status >= 400 && status < 500 ? BizException.class : ServiceCallException.class;
            assertThatThrownBy(() -> new RemoteConfigRevisionService(api).listRevisions("E", "S", query))
                    .isInstanceOf(expected);
            verify(api).listRevisions(new ConfigRevisionInternalApi.Lookup("E", "S", query));
            verifyNoMoreInteractions(api);
        }
    }

}
