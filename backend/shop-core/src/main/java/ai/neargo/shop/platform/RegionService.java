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
     * 运营新增一个区划节点（人工维护）。
     *
     * <p>官方数据停更（统计局 2024-10 起不再公开），之后真实发生的区划调整
     * （新设街道/镇、撤并）只能靠运营手工补。生成码 = 父码 + X + 两位序号 ——
     * 字母段保证与官方纯数字码永不冲突，这条规矩与聚落的 origin 同源。
     *
     * @param parentCode 父节点；层级由父级推导（省下是市、区下是街道…），不让人选
     */
    RegionVO createNode(String parentCode, String name, String operatorNo);

    /**
     * 停用 / 启用。<b>enabled 的第一个写入口</b> ——
     * 这一列带着注释上线两年，此前全仓没有任何地方写它，「开城开关」从来没有开关。
     * 停用只影响新选择，存量商家的经营范围不动（与行业停用同一口径）。
     */
    RegionVO toggleNode(String regionCode, boolean enabled, String operatorNo);

    /** 改名。撤并更名真实发生；改名不动码，存量引用不受影响 */
    RegionVO renameNode(String regionCode, String name, String operatorNo);

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
