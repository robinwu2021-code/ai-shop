package ai.neargo.shop.gateway.core.end;

import ai.neargo.shop.auth.Realm;
import org.springframework.core.env.Environment;

/**
 * 一端的会话用哪种存储。键是现有的两个，这里只是把读法收成一处：
 * {@code shop.auth.token-store-by-realm.<端>} 优先，其次全局的 {@code shop.auth.token-store}，都没配是 memory。
 */
public final class SessionStoreKinds {

    public static final String GLOBAL = "shop.auth.token-store";
    public static final String BY_REALM = "shop.auth.token-store-by-realm.";
    public static final String DB = "db";
    public static final String REDIS = "redis";

    private SessionStoreKinds() {
    }

    public static String kindOf(Environment env, Realm realm) {
        String per = env.getProperty(BY_REALM + realm.name().toLowerCase());
        if (per != null && !per.isBlank()) {
            return per.trim();
        }
        return env.getProperty(GLOBAL, "memory").trim();
    }

    /** 端名（mp / biz / ops）对应的 Realm。 */
    public static Realm realmOf(String end) {
        return switch (end) {
            case "mp" -> Realm.CONSUMER;
            case "biz" -> Realm.MERCHANT;
            case "ops" -> Realm.OPERATOR;
            default -> throw new IllegalArgumentException("不是三端之一：" + end);
        };
    }

    public static String endOf(Realm realm) {
        return switch (realm) {
            case CONSUMER -> "mp";
            case MERCHANT -> "biz";
            case OPERATOR -> "ops";
        };
    }

    /** 这一端的会话用 Redis，且配了自己的 Redis。只读环境变量，装配条件里也能用。 */
    public static boolean hasDedicatedRedis(Environment env, Realm realm) {
        String host = env.getProperty("shop.gateway." + endOf(realm) + ".redis.host");
        return REDIS.equals(kindOf(env, realm)) && host != null && !host.isBlank();
    }
}
