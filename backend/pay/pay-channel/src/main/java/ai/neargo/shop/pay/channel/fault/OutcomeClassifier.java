package ai.neargo.shop.pay.channel.fault;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把「发送出了什么问题」归入四态（渠道接入统一封装设计 §8.2）。<b>拿不准的一律 {@code Unknown}</b>：
 * 当失败的话，调用方会换个时间再发 —— 对退款、刷卡，那就是重复扣钱。
 *
 * <table>
 *   <tr><td>请求没发出去（连接拒绝、DNS）</td><td>Failed(retryable)</td></tr>
 *   <tr><td>连上后超时 / 断连、HTTP 5xx</td><td>Unknown（渠道可能已处理）</td></tr>
 *   <tr><td>4xx + 已登记业务码</td><td>Failed(按映射)</td></tr>
 *   <tr><td>4xx + 未登记码、验签失败、无法解析</td><td>Unknown</td></tr>
 * </table>
 */
public final class OutcomeClassifier {

    private final Map<String, ChannelErrorMapper> mappers;

    public OutcomeClassifier(List<? extends ChannelErrorMapper> mappers) {
        this.mappers = mappers.stream().collect(Collectors.toMap(ChannelErrorMapper::payChannel, Function.identity()));
    }

    public ChannelResult classify(String payChannel, ChannelOperation op, Throwable error) {
        if (error instanceof ChannelTransportException t) {
            return switch (t.kind()) {
                case CONNECT_FAILED -> new ChannelResult.Failed(PayErrorCode.CHANNEL_CONNECT_FAILED, null,
                        t.getMessage(), true);
                case TIMEOUT -> new ChannelResult.Unknown("TIMEOUT: " + t.getMessage());
                case SERVER_ERROR -> new ChannelResult.Unknown("HTTP_" + t.httpStatus() + ": " + t.getMessage());
                case BAD_SIGNATURE -> new ChannelResult.Unknown("BAD_SIGNATURE: " + t.getMessage());
                case UNPARSEABLE -> new ChannelResult.Unknown("UNPARSEABLE: " + t.getMessage());
                case CLIENT_ERROR -> mapped(payChannel, op, t)
                        .<ChannelResult>map(m -> new ChannelResult.Failed(m.code(), t.channelCode(), t.getMessage(), m.retryable()))
                        .orElseGet(() -> new ChannelResult.Unknown("UNMAPPED_CODE: " + t.channelCode()));
            };
        }
        // 处理器里任何意料之外的异常：不知道请求有没有发出去，按不明处理
        return new ChannelResult.Unknown("UNEXPECTED: " + error.getClass().getSimpleName());
    }

    private Optional<ChannelErrorMapper.MappedError> mapped(String payChannel, ChannelOperation op,
                                                            ChannelTransportException t) {
        ChannelErrorMapper m = mappers.get(payChannel);
        return m == null ? Optional.empty() : m.map(op, t.httpStatus(), t.channelCode());
    }
}
