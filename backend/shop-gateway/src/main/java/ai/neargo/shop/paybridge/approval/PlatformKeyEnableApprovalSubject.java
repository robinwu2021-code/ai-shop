package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.KeyVersionRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.PlatformKeyRow;
import ai.neargo.shop.payclient.OpsPaymentAppService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * T6 · 平台密钥启用接入审批（方案-第一批审批工作流 T6）。三级 · 密钥：审核 → 复核，均 {@code sec:key:enable}。
 *
 * <p>对象号 {@code keyId:version}。修订号 = 密钥当前生效版本号 × 16 + 本版本状态序号：
 * 别的版本被启用、本版本被吊销或状态变了，修订号都会变。
 * 生效 = 原「启用」，以「审批服务」身份执行：密钥模块的「启用人 ≠ 录入人」（DUAL_CONTROL 与库约束）照常生效且必然满足 ——
 * 启用人记为 OPSKIT，真正的审批人记在 OpsKit 审批单上，所以不需要放开密钥模块的两人控制。
 */
@Component
public class PlatformKeyEnableApprovalSubject implements ApprovalSubject {

    public static final String SUBJECT_TYPE = "shop.platform_key_enable";
    static final List<String> STATUSES = List.of("DRAFT", "VERIFIED", "ACTIVE", "ROTATING", "RETIRED", "REVOKED");

    private final OpsPaymentAppService keys;

    public PlatformKeyEnableApprovalSubject(OpsPaymentAppService keys) {
        this.keys = keys;
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_platform_key_enable"; }
    @Override public String processName() { return "平台密钥启用"; }
    @Override public String approverPerm() { return Perms.SEC_KEY_ENABLE; }
    @Override public String recheckPerm() { return Perms.SEC_KEY_ENABLE; }
    @Override public Level level() { return Level.FUNDS; }

    public static String ref(long keyId, int version) {
        return keyId + ":" + version;
    }

    /** 当前修订号；版本不存在返回 -1。 */
    public long revision(long keyId, int version) {
        var state = state(keyId, version);
        return state == null ? -1 : revision(state.key(), state.version());
    }

    @Override
    public Snapshot snapshot(String ref) {
        var s = require(ref);
        var active = keys.platformKeyVersions(s.key().keyId()).stream()
                .filter(v -> v.version() == s.key().currentVersion()).findFirst().orElse(null);
        return new Snapshot("平台密钥启用 · " + s.key().name() + " v" + s.version().version(),
                s.key().counterparty() + " · " + s.key().usage(), Set.of(), Map.of("keyId", s.key().keyId()),
                List.of(Fact.of("密钥", s.key().name()),
                        Fact.of("归属 / 对端", s.key().ownerType() + " / " + s.key().counterparty()),
                        Fact.of("方向 / 用途", s.key().direction() + " / " + s.key().usage()),
                        Fact.change("生效版本", active == null ? "无" : "v" + active.version(), "v" + s.version().version()),
                        Fact.change("指纹", active == null ? "—" : active.fingerprint(), s.version().fingerprint()),
                        Fact.of("序号", s.version().serial() == null ? "—" : s.version().serial()),
                        Fact.of("录入人", s.version().enteredBy())));
    }

    @Override
    public void apply(String ref, long revision) {
        var s = require(ref);
        if ("ACTIVE".equals(s.version().status())) return; // 重投
        if (!"VERIFIED".equals(s.version().status()) && !"DRAFT".equals(s.version().status()) || revision(s.key(), s.version()) != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "密钥版本已变化：status=" + s.version().status());
        }
        try {
            PayApprovalSupport.asApprovalService(() -> {
                keys.enablePlatformKey(s.key().keyId(), s.version().version());
                return null;
            });
        } catch (RuntimeException e) {
            throw PayApprovalSupport.failure(e);
        }
    }

    @Override
    public void onClosed(String ref, long revision, Outcome outcome, String reason) {
        // 未获批：版本保持原状态；要作废走「吊销」
    }

    private static long revision(PlatformKeyRow key, KeyVersionRow version) {
        return key.currentVersion() * 16L + Math.max(0, STATUSES.indexOf(version.status()));
    }

    private State require(String ref) {
        String[] p = ref.split(":");
        State s = p.length == 2 ? state(Long.parseLong(p[0]), Integer.parseInt(p[1])) : null;
        if (s == null) throw new ApprovalApplyException("NOT_FOUND", "密钥版本不存在：" + ref);
        return s;
    }

    private State state(long keyId, int version) {
        var key = keys.platformKeys().stream().filter(k -> k.keyId() == keyId).findFirst().orElse(null);
        if (key == null) return null;
        var v = keys.platformKeyVersions(keyId).stream().filter(x -> x.version() == version).findFirst().orElse(null);
        return v == null ? null : new State(key, v);
    }

    private record State(PlatformKeyRow key, KeyVersionRow version) {
    }
}
