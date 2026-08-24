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
     * 按名称搜区划（选择器「任何一级都能搜」）。<b>四级都搜：省 / 市 / 区县 / 街道镇</b>。
     *
     * <p>此前不搜省，理由写的是「没人按省框范围」—— 而经营范围本来就是
     * 「任意一级的并集」：快递发货的商家框的就是省，搜「山西」一条也搜不到时，
     * 他只能从全国列表一级一级点。同一处还有第二个坑：曾经是
     * 「一个 LIMIT 20 + 按 level 字符串降序」，而降序是 STREET &gt; PROVINCE &gt; DISTRICT &gt; CITY，
     * 于是街道把整份配额吃光 —— 搜「运城」时「运城市」根本进不了列表。
     *
     * <p>现在按<b>每级配额</b>取（省 3 / 市 5 / 区 8 / 街道 8），保证每一级都有代表；
     * 组内排序按命中强度：完全相同 &gt; 前缀命中 &gt; 包含；同强度时给了坐标就近的排前面
     * （同名的「城关街道」全国上百个，不按距离排等于让人从一堆同名里猜）。
     *
     * @param limit      总条数上限（&le; 30）。这是给人挑的，不是给机器遍历的
     * @param nearLatE6  门店坐标，可空；给了就参与同名排序
     */
    List<RegionVO> search(String keyword, int limit, Integer nearLatE6, Integer nearLngE6);

    /** 不带坐标的老签名。存量调用方（运营端）没有「门店位置」这个概念 */
    default List<RegionVO> search(String keyword, int limit) {
        return search(keyword, limit, null, null);
    }

    /**
     * 按名称搜**村级**（第五级，62 万条那份）。与 {@link #search} 分开是因为口径不同：
     * 那个搜的是导航层级（市/区/街道），这个搜的是终点。
     *
     * <p>为什么要有：商家心里的「我做哪儿」就是一个村名或小区名，
     * 而此前搜索框只认市/区/街道与**已开通**的聚落 —— 他打「狮径」什么也搜不到，
     * 只能自己一级级点到街道才发现名录里有。
     *
     * <p>命中的村多数还没开通；官方村现在提报即开通，所以端上点一条就能直接用。
     */
    List<RegionVO> searchVillages(String keyword, int limit, Integer nearLatE6, Integer nearLngE6);

    /**
     * 从「地址文本 + 坐标」推断这条提报该挂哪个街道。
     *
     * <p>裁决那一屏原本要运营从 31 个省一路点到街道 —— 而提报单上明明写着
     * 「广东省深圳市龙华区福城街道福庆路1号」，坐标也在。让人把机器已经知道的事再点四次，
     * 是把系统的活推给人做，还容易点错：330106003 与 330106004 只差一位，
     * 而挂错的后果是这个社区在任何「按区覆盖」里都出不来。
     *
     * <p>两条线索各出一个候选，都给出来让运营挑：
     * <ul>
     *   <li><b>地址文本</b>：抠出省/市/区/街道四段名字，沿树走下去；</li>
     *   <li><b>坐标最近邻</b>：在<b>已补录坐标</b>的村级区划里找最近的一条，取它的父街道 ——
     *       这条只在补过坐标的城市有效（当前是运城、深圳），没有就不出。</li>
     * </ul>
     *
     * @return 候选街道，最多几条，按可信度排；推不出来给空列表（端上退回手选，不拦）
     */
    List<Suggestion> resolve(String address, Integer latE6, Integer lngE6);

    /**
     * @param source ADDRESS 地址文本推断 / COORDS 坐标最近邻
     * @param detail 给运营看的依据（匹配到的地址片段，或「距提报坐标 320 米」）
     */
    record Suggestion(RegionVO region, String path, String source, String detail) {
    }

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
    /**
     * @param latE6 中心点（gcj02，E6）。<b>可能为 null</b>：批量补录没命中的区划就是空的，
     *              端上据此决定是直接用还是临时去地图上搜
     */
    /**
     * @param rural 只对 level=VILLAGE 有意义：是不是村委会。true=到此为止不再往下钻，
     *              false=居委会/社区，底下还能再挑具体小区（见 SysRegion#rural 的注释）
     */
    record RegionVO(String regionCode, String parentCode, String level, String name,
                    boolean enabled, boolean hasChild, String source, boolean pending,
                    String auditStatus, String rejectReason,
                    Integer latE6, Integer lngE6, boolean rural) {
    }
}
