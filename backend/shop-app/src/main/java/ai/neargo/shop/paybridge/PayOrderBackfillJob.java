package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.job.api.TriggerType;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill.Assessment;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill.Status;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Manual B6/B7 inspection is read-only unless apply=true; ambiguous identities stay in the review report. */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class PayOrderBackfillJob implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(PayOrderBackfillJob.class);
    @Value("${shop.job.pay-order-backfill.batch:500}")
    private int batch = 500;
    private final PayOrderBackfill backfill;
    private final MerchantQueryPort merchants;
    private final JdbcTemplate jdbc;

    public PayOrderBackfillJob(PayOrderBackfill backfill, MerchantQueryPort merchants, JdbcTemplate jdbc) {
        this.backfill = backfill;
        this.merchants = merchants;
        this.jdbc = jdbc;
    }

    @Override
    public String name() { return "pay-order-backfill"; }

    @Bean
    public JobDeclaration payOrderBackfillDeclaration() {
        return new JobDeclaration(name(), "支付单回填（B6 / B7）",
                "默认只读预检；apply=true显式写入，limit限制批量、afterOrderNo续扫；冲突保留人工处理",
                "shop-app", "0 0 4 1 1 ?", false, 3600, 3600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        String mode = param(invocation, "apply", "false").trim();
        String after = param(invocation, "afterOrderNo", "").trim();
        int limit;
        try {
            if (!List.of("true", "false").contains(mode) || after.length() > 64) {
                return JobResult.failed("回填参数无效", "PAY_BACKFILL_INVALID_PARAMETERS");
            }
            limit = Integer.parseInt(param(invocation, "limit", String.valueOf(batch)));
            if (limit < 1 || limit > 1000) return JobResult.failed("批量必须为1–1000", "PAY_BACKFILL_INVALID_PARAMETERS");
        } catch (NumberFormatException invalid) {
            return JobResult.failed("批量必须为整数", "PAY_BACKFILL_INVALID_PARAMETERS");
        }
        boolean apply = "true".equals(mode);
        if (apply && (invocation == null || invocation.type() != TriggerType.MANUAL)) {
            return JobResult.failed("写入只能由人工确认触发", "PAY_BACKFILL_MANUAL_APPLY_REQUIRED");
        }
        List<String> candidates = backfill.pendingOrderNos(after, limit);
        int ready = 0;
        int already = 0;
        int applied = 0;
        int blocked = 0;
        List<String> problems = new ArrayList<>();
        List<String> decisions = new ArrayList<>();
        for (String order : candidates) {
            try {
                Source source = source(order);
                var store = source.store();
                Assessment facts = backfill.inspect(order, source.bizType(), store.storeId(), store.storeName(),
                        store.entityName(), store.market(), null);
                require(facts.status() != Status.BLOCKED, facts.reason());
                require(Objects.equals(facts.userNo(), source.userNo()), "SOURCE_BUYER_CONFLICT");
                require(facts.storeNos().isEmpty() || facts.storeNos().equals(List.of(store.storeNo())),
                        "SOURCE_STORE_CONFLICT");
                require(facts.merchantNos().isEmpty() || facts.merchantNos().equals(List.of(store.entityNo())),
                        "SOURCE_ENTITY_CONFLICT");
                require(source.outTradeNos().isEmpty() || source.outTradeNos().equals(List.of(facts.outTradeNo())),
                        "SOURCE_PAYMENT_CONFLICT");
                if (decisions.size() < 10) decisions.add(order + ":" + facts.status());
                log.info("[pay-order-backfill] runId={} order={} decision={} storeId={} currency={} paid={} refunded={}"
                                + " fundsPath={} target={}", invocation == null ? "LOCAL" : invocation.runId(), order,
                        facts.status(), store.storeId(), facts.currency(), facts.paidMinor(), facts.refundedMinor(),
                        facts.fundsPath(), facts.payOrderId());
                if (facts.status() == Status.ALREADY_LINKED) {
                    already++;
                    continue;
                }
                ready++;
                if (apply) {
                    // Current display names do not establish names at the time of a historical transaction.
                    backfill.backfill(order, source.bizType(), store.storeId(), null, null,
                            store.market(), facts.fundsPath());
                    applied++;
                }
            } catch (RuntimeException failure) {
                blocked++;
                String reason = failure instanceof Review ? failure.getMessage()
                        : failure instanceof IllegalStateException && failure.getMessage() != null
                        && failure.getMessage().startsWith("PAY_BACKFILL_") ? failure.getMessage()
                        : "INSPECTION_FAILED:" + failure.getClass().getSimpleName();
                if (problems.size() < 10) problems.add(order + ":" + reason);
                log.warn("[pay-order-backfill] runId={} order={} apply={} blocked={}",
                        invocation == null ? "LOCAL" : invocation.runId(), order, apply, reason);
            }
        }
        String cursor = candidates.isEmpty() ? after : candidates.get(candidates.size() - 1);
        String summary = "%s｜检查%d 可执行%d 已关联%d 已写入%d 阻断%d｜nextCursor=%s more=%s｜".formatted(
                apply ? "apply" : "dry-run", candidates.size(), ready, already, applied, blocked, cursor,
                candidates.size() == limit);
        String examples = "decisions=" + String.join(",", decisions) + " blocked=" + String.join(",", problems);
        // job_log.detail is VARCHAR(500). Preserve the complete cursor before clipping examples.
        String suffix = "…完整明细见本次运行日志";
        int available = 500 - summary.length();
        String detail = summary + (examples.length() <= available ? examples
                : examples.substring(0, available - suffix.length()) + suffix);
        return blocked > 0 ? JobResult.failed(detail, "PAY_BACKFILL_REVIEW_REQUIRED") : JobResult.ok(detail);
    }

    private Source source(String orderNo) {
        List<Map<String, Object>> children = jdbc.queryForList(
                "SELECT sub_order_no,store_no,entity_no,user_no,tenant_no,"
                + " CASE WHEN deleted=0 THEN 1 ELSE 0 END source_active"
                + " FROM ord_sub_order WHERE order_no=? ORDER BY sub_order_no", orderNo);
        require(!children.isEmpty(), "SOURCE_ORDER_REQUIRED");
        require(children.stream().allMatch(c -> ((Number) c.get("source_active")).intValue() == 1),
                "SOURCE_ORDER_DELETED");
        require(children.stream().allMatch(c -> "MAIN".equals(c.get("tenant_no"))), "SOURCE_TENANT_UNSUPPORTED");
        String storeNo = only(children, "store_no", "SOURCE_STORE_AMBIGUOUS");
        String entityNo = only(children, "entity_no", "SOURCE_ENTITY_AMBIGUOUS");
        String userNo = only(children, "user_no", "SOURCE_BUYER_AMBIGUOUS");
        var store = merchants.payStoreOf(storeNo).orElseThrow(() -> new Review("SOURCE_STORE_REQUIRED"));
        require(Objects.equals(store.storeNo(), storeNo) && Objects.equals(store.entityNo(), entityNo)
                && store.storeId() != null && text(store.market()), "SOURCE_ENTITY_CONFLICT");
        List<Map<String, Object>> bindings = jdbc.queryForList("SELECT sub_order_no,store_no,entity_no,buyer_no,"
                + "source_system,status,payment_out_trade_no,tenant_no,"
                + " CASE WHEN deleted=0 THEN 1 ELSE 0 END source_active FROM ord_industry_order_source"
                + " WHERE order_no=?", orderNo);
        if (bindings.isEmpty()) return new Source(PaymentPort.MALL_ORDER, store, userNo, List.of());
        require(bindings.stream().allMatch(b -> ((Number) b.get("source_active")).intValue() == 1),
                "SOURCE_BINDING_DELETED");
        require(bindings.stream().allMatch(b -> "FOOD".equals(b.get("source_system"))),
                "SOURCE_BUSINESS_UNKNOWN");
        require(bindings.stream().allMatch(b -> "BOUND".equals(b.get("status"))
                && "MAIN".equals(b.get("tenant_no")) && storeNo.equals(b.get("store_no"))
                && entityNo.equals(b.get("entity_no")) && userNo.equals(b.get("buyer_no"))), "SOURCE_BINDING_CONFLICT");
        List<String> expected = children.stream().map(c -> field(c, "sub_order_no")).distinct().sorted().toList();
        List<String> actual = bindings.stream().map(b -> field(b, "sub_order_no")).distinct().sorted().toList();
        require(expected.equals(actual) && bindings.size() == expected.size(), "SOURCE_BINDING_CONFLICT");
        String reference = only(bindings, "payment_out_trade_no", "SOURCE_PAYMENT_REQUIRED");
        return new Source(PaymentPort.FOOD_CHECK, store, userNo, List.of(reference));
    }

    private static String only(List<Map<String, Object>> rows, String column, String reason) {
        List<String> values = rows.stream().map(r -> field(r, column)).distinct().toList();
        require(values.size() == 1 && text(values.get(0)), reason);
        return values.get(0);
    }
    private static String field(Map<String, Object> row, String column) { return (String) row.get(column); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static String param(JobInvocation invocation, String key, String fallback) {
        return invocation == null ? fallback : invocation.param(key, fallback);
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new Review(reason); }
    private record Source(String bizType, MerchantQueryPort.PayStore store, String userNo, List<String> outTradeNos) { }
    private static class Review extends RuntimeException {
        Review(String reason) { super(reason); }
    }
}
