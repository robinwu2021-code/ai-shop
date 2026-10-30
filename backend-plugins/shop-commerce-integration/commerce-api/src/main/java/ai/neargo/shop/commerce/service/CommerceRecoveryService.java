package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.CatalogContracts.RecoveryVO;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

/** 目录能力沿原主体/门店范围；网络事实与本域事务分开，不默认通过来源或外部结果。 */
public interface CommerceRecoveryService {
    RecoveryVO recoverDue(int limit);

    void onPublished(
            String entity,
            String store,
            Owner owner,
            String sourceRef,
            String newRevision,
            String eventNo);

    /**
     * Durable source notices process one explicitly scoped target without rescanning the same
     * store.
     */
    default void onPublishedScope(
            String entityNo,
            String storeNo,
            String scopeNo,
            Owner owner,
            String sourceRef,
            String revision,
            String eventNo) {
        throw new UnsupportedOperationException("Scoped source publication unavailable");
    }

    /**
     * Explicit native withdrawal retains its configured subscription and every immutable mapping
     * revision.
     */
    default void onWithdrawnScope(
            String entityNo,
            String storeNo,
            String scopeNo,
            Owner owner,
            String sourceRef,
            String previousRevision,
            String eventNo) {
        throw new UnsupportedOperationException("Scoped original withdrawal unavailable");
    }
}
