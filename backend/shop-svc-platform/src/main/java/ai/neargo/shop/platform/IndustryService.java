package ai.neargo.shop.platform;

import ai.neargo.shop.platform.dto.IndustryVO;

import java.util.List;

/**
 * 行业主数据：**平台维护**（V40）。
 *
 * <p>行业是商家的基础属性，决定他<b>可选的主体类型</b> ——
 * 微信小微的准入白名单是按行业给的，线上业态不能选小微。
 *
 * <p>为什么维护权在平台而不是商家：准入结论来自<b>通道的规则</b>，
 * 商家自报行业的话，报错了受害的是他自己（填完资料被拒），
 * 而平台既没法阻止也不知道。
 */
public interface IndustryService {

    /** 全部行业（含停用的）。运营维护页用。 */
    List<IndustryVO> list();

    /** 入驻表单用：只返回启用的，按 sort 排。 */
    List<IndustryVO> enabled();

    /**
     * 某行业在某通道能否以**小微**主体进件。
     *
     * <p>查不到行业时返回 <b>false</b> —— 未知一律不允许。
     * 返回 true 的话，商家会填完全部资料才被通道拒绝，那是最贵的失败时点。
     */
    boolean microAllowed(String industry, String payChannel);

    /** 改准入结论。**只有平台能改** —— 它反映的是通道规则，不是商家意愿。 */
    IndustryVO setMicroAllowed(String industry, String payChannel, boolean allowed, String remark);

    /** 启停。停用后新入驻选不到，**存量商家不受影响** —— 停用不是撤销资质。 */
    IndustryVO setEnabled(String industry, boolean enabled);

    /** 设/取消该行业强制开启积分。补上 `mch_entity.points_forced` 一直缺的依据。 */
    IndustryVO setPointsForced(String industry, boolean forced);
}
