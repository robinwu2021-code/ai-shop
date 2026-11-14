package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.dto.TaxSourceNoticeContracts;
import ai.neargo.shop.commerce.service.TaxSourceNoticeService;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.OutboxRouteContribution;
import ai.neargo.shop.spi.trade.PricingTaxSourceChanged;

import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Native tax events are reread hints only; the source payload never becomes a new catalog tax
 * definition.
 */
public final class CommerceTaxSourceEventConsumer
        implements OutboxHandler, OutboxRouteContribution {
    private final TaxSourceNoticeService service;
    private final ObjectMapper json;

    public CommerceTaxSourceEventConsumer(TaxSourceNoticeService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @Override
    public String handlerId() {
        return "commerce-tax-source-events-v1";
    }

    @Override
    public boolean externalWorkflow() {
        return true;
    }

    @Override
    public boolean supports(String type) {
        return "PRICING_TAX_SOURCE_CHANGED".equals(type);
    }

    @Override
    public Set<String> consumedArchiveRoutes() {
        return Set.of("PLATFORM:PRICING_TAX_SOURCE_CHANGED");
    }

    @Override
    public DeliveryResult handle(OutboxEnvelope envelope) {
        if (!"PLATFORM".equals(envelope.sourceSystem())
                || envelope.schemaVersion() != 1
                || !supports(envelope.eventType()))
            throw new IllegalArgumentException("Invalid original tax event");
        var event = json.readValue(envelope.payload(), PricingTaxSourceChanged.class);
        if (!"PRICING_TAX_SETTING".equals(envelope.aggregateType())
                || !event.settingKey().equals(envelope.aggregateId()))
            throw new IllegalArgumentException("Tax source identity mismatch");
        service.receive(
                new TaxSourceNoticeContracts.Notice(
                        "PLATFORM",
                        envelope.sourceEventNo(),
                        event.tenantNo(),
                        event.entityNo(),
                        event.settingKey(),
                        event.version(),
                        event.contentHash(),
                        envelope.occurredAt()));
        return DeliveryResult.sent();
    }
}
