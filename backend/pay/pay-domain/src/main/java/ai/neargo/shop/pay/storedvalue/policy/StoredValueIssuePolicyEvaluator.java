package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic, Pay-owned policy intersection. This evaluator does not reserve funds or grant approval. */
@Component
public class StoredValueIssuePolicyEvaluator {
    public EffectivePolicy evaluate(IssueScope scope, List<PolicyVersion> versions) {
        ContractChecks.present(scope); ContractChecks.present(versions);
        ContractChecks.require(versions.size() <= 3);
        var seenPolicies = new HashSet<String>(); var seenTargets = new HashSet<String>();
        var sources = versions.stream().sorted(Comparator.comparing(PolicyVersion::targetType)
                .thenComparing(PolicyVersion::targetNo).thenComparing(PolicyVersion::policyNo)).toList();
        boolean configured = false;
        Rules rules = new Rules(true, false, 10000, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);
        for (var version : sources) {
            ContractChecks.require(scope.tenantNo().equals(version.tenantNo())
                    && scope.managementNo().equals(version.managementNo()) && scope.currency().equals(version.currency())
                    && seenPolicies.add(version.policyNo()) && seenTargets.add(version.targetType() + ":" + version.targetNo()));
            boolean matches = switch (version.targetType()) {
                case "SPACE" -> scope.managementNo().equals(version.targetNo());
                case "BRAND_STORES" -> scope instanceof StoreScope&&Objects.equals(scope.brandNo(),version.targetNo());
                case "STORE" -> scope instanceof StoreScope&&scope.storeNo().equals(version.targetNo());
                default -> false;
            };
            ContractChecks.require(matches);
            configured |= "SPACE".equals(version.targetType());
            rules = intersect(rules, version.rules());
        }
        if (!configured) rules = new Rules(false, rules.requireApproval(), rules.maxBonusBps(), rules.maxPaidMinor(),
                rules.maxBonusMinor(), rules.maxActiveDays(), rules.maxOutstandingMinor());
        return new EffectivePolicy(scope, rules, sources, digest(scope, sources), configured);
    }

    public Decision check(EffectivePolicy policy, IssueRequest request) {
        ContractChecks.present(policy); ContractChecks.present(request);
        // Do not accept an EffectivePolicy assembled by a caller with unrelated sources or a fabricated hash.
        var verified = evaluate(policy.scope(), policy.sources());
        ContractChecks.require(verified.equals(policy));
        var r = policy.rules(); var blockers = new ArrayList<String>();
        if (!policy.configured()) blockers.add("ISSUE_POLICY_NOT_CONFIGURED");
        if (!r.allowIssue()) blockers.add("ISSUANCE_DISABLED");
        if (request.paidMinor() > r.maxPaidMinor()) blockers.add("PAID_LIMIT_EXCEEDED");
        if (request.bonusMinor() > r.maxBonusMinor()) blockers.add("BONUS_LIMIT_EXCEEDED");
        if (BigInteger.valueOf(request.bonusMinor()).multiply(BigInteger.valueOf(10000))
                .compareTo(BigInteger.valueOf(request.paidMinor()).multiply(BigInteger.valueOf(r.maxBonusBps()))) > 0)
            blockers.add("BONUS_RATIO_EXCEEDED");
        if (request.activeDays() > r.maxActiveDays()) blockers.add("ACTIVE_DAYS_EXCEEDED");
        var exposure = BigInteger.valueOf(request.outstandingMinor()).add(BigInteger.valueOf(request.reservedMinor()))
                .add(BigInteger.valueOf(request.paidMinor())).add(BigInteger.valueOf(request.bonusMinor()));
        if (exposure.compareTo(BigInteger.valueOf(r.maxOutstandingMinor())) > 0) blockers.add("OUTSTANDING_LIMIT_EXCEEDED");
        return new Decision(blockers.isEmpty(), blockers.isEmpty() && r.requireApproval(), policy.hash(), blockers);
    }

    public boolean tightens(Rules child, Rules parent) {
        ContractChecks.present(child); ContractChecks.present(parent);
        return (!child.allowIssue() || parent.allowIssue()) && (child.requireApproval() || !parent.requireApproval())
                && child.maxBonusBps() <= parent.maxBonusBps() && child.maxPaidMinor() <= parent.maxPaidMinor()
                && child.maxBonusMinor() <= parent.maxBonusMinor() && child.maxActiveDays() <= parent.maxActiveDays()
                && child.maxOutstandingMinor() <= parent.maxOutstandingMinor();
    }
    private Rules intersect(Rules a, Rules b) {
        return new Rules(a.allowIssue() && b.allowIssue(), a.requireApproval() || b.requireApproval(),
                Math.min(a.maxBonusBps(), b.maxBonusBps()), Math.min(a.maxPaidMinor(), b.maxPaidMinor()),
                Math.min(a.maxBonusMinor(), b.maxBonusMinor()), Math.min(a.maxActiveDays(), b.maxActiveDays()),
                Math.min(a.maxOutstandingMinor(), b.maxOutstandingMinor()));
    }
    private String digest(IssueScope scope, List<PolicyVersion> sources) {
        var parts = new ArrayList<String>();
        if(scope instanceof BrandScope brand)Collections.addAll(parts,"SV_BRAND_POLICY_V1",scope.tenantNo(),scope.managementNo(),brand.brandNo(),brand.issuerNo(),scope.currency());
        else Collections.addAll(parts,"SV_POLICY_V1",scope.tenantNo(),scope.managementNo(),scope.storeNo(),Objects.toString(scope.brandNo(),""),scope.currency());
        for (var source : sources) {
            var r = source.rules();
            Collections.addAll(parts, source.policyNo(), source.versionNo(), source.targetType(), source.targetNo(),
                    Boolean.toString(r.allowIssue()), Boolean.toString(r.requireApproval()), Integer.toString(r.maxBonusBps()),
                    Long.toString(r.maxPaidMinor()), Long.toString(r.maxBonusMinor()), Integer.toString(r.maxActiveDays()),
                    Long.toString(r.maxOutstandingMinor()));
        }
        var material = new StringBuilder();
        for (String part : parts) material.append(part.length()).append(':').append(part);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
