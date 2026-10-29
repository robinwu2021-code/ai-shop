package ai.neargo.shop.retail.commerce.service.impl;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionContracts.Prepared;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionContracts.Publish;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.retail.commerce.port.RetailSourceValidationProviderImpl;
import ai.neargo.shop.retail.commerce.service.RetailSelectionService;
import ai.neargo.shop.spi.product.RetailProductSourcePort;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Publish selection identities only after original product and exact channel authorization have
 * been checked.
 */
public final class RetailSelectionServiceImpl implements RetailSelectionService {
    private final RetailSelectionMapper selections;
    private final RetailProductSourcePort products;
    private final ChannelCatalogAuthorityService authorities;
    private final Clock clock;

    public RetailSelectionServiceImpl(
            RetailSelectionMapper selections,
            RetailProductSourcePort products,
            ChannelCatalogAuthorityService authorities,
            Clock clock) {
        this.selections = selections;
        this.products = products;
        this.authorities = authorities;
        this.clock = clock;
    }

    @Override
    public RetailSelection publish(Publish c) {
        var prepared = prepareWithEvidence(c);
        assertFresh(prepared);
        selections.publish(prepared.selection(), c.expectedRevision());
        return prepared.selection();
    }

    @Override
    public RetailSelection prepare(Publish c) {
        return prepareWithEvidence(c).selection();
    }

    @Override
    public void assertFresh(Prepared prepared) {
        if (prepared == null || !prepared.validUntil().isAfter(clock.instant()))
            throw new IllegalStateException("Expired retail publication evidence");
    }

    @Override
    public Prepared prepareWithEvidence(Publish c) {
        RetailSourceValidationProviderImpl.outsideTransaction();
        if (c == null || c.picks() == null || c.picks().isEmpty() || c.picks().size() > 100)
            throw new IllegalArgumentException("Invalid selection request");
        RetailSelection.key(c.entityNo());
        RetailSelection.key(c.storeNo());
        RetailSelection.key(c.selectionNo());
        if (c.expectedRevision() != null) RetailSelection.key(c.expectedRevision());
        var aq =
                new ChannelCatalogAuthorityContracts.Query(
                        c.entityNo(), c.storeNo(), c.connectionNo(), c.connectionRevisionNo());
        var deadlines = new ArrayList<Instant>();
        deadlines.add(clock.instant().plusSeconds(30));
        var authority = authorities.capture(aq);
        ChannelCatalogAuthorityPolicy.validate(aq, authority, clock.instant());
        if (!"RETAIL".equals(authority.connection().sourceOwner()))
            throw new IllegalStateException("Wrong original owner");
        deadlines.add(authority.validUntil());
        var entries = new ArrayList<RetailSelection.Entry>();
        for (var pick : c.picks()) {
            if (pick == null || pick.skuNos() == null || pick.skuNos().isEmpty())
                throw new IllegalArgumentException("Missing SKU selection");
            RetailSelection.key(pick.goodsNo());
            pick.skuNos().forEach(RetailSelection::key);
            var q =
                    new RetailProductSourcePort.Query(
                            c.entityNo(), c.storeNo(), pick.goodsNo(), null, false);
            var facts = products.read(q);
            RetailSourceValidationProviderImpl.validateProduct(q, facts, clock.instant());
            deadlines.add(facts.validUntil());
            if (!facts.publishedSkuNos().containsAll(pick.skuNos()))
                throw new IllegalStateException("SKU absent from original publication");
            entries.add(
                    new RetailSelection.Entry(
                            pick.goodsNo(),
                            facts.revisionNo(),
                            facts.contentHash(),
                            pick.skuNos()));
        }
        var selection =
                new RetailSelection(
                        c.entityNo(),
                        c.storeNo(),
                        c.selectionNo(),
                        UUID.randomUUID().toString().replace("-", ""),
                        c.connectionNo(),
                        c.connectionRevisionNo(),
                        entries);
        selection.validate();
        for (var e : entries) {
            var q =
                    new RetailProductSourcePort.Query(
                            c.entityNo(), c.storeNo(), e.goodsNo(), e.goodsRevision(), false);
            var facts = products.read(q);
            RetailSourceValidationProviderImpl.validateProduct(q, facts, clock.instant());
            deadlines.add(facts.validUntil());
            if (!e.goodsContentHash().equals(facts.contentHash())
                    || !facts.publishedSkuNos().containsAll(e.skuNos()))
                throw new IllegalStateException("Original publication changed");
        }
        var after = authorities.capture(aq);
        ChannelCatalogAuthorityPolicy.validate(aq, after, clock.instant());
        if (!authority.connection().equals(after.connection())
                || !authority.grant().equals(after.grant())
                || !authority.account().equals(after.account())
                || !authority.profile().equals(after.profile())
                || !authority.provider().equals(after.provider()))
            throw new IllegalStateException("Channel authority changed");
        deadlines.add(after.validUntil());
        var prepared =
                new Prepared(selection, deadlines.stream().min(Instant::compareTo).orElseThrow());
        assertFresh(prepared);
        return prepared;
    }
}
