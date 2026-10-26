package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

/** 显式历史修订解析；返回原映射与新核验依据，不批准价格、库存、资金或接单。 */
public interface ChannelResourceMappingPort {
    Resolution resolveHistorical(Reference reference);

    record Reference(
            String entityNo,
            String storeNo,
            String connectionNo,
            String mappingNo,
            String revisionNo) {}

    record Resolution(
            String mappingNo,
            String revisionNo,
            ObjectType objectType,
            Identity identity,
            Owner sourceOwner,
            String sourceRef,
            String sourceRevision,
            String salesOfferRef,
            String sourceHash,
            String historicalExternalEvidenceRef,
            String freshSourceReceiptNo) {}
}
