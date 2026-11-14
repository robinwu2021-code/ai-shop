package ai.neargo.shop.platform.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** App 版本策略的对外结构（TDD-商户App问题回报与版本 §2 接口三、运营端配套）。 */
public final class AppVersionVOs {

    private AppVersionVOs() {
    }

    /**
     * {@code GET /common/app/version} 的响应。没配置的平台：{@code latest}/{@code minSupported} 为空串、
     * {@code notes} 空数组、{@code storeUrl} 与 {@code upgradeRequired} 为 null —— App 据此不提示。
     *
     * @param platform        归一后的平台 ANDROID / IOS
     * @param notes           按 {@code Accept-Language} 取的那一种语言；没填回落 zh-Hant
     * @param upgradeRequired 只在请求带了 {@code build} 时有值：{@code build < minSupported}。
     *                        App 应以它为准（10426 拒写用的是同一个比较）
     */
    public record AppVersionVO(String platform, String latest, String minSupported, List<String> notes,
                               String storeUrl, Boolean upgradeRequired) {
    }

    /**
     * 运营端一行策略。没配过的平台也列出来：{@code revision=0}、字段为空。
     *
     * @param notes     语言 → 条目（zh-Hant / zh-Hans / en / ar）
     * @param updatedAt 毫秒；没配过为 null
     * @param revision  乐观锁版本，改的时候原样带回 {@code expectedRevision}
     */
    public record OpsAppVersionVO(String appCode, String platform, String latest, String minSupported,
                                  Map<String, List<String>> notes, String storeUrl, String updatedBy,
                                  Long updatedAt, long revision) {
    }

    /**
     * 运营端改一行策略。
     *
     * @param latest           空 = 不提示更新
     * @param minSupported     空 = 不拦写；非空时必须 ≤ latest
     * @param notes            zh-Hant 必填（latest 非空时），其它语言可空、回落 zh-Hant
     * @param storeUrl         只收 {@code https://apps.apple.com/…} 或 {@code https://play.google.com/…}
     * @param expectedRevision 列表里读到的 revision；没配过的平台传 0
     */
    public record SaveAppVersionReq(String latest, String minSupported, Map<String, List<String>> notes,
                                    String storeUrl, @NotNull Long expectedRevision) {
    }

    /** 闸门拒写时带回给 App 的两样东西：最低支持版与去哪升级。 */
    public record UpgradeRequirement(String minSupported, String storeUrl) {
    }
}
