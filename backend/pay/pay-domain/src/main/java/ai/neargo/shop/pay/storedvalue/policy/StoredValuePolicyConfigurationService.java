package ai.neargo.shop.pay.storedvalue.policy;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Explicitly authorized configuration only; no channel calls and no changes to existing commitments. */
@Service
public class StoredValuePolicyConfigurationService {
    private final IssuerRepository db;
    private final StoredValueIssuePolicyEvaluator evaluator;
    public StoredValuePolicyConfigurationService(IssuerRepository db, StoredValueIssuePolicyEvaluator evaluator) {
        this.db = db; this.evaluator = evaluator;
    }
    public PolicyView configure(PolicyAuthority scope, PolicyEdit edit, String key) {
        ContractChecks.present(scope); ContractChecks.present(edit); ContractChecks.id(key);
        String hash = hash(db.encode(List.of(scope.tenantNo(), scope.actorNo(), scope.managementNo(), scope.targetType(),
                scope.targetNo(), scope.currency(), edit.expectedRevision(),edit.createOnly(), edit.rules())));
        return db.write(() -> {
            // Read-only replay first; a newer policy must not transform an old successful command into a new edit.
            var prior = db.one("SELECT v.* FROM sv_issue_policy_version v JOIN sv_issue_policy p "
                    + "ON CAST(p.tenant_no AS BINARY(256))=CAST(v.tenant_no AS BINARY(256)) AND CAST(p.policy_no AS BINARY(256))=CAST(v.policy_no AS BINARY(256)) WHERE p.tenant_no=? AND p.management_no=? "
                    + "AND p.target_type=? AND p.target_no=? AND p.currency=? AND v.configuration_key=?",
                    scope.tenantNo(), scope.managementNo(), scope.targetType(), scope.targetNo(), scope.currency(), key);
            if (prior != null) {
                if (!hash.equals(text(prior,"content_hash"))) conflict();
                return view(scope, prior);
            }
            var parents = parents(scope);
            if (!"SPACE".equals(scope.targetType()) && parents.stream().noneMatch(p -> "SPACE".equals(text(p,"target_type"))))
                conflict();
            for (var parent : parents) if (!evaluator.tightens(edit.rules(), rules(parent))) conflict();
            var existing = db.one("SELECT * FROM sv_issue_policy WHERE tenant_no=? AND management_no=? AND target_type=? "
                    + "AND target_no=? AND currency=? FOR UPDATE", scope.tenantNo(), scope.managementNo(),
                    scope.targetType(), scope.targetNo(), scope.currency());
            if (existing != null && (number(existing,"deleted") != 0 || !"ACTIVE".equals(text(existing,"status")))) conflict();
            // Recheck after locking for concurrent commands with the same original key.
            if (existing != null) {
                var replay = db.one("SELECT * FROM sv_issue_policy_version WHERE tenant_no=? AND policy_no=? AND configuration_key=?",
                        scope.tenantNo(), text(existing,"policy_no"), key);
                if (replay != null) {
                    if (!hash.equals(text(replay,"content_hash"))) conflict();
                    return view(scope, replay);
                }
            }
            if(existing!=null&&edit.createOnly())conflict();
            long revision = existing == null ? 0 : number(existing,"version");
            if (edit.expectedRevision() != revision) conflict();
            String policyNo = existing == null ? BizKey.next(BizKey.STORED_VALUE_ISSUE_POLICY) : text(existing,"policy_no");
            String versionNo = BizKey.next(BizKey.STORED_VALUE_POLICY_VERSION);
            long nextRevision = existing == null ? 0 : Math.addExact(revision, 1);
            var at = Timestamp.from(Instant.now()); var rules = edit.rules();
            var refs = parents.stream().map(p -> text(p,"policy_version_no")).toList();
            var row = base(scope, at);
            row.put("policy_version_no", versionNo); row.put("policy_no", policyNo);
            row.put("version_index", Math.addExact(nextRevision, 1)); row.put("configuration_key", key);
            row.put("allow_issue", rules.allowIssue() ? 1 : 0); row.put("require_approval", rules.requireApproval() ? 1 : 0);
            row.put("max_bonus_bps", rules.maxBonusBps()); row.put("max_paid_minor", rules.maxPaidMinor());
            row.put("max_bonus_minor", rules.maxBonusMinor()); row.put("max_active_days", rules.maxActiveDays());
            row.put("max_outstanding_minor", rules.maxOutstandingMinor()); row.put("effect_mode", "NEW_ISSUANCE_ONLY");
            row.put("parent_version_refs", db.encode(refs)); row.put("content_hash", hash); row.put("effective_at", at);
            row.put("permission_snapshot", db.encode(Map.of("actorNo", scope.actorNo(), "managementNo", scope.managementNo(),
                    "authorityRevision", scope.authorityRevision(), "targetType", scope.targetType(), "targetNo", scope.targetNo())));
            db.insert("sv_issue_policy_version", row);
            if (existing == null) {
                var policy = base(scope, at); policy.put("policy_no", policyNo); policy.put("management_no", scope.managementNo());
                policy.put("target_type", scope.targetType()); policy.put("target_no", scope.targetNo());
                policy.put("currency", scope.currency()); policy.put("current_version_no", versionNo); policy.put("status", "ACTIVE");
                db.insert("sv_issue_policy", policy);
            } else {
                int changed = db.update("UPDATE sv_issue_policy SET current_version_no=?,version=?,updated_at=?,updated_by=? "
                        + "WHERE tenant_no=? AND policy_no=? AND version=?", versionNo, nextRevision, at, scope.actorNo(),
                        scope.tenantNo(), policyNo, revision);
                if (changed != 1) conflict();
            }
            return new PolicyView(policyNo, versionNo, nextRevision, scope.targetType(), scope.targetNo(), scope.currency(), rules, refs, hash);
        });
    }
    private List<Map<String,Object>> parents(PolicyAuthority scope) {
        if ("SPACE".equals(scope.targetType())) return List.of();
        var result=new ArrayList<Map<String,Object>>();var targets=new ArrayList<Target>();targets.add(new Target("SPACE",scope.managementNo()));
        if("STORE".equals(scope.targetType())&&scope.brandNo()!=null)targets.add(new Target("BRAND_STORES",scope.brandNo()));
        for(var target:targets){
            var head=db.one("SELECT * FROM sv_issue_policy WHERE tenant_no=? AND management_no=? AND currency=? AND target_type=? AND target_no=? AND status='ACTIVE' AND deleted=0 FOR UPDATE",scope.tenantNo(),scope.managementNo(),scope.currency(),target.type(),target.no());
            if(head==null)continue;
            var version=db.one("SELECT * FROM sv_issue_policy_version WHERE tenant_no=? AND policy_no=? AND policy_version_no=? AND deleted=0 FOR UPDATE",scope.tenantNo(),text(head,"policy_no"),text(head,"current_version_no"));
            if(version==null)conflict();var row=new LinkedHashMap<String,Object>(version);row.put("target_type",target.type());result.add(row);
        }
        return List.copyOf(result);
    }
    private PolicyView view(PolicyAuthority scope, Map<String,Object> row) {
        return new PolicyView(text(row,"policy_no"), text(row,"policy_version_no"), number(row,"version_index") - 1,
                scope.targetType(), scope.targetNo(), scope.currency(), rules(row),
                List.of(db.decode(text(row,"parent_version_refs"), String[].class)), text(row,"content_hash"));
    }
    private Rules rules(Map<String,Object> row) {
        return new Rules(number(row,"allow_issue") == 1, number(row,"require_approval") == 1,
                Math.toIntExact(number(row,"max_bonus_bps")), number(row,"max_paid_minor"), number(row,"max_bonus_minor"),
                Math.toIntExact(number(row,"max_active_days")), number(row,"max_outstanding_minor"));
    }
    private Map<String,Object> base(PolicyAuthority scope, Timestamp at) {
        var row = new LinkedHashMap<String,Object>(); row.put("tenant_no", scope.tenantNo()); row.put("version", 0L);
        row.put("deleted", 0); row.put("created_at", at); row.put("updated_at", at);
        row.put("created_by", scope.actorNo()); row.put("updated_by", scope.actorNo()); return row;
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
