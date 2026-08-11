package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * 平台端 · 授权码<b>字典</b>维护。
 *
 * <p>与 {@link MerchantAuthCodeService} 是两件事，刻意分开：
 *
 * <ul>
 *   <li>这里维护的是<b>「有哪些经营能力、各要什么证」</b> —— 门槛的定义</li>
 *   <li>那边做的是<b>「给这家店发哪几张证」</b> —— 门槛的发证</li>
 * </ul>
 *
 * <p>混成一个 Service 的话，两个受众（商品运营定义门槛 / BD 给商家发证）会共用同一套权限，
 * 而 BD 本不该有权改「一共有哪些门槛」。
 *
 * <p><b>为什么必须有它</b>：在此之前授权码只能靠迁移增删。一期收敛要加
 * {@code PACKAGED_FOOD}、停 {@code SERVICE_REPAIR}，全得改代码发版 ——
 * 而一期之后还要按同样的方式放开，等于把「平台升级」永久绑定在工程排期上。
 */
public interface AuthCodeAdminService {

    /**
     * <b>全量，含已停用</b>。运营视图与「给商家授权时的可选项」
     * （{@link MerchantAuthCodeService#listCodes()}，只给启用的）是两个口径，
     * 不能合并：前者要能看见并恢复停用的码，后者绝不能把停用的码发出去。
     */
    List<AuthCodeAdminVO> list();

    /** 新建或更新（按 {@code code} 判定）。{@code code} 本身不可改 —— 改它等于换一张证。 */
    AuthCodeAdminVO save(SaveCommand cmd);

    /**
     * 启停。
     *
     * @param reason 必填 —— 它决定一批商家还能不能上新品，事后要能查到依据
     * @throws ai.neargo.shop.common.BizException 停用时仍有在用的类目引用它
     */
    AuthCodeAdminVO setEnabled(String code, boolean enabled, String reason);

    /**
     * @param code                  授权码，如 {@code FRESH_VEG}
     * @param name                  展示名，运营授权时看到的就是它
     * @param requiredQualification 需要的资质证件名。空 = 无证件要求
     * @param sort                  排序
     * @param enabled               是否可发放
     * @param merchantCount         持有该码的商家数 —— 停用前要知道影响面
     * @param categoryCount         引用该码的<b>在用</b>类目数。&gt; 0 时不允许停用
     */
    record AuthCodeAdminVO(String code, String name, String requiredQualification,
                           int sort, boolean enabled,
                           long merchantCount, long categoryCount) {
    }

    record SaveCommand(String code, String name, String requiredQualification, Integer sort) {
    }
}
