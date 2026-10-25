package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.mapper.InventoryExactSupportMapper;
import ai.neargo.shop.inventory.mapper.InventoryExactSupportMapper.PolicyRow;
import ai.neargo.shop.inventory.service.InventoryValuationService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

@Service
@ConditionalOnInventory
@Transactional(transactionManager = "invTransactionManager")
public class InventoryValuationServiceImpl implements InventoryValuationService {
    private final InventoryExactSupportMapper mapper;
    private final JsonMapper json;

    public InventoryValuationServiceImpl(InventoryExactSupportMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Policy get(String owner, String item) {
        return policy(row(owner, item, false));
    }

    @Override
    public Policy configure(String owner, String item, String actor, Policy req, String key) {
        if (req == null
                || req.version() < 0
                || req.version() == Long.MAX_VALUE
                || req.currency() == null
                || !req.currency().matches("[A-Z]{3}")
                || !Set.of("MANUAL", "LATEST").contains(req.costMethod())
                || req.unitCostMinor() != null && req.unitCostMinor() < 0
                || "MANUAL".equals(req.costMethod()) && req.unitCostMinor() == null
                || actor == null
                || actor.isBlank()
                || key == null
                || key.isBlank()
                || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        String operation = PricingSnapshotHash.of(List.of(owner, key)),
                hash = PricingSnapshotHash.of(List.of(item, actor, req));
        var before = policy(row(owner, item, true));
        var prior = mapper.history(owner, operation);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().requestHash()))
                throw BizException.of(ErrorCode.CONFLICT);
            return json.readValue(prior.getFirst().afterSnapshot(), Policy.class);
        }
        if (before.version() != req.version()) throw BizException.of(ErrorCode.CONFLICT);
        if (before.batchTracked() != req.batchTracked() && mapper.ledgerCount(owner, item) > 0)
            throw BizException.of(ErrorCode.CONFLICT);
        var after =
                new Policy(
                        req.version() + 1,
                        req.currency(),
                        req.costMethod(),
                        req.unitCostMinor(),
                        req.batchTracked());
        if (mapper.updatePolicy(
                        owner,
                        item,
                        actor,
                        req.version(),
                        after.currency(),
                        after.costMethod(),
                        after.unitCostMinor(),
                        after.batchTracked() ? 1 : 0)
                != 1) throw BizException.of(ErrorCode.CONFLICT);
        if (mapper.insertHistory(
                        owner,
                        item,
                        operation,
                        hash,
                        json.writeValueAsString(before),
                        json.writeValueAsString(after),
                        actor)
                != 1) throw BizException.of(ErrorCode.CONFLICT);
        return after;
    }

    private PolicyRow row(String owner, String item, boolean lock) {
        var rows = mapper.policy(owner, item, lock);
        if (rows.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private Policy policy(PolicyRow row) {
        return new Policy(
                row.version(),
                row.currency(),
                row.costMethod(),
                row.unitCostMinor(),
                row.batchTracked() == 1);
    }
}
