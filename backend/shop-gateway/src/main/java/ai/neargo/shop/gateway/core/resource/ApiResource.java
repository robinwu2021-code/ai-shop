package ai.neargo.shop.gateway.core.resource;

import ai.neargo.shop.gateway.core.end.Access;

/**
 * 一条链接：在哪、要什么权限（TDD-网关层与统一权限 §2.13）。
 *
 * @param method   HTTP 方法；{@code *} 表示不限
 * @param pattern  地址模式，如 {@code /ops/payments/refunds/{payRefundNo}/review}
 * @param location {@link #LOCAL}，或目标服务名
 * @param rule     {@link Access#POINT} 时的权限式；其余为 null
 * @param handler  本进程入口的类与方法，排查用
 * @param targetPath 微服务链接的内部地址模板，路径变量与 {@code pattern} 同名；本进程入口为 null
 * @param basis    没有权限式时，这个类别是凭什么定的（给人读，如入口上原来的表达式）；说不出来为 null
 */
public record ApiResource(String method, String pattern, String location, Access access, PermRule rule,
                          String handler, String targetPath, String basis) {

    public static final String LOCAL = "LOCAL";

    /** 本进程入口：没有内部地址。 */
    public ApiResource(String method, String pattern, String location, Access access, PermRule rule,
                       String handler) {
        this(method, pattern, location, access, rule, handler, null, null);
    }

    /** 微服务链接：没有「凭什么」可说。 */
    public ApiResource(String method, String pattern, String location, Access access, PermRule rule,
                       String handler, String targetPath) {
        this(method, pattern, location, access, rule, handler, targetPath, null);
    }

    /** 本进程入口，类别不是从权限式来的，带上依据。 */
    public static ApiResource local(String method, String pattern, Access access, String handler, String basis) {
        return new ApiResource(method, pattern, LOCAL, access, null, handler, null, basis);
    }

    /** 落在微服务上，要由网关转发。 */
    public boolean remote() {
        return !LOCAL.equals(location);
    }
}
