package ai.neargo.shop.gateway.core.config;

import java.util.List;

/**
 * 一端的配置（{@code shop.gateway.mp} / {@code .biz} / {@code .ops}）。
 *
 * <p>两项都可以不配：{@code enabled} 不配时沿用部署面（{@code api} 等于开 C 端与 B 端，{@code ops} 等于开运营端），
 * {@code publicPaths} 不配时用各端代码里的默认清单。{@code redis} 与 {@code datasource} 不配时本端没有自己的那一份。
 * 会话用哪种存储仍由现有的 {@code shop.auth.token-store} 与 {@code shop.auth.token-store-by-realm.*} 决定，这里不重复。
 */
public class GatewayEndProperties {

    /** 显式开关。不配 = 跟着部署面走；配成 false 而该端的入口仍被装配时，该端前缀下一律 404。 */
    private Boolean enabled;

    /** 不用登录就能访问的地址。不配 = 用该端的默认清单。 */
    private List<String> publicPaths;

    /** 本端自己的 Redis。不配主机 = 没有，会话落回共用的那一份。 */
    private Redis redis = new Redis();

    /** 本端自己的数据源。不配地址 = 没有，会话表与权限表落回主库。 */
    private Datasource datasource = new Datasource();

    /**
     * 提前判权的档位：{@code off}（默认）不做；{@code observe} 只记日志、照常放行；{@code enforce} 拦截。
     * 目前只有运营端实现了判定。
     */
    private String precheck = "off";

    public String getPrecheck() {
        return precheck;
    }

    public void setPrecheck(String precheck) {
        this.precheck = precheck;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public void setDatasource(Datasource datasource) {
        this.datasource = datasource;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    /** 一端自己的 Redis 连接。 */
    public static class Redis {
        private String host;
        private int port = 6379;
        private int database = 0;
        private String password;
        /** 会话键前缀。不配时用 {@code gw:<端名>:}。 */
        private String keyPrefix;
        /** 单条命令的超时。短一点：一端的 Redis 挂了，该端的请求应当很快失败而不是挂住线程。 */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(2);

        public boolean configured() {
            return host != null && !host.isBlank();
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public java.time.Duration getTimeout() { return timeout; }
        public void setTimeout(java.time.Duration timeout) { this.timeout = timeout; }
    }

    /** 一端自己的数据源。 */
    public static class Datasource {
        private String url;
        private String username;
        private String password;
        private int maxPoolSize = 8;

        public boolean configured() {
            return url != null && !url.isBlank();
        }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    }
}
