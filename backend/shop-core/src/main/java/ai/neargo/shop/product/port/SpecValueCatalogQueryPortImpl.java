package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdSpecDim;
import ai.neargo.shop.product.entity.PrdSpecValue;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecValueMapper;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads original value/dimension facts with explicit ownership on both sides of the reference. */
@Component
public class SpecValueCatalogQueryPortImpl implements SpecValueCatalogQueryPort {
    private final SpecDimMapper dims;
    private final SpecValueMapper values;

    public SpecValueCatalogQueryPortImpl(SpecDimMapper dims, SpecValueMapper values) {
        this.dims = dims;
        this.values = values;
    }

    @Override
    @Transactional(transactionManager="transactionManager", readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<SpecValueFact> getValues(String entity, List<String> numbers) {
        SpecValueCatalogQueryPort.validateLookup(entity, numbers);
        return DataScopeContext.executeWithoutScope(() -> {
            var rows = values.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                    .select(PrdSpecValue::getValueNo, PrdSpecValue::getDimNo, PrdSpecValue::getLabel,
                            PrdSpecValue::getScope, PrdSpecValue::getEntityNo, PrdSpecValue::getVersion,
                            PrdSpecValue::getStatus)
                    .in(PrdSpecValue::getValueNo, numbers).eq(PrdSpecValue::getStatus, PrdSpecDim.ACTIVE)
                    .and(q -> q.isNull(PrdSpecValue::getMergedInto).or().eq(PrdSpecValue::getMergedInto, ""))
                    .and(q -> q.eq(PrdSpecValue::getScope, PrdSpecDim.PLATFORM).or(n ->
                            n.eq(PrdSpecValue::getScope, PrdSpecDim.MERCHANT).eq(PrdSpecValue::getEntityNo, entity)))
                    .orderByAsc(PrdSpecValue::getValueNo)).stream()
                    .filter(row -> numbers.contains(row.getValueNo()) && PrdSpecDim.ACTIVE.equals(row.getStatus())
                            && visible(entity, row.getScope(), row.getEntityNo())).toList();
            if (rows.isEmpty()) return List.of();
            var dimNos = rows.stream().map(PrdSpecValue::getDimNo).distinct().toList();
            var byDim = dims.selectList(Wrappers.<PrdSpecDim>lambdaQuery()
                    .select(PrdSpecDim::getDimNo, PrdSpecDim::getName, PrdSpecDim::getValueType,
                            PrdSpecDim::getUsageType, PrdSpecDim::getScope, PrdSpecDim::getEntityNo,
                            PrdSpecDim::getVersion, PrdSpecDim::getStatus)
                    .in(PrdSpecDim::getDimNo, dimNos).eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE)
                    .in(PrdSpecDim::getValueType, PrdSpecDim.ENUM, PrdSpecDim.QUANT)
                    .in(PrdSpecDim::getUsageType, PrdSpecDim.SALE, PrdSpecDim.PROP)
                    .and(q -> q.eq(PrdSpecDim::getScope, PrdSpecDim.PLATFORM).or(n ->
                            n.eq(PrdSpecDim::getScope, PrdSpecDim.MERCHANT).eq(PrdSpecDim::getEntityNo, entity))))
                    .stream().filter(d -> PrdSpecDim.ACTIVE.equals(d.getStatus())
                            && visible(entity, d.getScope(), d.getEntityNo())
                            && (PrdSpecDim.ENUM.equals(d.getValueType()) || PrdSpecDim.QUANT.equals(d.getValueType()))
                            && (PrdSpecDim.SALE.equals(d.getUsageType()) || PrdSpecDim.PROP.equals(d.getUsageType())))
                    .collect(Collectors.toMap(PrdSpecDim::getDimNo, d -> d));
            return rows.stream().filter(v -> byDim.containsKey(v.getDimNo())).map(v -> {
                var dim = byDim.get(v.getDimNo());
                return new SpecValueFact(v.getValueNo(), dim.getDimNo(), v.getLabel(), dim.getName(),
                        dim.getValueType(), dim.getUsageType(), v.getScope(), v.getEntityNo(), dim.getScope(),
                        dim.getEntityNo(), v.getVersion(), dim.getVersion());
            }).toList();
        });
    }
    // Original dictionary columns may use case-insensitive database collations; Java identities stay exact.
    private static boolean visible(String entity, String scope, String owner) {
        return PrdSpecDim.PLATFORM.equals(scope) || PrdSpecDim.MERCHANT.equals(scope) && entity.equals(owner);
    }

}
