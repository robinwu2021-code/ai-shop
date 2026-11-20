package ai.neargo.shop.gateway.core.function;

import java.util.List;

/**
 * 一个功能点在下游微服务里对应什么（TDD-网关层与统一权限 §2.6）。
 *
 * @param service 服务名
 * @param points  下游的功能点码
 */
public record Downstream(String service, List<String> points) {
}
