package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.ConfigRevisionInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/** Verifies publication identity and frozen content over one existing authenticated FOOD call per attempt. */
public class RemoteConfigRevisionService implements ConfigRevisionService {
    private final ConfigRevisionInternalApi api;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    public RemoteConfigRevisionService(ConfigRevisionInternalApi api) { this.api = api; }

    @Override
    public PublishConfigResultVO publishModifier(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        var result = invoke(() -> api.publishModifier(new ConfigRevisionInternalApi.Command(
                entity, store, number, actor, key, req)));
        return publication(result, "MODIFIER", number);
    }
    @Override
    public PublishConfigResultVO publishOptionGroup(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        var result = invoke(() -> api.publishOptionGroup(new ConfigRevisionInternalApi.Command(
                entity, store, number, actor, key, req)));
        return publication(result, "OPTION_GROUP", number);
    }
    @Override
    public PageData<ConfigRevisionVO> listRevisions(String entity, String store, ListConfigRevisionsQuery query) {
        var page = invoke(() -> api.listRevisions(new ConfigRevisionInternalApi.Lookup(entity, store, query)));
        int p = query.page() == null ? 1 : query.page(), size = query.size() == null ? 20 : query.size();
        var seen = new HashSet<String>(); var versions = new HashSet<Long>();
        if (page == null || page.page() != p || page.size() != size || page.records() == null
                || page.records().size() > size || page.total() < page.records().size()
                || page.records().stream().anyMatch(v -> !valid(v) || !query.configKind().equals(v.configKind())
                        || !query.configNo().equals(v.configNo()) || !seen.add(v.revisionNo()) || !versions.add(v.revision()))) {
            throw malformed();
        }
        return page;
    }
    @Override
    public ConfigRevisionContentVO getRevision(String entity, String store, String number) {
        var result = invoke(() -> api.getRevision(new ConfigRevisionInternalApi.Identity(entity, store, number)));
        content(result);
        if (!number.equals(result.publication().revisionNo())) throw malformed();
        return result;
    }
    @Override
    public ConfigRevisionContentVO resolveRevision(String entity, String store, String kind, String number,
            OffsetDateTime asOf) {
        var result = invoke(() -> api.resolveRevision(new ConfigRevisionInternalApi.Resolve(entity, store, kind, number, asOf)));
        content(result);
        var meta = result.publication();
        if (!kind.equals(meta.configKind()) || !number.equals(meta.configNo()) || meta.effectiveAt().isAfter(asOf)
                || !"EFFECTIVE".equals(meta.status())) throw malformed();
        return result;
    }
    private PublishConfigResultVO publication(PublishConfigResultVO result, String kind, String number) {
        if (result == null || !valid(result.publication()) || !kind.equals(result.publication().configKind())
                || !number.equals(result.publication().configNo()) || "CANCELLED".equals(result.publication().status())) {
            throw malformed();
        }
        return result;
    }
    private void content(ConfigRevisionContentVO result) {
        if (result == null || !valid(result.publication()) || result.definitionSnapshot() == null
                || result.definitionSnapshot().length() > 1_048_576
                || !PublicationDocuments.hash(json, result.definitionSnapshot()).equals(result.publication().contentHash())) {
            throw malformed();
        }
        var body = json.readValue(result.definitionSnapshot(), PublicationSnapshot.class);
        if (body.contentSchema() != 1 || body.sourceVersion() < 0
                || !result.publication().configKind().equals(body.configKind())
                || !result.publication().configNo().equals(body.configNo())
                || "MODIFIER".equals(body.configKind()) && (body.modifier() == null || body.optionGroup() != null)
                || "OPTION_GROUP".equals(body.configKind()) && (body.optionGroup() == null || body.modifier() != null)) {
            throw malformed();
        }
    }
    private static boolean valid(ConfigRevisionVO v) {
        return v != null && key(v.revisionNo()) && key(v.configNo()) && v.configKind() != null
                && Set.of("MODIFIER", "OPTION_GROUP").contains(v.configKind()) && v.revision() > 0
                && v.effectiveAt() != null && v.contentHash() != null && v.contentHash().matches("[a-f0-9]{64}")
                && v.status() != null && Set.of("SCHEDULED", "EFFECTIVE", "CANCELLED").contains(v.status());
    }
    private static boolean key(String v) {
        return v != null && !v.isBlank() && v.length() <= 32 && v.equals(v.trim());
    }
    private static IllegalStateException malformed() { return new IllegalStateException("Invalid config publication receipt"); }
    private <T> T invoke(Supplier<T> call) {
        try { return ServiceCalls.call(ServiceName.FOOD, call); }
        catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST; case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND; case 409 -> ErrorCode.CONFLICT; default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}
