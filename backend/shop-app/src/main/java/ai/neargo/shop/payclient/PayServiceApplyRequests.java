package ai.neargo.shop.payclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.NearPayStoreProfile;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 组装「申请支付服务」请求（TDD-运营端申请支付服务 §1）。纯函数：输入主体、门店快照、运营填的资料，输出 near-pay 请求。
 *
 * <p>门店的 externalId / revision / profileHash 必须与门店同步推给 near-pay 的完全一致，否则 near-pay 关联不上门店投影 ——
 * 所以直接复用 {@link NearPayStoreProfile#request}，不另算一遍。
 */
public final class PayServiceApplyRequests {

    public static final String PERM = "merchant:merchant:pay-apply";

    /** 运营在界面上填的主体资料 */
    public record Form(String legalName, String registrationNo, String representativeName, String contactPhone,
                       String registeredAddress) {
    }

    private PayServiceApplyRequests() {
    }

    public static PaySourceLinkApi.Apply build(StoreSnapshotReader.Entity entity, List<StoreSnapshotReader.Snapshot> stores,
                                               Form form, String operatorNo, StoreSyncProperties props) {
        if (entity == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (form == null || blank(form.legalName()) || blank(form.registrationNo()) || blank(operatorNo)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String legalName = cut(form.legalName(), 128);
        String registrationNo = cut(form.registrationNo(), 64);
        long revision = Math.max(1, entity.revision());
        List<PaySourceLinkApi.StoreInput> storeInputs = stores.stream().filter(s -> !s.deleted()).map(s -> {
            Map<String, Object> r = NearPayStoreProfile.request(s, props);
            return new PaySourceLinkApi.StoreInput((String) r.get("storeRef"), ((Number) r.get("revision")).longValue(),
                    (String) r.get("profileHash"), s.storeNo());
        }).toList();
        if (storeInputs.size() > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        PaySourceLinkApi.Profile profile = new PaySourceLinkApi.Profile("FIAT", legalName, registrationNo,
                trimOrNull(form.registeredAddress(), 256), trimOrNull(form.representativeName(), 64),
                trimOrNull(form.contactPhone(), 32), null, List.of());
        String event = "ais-apply-" + entity.entityNo() + "-" + revision + "-" + digest(profile).substring(0, 8);
        return new PaySourceLinkApi.Apply(event, props.sourceScope(), entity.entityNo(), revision,
                blank(entity.name()) ? legalName : entity.name(), storeInputs,
                new PaySourceLinkApi.ActorRef("OPERATOR", operatorNo, "1", PERM), "FIAT", revision, profile);
    }

    private static String digest(PaySourceLinkApi.Profile p) {
        String canonical = String.join("\u0001", p.purpose(), n(p.legalName()), n(p.registrationNo()),
                n(p.registeredAddress()), n(p.representativeName()), n(p.contactPhone()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String n(String s) { return s == null ? "" : s; }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String cut(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String trimOrNull(String s, int max) {
        return blank(s) ? null : cut(s, max);
    }
}
