package ai.neargo.shop.spi.user;

import java.util.List;

/**
 * platform → user：审核通过后创建/激活商家主体。
 *
 * <p><b>审核通过才创建</b>：驳回的申请不该在库里留下一个「僵尸商家」——
 * 那些记录会出现在商家列表、报表、分账接收方清单里，谁也说不清它算不算数。
 */
public interface MerchantAdminPort {

    /**
     * 激活商家。<b>建商家 + 配可达范围 + 建分账主体，三件事在一个事务里</b>。
     *
     * <p>为什么不拆成三次调用：拆开就意味着可以只做前一件。
     * 而只建商家不配范围的结果是 —— 商家审核通过、登录 B 端、上架商品，
     * 然后<b>一个订单都不来</b>，他和运营都查不出原因（ADR-009：
     * {@code service_scope} 默认 COMMUNITY，而一个社区都没覆盖 = 对谁都不可见）。
     * 这个故障没有任何报错，只有「生意没来」。
     *
     * @param cmd 激活参数
     * @return merchantNo
     */
    String activate(ActivateCommand cmd);

    /**
     * @param serviceScope   COMMUNITY / CITY / PLATFORM（ADR-009）
     * @param communityNos   scope=COMMUNITY 时<b>必须非空</b>，否则该商家对谁都不可见
     * @param settleAccountType 分账账户类型（ADR-002）。为空表示申请时没填，
     *                          通过后由商家在 B 端补 —— 但分账主体记录要先建出来占位，
     *                          否则第一笔订单来了才发现没有收款方
     */
    /**
     * @param industry    行业（{@code sys_industry.industry}）。
     *                    <b>决定这家店能不能以小微主体进件</b>（微信小微白名单按行业给），
     *                    也是 {@code points_forced} 默认值的来源。
     *                    此前申请单上存了行业却传不过来 —— 商家主体的行业永远是空的，
     *                    于是进件时才发现选错了主体
     * @param description 店铺简介。同样此前只存在申请单上：<b>C 端门店页读的是
     *                    {@code mch_entity.description}</b>，商家认真写的简介
     *                    通过审核后就消失了，而这不会报错，只是门店页少一段字
     */
    /**
     * @param activatedEntityNo 这份申请<b>之前是否已经激活过</b>：非空表示激活过，
     *                          本次是重复点击「通过」，按幂等重放到这个主体上；
     *                          为空表示首次激活，一律新建主体。
     *
     *                          <p><b>幂等判据必须是申请单，不能是人</b>。曾经按
     *                          {@code owner_user_no} 判重：老板申请<b>第二张执照</b>、
     *                          审核通过时被当成重复点击，系统去改<b>第一个主体</b> ——
     *                          名称/行业/法律形态被覆盖，两家店变一家，
     *                          <b>全程没有任何报错</b>，商家只看到「审核通过了」。
     */
    record ActivateCommand(String ownerUserNo, String name, String subject,
                           String serviceScope, List<String> communityNos,
                           String settleAccountType, String industry, String description,
                           String activatedEntityNo) {
    }

    /**
     * 开/关本店积分。
     *
     * <p><b>关闭只影响将来</b>：不动已发出的分，也不退已扣的服务费 ——
     * 否则关一次开关就是一次资金事故。
     */
    void setPointsEnabled(String merchantNo, boolean enabled);

    /**
     * 支付成功后累加该店的<b>收款额度用量</b>。
     *
     * <p>微信对小微商户的收款有累计额度，超了之后收款直接失败。
     * 不累加的话系统永远不知道用掉了多少 —— 它只会在某个买家付款的那一刻
     * 表现为「支付失败」，而那时候平台既解释不清也补救不了。
     *
     * <p><b>周期翻篇时清零重算</b>：周期标识由实现按当前时间算，
     * 调用方不传 —— 传进来的话，补发的历史回调会把用量记进当前周期。
     *
     * <p>没有收款记录时静默跳过：进件还没走完的商家没有额度可记。
     */
    void accruePayQuota(String merchantNo, String storeNo, long amountMinor);
}
