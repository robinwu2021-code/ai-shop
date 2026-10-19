package ai.neargo.shop.trade.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;

/** Authorization may be located by the server from this exact preview; no arbitrary recipient can be supplied. */
public record ServiceUseSubmit(String serviceOrderNo, String previewRef, String authorizationRef,
                                long expectedAssetRevision) {
    public ServiceUseSubmit {
        ContractChecks.id(serviceOrderNo); ContractChecks.id(previewRef); ContractChecks.optionalId(authorizationRef);
        ContractChecks.require(expectedAssetRevision >= 0);
    }
}
