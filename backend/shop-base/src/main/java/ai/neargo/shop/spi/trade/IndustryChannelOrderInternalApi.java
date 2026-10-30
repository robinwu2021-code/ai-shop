package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Cancellation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Internal original-owner transport carries references only, never prices, buyer accounts or a paid
 * flag.
 */
public interface IndustryChannelOrderInternalApi {
    String ROOT = "/internal/trade/channel-orders";

    record Request(Reference reference) {
        public Request {
            IndustryChannelOrderFacts.reference(reference);
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown original channel command field");
        }
    }

    @PostExchange(ROOT + "/prepare")
    Preparation prepare(@RequestBody Request request);

    @PostExchange(ROOT + "/get")
    Preparation get(@RequestBody Request request);

    @PostExchange(ROOT + "/confirm")
    Acceptance confirm(@RequestBody Request request);

    @PostExchange(ROOT + "/cancel")
    Cancellation cancel(@RequestBody Request request);
}
