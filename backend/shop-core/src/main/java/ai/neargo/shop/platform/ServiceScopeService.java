package ai.neargo.shop.platform;

import java.util.Set;

/**
 * 经营范围（ADR-009 三档）的<b>启用白名单</b>。
 *
 * <p>档位本身是枚举（{@code ServiceScopes}），永远是那三个，运营改不了；
 * 这里管的是<b>「这一期开放哪几档」</b>，存在 {@code sys_setting} 的
 * {@code merchant.service-scope-enabled} 里。
 *
 * <p><b>为什么值域与启用要分开</b>：合成一件事的话，运营在后台放开某一档时
 * 会顺手获得「往这个字段写任意字符串」的能力 —— 而那正是本次修掉的 D1。
 *
 * <p><b>这里刻意不带商家计数</b>。带上的话依赖链是
 * {@code MerchantStoreService → MasterDataPort → MasterDataService → 这里 → MerchantQueryPort
 * → MerchantPortImpl → MerchantStoreService}，一个真实存在的环 —— Spring 直接起不来。
 * 运营视图要的计数在 {@link ServiceScopeAdminService}：它是叶子，谁也不依赖它。
 */
public interface ServiceScopeService {

    /**
     * 当前开放的档位。
     *
     * <p>每次保存门店都要问一次，所以这里只读一行参数，不做任何统计。
     */
    Set<String> enabledScopes();

    /**
     * 开关某一档。
     *
     * @param reason 必填 —— 关掉一档等于把一类商家挡在门外，事后要能查到依据
     * @throws ai.neargo.shop.common.BizException 档位不存在，或这一关会把白名单清空
     */
    void setEnabled(String scope, boolean enabled, String reason);
}
