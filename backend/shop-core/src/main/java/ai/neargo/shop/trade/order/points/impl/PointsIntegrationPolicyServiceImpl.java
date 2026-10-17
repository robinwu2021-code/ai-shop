package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Service
@Transactional("transactionManager")
public class PointsIntegrationPolicyServiceImpl implements PointsIntegrationPolicyService {
    private final OrderPointsBindingMapper mapper;
    private final ObjectProvider<DetachmentGuard> guards;
    private final ObjectMapper json = new ObjectMapper();

    public PointsIntegrationPolicyServiceImpl(
            OrderPointsBindingMapper mapper, ObjectProvider<DetachmentGuard> guards) {
        this.mapper = mapper;
        this.guards = guards;
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public IntegrationPolicy policy(String store, String scene) {
        validate(store, scene);
        return DataScopeContext.executeWithoutScope(
                () -> decode(store, scene, mapper.policy(store, scene)));
    }

    @Override
    public IntegrationPolicy change(IntegrationPolicyChange c, String actor) {
        require(
                c != null
                        && nonblank(c.requestId())
                        && c.requestId().length() <= 120
                        && nonblank(c.reason())
                        && nonblank(actor)
                        && c.mode() != null,
                "POINTS_INTEGRATION_INPUT_INVALID");
        validate(c.storeNo(), c.scene());
        require(
                c.mode() == Mode.ACTIVE
                        ? c.earnEnabled() || c.redeemEnabled()
                        : !c.earnEnabled() && !c.redeemEnabled(),
                "POINTS_INTEGRATION_FLAGS_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> {
                    mapper.initializePolicy(c.storeNo(), c.scene());
                    var row = mapper.lockPolicy(c.storeNo(), c.scene());
                    String body = json.writeValueAsString(new Intent(c, actor));
                    var prior = mapper.command(c.requestId());
                    if (prior != null) {
                        require(
                                body.equals(prior.get("command_json")),
                                "POINTS_INTEGRATION_COMMAND_CONFLICT");
                        return json.readValue(
                                (String) prior.get("result_json"), IntegrationPolicy.class);
                    }
                    long version = ((Number) row.get("version")).longValue();
                    require(version == c.expectedVersion(), "POINTS_INTEGRATION_REVISION_CONFLICT");
                    if (c.mode() == Mode.DETACHED && !"DETACHED".equals(row.get("mode"))) {
                        require(
                                "DRAINING".equals(row.get("mode")),
                                "POINTS_INTEGRATION_DRAIN_REQUIRED");
                        var guard = guards.getIfAvailable();
                        require(
                                guard != null && guard.canDetach(c.storeNo()),
                                "POINTS_INTEGRATION_OBLIGATIONS_OPEN");
                    }
                    var result =
                            new IntegrationPolicy(
                                    c.storeNo(),
                                    c.scene(),
                                    c.mode(),
                                    c.earnEnabled(),
                                    c.redeemEnabled(),
                                    version + 1,
                                    actor,
                                    c.reason(),
                                    System.currentTimeMillis());
                    require(
                            mapper.changePolicy(
                                            c.storeNo(),
                                            c.scene(),
                                            version,
                                            c.mode().name(),
                                            json.writeValueAsString(result))
                                    == 1,
                            "POINTS_INTEGRATION_REVISION_CONFLICT");
                    mapper.saveCommand(c.requestId(), body, json.writeValueAsString(result));
                    return result;
                });
    }

    private IntegrationPolicy decode(String store, String scene, Map<String, Object> row) {
        if (row == null || row.get("payload_json") == null)
            return new IntegrationPolicy(
                    store, scene, Mode.DETACHED, false, false, -1, null, null, 0);
        return json.readValue((String) row.get("payload_json"), IntegrationPolicy.class);
    }

    private static void validate(String store, String scene) {
        require(
                nonblank(store) && store.length() <= 64 && PayModes.isValid(scene),
                "POINTS_INTEGRATION_INPUT_INVALID");
    }

    private static boolean nonblank(String v) {
        return v != null && !v.isBlank();
    }

    private static void require(boolean v, String error) {
        if (!v) throw new IllegalArgumentException(error);
    }

    public record Intent(IntegrationPolicyChange command, String actor) {}
}
