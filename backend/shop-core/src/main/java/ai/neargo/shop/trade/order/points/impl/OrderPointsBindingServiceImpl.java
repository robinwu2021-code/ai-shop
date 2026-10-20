package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.*;

@Service
@Transactional("transactionManager")
public class OrderPointsBindingServiceImpl implements OrderPointsBindingService {
    private final OrderPointsBindingMapper mapper;
    private final PointsIntegrationPolicyService policies;
    private final ObjectProvider<CompletionGuard> guards;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPointsBindingServiceImpl(
            OrderPointsBindingMapper mapper,
            PointsIntegrationPolicyService policies,
            ObjectProvider<CompletionGuard> guards) {
        this.mapper = mapper;
        this.policies = policies;
        this.guards = guards;
    }

    @Override
    public Binding admit(Admission c) {
        validate(c);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var policy = policies.policy(c.storeNo(), c.scene());
                    require(policy.mode() == Mode.ACTIVE, "POINTS_ADMISSION_CLOSED");
                    require(
                            policy.version() == c.policyVersion(),
                            "POINTS_ADMISSION_REVISION_CONFLICT");
                    require(
                            (!c.earning() || policy.earnEnabled())
                                    && (!c.redemption() || policy.redeemEnabled()),
                            "POINTS_ADMISSION_CLOSED");
                    var prior = mapper.existingBinding(c.requestId(), c.subOrderNo());
                    if (!prior.isEmpty()) {
                        require(prior.size() == 1, "POINTS_BINDING_IDENTITY_CONFLICT");
                        var original = decode(prior.getFirst());
                        require(original.admission().equals(c), "POINTS_BINDING_INPUT_CONFLICT");
                        return original;
                    }
                    var result =
                            new Binding(UUID.randomUUID().toString(), c, Phase.PENDING, 0, null);
                    mapper.createBinding(
                            result.bindingNo(),
                            c.requestId(),
                            c.orderNo(),
                            c.subOrderNo(),
                            c.storeNo(),
                            json.writeValueAsString(result));
                    return result;
                });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public Binding find(String sub) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var row = mapper.binding(sub);
                    return row == null ? null : decode(row);
                });
    }

    @Override
    public Binding transition(Transition c) {
        require(
                c != null && nonblank(c.bindingNo()) && c.target() != null,
                "POINTS_BINDING_TRANSITION_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var row = mapper.lockBinding(c.bindingNo());
                    require(row != null, "POINTS_BINDING_UNKNOWN");
                    var old = decode(row);
                    String body = json.writeValueAsString(c);
                    if (body.equals(row.get("last_transition_json"))) return old;
                    require(
                            old.version() == c.expectedVersion(),
                            "POINTS_BINDING_REVISION_CONFLICT");
                    require(
                            old.holdNo() == null
                                    || c.holdNo() == null
                                    || old.holdNo().equals(c.holdNo()),
                            "POINTS_BINDING_HOLD_CONFLICT");
                    boolean allowed =
                            (old.phase() == Phase.PENDING
                                            && (c.target() == Phase.BOUND
                                                    || c.target() == Phase.CLOSING))
                                    || (old.phase() == Phase.BOUND && c.target() == Phase.CLOSING)
                                    || (old.phase() == Phase.CLOSING && c.target() == Phase.CLOSED);
                    require(allowed, "POINTS_BINDING_PHASE_CONFLICT");
                    if (c.target() == Phase.BOUND && old.admission().redemption())
                        require(nonblank(c.holdNo()), "POINTS_BINDING_HOLD_REQUIRED");
                    if (c.target() == Phase.CLOSED) {
                        var guard = guards.getIfAvailable();
                        require(
                                guard != null && guard.complete(old),
                                "POINTS_BINDING_OBLIGATIONS_OPEN");
                    }
                    var result =
                            new Binding(
                                    old.bindingNo(),
                                    old.admission(),
                                    c.target(),
                                    old.version() + 1,
                                    old.holdNo() == null ? c.holdNo() : old.holdNo());
                    require(
                            mapper.changeBinding(
                                            c.bindingNo(),
                                            old.version(),
                                            c.target().name(),
                                            json.writeValueAsString(result),
                                            body)
                                    == 1,
                            "POINTS_BINDING_REVISION_CONFLICT");
                    return result;
                });
    }

    private Binding decode(Map<String, Object> row) {
        return json.readValue((String) row.get("payload_json"), Binding.class);
    }

    private static void validate(Admission c) {
        require(
                c != null
                        && nonblank(c.requestId())
                        && c.requestId().length() <= 120
                        && nonblank(c.orderNo())
                        && nonblank(c.subOrderNo())
                        && nonblank(c.userNo())
                        && nonblank(c.storeNo())
                        && nonblank(c.merchantNo())
                        && nonblank(c.typeCode())
                        && c.currency() != null
                        && c.currency().matches("[A-Z]{3}")
                        && PayModes.isValid(c.scene())
                        && (c.earning() || c.redemption())
                        && c.snapshot() != null,
                "POINTS_ADMISSION_INPUT_INVALID");
        var s = c.snapshot();
        var p = s.payment();
        require(
                p != null
                        && c.storeNo().equals(p.storeNo())
                        && c.merchantNo().equals(p.merchantNo()),
                "POINTS_ADMISSION_STORE_MISMATCH");
        require(
                s.goodsMinor() >= 0
                        && s.quotedMinor() >= 0
                        && s.quotedMinor() <= s.goodsMinor()
                        && s.quotedPoints() >= 0
                        && s.ratePoints() > 0
                        && s.rateMinor() > 0
                        && nonblank(s.ruleSnapshot())
                        && nonblank(s.agreementSnapshot()),
                "POINTS_ADMISSION_SNAPSHOT_INVALID");
        require(
                BigInteger.valueOf(s.quotedPoints())
                        .multiply(BigInteger.valueOf(s.rateMinor()))
                        .equals(
                                BigInteger.valueOf(s.quotedMinor())
                                        .multiply(BigInteger.valueOf(s.ratePoints()))),
                "POINTS_ADMISSION_RATE_MISMATCH");
        require(!c.redemption() || s.quotedPoints() > 0, "POINTS_ADMISSION_POINTS_INVALID");
        long sum = 0;
        var ids = new HashSet<String>();
        for (var item : s.items()) {
            require(
                    item != null
                            && nonblank(item.itemId())
                            && item.goodsMinor() >= 0
                            && ids.add(item.itemId()),
                    "POINTS_ADMISSION_ITEMS_INVALID");
            sum = Math.addExact(sum, item.goodsMinor());
        }
        require(sum == s.goodsMinor(), "POINTS_ADMISSION_ITEMS_INVALID");
    }

    private static boolean nonblank(String v) {
        return v != null && !v.isBlank();
    }

    private static void require(boolean value, String error) {
        if (!value) throw new IllegalArgumentException(error);
    }
}
