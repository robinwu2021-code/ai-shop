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
 */
public record ApiResource(String method, String pattern, String location, Access access, PermRule rule,
                          String handler) {

    public static final String LOCAL = "LOCAL";
}
