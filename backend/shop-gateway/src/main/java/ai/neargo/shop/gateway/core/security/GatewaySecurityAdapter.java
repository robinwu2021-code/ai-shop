package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.gateway.core.end.GatewayEndSpec;

/**
 * 把一端的认证链装进某个安全框架（TDD-网关层与统一权限 §2.14 的接缝一）。
 *
 * <p>目前只有 Spring Security 一个实现；留接口不预写第二个（用户 2026-11-18 确认）。
 *
 * @param <B> 框架给的构建入口
 * @param <C> 装好的链
 */
public interface GatewaySecurityAdapter<B, C> {

    C install(B builder, GatewayEndSpec spec) throws Exception;
}
