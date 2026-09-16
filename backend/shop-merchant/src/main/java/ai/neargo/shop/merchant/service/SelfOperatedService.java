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
     * @param serviceScope COMMUNITY / CITY / PLATFORM（ADR-009）。空按 COMMUNITY。
     *                     取值先过<b>一期启用白名单</b>（{@code merchant.service-scope-enabled}）——
     *                     绕过去就能写进一个平台没开放的档，而表现是这家店按范围查时被静默漏掉
     * @param communityNos 覆盖社区。<b>只有 scope=COMMUNITY 时必填</b> —— 与
     *                     {@code activate} 自己那条规则同口径，不另立一套
     * @param industry     行业码（{@code sys_industry.industry}），可空
     * @param description  店铺简介，可空；C 端门店页读的就是它
     */
    record CreateCommand(String phone, String name, String serviceScope,
                         List<String> communityNos, String industry, String description) {

        /** 老形状：不传范围 = 按社区。留着是因为已有调用点只建社区档 */
        public CreateCommand(String phone, String name, List<String> communityNos,
                             String industry, String description) {
            this(phone, name, null, communityNos, industry, description);
        }
    }

    /**
     * @param created 本次是否<b>真的新建</b>了主体。false = 这个手机号名下已经有主体，
     *                原样返回它。两者要在界面上分开说：运营连点两次时，
     *                「又建了一个」和「就是刚才那个」是完全不同的事实
     * @param fundsMode    回读值，不是入参回显。判据要来自库
     * @param businessMode 默认门店的经营模式，同样是回读值
     * @param serviceScope 回读值
     * @param selfOperated 回读值，应为 true。它是<b>免证件与运营建店的唯一判据</b> ——
     *        {@code fundsMode} 认不出平台自己（归集同时盖着代销），
     *        {@code businessMode} 也认不出（门店级，且默认值就是自营）
     * @param reachableCommunities <b>这家店现在对多少个小区可见。</b>
     *
     *        <p>它是本入口最要紧的一个返回值，而不是装饰。ADR-009 那条约束防的是
     *        「商家上着架却对谁都不可见，且没有任何报错」—— 而<b>光有覆盖范围不够</b>：
     *        可见性最终一律展开成小区号，库里一个小区都没有时，
     *        CITY 档同样是 0（区划表里有深圳，不代表深圳有小区）。
     *
     *        <p>所以这里把真实数字回读出来：建完是 0，就是「建好了，但现在谁也看不到」，
     *        运营当场知道下一步要去提报小区，而不是等一个月后问「为什么一单都没有」。
     */
    record ResultVO(String merchantNo, String storeNo, String ownerUserNo,
                    String fundsMode, String businessMode, String serviceScope,
                    boolean created, int reachableCommunities, boolean selfOperated) {
    }

    ResultVO create(CreateCommand cmd, String operatorNo);

    /**
     * 给<b>平台自营主体</b>再开一家门店。
     *
     * <p>第三方商家自己在 B 端开店（{@code POST /biz/store/create}），而平台自己的店
     * 没有「商家」去点那个按钮 —— 与建主体是同一个形状的缺口，所以开在运营端。
     *
     * <p><b>只对 {@code self_operated=1} 的主体开放。</b> 否则运营就能绕过商家、
     * 替第三方开店并吃掉他买的订阅额度，而商家那边看不到是谁开的。
     *
     * <p><b>不走订阅额度</b>：额度是卖给商家的商品，平台自己的店不该被自己的定价限制。
     * <b>也不需要进件</b>：自营门店按自营结算（钱先进平台户），
     * 「第三方模式要有二级商户号」那条硬前提对它不成立 —— 见
     * {@code MerchantGovernService#setBusinessMode} 里的同一段理由。
     *
     * @param categoryNos 这家店的货架（经营类目）。空 = 复制默认店的
     */
    record AddStoreCommand(String merchantNo, String name, String address,
                           List<String> categoryNos) {
    }

    /**
     * @param businessMode 回读值，应为 {@code SELF_OPERATED}
     * @param payMerchantNo 收款号。<b>为空是正常的</b> —— 自营门店不进件，
     *        这一列空着不代表这家店有问题（第三方模式下它为空才是硬阻塞）
     */
    record StoreVO(String storeNo, String merchantNo, String name, String address,
                   String businessMode, String payMerchantNo) {
    }

    StoreVO addStore(AddStoreCommand cmd, String operatorNo);
}
