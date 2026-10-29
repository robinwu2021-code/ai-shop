package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.commerce.service.ChannelStatisticsService;
import ai.neargo.shop.commerce.service.CommerceSupportService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * Exact entity/store predicates precede safe projection; platform support never sees an
 * operator-bound OAuth URL.
 */
public final class CommerceSupportServiceImpl implements CommerceSupportService {
    private final CatalogPersistence db;
    private final ChannelStatisticsService statistics;
    private final ChannelOperationService operations;

    public CommerceSupportServiceImpl(
            CatalogPersistence db,
            ChannelStatisticsService statistics,
            ChannelOperationService operations) {
        this.db = db;
        this.statistics = statistics;
        this.operations = operations;
    }

    @Override
    public ChannelStatisticsContracts.Health supportHealth(String entity, String store) {
        return statistics.health(entity, store, null, null);
    }

    @Override
    public OperationVO supportOperation(String entity, String store, String number) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(number, 32);
        if (store != null) CommerceValidation.text(store, 32);
        return db.commands.query(
                () -> {
                    var q =
                            Wrappers.<CimOperation>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("operation_no", number);
                    if (store == null) q.isNull("store_no");
                    else q.eq("store_no", store);
                    if (db.operations.selectOne(q) == null)
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    var result = operations.getOperation(entity, number);
                    return new OperationVO(
                            result.operationNo(),
                            result.type(),
                            result.status(),
                            result.accountNo(),
                            null,
                            result.expiresAt(),
                            result.errorCode(),
                            result.version());
                });
    }
}
