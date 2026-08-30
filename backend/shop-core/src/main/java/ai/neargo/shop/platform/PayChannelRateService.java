package ai.neargo.shop.platform;

import ai.neargo.shop.platform.entity.SysPayChannelRate;

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
    SysPayChannelRate effective(String payChannel, String payMethod, String legalForm, long at);

    /** 某通道的全部版本，按生效时间倒序 —— 运营页要看「现在是哪一版、下一版什么时候生效」。 */
    List<SysPayChannelRate> history(String payChannel);

    /** 加一版。<b>不改旧行。</b> */
    SysPayChannelRate add(SysPayChannelRate rate);
}
