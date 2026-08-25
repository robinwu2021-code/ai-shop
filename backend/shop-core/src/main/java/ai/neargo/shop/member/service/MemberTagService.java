package ai.neargo.shop.member.service;

import ai.neargo.shop.member.dto.MemberVOs.MergePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.TagVO;

import java.util.List;

/**
 * 会员标签。
 *
 * <p><b>两类标签的规则不同</b>：系统标签由平台按公开口径每日算，商家只读 ——
 * 可编辑的自动标签三周后一定是脏的（有人改了、有人没改，谁也不敢再用它筛人）；
 * 商家标签自由文本、限量、可改名可合并。
 *
 * <p><b>标签属主体不属门店</b>：同一个人在总店买米、南门店买油，仍是同一个「爱囤货」的人。
 * 门店维度体现在筛选上，不体现在标签归属上。
 */
public interface MemberTagService {

    /** 字典 + 每个标签多少人（COUNT 出来的，不存冗余列） */
    List<TagVO> tags(String entityNo);

    TagVO create(String entityNo, String name, String operatorNo);

    /** 改名。<b>只动字典一行</b>，关系表存的是号 —— 历史统计不断 */
    TagVO rename(String entityNo, String tagNo, String name);

    /** 停用 / 恢复。停用后新的打不上，已经打的照常显示与可筛 */
    TagVO setEnabled(String entityNo, String tagNo, boolean enabled);

    /**
     * 合并：把 {@code fromTagNo} 并进 {@code intoTagNo}。
     *
     * @param confirm {@code false} = <b>只试算</b>，返回影响面不落库。
     *                界面必须先把影响面摆给商家看再让他按 —— 这是不可逆操作
     */
    MergePreviewVO merge(String entityNo, String fromTagNo, String intoTagNo,
                         boolean confirm, String operatorNo);

    /**
     * 批量打标 / 去标。<b>系统标签一律拒绝</b>。
     *
     * @param add    要打上的标签号
     * @param remove 要去掉的标签号
     */
    void tag(String entityNo, List<String> memberNos, List<String> add, List<String> remove,
             String operatorNo);

    /** 某个会员身上的标签（详情页用） */
    List<TagVO> tagsOf(String entityNo, String memberNo);
}
