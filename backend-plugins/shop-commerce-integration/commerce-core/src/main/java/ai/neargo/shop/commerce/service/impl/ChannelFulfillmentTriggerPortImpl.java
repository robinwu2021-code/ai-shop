package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.FulfillmentContracts;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderLinkMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.service.ChannelFulfillmentService;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Only an actual confirmed import may publish original committed fulfillment; original source calls
 * run after the lookup transaction closes.
 */
public final class ChannelFulfillmentTriggerPortImpl implements ChannelFulfillmentTriggerPort {
    private final OrderLinkMapper orders;
    private final CommerceCommands commands;
    private final ChannelFulfillmentService fulfillment;

    public ChannelFulfillmentTriggerPortImpl(
            OrderLinkMapper orders,
            CommerceCommands commands,
            ChannelFulfillmentService fulfillment) {
        this.orders = orders;
        this.commands = commands;
        this.fulfillment = fulfillment;
    }

    @Override
    public Result record(Request request) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Original fulfillment trigger requires an external transaction boundary");
        require(request != null && request.sourceOwner() != null);
        text(request.entityNo(), 32);
        text(request.storeNo(), 32);
        text(request.importNo(), 32);
        text(request.sourceOrderRef(), 128);
        text(request.coreOrderRef(), 128);
        text(request.sourceFulfillmentRef(), 128);
        text(request.sourceRevision(), 128);
        text(request.originalEventNo(), 64);
        var matches =
                commands.query(
                        () ->
                                orders.selectList(
                                        Wrappers.<CimOrderLink>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", request.entityNo())
                                                .eq("store_no", request.storeNo())
                                                .eq("source_owner", request.sourceOwner().name())
                                                .eq("import_ref", request.importNo())
                                                .last("LIMIT 2")));
        require(matches.size() <= 1);
        if (matches.isEmpty()) return new Result(request, Status.WAITING_IMPORT, null, null);
        var order = matches.getFirst();
        if (order.getSourceOrderRef() != null)
            require(order.getSourceOrderRef().equals(request.sourceOrderRef()));
        if (order.getCoreOrderRef() != null)
            require(order.getCoreOrderRef().equals(request.coreOrderRef()));
        if (!"CONFIRMED".equals(order.getImportStatus())
                || !"CONFIRMED".equals(order.getAcceptanceStatus())
                || order.getSourceOrderRef() == null
                || order.getCoreOrderRef() == null)
            return new Result(request, Status.WAITING_IMPORT, null, null);
        String key = "FULFILLMENT_EVENT:" + ChannelIdentity.hash(PricingSnapshotHash.of(request));
        var operation =
                fulfillment.publishFulfillment(
                        request.entityNo(),
                        request.storeNo(),
                        "SYSTEM",
                        new FulfillmentContracts.Publish(
                                order.getChannelOrderNo(),
                                request.sourceOwner(),
                                request.sourceFulfillmentRef(),
                                request.sourceRevision()),
                        key);
        require(operation != null);
        text(operation.operationNo(), 32);
        text(operation.status(), 32);
        return new Result(request, Status.RECORDED, operation.operationNo(), operation.status());
    }
}
