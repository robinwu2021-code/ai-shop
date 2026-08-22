package ai.neargo.shop.platform;

import java.util.List;

/**
 * 行政区划查询（ADR-013 阶段一）。
 *
 * <p><b>只按层级懒加载，不给整棵树</b>：五级共 66 万行 ——
 * 运营挑一个村只需要沿着「省 → 市 → 区 → 街道 → 村」走五次，每次几十条。
 * 给整棵树的话，每开一次页面都要传一遍全国，而其中 99.99% 是用不到的。
 *
 * <p>补上村级（V181，62 万行）之后这条约束从「建议」变成「前提」：
 * 四级时代一次性下发是 1.6 MB，还算撑得住；现在是几十 MB。
 */
public interface RegionService {

    /**
     * 某个区划的直接下级。
     *
     * @param parentCode 空 = 取省级（顶层）
     * @param enabledOnly true 只给启用的（端上选择器用）；false 给全量（运营维护面用）。
     *                    <b>两个口径不能合并</b>：合并之后停用过的区划在运营端也看不见，
     *                    于是再也开不回来 —— 与行业、授权码那两处是同一条规矩
     */
    List<RegionVO> children(String parentCode, boolean enabledOnly);

    /**
     * 某区划的直接下级，<b>带上这家商家自己补录的那些</b>。
     *
     * <p>与上面那个的差别只在可见范围：商家补录的村在运营确认之前
     * {@code owner_entity_no} 非空，只有他自己看得到。运营确认后置 NULL，
     * 走哪个方法都能看到。
     *
     * @param entityNo 当前商家；传 null 等价于上面那个方法
     */
    List<RegionVO> children(String parentCode, boolean enabledOnly, String entityNo);

    /**
     * 商家补录一个村/社区（行政区划第五级）。
     *
     * <p><b>为什么要给商家这个口子</b>：官方村级数据停在 2023-06-30
     * （统计局 2024-10 起不再公开），之后新增或改名的村没有任何官方渠道能拿到。
     * 而缺一个村就等于那一片做不了生意 —— 让他等平台更新，
     * 而平台的「下次更新」在源头停发之后根本不会到来。
     *
     * <p><b>录完立刻可用，但只对他自己可见</b>。运营确认后才转为全网共享 ——
     * 不这样做只有两条更差的路：立刻全网可见（一家店打错字污染全平台），
     * 或者压在待审队列里不给用（他今天就做不了这单生意）。
     *
     * @param parentStreetCode 上级街道（9 位）。<b>只能挂在街道下</b> ——
     *                         挂到区县下的话它在按街道覆盖的场景里永远出不来
     * @param name             村/社区名
     * @param entityNo         提报的商家
     * @throws ai.neargo.shop.common.BizException 上级不是街道、或同一街道下同名已存在
     */
    RegionVO createVillage(String parentStreetCode, String name, String entityNo);

    /**
     * 改了再提。<b>驳回理由多半是「名字应该叫 XX」</b> ——
     * 让他换个名字重录一条的话，被驳回的那条会一直留着，
     * 同一个村在运营队列里攒下几条一模一样的驳回记录。
     *
     * @throws ai.neargo.shop.common.BizException 不是自己提报的、或状态不是 REJECTED
     */
    RegionVO resubmitVillage(String regionCode, String name, String entityNo);

    /** 待运营确认的补录（PENDING）。给运营队列用 */
    List<PendingVO> pendingVillages(String status);

    /**
     * 运营裁决。
     *
     * @param pass   true 转 APPROVED 全网可见；false 转 REJECTED（<b>不删行</b>，
     *               提报方要能看到理由，否则他只会原样再提一次）
     * @param reason 驳回原因，驳回时必填 —— 它原样出现在商家端
     */
    void confirmVillage(String regionCode, boolean pass, String reason, String operatorNo);

    /**
     * @param path       从省到这个村的整条路径名。**必须给** ——
     *                   光一个「新桥社区」全国有好几个，运营判断不了真假
     * @param entityName 提报商家名，运营要看的是名字不是编号
     */
    record PendingVO(String regionCode, String name, String path, String auditStatus,
                     String entityNo, String entityName, String rejectReason, long createdAt) {
    }

    /**
     * 从一个区划码回溯到顶层，<b>从省到自身</b>排列。
     *
     * <p>给运营端回显用：拿到一个社区的 {@code region_code=330106001}，
     * 要在选择器里显示「浙江省 / 杭州市 / 西湖区 / 北山街道」。
     * 不提供的话端上只能自己按码长切片再逐级查，那等于把国标的编码规则
     * 复制到端上 —— 而编码规则不是端该知道的事。
     *
     * @return 查不到时返回空列表，不抛异常 —— 存量数据里可能有已撤并的区划码
     */
    List<RegionVO> path(String regionCode);

    /**
     * @param level    PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级）
     * @param hasChild 下面还有没有下级。端上据此决定「还要不要再往下选一层」，
     *                 而不是点进去才发现是空的
     */
    /**
     * @param source  {@code OFFICIAL} / {@code MERCHANT}。端上据此标出「我补录的」——
     *                不标的话商家分不清哪些是自己填的，也就不知道哪些还没被运营确认
     * @param pending 商家补录且尚未被运营确认（只有自己看得见）
     */
    record RegionVO(String regionCode, String parentCode, String level, String name,
                    boolean enabled, boolean hasChild, String source, boolean pending,
                    String auditStatus, String rejectReason) {
    }
}
