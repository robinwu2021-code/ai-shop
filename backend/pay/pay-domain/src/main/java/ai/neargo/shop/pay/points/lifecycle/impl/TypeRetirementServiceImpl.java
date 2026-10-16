package ai.neargo.shop.pay.points.lifecycle.impl;

import ai.neargo.shop.pay.points.lifecycle.TypeRetirementService;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.LifecycleStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Draining alone is not proof that money and refund responsibility have disappeared. */
@Service
@Transactional("payTxManager")
public class TypeRetirementServiceImpl implements TypeRetirementService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final LifecycleStore store;
    private final PointsPostingService posting;
    private final ObjectMapper json = new ObjectMapper();

    public TypeRetirementServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore config,
            LifecycleStore store,
            PointsPostingService posting) {
        this.read = read;
        this.config = config;
        this.store = store;
        this.posting = posting;
    }

    @Override
    public PointType retire(String no, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("POINTS_OPERATOR_REQUIRED");
        }
        config.lockTypeAndPool(no);
        var type = read.type(no).orElseThrow();
        if (type.status() == PointType.Status.RETIRED) {
            return type;
        }
        if (type.status() != PointType.Status.DRAINING) {
            throw new IllegalArgumentException("POINTS_TYPE_NOT_DRAINING");
        }
        if (!store.clearForRetirement(no)) {
            throw new IllegalArgumentException("POINTS_RETIREMENT_OBLIGATIONS_REMAIN");
        }
        posting.post(
                new Voucher(
                        "TYPE_RETIRE:" + no,
                        no,
                        type.currency(),
                        type.rate(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        json.writeValueAsString(Map.of("actor", actor))));
        config.changeTypeStatus(
                no, PointType.Status.DRAINING, PointType.Status.RETIRED, null, actor);
        return read.type(no).orElseThrow();
    }
}
