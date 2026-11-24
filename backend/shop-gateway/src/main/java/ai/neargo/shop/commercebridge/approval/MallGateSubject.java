package ai.neargo.shop.commercebridge.approval;

import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 「审批通过 → 运营再点执行」的商城审批（方案-第一批审批工作流 T9a、T9b）。
 *
 * <p>商城的资金动作要求运营本人的有效登录会话，审批回调线程没有，所以执行节点<b>不执行业务</b>，只做「放行」：
 * 核对对象与请求内容没变，就让审批单结束为「已生效」。真正执行仍由运营在原页面再点一次，
 * {@link MallApprovalGate} 看到同一修订已放行才让请求进入商城。
 *
 * <p>修订号 = 业务现状 + 请求内容（规范化 JSON）的 CRC32：金额、凭证、报价任何一项不同都是另一份审批。
 * 对象号 {@code tenant:store:order:subject}。
 */
public abstract class MallGateSubject implements ApprovalSubject {

    static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    protected final JdbcTemplate db;

    protected MallGateSubject(JdbcTemplate db) {
        this.db = db;
    }

    /** 门禁对应的商城动作（{@code OpsApprovalGatePort} 常量）。 */
    abstract String action();

    /** 业务现状指纹；对象不存在返回 null。 */
    abstract String state(Ref ref);

    /** 还在待处理（该送审 / 等审批）；否则交给商城自己判断（幂等重放或状态冲突）。 */
    abstract boolean awaiting(Ref ref);

    /** 收到的请求体 → 拟定结果（JSON 原生类型，与 OpsKit 往返后一致）。 */
    static Map<String, Object> proposal(Object request) {
        try {
            return JSON.readValue(JSON.writeValueAsString(request), new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("INVALID_REQUEST", e);
        }
    }

    /** 当前修订号；对象不存在返回 -1。 */
    long revision(Ref ref, Map<String, Object> proposal) {
        String s = state(ref);
        if (s == null) return -1;
        try {
            var crc = new CRC32();
            crc.update((s + "|" + JSON.writeValueAsString(proposal)).getBytes(StandardCharsets.UTF_8));
            return crc.getValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Snapshot snapshot(String ref) {
        return snapshot(ref, Map.of());
    }

    @Override
    public void apply(String ref, long revision) {
        throw new ApprovalApplyException("PROPOSAL_REQUIRED", "缺少拟定结果");
    }

    /** 放行：对象与请求内容都没变才通过；业务由运营再点执行。 */
    @Override
    public void apply(String ref, long revision, Map<String, Object> proposal) {
        if (proposal == null || proposal.isEmpty()) apply(ref, revision);
        var r = Ref.parse(ref);
        if (!awaiting(r) || revision(r, proposal) != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "对象已变化，请重新送审：" + ref);
        }
    }

    @Override
    public void onClosed(String ref, long revision, Outcome outcome, String reason) {
        // 未通过：商城对象保持原状，运营可改了再送，或在页面上直接驳回
    }

    record Ref(String tenant, String store, String order, String subject) {
        static Ref parse(String ref) {
            String[] p = ref.split(":", 4);
            if (p.length != 4) throw new ApprovalApplyException("NOT_FOUND", "对象号不合法：" + ref);
            return new Ref(p[0], p[1], p[2], p[3]);
        }

        @Override
        public String toString() {
            return tenant + ":" + store + ":" + order + ":" + subject;
        }
    }

    /** 范围标签：门店所属主体（被限定范围的运营据此可见）+ 门店。 */
    java.util.Set<String> scopes(Ref r) {
        var entity = db.queryForList("SELECT entity_no FROM mch_store WHERE store_no=? AND deleted=0", String.class, r.store());
        return entity.isEmpty() || entity.getFirst() == null ? java.util.Set.of("store:" + r.store())
                : java.util.Set.of("merchant:" + entity.getFirst(), "store:" + r.store());
    }

    static String yuan(Object money) {
        if (!(money instanceof Map<?, ?> m) || !(m.get("minor") instanceof Number n)) return "—";
        return m.get("currency") + " " + java.math.BigDecimal.valueOf(n.longValue(), 2).toPlainString();
    }

    static String text(Object v) {
        return v == null ? "—" : v.toString();
    }
}
