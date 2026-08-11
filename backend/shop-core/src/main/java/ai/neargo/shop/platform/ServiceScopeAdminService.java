package ai.neargo.shop.platform;

import java.util.List;

/**
 * 运营视图的经营范围：<b>三档全量 + 影响面计数</b>。
 *
 * <p>与 {@link ServiceScopeService} 分开，理由和
 * {@code AuthCodeAdminService} 与 {@code MerchantAuthCodeService} 那一对完全一样：
 *
 * <ul>
 *   <li>写入路径要的是「这一档能不能用」，一次参数读取，每次保存门店都会走</li>
 *   <li>运营页要的是「三档各有多少家店在用」，三次 count，一天点开几次</li>
 * </ul>
 *
 * <p>合成一个 Service 的话，除了把三次 count 挂到写入路径上，更要命的是
 * 它会让 platform 反向依赖 merchant，而 merchant 的门店服务又依赖 platform ——
 * 一个 Spring 起不来的环。
 */
public interface ServiceScopeAdminService {

    /** 三档全量，带启用状态与在用商家数。 */
    List<ServiceScopeVO> list();

    /** 开关后返回最新的三档全量（页面直接拿它重绘，不用再请求一次）。 */
    List<ServiceScopeVO> setEnabled(String scope, boolean enabled, String reason);

    /**
     * @param scope         档位码
     * @param enabled       这一期是否开放
     * @param merchantCount 当前在用的商家数 —— 关之前要知道影响面。
     *                      不带计数的开关是<b>盲操作</b>：关掉一个 300 家店在用的档，
     *                      和关掉一个没人用的，界面上看起来完全一样
     */
    record ServiceScopeVO(String scope, boolean enabled, long merchantCount) {
    }
}
