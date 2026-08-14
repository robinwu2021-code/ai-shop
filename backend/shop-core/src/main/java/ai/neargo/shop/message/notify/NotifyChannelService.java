package ai.neargo.shop.message.notify;

import java.util.List;

/**
 * 通道体检（P-14.1 / TDD-运营端触达中心 §4.1）。
 *
 * <p><b>它回答的唯一问题是「这条通道现在能不能用」</b> —— 开没开、凭据齐不齐、
 * 今天发了多少、失败几条。此前这些只能登服务器看环境变量与日志。
 *
 * <p><b>密钥明文永不出后端</b>：凭据项只回传 {@code present}（配了没有）。
 * 一个能在 Web 上读出生产短信密钥的接口，泄漏一次就是全平台可群发 ——
 * 这条边界与 {@code application.yml} 顶上「密钥一律走环境变量」是同一条。
 */
public interface NotifyChannelService {

    List<ChannelHealth> health();

    /** 当前生效的微信模板号（运营配置优先，回落环境变量）。 */
    String templateIdOf(String scene);

    /**
     * 保存微信模板号映射。**空值表示「清掉覆盖，回落环境变量」**，不是「设成空」——
     * 后者会让通道以为没配模板而静默不发，且页面上看着像配好了。
     */
    void saveWxTemplates(String orderArrived, String refunded, String operatorNo);

    /**
     * 平台默认语言（{@link ai.neargo.shop.spi.notify.MailTemplatePort#DEFAULT_LANG_SETTING_KEY}）。
     *
     * <p><b>只在收件人语言未知时用</b>。没配过、或配了个不认识的值，都回落 {@code zh-CN} ——
     * 一个拼错的语言码不该让账号邮件发不出去。
     */
    String defaultLang();

    /** 保存平台默认语言。**只接受 {@link #SUPPORTED_LANGS} 里的值**，其余拒绝。 */
    void saveDefaultLang(String lang, String operatorNo);

    /**
     * 平台支持的语言。**与端上的三语一致** —— 这里多一个的话，
     * 运营能选到一个模板永远不会有译文的语言，然后每封信都在回落。
     */
    java.util.List<String> SUPPORTED_LANGS = java.util.List.of("zh-CN", "en", "ar");

    /**
     * @param channel     {@code SysNotifyLog} 的四个通道常量之一
     * @param stub        当前是否走桩（桩=不真发）
     * @param enabled     真实通道是否已启用（{@code !stub}）
     * @param credentials 凭据体检。**只有配没配，没有值**
     * @param params      非密业务参数，可回显（模板号、endpoint 这类本就印在短信里的东西）
     * @param todaySent   今日成功条数
     * @param todayFailed 今日失败条数
     */
    record ChannelHealth(String channel, boolean stub, boolean enabled,
                         List<Credential> credentials, List<Param> params,
                         long todaySent, long todayFailed) {
    }

    /**
     * @param envVar   环境变量名。**运维要照着它去配**，所以必须给准
     * @param present  已配置（非空）
     * @param required 缺它这条通道就起不来／发不出
     */
    record Credential(String envVar, boolean present, boolean required) {
    }

    record Param(String key, String value) {
    }
}
