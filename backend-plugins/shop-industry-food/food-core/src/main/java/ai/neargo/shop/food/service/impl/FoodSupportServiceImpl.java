package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.config.FoodProperties;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.dto.FoodSupportContracts.ReadinessVO;
import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.service.FoodSupportService;

import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the existing schema receipt and original service facts, without recovery side effects. */
public final class FoodSupportServiceImpl implements FoodSupportService {
    private final FoodProperties properties;
    private final ObjectProvider<FoodSchemaReady> schemas;
    private final Map<String, ObjectProvider<?>> providers;
    private final BusinessOperationsService operations;

    public FoodSupportServiceImpl(
            FoodProperties properties,
            ObjectProvider<FoodSchemaReady> schemas,
            Map<String, ObjectProvider<?>> providers,
            BusinessOperationsService operations) {
        this.properties = properties;
        this.schemas = schemas;
        this.providers = Map.copyOf(providers);
        this.operations = operations;
    }

    @Override
    public ReadinessVO readiness() {
        FoodSchemaReady schema = schemas.getIfAvailable();
        List<String> missing = new ArrayList<>();
        if (schema == null) {
            missing.add("primary-schema");
        } else {
            missing.addAll(schema.missingCapabilities());
            if (!schema.validated()) {
                missing.add("primary-schema-not-validated");
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        providers.forEach(
                (name, provider) -> {
                    int count = (int) provider.orderedStream().count();
                    counts.put(name, count);
                    if (count != 1) {
                        missing.add(name);
                    }
                });
        missing.sort(String::compareTo);
        return new ReadinessVO(
                properties.deployment().name(),
                schema != null && schema.ready() && missing.isEmpty(),
                schema != null && schema.validated(),
                schema == null ? null : schema.version(),
                missing,
                counts);
    }

    @Override
    public List<CaseVO> cases(String entityNo, String storeNo, String status) {
        return operations.cases(entityNo, storeNo, status);
    }

    @Override
    public Evidence evidence(String entityNo, String storeNo, Source source) {
        return operations.evidence(entityNo, storeNo, source);
    }
}
