package ai.neargo.shop.pay.channel.master;

import ai.neargo.shop.pay.channel.entity.SysPayChannelRate;

import java.util.List;

/**
 * 通道费率：查生效版本、加新版本。
 *
 * <p><b>只增不改。</b>调费率是「在某个时刻起换一个数」，不是「把那个数改掉」——
 * 改掉的话，那一刻之前的账按什么费率结的就再也说不清了。
 */
public interface PayChannelRateService {

    /**
     * 某一时刻生效的费率。
     *
     * <p><b>匹配顺序：精确 → 通配。</b>先找 {@code (通道, 支付方式, 主体形态)} 全中的，
     * 找不到再放宽到 {@code *}。反过来的话，配了「企业专属费率」也永远取不到 ——
     * 而那种错不会报警，只会让某一类商家一直按通用费率结算。
     *
     * @return 命中的那一版；<b>一条都没配返回 null</b>，调用方自己决定怎么办 ——
     *         这里不兜 0，兜 0 等于悄悄按「零手续费」算账
     */
    /**
     * 此刻生效的费率。<b>三维回退，市场是最外层</b>。
     *
     * <p>顺序是 市场 → 支付方式 → 法律形态，各自「精确 → 通配」，共八档。
     * 市场排最外是因为它是最粗的商务分割：不同市场是不同的合同、
     * 不同的货币、不同的结算周期。<b>把它排在里层的话，
     * 一条「大陆 · 通用」的费率会盖过「台湾 · 企业」那条</b> ——
     * 而那意味着按错误的国家费率结算，且金额看着完全正常。
     */
    ChannelFeeRate effective(String market, String payChannel, String payMethod,
                             String legalForm, long at);

    /**
     * 生效费率的三个数。<b>不返回 entity</b>：{@code SysPayChannelRate} 带
     * {@code @TableName}，把它放进接口签名会让调用方绑上持久化框架 ——
     * 与 {@code FeeRuleService} 换 {@code FeeRuleVO} 是同一条理由
     * （见 PayHasNoControllerTest 的第三条闸门）。
     *
     * @param rateNo 版本号。<b>要落到结算单上</b> —— 事后回答「这笔按哪一版算的」
     *               靠的就是它，而只记一个费率数字答不了这个问题
     */
    record ChannelFeeRate(int rateBp, long minFeeMinor, String rateNo) {
    }

    /** 某通道的全部版本，按生效时间倒序 —— 运营页要看「现在是哪一版、下一版什么时候生效」。 */
    List<SysPayChannelRate> history(String payChannel);

    /** 加一版。<b>不改旧行。</b> */
    SysPayChannelRate add(SysPayChannelRate rate);
}
