package ai.neargo.shop.gateway.core.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 一端开没开，只在这里判，装配条件与端基类都问它。
 *
 * <p><b>为什么不是一个布尔</b>：端关着有两种情形，后果不同。
 * <ul>
 *   <li>{@link State#ABSENT}：部署面没带这一端（比如运营端部署里的 C 端）。它的入口根本没装配，
 *       打过去是 Spring MVC 的 404，不需要认证链 —— 与拆分前按 {@code @Profile} 装链的结果一致。</li>
 *   <li>{@link State#CLOSED}：部署面带了这一端、入口都在，但配置里把它关了。这时<b>不能</b>只是不装认证链：
 *       没有链匹配的请求会绕过认证直接落到入口上。所以装一条只回 404 的链把前缀封住。</li>
 * </ul>
 *
 * <p><b>为什么保留按部署面判</b>：有一批切片测试只设 {@code setActiveProfiles("api")}、不加载
 * {@code application.yml}，在它们那里 {@code shop.gateway.*} 一个键都没有。开关不配时落回部署面，
 * 这些测试与所有现有部署的行为都不变。
 */
public final class GatewayEndSwitch {

    /** 一端的装配状态。 */
    public enum State { ON, CLOSED, ABSENT }

    private GatewayEndSwitch() {
    }

    public static State resolve(Environment environment, String end, String... legacyProfiles) {
        boolean deployed = legacyProfiles.length > 0 && environment.acceptsProfiles(Profiles.of(legacyProfiles));
        Boolean explicit = environment.getProperty("shop.gateway." + end + ".enabled", Boolean.class);
        if (explicit == null) {
            return deployed ? State.ON : State.ABSENT;
        }
        if (explicit) {
            return State.ON;
        }
        return deployed ? State.CLOSED : State.ABSENT;
    }
}
