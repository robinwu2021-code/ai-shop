package ai.neargo.shop.pay.channel.callback;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.PayloadMasker;
import ai.neargo.shop.pay.channel.entity.PayCallbackLog;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayCallbackLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code pay_callback_log}（四向记录 ③ 渠道 → 支付）。<b>先落、再处理</b>，独立事务：处理抛异常、调用方回滚，
 * 这一行都要留下 —— 它停在 RECEIVED 本身就是线索。
 *
 * <p>验签前只存原文指纹与前缀（这个端点公网可写，原样落库就是个写入口）；验签通过后按字段存、敏感键遮掉。
 * 落库失败只记日志，不挡回调处理。
 */
public final class CallbackLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(CallbackLogRecorder.class);
    public static final String RECEIVED = "RECEIVED";
    public static final String APPLIED = "APPLIED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String REJECTED = "REJECTED";

    private final PayCallbackLogMapper mapper;
    private final TransactionTemplate tx;

    public CallbackLogRecorder(PayCallbackLogMapper mapper, PlatformTransactionManager payTxManager) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(payTxManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return 记录 id；落不进去返回 null（回调照常处理） */
    public Long received(String payChannel, String notifyType, String pathRef, String routeStyle, String httpMethod,
                         String remoteIp, Map<String, String> headers, String rawBody, String sha256) {
        PayCallbackLog row = new PayCallbackLog();
        row.setPayChannel(payChannel);
        row.setNotifyType(notifyType);
        row.setPathRef(pathRef);
        row.setRouteStyle(routeStyle);
        row.setHttpMethod(httpMethod);
        row.setRemoteIp(remoteIp);
        row.setHeadersMasked(headers == null ? null : PayloadMasker.mask(headers));
        row.setBodyMasked(PayloadMasker.unverified(rawBody));
        row.setRawSha256(sha256);
        row.setVerifyResult("PENDING");
        row.setOutcome(RECEIVED);
        row.setReceivedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> mapper.insert(row)));
            return row.getId();
        } catch (RuntimeException e) {
            log.error("[pay-callback] 回调记录落不进去 {} {}", payChannel, notifyType, e);
            return null;
        }
    }

    /** 同一原文是否已经入过账：返回那一行的我方单号（调用方再确认账还在） */
    public List<String> appliedOutNos(String sha256) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(Wrappers.<PayCallbackLog>lambdaQuery()
                        .eq(PayCallbackLog::getRawSha256, sha256).eq(PayCallbackLog::getOutcome, APPLIED)))
                .stream().map(PayCallbackLog::getOutTradeNo).filter(n -> n != null).distinct().toList();
    }

    /** 回写：验签结果、核对结果、最终去向。只改传进来的字段 */
    public void update(Long id, Consumer<PayCallbackLog> patch) {
        if (id == null) {
            return;
        }
        PayCallbackLog row = new PayCallbackLog();
        row.setId(id);
        patch.accept(row);
        try {
            tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> mapper.updateById(row)));
        } catch (RuntimeException e) {
            log.error("[pay-callback] 回调记录回写失败 id={}", id, e);
        }
    }

    /** 验签通过后的报文：按字段存，敏感键遮掉 */
    public static String masked(Map<String, ?> payload) {
        return payload == null ? null : PayloadMasker.mask(payload);
    }

    public static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
