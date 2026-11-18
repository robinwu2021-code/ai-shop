package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.gateway.core.resource.ApiResource;

/**
 * 这条链接此人能不能访问（TDD-网关层与统一权限 §2.14 的接缝二）。与安全框架无关：
 * 想把判定交给别的权限引擎，实现它即可，管线与索引不动。
 */
public interface AccessDecider {

    /** @param user 已登录的人；未登录的请求不会走到这里，由认证链回 401 */
    Decision decide(LoginUser user, ApiResource resource);
}
