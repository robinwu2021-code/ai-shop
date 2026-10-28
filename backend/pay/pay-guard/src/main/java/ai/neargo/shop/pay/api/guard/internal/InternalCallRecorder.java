package ai.neargo.shop.pay.api.guard.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.access.service.ApiRequestRecorder;
import ai.neargo.shop.pay.api.guard.core.RequestLogWriter;
import ai.neargo.shop.spi.pay.CallSource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 内嵌形态的内部调用留痕（ADR-045 G9；TDD-支付服务-接入守卫模块pay-guard §2.3 A-3）：进程内调支付不经 HTTP，
 * 没有守卫管线 —— 由 {@code RecordingPaymentPort} 在每个方法外包一层，开始计时 → 调用 → 成败都写一行 {@code INTERNAL_PORT}。
 *
 * <p>与 {@code INTERNAL_HTTP} 那一行的列一致（来源经 {@link RequestLogWriter#applySource}）；没有 HTTP 状态与对端 IP。
 * 记录本身失败只打日志，不影响调用；调用的异常原样抛回。
 */
@Component
public class InternalCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(InternalCallRecorder.class);

    /**
     * 一次调用里要落库的业务信息
     *
     * @param appNo      内部调用方码：MALL / FOOD
     * @param operatorNo 命令里明确的操作人（退款的商家操作员）；为空取来源的
     */
    public record Call(String api, String payAction, String appNo, String bizType, String bizNo, Long storeId,
                       Long amountMinor, String currency, String operatorNo, CallSource source) {
    }

    private final ApiRequestRecorder recorder;

    public InternalCallRecorder(ApiRequestRecorder recorder) {
        this.recorder = recorder;
    }

    /** @param payOrderIdOf 从结果取支付单 id（结果为空或没有就给空） */
    public <T> T record(Call call, Supplier<T> body, Function<T, Long> payOrderIdOf) {
        long started = System.currentTimeMillis();
        try {
            T result = body.get();
            write(call, started, "ACCEPTED", "OK", result == null ? null : payOrderIdOf.apply(result));
            return result;
        } catch (BizException rejected) {
            // 业务拒绝（可退余额不足、越权……）：与 HTTP 入口的 4xx 同一个结局
            write(call, started, "REJECTED_PARAM", rejected.errorCode().name(), null);
            throw rejected;
        } catch (RuntimeException failed) {
            write(call, started, "ERROR", failed.getClass().getSimpleName(), null);
            throw failed;
        }
    }

    private void write(Call c, long started, String outcome, String resultCode, Long payOrderId) {
        try {
            PayApiRequestLog r = new PayApiRequestLog();
            r.setEntry("INTERNAL_PORT");
            r.setApi(c.api());
            r.setPayAction(c.payAction());
            r.setSourceType("INTERNAL");
            r.setAppNo(c.appNo());
            r.setClientType("SERVER");
            r.setOperatorNo(c.operatorNo());
            r.setBizType(c.bizType());
            r.setBizNo(c.bizNo());
            r.setStoreId(c.storeId());
            r.setRequestAmountMinor(c.amountMinor());
            r.setRequestCurrency(c.currency());
            r.setPayOrderId(payOrderId);
            r.setResultCode(resultCode);
            r.setOutcome(outcome);
            r.setElapsedMs((int) (System.currentTimeMillis() - started));
            r.setReceivedAt(LocalDateTime.now(ZoneOffset.UTC));
            RequestLogWriter.applySource(r, c.source().withModuleIfAbsent(c.appNo()));
            recorder.record(r);
        } catch (RuntimeException e) {
            log.warn("[internal-call] 记录失败（不影响调用）：{} {}", c.api(), e.toString());
        }
    }
}
