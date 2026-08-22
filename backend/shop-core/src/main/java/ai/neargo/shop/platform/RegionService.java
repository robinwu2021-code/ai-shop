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
    record RegionVO(String regionCode, String parentCode, String level, String name,
                    boolean enabled, boolean hasChild) {
    }
}
