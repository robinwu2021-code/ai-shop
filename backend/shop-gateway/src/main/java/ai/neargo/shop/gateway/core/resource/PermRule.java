package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 权限码的与或式，如 {@code (A 或 B 或 C) 且 D}（TDD-网关层与统一权限 §2.13）。
 *
 * <p>叶子是一个权限码；{@link Kind#ALL} 要求子式全部成立，{@link Kind#ANY} 要求任一成立。
 */
public record PermRule(Kind kind, String code, List<PermRule> children) {

    /** 节点种类。 */
    public enum Kind { CODE, ALL, ANY }

    public static PermRule code(String code) {
        return new PermRule(Kind.CODE, code, List.of());
    }

    public static PermRule all(List<PermRule> children) {
        return new PermRule(Kind.ALL, null, List.copyOf(children));
    }

    public static PermRule any(List<PermRule> children) {
        return new PermRule(Kind.ANY, null, List.copyOf(children));
    }

    /** {@code has} 回答「此人有没有这个权限码」。 */
    public boolean test(Predicate<String> has) {
        return switch (kind) {
            case CODE -> has.test(code);
            case ALL -> children.stream().allMatch(child -> child.test(has));
            case ANY -> children.stream().anyMatch(child -> child.test(has));
        };
    }

    /** 式子里出现的全部权限码，按出现顺序。 */
    public List<String> codes() {
        List<String> out = new ArrayList<>();
        collect(out);
        return out;
    }

    private void collect(List<String> out) {
        if (kind == Kind.CODE) {
            out.add(code);
        } else {
            children.forEach(child -> child.collect(out));
        }
    }

    @Override
    public String toString() {
        return switch (kind) {
            case CODE -> code;
            case ALL -> "(" + String.join(" 且 ", children.stream().map(PermRule::toString).toList()) + ")";
            case ANY -> "(" + String.join(" 或 ", children.stream().map(PermRule::toString).toList()) + ")";
        };
    }
}
