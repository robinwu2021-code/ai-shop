package ai.neargo.shop.merchant.paysync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/**
 * 把门店快照转成 near-pay V2-02 请求（门店信息同步设计 §3）。profileHash 与 near-pay {@code Canonical} 同规则：
 * 键排序、去 null、紧凑 JSON、SHA-256 小写十六进制。
 */
public final class NearPayStoreProfile {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 门店资料的结构版本，加在推给 near-pay 的修订号上。near-pay 对「同一修订号、不同资料摘要」一律判冲突，
     * 所以资料里每多一个字段（摘要必然变），这个数就加一 —— 否则已同步过的门店再也推不过去。
     * 1：2026-11-06 加 fundsPath。修订号对每家门店仍然单调递增（变更时间毫秒 + 常数）。
     */
    public static final long PROFILE_VERSION = 1;

    private NearPayStoreProfile() {
    }

    public static Map<String, Object> request(StoreSnapshotReader.Snapshot s, StoreSyncProperties p) {
        Map<String, Object> profile = new TreeMap<>();
        put(profile, "name", cut(s.name(), 128));
        put(profile, "address", cut(join(s.address(), s.addressDetail()), 512));
        put(profile, "status", status(s));
        put(profile, "market", s.market() == null || s.market().isBlank() ? p.defaultMarket() : s.market());
        put(profile, "entityName", cut(s.entityName(), 128));
        put(profile, "logoUrl", logo(s, p));
        put(profile, "openHours", cut(s.openHours(), 128));
        put(profile, "storeCode", storeCode(s.slug()));
        put(profile, "fundsPath", p.fundsPath());
        Map<String, Object> body = new LinkedHashMap<>();
        long revision = s.revision() + PROFILE_VERSION;
        body.put("eventId", ("ais-" + s.storeNo() + "-" + revision));
        body.put("sourceScope", p.sourceScope());
        body.put("storeRef", Long.toString(s.storeId()));
        body.put("storeNo", s.storeNo());
        body.put("entityRef", s.entityNo());
        body.put("revision", revision);
        body.put("deleted", s.deleted());
        body.put("profileHash", hash(profile));
        body.put("profile", profile);
        return body;
    }

    static String status(StoreSnapshotReader.Snapshot s) {
        if (s.deleted()) return "DELETED";
        return switch (s.status() == null ? "" : s.status()) {
            case "ACTIVE" -> "ACTIVE";
            case "READONLY" -> "READONLY";
            default -> "DISABLED";
        };
    }

    static String storeCode(String slug) {
        if (slug == null) return null;
        String code = slug.trim().toLowerCase(Locale.ROOT);
        return code.matches("^[a-z0-9](?:[a-z0-9-]{1,30})[a-z0-9]$") && !code.contains("--") ? code : null;
    }

    static String logo(StoreSnapshotReader.Snapshot s, StoreSyncProperties p) {
        String raw = s.entityLogo() != null && !s.entityLogo().isBlank() ? s.entityLogo() : s.bannerUrl();
        if (raw == null || raw.isBlank()) return null;
        String url = raw.trim();
        if (!url.startsWith("https://")) {
            if (url.startsWith("/") && p.mediaBaseUrl() != null && p.mediaBaseUrl().startsWith("https://")) {
                url = p.mediaBaseUrl().replaceAll("/+$", "") + url;
            } else {
                return null;
            }
        }
        return url.length() <= 512 && url.matches("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/[^\\s\"'<>]*") ? url : null;
    }

    static String hash(Map<String, Object> profile) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(new TreeMap<>(profile)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void put(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.trim(), y = b == null ? "" : b.trim();
        return (x + (x.isEmpty() || y.isEmpty() ? "" : " ") + y).trim();
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
