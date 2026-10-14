package ai.neargo.shop.channel.express.port;

import ai.neargo.shop.spi.fulfillment.ExpressPickupPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 快递代下单的桩（默认装配）。
 *
 * <p><b>不假装下单成功</b>：{@link #enabled()} 返回 false，调用方据此报「平台快递代下单还没开通」。
 * 与发货上报的桩不同 —— 那边桩恒成功是为了让台账链路能跑通；这边一旦装成功，
 * 商家会等一个永远不会来的快递员。
 */
@Component
@ConditionalOnProperty(name = "shop.express.kuaidi100.stub", havingValue = "true", matchIfMissing = true)
public class StubExpressPickupGateway implements ExpressPickupPort {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public List<String> carriers() {
        return List.of();
    }

    @Override
    public Optional<Quote> quote(String carrier, String senderAddress, String receiverAddress, int weightG,
                                 boolean sandbox) {
        return Optional.empty();
    }

    @Override
    public Booked create(CreateCmd cmd) {
        return Booked.fail("stub");
    }

    @Override
    public Booked cancel(String taskId, String providerOrderId, String reason, boolean sandbox) {
        return Booked.fail("stub");
    }

    @Override
    public Optional<Callback> parseCallback(String taskId, String sign, String param) {
        return Optional.empty();
    }
}
