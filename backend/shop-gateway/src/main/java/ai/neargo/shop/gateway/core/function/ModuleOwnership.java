package ai.neargo.shop.gateway.core.function;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.Realm;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 权限码归哪个模块（TDD-网关层与统一权限 §2.6，解「权限点没有模块归属」）。
 *
 * <p>码值仍然写在 {@link Perms} 与 {@link BizPerms} 里，一个都不动；这里只回答「这个码是哪个模块的」。
 * 按前缀归属，最长的前缀说了算；哪个前缀都不匹配的归该端的基础模块。
 * 归属是静态的、与装配无关 —— 一个模块没装配时，它的码不能因此被算到基础模块头上。
 */
public final class ModuleOwnership {

    /** 商家端的基础模块：零售。 */
    public static final String RETAIL = "retail";
    /** 运营端的基础模块：平台。 */
    public static final String PLATFORM = "platform";
    public static final String PAY = "pay";
    public static final String INVENTORY = "inventory";
    public static final String SERVICE = "service";
    public static final String PRINT = "print";
    public static final String COMMERCE = "commerce";
    public static final String ELEC = "elec";
    public static final String JOB = "job";

    /** 前缀 → 模块。带冒号结尾的是「这一段下面的全部」，不带的是这一个码及其子码。 */
    private static final Map<Realm, Map<String, String>> PREFIXES = Map.of(
            Realm.MERCHANT, Map.of(
                    "mch:pay:", PAY, "pay:", PAY,
                    "biz:print:", PRINT,
                    "biz:booking", SERVICE, "biz:service", SERVICE),
            Realm.OPERATOR, Map.of(
                    "pay:", PAY, "ops:pay:", PAY,
                    "inventory:", INVENTORY,
                    "commerce:", COMMERCE,
                    "elec:", ELEC,
                    "system:job:", JOB));

    private ModuleOwnership() {
    }

    /** 这个码归哪个模块。 */
    public static String moduleOf(Realm realm, String code) {
        Optional<String> longest = PREFIXES.getOrDefault(realm, Map.of()).keySet().stream()
                .filter(prefix -> code.equals(prefix) || code.startsWith(prefix.endsWith(":") ? prefix : prefix + ":"))
                .max(Comparator.comparingInt(String::length));
        return longest.map(prefix -> PREFIXES.get(realm).get(prefix))
                .orElse(realm == Realm.MERCHANT ? RETAIL : PLATFORM);
    }

    /** 一个模块在两端的全部功能点，取自两个常量类。 */
    public static List<FunctionPoint> pointsOf(String module) {
        List<FunctionPoint> out = new ArrayList<>();
        for (Realm realm : List.of(Realm.MERCHANT, Realm.OPERATOR)) {
            for (String code : codes(realm)) {
                if (module.equals(moduleOf(realm, code))) {
                    out.add(FunctionPoint.action(code, realm));
                }
            }
        }
        return out;
    }

    /** 一端的全部权限码：常量类里带冒号的字符串常量（角色名不带冒号，不是权限码）。 */
    public static TreeSet<String> codes(Realm realm) {
        TreeSet<String> out = new TreeSet<>();
        for (Field field : (realm == Realm.MERCHANT ? BizPerms.class : Perms.class).getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != String.class || !Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers)) {
                continue;
            }
            try {
                String value = (String) field.get(null);
                if (value != null && value.contains(":") && !value.contains("*")) {
                    out.add(value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }
}
