package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.ShipTrackPort;
import ai.neargo.shop.trade.track.ShipTrackToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link ShipTrackPort} 的薄转发：把看件令牌的签发/验签暴露给跨域调用方（message 域的发货通知）。
 *
 * <p>实现放在本域的 {@code .port} 包里（ArchitectureTest 的 implsMustLiveInDedicatedPackage），
 * 真正的签名逻辑在 {@link ShipTrackToken}（trade.track）—— 那是本域内部实现，可以随时改，
 * 这层只保证跨域看到的能力面稳定。
 */
@Component
public class ShipTrackPortImpl implements ShipTrackPort {

    private final ShipTrackToken token;

    public ShipTrackPortImpl(ShipTrackToken token) {
        this.token = token;
    }

    @Override
    public String sign(String subOrderNo) {
        return token.sign(subOrderNo);
    }

    @Override
    public Optional<String> verify(String t) {
        return token.verify(t);
    }
}
