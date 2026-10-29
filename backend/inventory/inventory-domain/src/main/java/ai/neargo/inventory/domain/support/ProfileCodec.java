package ai.neargo.inventory.domain.support;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.BusinessProfile.OutboundPurpose;
import ai.neargo.inventory.api.profile.BusinessProfile.TargetType;
import ai.neargo.inventory.api.profile.Capability;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 业态档案的读写与校验（TDD §2.5）。
 *
 * <p><b>校验在保存时一次做完</b>，过账时只读已校验过的版本 —— 否则一份坏档案会在第一张单据过账时才炸，
 * 而那时候档案已经生效、坏在哪也难找。每条规则失败都带上是哪一条，参数原样给调用方。
 */
public final class ProfileCodec {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    private ProfileCodec() {
    }

    public static BusinessProfile parse(String json) {
        try {
            return validate(JSON.readValue(json, BusinessProfile.class));
        } catch (JacksonException e) {
            throw invalid("json");
        }
    }

    public static String write(BusinessProfile profile) {
        return JSON.writeValueAsString(profile);
    }

    public static BusinessProfile validate(BusinessProfile p) {
        if (p == null || p.schemaVersion() != BusinessProfile.CURRENT_SCHEMA) {
            throw invalid("schemaVersion");
        }
        Set<Capability> caps = p.capabilities() == null ? Set.of() : p.capabilities();
        Map<String, String> parents = new HashMap<>();
        for (TargetType t : nullSafe(p.targetTypes())) {
            if (t == null || !code(t.code()) || parents.containsKey(t.code())) {
                throw invalid("targetTypes.code");
            }
            parents.put(t.code(), t.parentType());
        }
        for (Map.Entry<String, String> e : parents.entrySet()) {
            if (e.getValue() != null && !parents.containsKey(e.getValue())) {
                throw invalid("targetTypes.parentType:" + e.getKey());
            }
            if (cyclic(e.getKey(), parents)) {
                throw invalid("targetTypes.cycle:" + e.getKey());
            }
        }
        Set<String> inbound = new HashSet<>();
        for (BusinessProfile.InboundPurpose ip : nullSafe(p.inboundPurposes())) {
            if (ip == null || !code(ip.code()) || !inbound.add(ip.code())) {
                throw invalid("inboundPurposes.code");
            }
        }
        Set<String> outbound = new HashSet<>();
        for (OutboundPurpose op : nullSafe(p.outboundPurposes())) {
            if (op == null || !code(op.code()) || !outbound.add(op.code()) || op.handover() == null) {
                throw invalid("outboundPurposes.code");
            }
            for (String t : nullSafe(op.requiredTargets())) {
                if (!parents.containsKey(t)) {
                    throw invalid("outboundPurposes.requiredTargets:" + op.code());
                }
            }
            for (String t : nullSafe(op.optionalTargets())) {
                if (!parents.containsKey(t) || !hasAncestorIn(t, nullSafe(op.requiredTargets()), parents)) {
                    throw invalid("outboundPurposes.optionalTargets:" + op.code());
                }
            }
            if (op.handover() == BusinessProfile.Handover.POOL) {
                if (!caps.contains(Capability.DISPATCH_POOL)) {
                    throw invalid("outboundPurposes.handover.requiresDispatchPool:" + op.code());
                }
                if (nullSafe(op.requiredTargets()).isEmpty()) {
                    throw invalid("outboundPurposes.handover.requiresTarget:" + op.code());
                }
            }
        }
        Set<String> gates = new HashSet<>();
        for (String g : nullSafe(p.gateRefTypes())) {
            if (!code(g) || !gates.add(g)) {
                throw invalid("gateRefTypes");
            }
        }
        if (p.countReview() == null) {
            throw invalid("countReview");
        }
        return p;
    }

    private static boolean cyclic(String start, Map<String, String> parents) {
        Set<String> seen = new HashSet<>();
        for (String at = start; at != null; at = parents.get(at)) {
            if (!seen.add(at)) {
                return true;
            }
        }
        return false;
    }

    /** 可选目标必须挂在某个必填目标之下（如站点挂在商家下），否则「这单发给谁」说不清。 */
    private static boolean hasAncestorIn(String type, List<String> required, Map<String, String> parents) {
        for (String at = parents.get(type); at != null; at = parents.get(at)) {
            if (required.contains(at)) {
                return true;
            }
        }
        return false;
    }

    private static boolean code(String value) {
        return value != null && CODE.matcher(value).matches();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static InventoryException invalid(String rule) {
        return InventoryException.of(InventoryError.PROFILE_INVALID, rule);
    }
}
