package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.domain.support.CanonicalHash;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.mapper.InventoryExactSupportMapper;
import ai.neargo.inventory.domain.mapper.InventoryExactSupportMapper.PolicyRow;
import ai.neargo.inventory.domain.service.InventoryValuationService;

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
                || key.length() > 128) throw InventoryException.of(InventoryError.BAD_REQUEST);
        String operation = CanonicalHash.of(List.of(owner, key)),
                hash = CanonicalHash.of(List.of(item, actor, req));
        var before = policy(row(owner, item, true));
        var prior = mapper.history(owner, operation);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().requestHash()))
                throw InventoryException.of(InventoryError.CONFLICT);
            return json.readValue(prior.getFirst().afterSnapshot(), Policy.class);
        }
        if (before.version() != req.version()) throw InventoryException.of(InventoryError.CONFLICT);
        if (before.batchTracked() != req.batchTracked() && mapper.ledgerCount(owner, item) > 0)
            throw InventoryException.of(InventoryError.CONFLICT);
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
                != 1) throw InventoryException.of(InventoryError.CONFLICT);
        if (mapper.insertHistory(
                        owner,
                        item,
                        operation,
                        hash,
                        json.writeValueAsString(before),
                        json.writeValueAsString(after),
                        actor)
                != 1) throw InventoryException.of(InventoryError.CONFLICT);
        return after;
    }

    private PolicyRow row(String owner, String item, boolean lock) {
        var rows = mapper.policy(owner, item, lock);
        if (rows.size() != 1) throw InventoryException.of(InventoryError.NOT_FOUND);
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
