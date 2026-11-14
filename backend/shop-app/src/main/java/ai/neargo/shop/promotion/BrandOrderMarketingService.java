package ai.neargo.shop.promotion;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordinary ORDER fixed-cut marketing application API. */
public interface BrandOrderMarketingService {
    List<Map<String,Object>> list(ActivityActor actor);
    ActivityView get(ActivityActor actor, String id);
    Map<String,Object> execute(ActivityActor actor, String action, Object payload, String key);
    Map<String,Object> view(ActivityActor actor, ActivityView activity);

    public static Map<String,Object> success(Map<String,Object> value) {
        var out = new LinkedHashMap<String,Object>(); out.put("success", true); out.putAll(value); return out;
    }
    public static String text(Map<String,Object> p, String field) {
        String s = optionalText(p, field);
        if (s == null || s.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST);
        return s;
    }
    public static String optionalText(Map<String,Object> p, String field) {
        Object v = p.get(field); if (v == null) return null;
        if (!(v instanceof String s)) throw BizException.of(ErrorCode.BAD_REQUEST); return s;
    }
    public static long number(Map<String,Object> p, String field) {
        Object v = p.get(field);
        if (!(v instanceof Number n) || n instanceof Float || n instanceof Double) throw BizException.of(ErrorCode.BAD_REQUEST);
        return n instanceof java.math.BigInteger big ? big.longValueExact() : n.longValue();
    }
    public static long revision(Map<String,Object> p, String field) {
        Object v = p.get(field);
        if (v instanceof String s) try { return Long.parseLong(s); } catch (NumberFormatException e) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return number(p, field);
    }
    public static long optionalNumber(Map<String,Object> p, String field, long fallback) {
        return p.get(field) == null ? fallback : number(p, field);
    }
    public static List<String> strings(Map<String,Object> p, String field) {
        if (!(p.get(field) instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof String))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return list.stream().map(String.class::cast).toList();
    }
}
