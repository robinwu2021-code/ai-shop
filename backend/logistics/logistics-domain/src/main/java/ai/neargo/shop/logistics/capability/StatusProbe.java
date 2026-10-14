package ai.neargo.shop.logistics.capability;

import ai.neargo.shop.spi.logistics.TraceResult;

import java.util.Optional;

/**
 * 主动探测：问一次这张运单走到哪了。只在读时校正与补偿作业里用；默认链只有微信（快递100 额度不够）。
 *
 * <p>微信 {@code query_trace} 只给状态不给节点 —— 返回的 {@link TraceResult#nodes()} 可以是空的。
 */
public interface StatusProbe extends ChannelCapability {

    Optional<TraceResult> probe(ProbeCmd cmd);

    /**
     * @param waybillToken 微信 waybill_token（只有微信探测要用，其余渠道忽略）
     */
    record ProbeCmd(String carrier, String channelCarrierCode, String waybillNo,
                    String phone, String waybillToken) {
    }
}
