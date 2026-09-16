package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * 建<b>平台自营商家</b>的特殊入口。
 *
 * <h2>为什么要绕开进件</h2>
 *
 * <p>现在建商家只有一条路：C 端用户交营业执照 → 运营端审核 → 通过后建主体。
 * 让平台走这条路，等于<b>平台向自己提交执照、再由自己审自己</b> ——
 * 与售后链路上曾经那处「自营单派给商家、而那个商家就是平台」是同一个形状。
 *
 * <p>进件资料的全部意义是「核验那个第三方是谁、有没有资格经营」。
 * 自营场景下不存在第三方主体，这个问题<b>不成立</b>，不是被豁免了
 * （ADR-017 §3.4 的四个条件约束的是<b>代销</b>，自营不是代销）。
 *
 * <h2>哪几条不能省</h2>
 *
 * <ul>
 *   <li><b>覆盖社区</b>：ADR-009 那条对谁都成立。没有覆盖社区的商家上着架却对谁都不可见，
 *       而这个故障没有任何报错 —— 所以这里和审核那条路一样硬拒。</li>
 *   <li><b>legal_form = ENTERPRISE</b>：它是税务口径（V87 三分），决定能不能开票。
 *       平台公司本身就是企业，如实填，不是走过场。</li>
 *   <li><b>建主体的三件事同一事务</b>：成员行 / 默认门店 / 可达范围 / 分账主体。
 *       所以这里调 {@code MerchantAdminPort#activate}，<b>不自己拼一套</b> ——
 *       否则那条保证会在新路径上悄悄丢掉，且不报错。</li>
 * </ul>
 *
 * @see ai.neargo.shop.spi.user.MerchantAdminPort#activate
 */
public interface SelfOperatedService {

    /**
     * @param phone        店主手机号（11 位）。<b>没有账号就按登录那条路建一个</b>
     *                     （{@code UserProvisionPort}），所以不需要本人先去 App 收验证码
     * @param name         主体名称（同时作为默认门店名）
     * @param communityNos 覆盖社区，<b>不得为空</b> —— 见上文
     * @param industry     行业码（{@code sys_industry.industry}），可空
     * @param description  店铺简介，可空；C 端门店页读的就是它
     */
    record CreateCommand(String phone, String name, List<String> communityNos,
                         String industry, String description) {
    }

    /**
     * @param created 本次是否<b>真的新建</b>了主体。false = 这个手机号名下已经有主体，
     *                原样返回它。两者要在界面上分开说：运营连点两次时，
     *                「又建了一个」和「就是刚才那个」是完全不同的事实
     * @param fundsMode    回读值，不是入参回显。判据要来自库
     * @param businessMode 默认门店的经营模式，同样是回读值
     */
    record ResultVO(String merchantNo, String storeNo, String ownerUserNo,
                    String fundsMode, String businessMode, boolean created) {
    }

    ResultVO create(CreateCommand cmd, String operatorNo);
}
