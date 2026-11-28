package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * 品牌域测试用的统一防重与统一审计（生产上分别是 sys_idempotent、sys_audit_log）：管理命令的重放与变更历史
 * 原先在 mch_management_command，新库不建（TDD-平台基础库换表 G5、G6）。按 JSON 往返存取，与真实实现同一口径。
 */
public class InMemoryJournal implements RequestIdempotencyPort, AuditLogPort {
    private final ObjectMapper json = new ObjectMapper();
    public final Map<String, String> completed = new LinkedHashMap<>();
    public final List<Entry> audits = new ArrayList<>();
    private final Map<Entry, String> targets = new LinkedHashMap<>();

    @Override
    public <T> T execute(String idemKey, String endpoint, String userNo, Class<T> type, Supplier<T> action) {
        return execute(idemKey, endpoint, userNo, null, type, action);
    }

    @Override
    public <T> T execute(String idemKey, String endpoint, String userNo, String requestHash, Class<T> type,
                         Supplier<T> action) {
        String key = idemKey + "|" + endpoint;
        if (completed.containsKey(key)) {
            return json.readValue(completed.get(key), type);
        }
        T result = action.get();
        completed.put(key, json.writeValueAsString(result));
        return result;
    }

    @Override
    public <T> Optional<T> findCompleted(String idemKey, String endpoint, String userNo, String requestHash, Class<T> type) {
        String stored = completed.get(idemKey + "|" + endpoint);
        return stored == null ? Optional.empty() : Optional.of(json.readValue(stored, type));
    }

    @Override
    public void record(String action, String target, String detail) {
        record(action, target, detail, false, null, null);
    }

    @Override
    public void record(String action, String target, String detail, boolean critical, String beforeJson, String afterJson) {
        // 与 AuditLogPortImpl 同一契约：事务里只登记，提交后才落；回滚不留痕
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { append(action, target, detail, afterJson); }
                    });
            return;
        }
        append(action, target, detail, afterJson);
    }

    private void append(String action, String target, String detail, String afterJson) {
        var entry = new Entry(audits.size() + 1, action, detail, afterJson, System.currentTimeMillis());
        audits.add(entry);
        targets.put(entry, target);
    }

    @Override
    public long countSince(String action, long since) {
        return audits.stream().filter(e -> e.action().equals(action) && e.at() >= since).count();
    }

    @Override
    public List<Entry> byTarget(String target, long beforeId, int limit) {
        return audits.stream().filter(e -> target.equals(targets.get(e)) && e.id() < beforeId)
                .sorted((a, b) -> Long.compare(b.id(), a.id())).limit(limit).toList();
    }
}
