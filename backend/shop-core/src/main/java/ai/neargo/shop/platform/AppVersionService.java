package ai.neargo.shop.platform;

import ai.neargo.shop.platform.dto.AppVersionVOs.AppVersionVO;
import ai.neargo.shop.platform.dto.AppVersionVOs.OpsAppVersionVO;
import ai.neargo.shop.platform.dto.AppVersionVOs.SaveAppVersionReq;
import ai.neargo.shop.platform.dto.AppVersionVOs.UpgradeRequirement;
import java.util.List;
import java.util.Optional;

/**
 * App 版本策略（TDD-商户App问题回报与版本 V2–V7）：匿名读、运营端改、商户链上的旧版本拒写。
 *
 * <p>三个读者口径不同：版本检查接口每次读库（匿名、低频、要即时）；拒写闸门在每个写请求上判，
 * 读<b>本机 60 秒缓存</b>，运营端改了就清本机缓存（多实例部署时别的实例最多晚 60 秒生效）。
 */
public interface AppVersionService {

    /** 首期唯一的 App。POS 安卓 App 以后要强更时加一行数据。 */
    String MERCHANT = "MERCHANT";

    /**
     * 版本检查。
     *
     * @param platform       android / ios，不分大小写；缺失或不认得 → 10400
     * @param build          App 当前版本，可空；带了才回 {@code upgradeRequired}
     * @param acceptLanguage 原样的 {@code Accept-Language} 头，可空
     */
    AppVersionVO current(String platform, String build, String acceptLanguage);

    /**
     * 拒写判定：{@code X-App-Platform} 与 {@code X-App-Version} 都带了、平台配了最低支持版、且版本低于它 → 有值。
     * 任一头缺失、平台不认得、版本号取不出前缀、没配最低版，都放行（空）—— 拦了就是对不带头的老客户端全量误伤。
     */
    Optional<UpgradeRequirement> blockingUpgrade(String platformHeader, String versionHeader);

    /** 运营端：每个 App 的两个平台各一行，没配过的也列出来。 */
    List<OpsAppVersionVO> list();

    /**
     * 运营端改一行策略并写审计。{@code minSupported > latest}、版本号不合法、商店地址不是两家商店的 https 地址、
     * zh-Hant 说明缺失 → 10400；{@code expectedRevision} 对不上 → 10409。
     */
    OpsAppVersionVO save(String appCode, String platform, SaveAppVersionReq req, String operatorNo);
}
