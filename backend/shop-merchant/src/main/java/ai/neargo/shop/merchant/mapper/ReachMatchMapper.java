package ai.neargo.shop.merchant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 可见范围的命中查询（ADR-034）：两条索引点查，替掉「每请求把全平台范围整表拉进内存逐店判」。
 *
 * <p>核心手法是把「消费者区划码以范围码为前缀」改写成「范围码 ∈ 消费者祖先码集合」——
 * 前缀匹配用不上索引，{@code IN} 能走现成的 {@code idx_service_area_ref(level, ref_code)}；
 * 多边形同理，经 S2 网格变成 {@code cell_id IN (各级 token)}，命中 {@code idx_sac_cell}。
 * 于是行政五级、聚落、多边形、排除<b>共用一种索引形状</b>，MySQL / H2 完全一致，不需要任何空间 SQL。
 *
 * <p><b>只查命中，不做判定</b>：排除先算、路类型闸、fail-closed 全在 {@code ReachRule} 一处。
 * 「不限」也不在这里 —— 它是门店属性，走快照。
 *
 * <p>纳入项要 {@code status='ACTIVE'}；排除项<b>不看 status</b> —— 缩小自己的范围不需要审核，待审的排除也立即生效。
 * 祖先集或 token 为空时用 {@code 1=0} 短路：空 {@code IN ()} 在两种库上都是语法错。
 */
@Mapper
public interface ReachMatchMapper {

    /** 行政级（按祖先码）与聚落级（按小区号/父聚落号）的命中行 */
    @Select("""
            <script>
            SELECT store_no AS storeNo, area_no AS areaNo, mode AS mode
            FROM mch_service_area
            WHERE deleted = 0
              AND store_no IS NOT NULL
              AND ((mode = 'INCLUDE' AND status = 'ACTIVE') OR mode = 'EXCLUDE')
              <if test="storeNo != null"> AND store_no = #{storeNo}</if>
              AND (
                <trim prefixOverrides="OR ">
                  <choose>
                    <when test="ancestors != null and ancestors.size() > 0">
                      (level IN ('PROVINCE','CITY','DISTRICT','STREET','VILLAGE') AND ref_code IN
                      <foreach collection="ancestors" item="a" open="(" separator="," close=")">#{a}</foreach>)
                    </when>
                    <otherwise>(1 = 0)</otherwise>
                  </choose>
                  <if test="communityNos != null and communityNos.size() > 0">
                    OR (level = 'COMMUNITY' AND ref_code IN
                    <foreach collection="communityNos" item="c" open="(" separator="," close=")">#{c}</foreach>)
                  </if>
                </trim>
              )
            </script>
            """)
    List<AreaHitRow> areaHits(@Param("ancestors") List<String> ancestors,
                              @Param("communityNos") List<String> communityNos,
                              @Param("storeNo") String storeNo);

    /** 多边形的网格命中行。{@code boundary=1} 的只是「可能在内」，判定前要用几何精判 */
    @Select("""
            <script>
            SELECT store_no AS storeNo, area_no AS areaNo, mode AS mode, boundary AS boundary
            FROM mch_service_area_cell
            WHERE
              <choose>
                <when test="tokens != null and tokens.size() > 0">
                  cell_id IN <foreach collection="tokens" item="t" open="(" separator="," close=")">#{t}</foreach>
                </when>
                <otherwise>1 = 0</otherwise>
              </choose>
              <if test="storeNo != null"> AND store_no = #{storeNo}</if>
            </script>
            """)
    List<CellHitRow> cellHits(@Param("tokens") List<String> tokens, @Param("storeNo") String storeNo);

    /**
     * 门店属性快照的<b>版本探针</b>（ADR-034）：影响判定的五张表、每张表的
     * {@code COUNT(*)} 与 {@code SUM(version)}，<b>拼成一个字符串</b>。
     *
     * <p>为什么是这两个量：新增/物理删改变 {@code COUNT(*)}；{@code updateById} 走 MyBatis-Plus 乐观锁
     * （{@code BaseEntity.version} 带 {@code @Version}）必然递增 {@code version}。于是 insert / update / delete
     * 三种写操作都会改变它，<b>且与时间精度无关</b> —— 同一毫秒内的修改也测得出来。
     *
     * <p>★ <b>为什么是拼接而不是相加</b>（2026-10-10 改）。第一版把这 10 个聚合<b>加成一个数</b>，
     * 那是有损的，而且碰撞一点都不难凑：
     * <ul>
     *   <li>跨表抵消：{@code mch_store} 删掉一行（−1）、{@code mch_service_area} 插进一行（+1）→ 和不变；</li>
     *   <li>表内抵消：删掉 {@code version=V} 的一行（COUNT −1、SUM −V），
     *       另有若干行的 version 合计 +V+1 → 和不变。</li>
     * </ul>
     * 和不变 = 探针判「没变」= 快照不重装，而门店属性已经变了。症状是**商品在买家那儿静默消失**
     * （门店 meta 读到旧的）或反过来，零报错。
     *
     * <p><b>说清边界</b>：抵消要求某处 {@code COUNT(*)} 真的减少，而本库大部分删除是全局逻辑删
     * （{@code delete(wrapper)} 被改写成 {@code UPDATE deleted=1}，COUNT 不减）。真能物理删的是
     * 「范围子集物理删后重插」那条路（{@code mch_channel_area}）与多边形项的清理。
     * 所以这是**可证的信息损失 + 存在真实触发路径**，但我<b>没有</b>证明它就是 2026-10-10 全量跑里
     * {@code M9bBizGoodsFlowTest#onSaleGoodsIsVisibleToBuyers} 那一红的原因 ——
     * 那一条仍在查。改成拼接是因为「加成一个数」本身不该留着，不是因为已经抓到现行。
     *
     * <p>拼接之后每个聚合占自己的位置，任一变化都改变整个串，不存在抵消。代价是多几个字节。
     *
     * <p>这替掉了「在每个写入口手工 evict 缓存」：那条路要覆盖 6 个 service 的二十多个事务方法，
     * 漏一处的症状是「商家改完范围，买家几十秒内看不到」，而且不报错。
     * 探针让正确性由机器保证，新增写路径不需要任何人记得接线。
     */
    @Select("""
            SELECT CONCAT_WS('/',
                   (SELECT COUNT(*) FROM mch_entity),
                   (SELECT COALESCE(SUM(version), 0) FROM mch_entity),
                   (SELECT COUNT(*) FROM mch_store),
                   (SELECT COALESCE(SUM(version), 0) FROM mch_store),
                   (SELECT COUNT(*) FROM mch_service_area),
                   (SELECT COALESCE(SUM(version), 0) FROM mch_service_area),
                   (SELECT COUNT(*) FROM mch_fulfillment_channel),
                   (SELECT COALESCE(SUM(version), 0) FROM mch_fulfillment_channel),
                   (SELECT COUNT(*) FROM mch_channel_area),
                   (SELECT COALESCE(SUM(version), 0) FROM mch_channel_area))
            """)
    String snapshotVersion();

    /** 行政级/聚落级命中的一行 */
    record AreaHitRow(String storeNo, String areaNo, String mode) {
    }

    /** 网格命中的一行 */
    record CellHitRow(String storeNo, String areaNo, String mode, Boolean boundary) {
    }
}
