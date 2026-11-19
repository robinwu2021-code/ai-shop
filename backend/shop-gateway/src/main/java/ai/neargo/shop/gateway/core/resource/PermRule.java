package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 权限码的与或式，如 {@code (A 或 B 或 C) 且 D}（TDD-网关层与统一权限 §2.13）。
 *
 * <p>叶子是一个权限码；{@link Kind#ALL} 要求子式全部成立，{@link Kind#ANY} 要求任一成立。
 * {@link Kind#SCOPED} 是带范围的权限码：码不是在当前门店的角色里找，而是在一个管理空间里、对某类资源持有
 * （TDD-管理范围权限搬到网关-权限码方案）。
 *
 * @param scopeType  {@link Kind#SCOPED} 时的资源类型（SPACE / BRAND / STORE / ENTITY …）；其余为 null
 * @param scopeParam {@link Kind#SCOPED} 时资源号取自请求上的哪个参数（路径变量或查询参数的名字）；
 *                   为 null 表示「空间内对任一该类型资源持有即可」
 */
public record PermRule(Kind kind, String code, List<PermRule> children, String scopeType, String scopeParam) {

    /** 节点种类。 */
    public enum Kind { CODE, ALL, ANY, SCOPED }

    public PermRule(Kind kind, String code, List<PermRule> children) {
        this(kind, code, children, null, null);
    }

    public static PermRule code(String code) {
        return new PermRule(Kind.CODE, code, List.of());
    }

    public static PermRule all(List<PermRule> children) {
        return new PermRule(Kind.ALL, null, List.copyOf(children));
    }

    public static PermRule any(List<PermRule> children) {
        return new PermRule(Kind.ANY, null, List.copyOf(children));
    }

    /** 带范围的权限码；{@code scopeParam} 为 null 即「任一资源」。 */
    public static PermRule scoped(String code, String scopeType, String scopeParam) {
        return new PermRule(Kind.SCOPED, code, List.of(), scopeType, scopeParam);
    }

    /** {@code has} 回答「此人有没有这个权限码」。带范围的码这里一律算没有：不知道范围就不能说有。 */
    public boolean test(Predicate<String> has) {
        return test(has, node -> false);
    }

    /** {@code scoped} 回答「此人在当前请求的管理空间里，满不满足这个带范围的码」。 */
    public boolean test(Predicate<String> has, Predicate<PermRule> scoped) {
        return switch (kind) {
            case CODE -> has.test(code);
            case SCOPED -> scoped.test(this);
            case ALL -> children.stream().allMatch(child -> child.test(has, scoped));
            case ANY -> children.stream().anyMatch(child -> child.test(has, scoped));
        };
    }

    /** 式子里有没有带范围的码。 */
    public boolean hasScoped() {
        return kind == Kind.SCOPED || children.stream().anyMatch(PermRule::hasScoped);
    }

    /** 式子里所有的叶子（普通码与带范围的码），按出现顺序。 */
    public List<PermRule> leaves() {
        List<PermRule> out = new ArrayList<>();
        if (kind == Kind.CODE || kind == Kind.SCOPED) {
            out.add(this);
        } else {
            children.forEach(child -> out.addAll(child.leaves()));
        }
        return out;
    }

    /** 式子里出现的全部权限码，按出现顺序。 */
    public List<String> codes() {
        List<String> out = new ArrayList<>();
        collect(out);
        return out;
    }

    private void collect(List<String> out) {
        if (kind == Kind.CODE || kind == Kind.SCOPED) {
            out.add(code);
        } else {
            children.forEach(child -> child.collect(out));
        }
    }

    @Override
    public String toString() {
        return switch (kind) {
            case CODE -> code;
            case SCOPED -> code + " @" + scopeType + (scopeParam == null ? "(任一)" : "(" + scopeParam + ")");
            case ALL -> "(" + String.join(" 且 ", children.stream().map(PermRule::toString).toList()) + ")";
            case ANY -> "(" + String.join(" 或 ", children.stream().map(PermRule::toString).toList()) + ")";
        };
    }
}
