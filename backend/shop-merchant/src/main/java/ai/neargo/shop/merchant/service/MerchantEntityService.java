package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.dto.EntityStoresVO;

import java.util.List;

/**
 * 跨证照查询（线 B · B2）：<b>这个人名下所有的营业执照，以及每张执照下的门店</b>。
 *
 * <p><b>与其余商家侧服务的根本差别：它不吃 {@code BizContext.merchantNo}</b>。
 * 其余每一个 {@code /biz/**} 都都在「当前这一张执照」的范围内查，
 * 而这里问的恰恰是「我一共有哪几张」—— 用当前执照去查的话，答案永远是它自己。
 *
 * <p>所以本服务的入参是 <b>{@code userNo}（登录人）</b>，不是 {@code merchantNo}，
 * 范围由 {@code mch_account} 的成员行划定。这也意味着<b>越权面在入参上</b>：
 * 传谁的 userNo 就查出谁的执照，调用方必须传当前登录人，不能接受端上传进来的值。
 *
 * <h2>对外不叫「主体」「实体」</h2>
 *
 * <p>老板不认识这两个词。界面上一律叫<b>「证照」</b>（营业执照那张纸），
 * 代码里保留 {@code entity} 是因为库表与既有代码都这么叫，改名的收益不抵风险。
 * 但凡是会露到端上的字段名与文案，按「证照」走。
 */
public interface MerchantEntityService {

    /**
     * 我名下的证照列表（03 屏）。
     *
     * <p><b>只给 owner 行</b>：被邀请去别人店里当店员，那不是他的证照 ——
     * 列出来的话，他会在「证照管理」里看到一张自己既改不了也交不了料的执照。
     */
    List<EntityVO> myEntities(String userNo);

    /**
     * 我能进的所有门店，<b>按证照分组</b>（01 屏门店选择器）。
     *
     * <p>每张证照下给哪几家店，与 {@code BizContext.storeNos()} 同一口径：
     * <ul>
     *   <li>老板 → 这张证照下全部门店（含停用的，看不见的话他会以为店被删了）</li>
     *   <li>店员 → 只有 {@code mch_store_role} 授权到的那几家</li>
     * </ul>
     *
     * <p><b>店员也要能调它</b> —— 它就是门店切换器。挂上「管证照」那档权限的话，
     * 「A 店店长 + B 店店员」这种人一家店都切不了，而那正是多门店授权的主要用途。
     */
    List<EntityStoresVO> myStores(String userNo);

    /**
     * 一张证照的详情（04 屏）。
     *
     * <p><b>必须校验归属</b>：{@code entityNo} 是端上传进来的路径参数，
     * 不是我名下的就 403，而不是返回空 —— 返回空会让人以为「这张证照不存在」，
     * 而它明明存在，只是不属于他。
     *
     * <p>只给「证照 + 它的门店」。收款账户与资质证件由应用层用既有服务按 {@code entityNo}
     * 拼上去 —— 那是跨域拼接，放进本服务的话 merchant 域就要去依赖资质与支付两个域的读侧。
     */
    EntityStoresVO detail(String userNo, String entityNo);
}
