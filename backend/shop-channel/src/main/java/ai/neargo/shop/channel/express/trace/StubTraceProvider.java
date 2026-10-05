package ai.neargo.shop.channel.express.trace;

import ai.neargo.shop.spi.logistics.TraceProvider;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 兜底 / 开发用的轨迹 provider：不打任何外部接口，返回一条固定的「运输中」。
 *
 * <p><b>永远注册、认全部承运商</b>：没接真 provider 的承运商落到它身上，端上拿到的是
 * 一条明确的占位轨迹而不是报错或白屏。真 provider（圆通）接上后，路由把对应承运商指过去，
 * 它自然就不再被用到那一类。
 *
 * <p>它的存在也让 Y1（架构）不依赖任何凭据就能跑通、能测。
 */
@Component
public class StubTraceProvider implements TraceProvider {

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public boolean covers(String carrier) {
        return true;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<TraceResult> trace(String carrier, String waybillNo) {
        if (waybillNo == null || waybillNo.isBlank()) {
            return Optional.empty();
        }
        var node = new TraceResult.TraceNode(
                System.currentTimeMillis(), TraceStatus.IN_TRANSIT, "（示例轨迹）运输中", "");
        return Optional.of(new TraceResult(waybillNo, carrier, TraceStatus.IN_TRANSIT,
                name(), List.of(node)));
    }
}
