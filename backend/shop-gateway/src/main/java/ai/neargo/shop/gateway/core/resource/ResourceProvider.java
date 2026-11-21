package ai.neargo.shop.gateway.core.resource;

import java.util.List;

/**
 * 链接从哪来。本进程入口的扫描是一种来源；库里录入的微服务链接（D2）、微服务清单接口（以后）是另外两种。
 * 加一种来源只加一个实现，索引与判定不动。
 */
public interface ResourceProvider {

    /** @param pathPrefixes 只要这些前缀下的链接 */
    List<ApiResource> load(List<String> pathPrefixes);
}
