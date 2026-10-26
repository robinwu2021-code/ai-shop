package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import java.time.Instant;

/** 历史解析必须显式原修订；当前指针变化不追改原订单。 */
public final class ChannelResourceMappingPortImpl implements ChannelResourceMappingPort {
    private final MappingPersistence data;

    public ChannelResourceMappingPortImpl(MappingPersistence data) {
        this.data = data;
    }

    public Resolution resolveHistorical(Reference ref) {
        require(ref != null);
        var before =
                data.db.commands.query(
                        () -> data.scoped(ref.entityNo(), ref.storeNo(), ref.mappingNo(), false));
        require(before.mapping().getConnectionNo().equals(ref.connectionNo()));
        var revision =
                data.db.commands.query(
                        () -> data.revision(ref.entityNo(), ref.mappingNo(), ref.revisionNo()));
        require(
                "VERIFIED".equals(revision.getValidationStatus())
                        && revision.getExternalEvidenceRef() != null
                        && revision.getSourceContentHash() != null
                        && ChannelIdentity.hash(revision.getMappingSnapshot())
                                .equals(revision.getContentHash()));
        var fresh = data.validate(before, revision);
        require(fresh.contentHash().equals(revision.getSourceContentHash()));
        return data.db.commands.query(
                () -> {
                    var current =
                            data.scoped(ref.entityNo(), ref.storeNo(), ref.mappingNo(), false);
                    data.db.unchanged(before.base(), current.base());
                    require(fresh.validUntil().isAfter(Instant.now()));
                    return new Resolution(
                            ref.mappingNo(),
                            ref.revisionNo(),
                            ObjectType.valueOf(before.mapping().getObjectType()),
                            data.identity(before.mapping()),
                            Owner.valueOf(revision.getSourceOwner()),
                            revision.getSourceRef(),
                            revision.getSourceRevisionRef(),
                            revision.getSalesOfferRef(),
                            revision.getSourceContentHash(),
                            revision.getExternalEvidenceRef(),
                            fresh.ownerReceiptNo());
                });
    }
}
