package ai.neargo.shop.elec.mapper;

import ai.neargo.shop.elec.entity.ElcManufacturer;
import ai.neargo.shop.elec.entity.ElcMfrAlias;
import ai.neargo.shop.elec.entity.ElcPart;
import ai.neargo.shop.elec.entity.ElcPartKey;
import ai.neargo.shop.elec.entity.ElcPartMarket;
import ai.neargo.shop.elec.entity.ElcSearchDaily;
import ai.neargo.shop.elec.entity.ElcRfq;
import ai.neargo.shop.elec.entity.ElcDispatch;
import ai.neargo.shop.elec.entity.ElcQuote;
import ai.neargo.shop.elec.entity.ElcRfqLine;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.entity.ElcStockBatchRow;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.entity.ElcSupplierMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 元器件域全部 Mapper。<b>它们只绑在 elecSqlSessionFactory 上</b>（ElecDataSourceConfig），
 * 平台的全局扫描把 {@code ai.neargo.shop.elec} 排除在外 —— 少任何一头，elc_* 的查询都会打到平台库。
 */
public final class ElecMappers {

    private ElecMappers() {
    }

    public interface ManufacturerMapper extends BaseMapper<ElcManufacturer> {
    }

    public interface MfrAliasMapper extends BaseMapper<ElcMfrAlias> {

        /** 每家厂牌有几种写法。运营端厂牌列表用 */
        @Select("SELECT mfr_code AS code, COUNT(*) AS cnt FROM elc_mfr_alias GROUP BY mfr_code")
        List<CodeCount> countsByMfr();
    }

    /** 「某个键 → 几条」的通用一行 */
    @Getter
    @Setter
    public static class CodeCount {
        private String code;
        private Long cnt;
    }

    public interface PartMapper extends BaseMapper<ElcPart> {

        /**
         * 料号前缀命中的候选（只要 part_no 与 mpn_norm，排序在 Java 里做）。
         *
         * <p>{@code prefix} 由 {@code Mpn.norm} 产出，只含字母数字与 {@code / . # + ,}，
         * 拼进 LIKE 不会混进 % 与 _。
         */
        @Select("""
                SELECT part_no, mpn_norm FROM elc_part
                 WHERE mpn_norm LIKE CONCAT(#{prefix}, '%') AND status <> 'MERGED'
                 ORDER BY mpn_norm
                 LIMIT #{limit}
                """)
        List<ElcPart> prefixCandidates(@Param("prefix") String prefix, @Param("limit") int limit);

        /**
         * 一批料号连同买家面投影。<b>只 join 投影表，不碰 elc_stock</b> ——
         * 买家侧的查询在结构上就拿不到供应商与精确数量。
         */
        @Select("""
                <script>
                SELECT p.part_no, p.mpn, p.mpn_norm, p.mfr_code, p.mfr_name_raw, p.pkg, p.description,
                       m.name_en AS mfr_name_en, m.name_cn AS mfr_name_cn,
                       k.qty_band, k.source_band, k.price_from_e6, k.price_from_qty, k.dc_year_max,
                       k.spot, k.lead_days_min, k.cond_set, k.next_expiry_at
                  FROM elc_part p
                  LEFT JOIN elc_part_market k ON k.part_no = p.part_no
                  LEFT JOIN elc_manufacturer m ON m.mfr_code = p.mfr_code
                 WHERE p.part_no IN
                 <foreach collection="partNos" item="n" open="(" separator="," close=")">#{n}</foreach>
                </script>
                """)
        List<PartHitRow> hitsOf(@Param("partNos") java.util.Collection<String> partNos);

        @Select("""
                SELECT p.part_no, p.mpn, p.mpn_norm, p.mfr_code, p.mfr_name_raw, p.pkg, p.description,
                       m.name_en AS mfr_name_en, m.name_cn AS mfr_name_cn,
                       k.qty_band, k.source_band, k.price_from_e6, k.price_from_qty, k.dc_year_max,
                       k.spot, k.lead_days_min, k.cond_set, k.next_expiry_at
                  FROM elc_part p
                  LEFT JOIN elc_part_market k ON k.part_no = p.part_no
                  LEFT JOIN elc_manufacturer m ON m.mfr_code = p.mfr_code
                 WHERE p.part_no = #{partNo}
                """)
        PartHitRow findHit(@Param("partNo") String partNo);

        /** 多行插入。第一次上传的供应商整张表都是新料号，逐行插是上万次往返 */
        @Insert("""
                <script>
                INSERT INTO elc_part (part_no, mpn, mpn_norm, mfr_code, mfr_name_raw, pkg, source, status, created_by)
                VALUES
                <foreach collection="rows" item="r" separator=",">
                  (#{r.partNo}, #{r.mpn}, #{r.mpnNorm}, #{r.mfrCode}, #{r.mfrNameRaw}, #{r.pkg}, #{r.source},
                   #{r.status}, #{r.createdBy})
                </foreach>
                </script>
                """)
        int insertAll(@Param("rows") List<ElcPart> rows);

        /** 每家厂牌名下几个料号（不含已合并的）。运营端厂牌列表用 */
        @Select("SELECT mfr_code AS code, COUNT(*) AS cnt FROM elc_part WHERE status <> 'MERGED' GROUP BY mfr_code")
        List<CodeCount> countsByMfr();
    }

    /** 搜索结果的一行：料号 + 投影。字段名与列别名一一对应（下划线转驼峰）。 */
    @Getter
    @Setter
    public static class PartHitRow {
        private String partNo;
        private String mpn;
        private String mpnNorm;
        private String mfrCode;
        private String mfrNameRaw;
        private String pkg;
        private String description;
        private String mfrNameEn;
        private String mfrNameCn;
        private String qtyBand;
        private String sourceBand;
        private Long priceFromE6;
        private Long priceFromQty;
        private Integer dcYearMax;
        private Boolean spot;
        private Integer leadDaysMin;
        private String condSet;
        private LocalDateTime nextExpiryAt;
    }

    public interface PartMarketMapper extends BaseMapper<ElcPartMarket> {
    }

    public interface PartKeyMapper extends BaseMapper<ElcPartKey> {

        /** 中段命中：分段键的前缀匹配。pos &gt; 0 的才算「中段」，pos = 0 的就是料号本身（前缀那一路已经覆盖） */
        @Select("""
                SELECT part_no, pos FROM elc_part_key
                 WHERE key_norm LIKE CONCAT(#{prefix}, '%') AND pos > 0
                 ORDER BY pos, key_norm
                 LIMIT #{limit}
                """)
        List<ElcPartKey> containCandidates(@Param("prefix") String prefix, @Param("limit") int limit);

        @Insert("""
                <script>
                INSERT INTO elc_part_key (key_norm, part_no, pos, created_by) VALUES
                <foreach collection="rows" item="r" separator=",">
                  (#{r.keyNorm}, #{r.partNo}, #{r.pos}, #{r.createdBy})
                </foreach>
                </script>
                """)
        int insertAll(@Param("rows") List<ElcPartKey> rows);
    }

    public interface SearchDailyMapper extends BaseMapper<ElcSearchDaily> {

        /**
         * 计数 +1。先 UPDATE，0 行再 INSERT（两个方言都认的写法；并发下撞唯一键由调用方重试一次 UPDATE）。
         */
        @org.apache.ibatis.annotations.Update("""
                UPDATE elc_search_daily
                   SET search_cnt = search_cnt + 1,
                       zero_cnt = zero_cnt + #{zero},
                       stock_cnt = stock_cnt + #{stock}
                 WHERE stat_date = #{day} AND keyword = #{keyword}
                """)
        int bump(@Param("day") java.time.LocalDate day, @Param("keyword") String keyword,
                 @Param("zero") int zero, @Param("stock") int stock);
    }

    public interface SupplierMapper extends BaseMapper<ElcSupplier> {
    }

    public interface SupplierMemberMapper extends BaseMapper<ElcSupplierMember> {
    }

    public interface StockBatchMapper extends BaseMapper<ElcStockBatch> {

        /** 一批供应商各自最近一次确认上架的时间。运营端供应商列表用 —— 一页一次查询，不是一家一次 */
        @Select("""
                <script>
                SELECT supplier_no, MAX(applied_at) AS last_at
                  FROM elc_stock_batch
                 WHERE status = 'APPLIED' AND supplier_no IN
                 <foreach collection="supplierNos" item="n" open="(" separator="," close=")">#{n}</foreach>
                 GROUP BY supplier_no
                </script>
                """)
        List<SupplierLastUpload> lastAppliedBySupplier(@Param("supplierNos") java.util.Collection<String> supplierNos);
    }

    @Getter
    @Setter
    public static class SupplierLastUpload {
        private String supplierNo;
        private LocalDateTime lastAt;
    }

    public interface StockBatchRowMapper extends BaseMapper<ElcStockBatchRow> {

        @Insert("""
                <script>
                INSERT INTO elc_stock_batch_row (batch_no, row_idx, cells, created_by) VALUES
                <foreach collection="rows" item="r" separator=",">
                  (#{r.batchNo}, #{r.rowIdx}, #{r.cells}, #{r.createdBy})
                </foreach>
                </script>
                """)
        int insertAll(@Param("rows") List<ElcStockBatchRow> rows);
    }

    public interface StockMapper extends BaseMapper<ElcStock> {

        @Insert("""
                <script>
                INSERT INTO elc_stock (stock_no, supplier_no, line_key, part_no, mpn_raw, mfr_raw, mpn_norm, qty,
                                       date_code, dc_year, pkg, moq, spq, price_tiers, price_e6, currency,
                                       tax_included, packing, cond_grade, lead_days, region, valid_until,
                                       confirmed_at, status, batch_no, created_by, updated_by)
                VALUES
                <foreach collection="rows" item="r" separator=",">
                  (#{r.stockNo}, #{r.supplierNo}, #{r.lineKey}, #{r.partNo}, #{r.mpnRaw}, #{r.mfrRaw}, #{r.mpnNorm},
                   #{r.qty}, #{r.dateCode}, #{r.dcYear}, #{r.pkg}, #{r.moq}, #{r.spq}, #{r.priceTiers},
                   #{r.priceE6}, #{r.currency}, #{r.taxIncluded}, #{r.packing}, #{r.condGrade}, #{r.leadDays},
                   #{r.region}, #{r.validUntil}, #{r.confirmedAt}, #{r.status}, #{r.batchNo},
                   #{r.createdBy}, #{r.updatedBy})
                </foreach>
                </script>
                """)
        int insertAll(@Param("rows") List<ElcStock> rows);

        /**
         * <b>只给平台看</b>（询价通知里「谁有货」那一段）：某料号当前有效的库存，连同供应商名称。
         * 买家侧的任何代码都不许调它 —— 这是全域唯一一处把库存和供应商名字放在同一行里的查询。
         */
        @Select("""
                SELECT s.supplier_no, s.company_name, s.contact_phone,
                       t.stock_no, t.qty, t.date_code, t.moq, t.spq, t.price_tiers, t.price_e6, t.currency,
                       t.tax_included, t.packing, t.cond_grade, t.lead_days, t.region, t.valid_until
                  FROM elc_stock t
                  JOIN elc_supplier s ON s.supplier_no = t.supplier_no
                 WHERE t.part_no = #{partNo}
                   AND t.status = 'ON'
                   AND t.valid_until >= #{today}
                   AND s.status = 'ACTIVE'
                 ORDER BY t.qty DESC
                 LIMIT #{limit}
                """)
        List<SourceRow> sourcesOf(@Param("partNo") String partNo, @Param("today") java.time.LocalDate today,
                                  @Param("limit") int limit);

        /**
         * 一批供应商各自的库存行数：在售未到期 / 其中快到期 / 在售已过期。
         * 「在售」指 status=ON（没被全量替换下架）；过没过期看 valid_until。
         */
        @Select("""
                <script>
                SELECT supplier_no,
                       COALESCE(SUM(CASE WHEN valid_until &gt;= #{today} THEN 1 ELSE 0 END), 0) AS on_cnt,
                       COALESCE(SUM(CASE WHEN valid_until &gt;= #{today} AND valid_until &lt;= #{until}
                                         THEN 1 ELSE 0 END), 0) AS expiring_cnt,
                       COALESCE(SUM(CASE WHEN valid_until &lt; #{today} THEN 1 ELSE 0 END), 0) AS expired_cnt
                  FROM elc_stock
                 WHERE status = 'ON' AND supplier_no IN
                 <foreach collection="supplierNos" item="n" open="(" separator="," close=")">#{n}</foreach>
                 GROUP BY supplier_no
                </script>
                """)
        List<SupplierStockStats> statsBySupplier(@Param("supplierNos") java.util.Collection<String> supplierNos,
                                                 @Param("today") java.time.LocalDate today,
                                                 @Param("until") java.time.LocalDate until);

        /**
         * 一批料号各自的精确家数与合计数量。<b>只算买家此刻能看到的那部分</b>
         * （在售、未到期、供应商未暂停）—— 与投影表同一个口径，只是不降精度。
         */
        @Select("""
                <script>
                SELECT t.part_no, COUNT(DISTINCT t.supplier_no) AS supplier_cnt, SUM(t.qty) AS total_qty
                  FROM elc_stock t
                  JOIN elc_supplier s ON s.supplier_no = t.supplier_no
                 WHERE t.status = 'ON' AND t.valid_until &gt;= #{today} AND s.status = 'ACTIVE'
                   AND t.part_no IN
                 <foreach collection="partNos" item="n" open="(" separator="," close=")">#{n}</foreach>
                 GROUP BY t.part_no
                </script>
                """)
        List<PartStockStats> statsByPart(@Param("partNos") java.util.Collection<String> partNos,
                                         @Param("today") java.time.LocalDate today);

        /**
         * 挂在「厂牌不明」料号下的库存，按 (原文, 供应商, 料号) 聚合。
         *
         * <p><b>按库存行算、不按料号算</b>：UNKNOWN 名下一个料号会汇集多家的库存，
         * 而料号上只记了第一家写的原文 —— 按料号数，后来那几家的写法永远不出现。
         * 规范化（大小写、空格、Co.,Ltd 后缀）在 Java 里做，SQL 里做不了。
         */
        @Select("""
                SELECT t.mfr_raw, t.supplier_no, t.part_no, COUNT(*) AS cnt
                  FROM elc_stock t
                  JOIN elc_part p ON p.part_no = t.part_no
                 WHERE p.mfr_code = 'UNKNOWN' AND t.status = 'ON' AND t.mfr_raw IS NOT NULL
                 GROUP BY t.mfr_raw, t.supplier_no, t.part_no
                 LIMIT #{limit}
                """)
        List<UnknownRawRow> unknownRows(@Param("limit") int limit);

        /** 挂在「厂牌不明」料号下、且写了厂牌原文的全部库存行（含已下架的）。补别名时逐行改认用 */
        @Select("""
                SELECT t.*
                  FROM elc_stock t
                  JOIN elc_part p ON p.part_no = t.part_no
                 WHERE p.mfr_code = 'UNKNOWN' AND t.mfr_raw IS NOT NULL
                """)
        List<ElcStock> underUnknown();
    }

    @Getter
    @Setter
    public static class SupplierStockStats {
        private String supplierNo;
        private Long onCnt;
        private Long expiringCnt;
        private Long expiredCnt;
    }

    @Getter
    @Setter
    public static class PartStockStats {
        private String partNo;
        private Long supplierCnt;
        private Long totalQty;
    }

    @Getter
    @Setter
    public static class UnknownRawRow {
        private String mfrRaw;
        private String supplierNo;
        private String partNo;
        private Long cnt;
    }

    @Getter
    @Setter
    public static class SourceRow {
        private String supplierNo;
        private String companyName;
        private String contactPhone;
        private String stockNo;
        private Long qty;
        private String dateCode;
        private Integer moq;
        private Integer spq;
        /** 阶梯价 JSON 原样，由服务层解析 */
        private String priceTiers;
        private Long priceE6;
        private String currency;
        private Boolean taxIncluded;
        private String packing;
        private String condGrade;
        private Integer leadDays;
        private String region;
        private java.time.LocalDate validUntil;
    }

    public interface RfqMapper extends BaseMapper<ElcRfq> {
    }

    public interface RfqLineMapper extends BaseMapper<ElcRfqLine> {
    }

    public interface DispatchMapper extends BaseMapper<ElcDispatch> {

        /**
         * 供应商的待办：派给他、还没响应的。
         *
         * <p><b>连着询价行一起查</b>（料号、数量、要求），但**一个买家字段都不取** ——
         * 买家是谁不在这条 SQL 的输出里，写错了也漏不出去。
         */
        /*
         * **必须包 <script>**：MyBatis 只在文本以 <script> 开头时才按 XML 解析 <if>，
         * 否则 <if> 原样发给数据库。原先这里只挂了 @Lang(XMLLanguageDriver) 没包 —— 那不起作用，
         * 供应商的求购列表、详情、报价后的返回三处一直是 500，而没有一条测试走到过这条 SQL。
         */
        @Select("""
                <script>
                SELECT d.dispatch_no, d.status, d.created_at,
                       l.mpn_raw, l.mfr_raw, l.qty, l.target_e6,
                       r.dc_req, r.cond_req, r.packing_req, r.need_by_days, r.allow_alt, r.need_invoice,
                       r.deliver_city, r.status AS rfq_status
                  FROM elc_dispatch d
                  JOIN elc_rfq_line l ON l.rfq_no = d.rfq_no AND l.line_no = d.line_no
                  JOIN elc_rfq r ON r.rfq_no = d.rfq_no
                 WHERE d.supplier_no = #{supplierNo}
                   <if test="status != null">AND d.status = #{status}</if>
                 ORDER BY d.id DESC
                 LIMIT #{limit} OFFSET #{offset}
                </script>
                """)
        List<DispatchRow> mine(@Param("supplierNo") String supplierNo, @Param("status") String status,
                               @Param("limit") int limit, @Param("offset") long offset);

        /**
         * 一家供应商在某个时间之后的派单响应。「看过」= 状态离开了 SENT（报价或拒绝时不一定先点过详情）。
         * 空集时 COUNT 是 0 而 SUM 是 NULL —— 所以 SUM 都包 COALESCE。
         */
        @Select("""
                SELECT COUNT(*) AS sent,
                       COALESCE(SUM(CASE WHEN status <> 'SENT' THEN 1 ELSE 0 END), 0) AS viewed,
                       COALESCE(SUM(CASE WHEN responded_at IS NOT NULL THEN 1 ELSE 0 END), 0) AS responded,
                       COALESCE(SUM(CASE WHEN status = 'QUOTED' THEN 1 ELSE 0 END), 0) AS quoted
                  FROM elc_dispatch
                 WHERE supplier_no = #{supplierNo} AND created_at >= #{since}
                """)
        DispatchStatsRow statsOf(@Param("supplierNo") String supplierNo, @Param("since") LocalDateTime since);

        /** 一批询价单各自有几家回了话（去重）。运营询价列表用 */
        @Select("""
                <script>
                SELECT rfq_no AS code, COUNT(DISTINCT supplier_no) AS cnt
                  FROM elc_dispatch
                 WHERE responded_at IS NOT NULL AND rfq_no IN
                 <foreach collection="rfqNos" item="n" open="(" separator="," close=")">#{n}</foreach>
                 GROUP BY rfq_no
                </script>
                """)
        List<CodeCount> respondedByRfq(@Param("rfqNos") java.util.Collection<String> rfqNos);

        /**
         * 一张询价单的全部派单连同结果。<b>平台面</b>：真名、电话、原价、备注都在这里，
         * 与供应商面的 {@link #mine} 正好相反 —— 那条里一个买家字段都没有，这条是给运营对账的。
         */
        @Select("""
                SELECT d.dispatch_no, d.line_no, d.supplier_no, s.company_name, s.contact_phone, d.via,
                       d.status AS dispatch_status, d.decline_reason, d.notified_at, d.responded_at,
                       q.quote_no, q.price_e6, q.currency, q.tax_included, q.qty_available, q.date_code,
                       q.lead_days, q.cond_grade, q.packing, q.moq, q.valid_until, q.remark,
                       q.status AS quote_status
                  FROM elc_dispatch d
                  JOIN elc_supplier s ON s.supplier_no = d.supplier_no
                  LEFT JOIN elc_quote q ON q.dispatch_no = d.dispatch_no
                 WHERE d.rfq_no = #{rfqNo}
                 ORDER BY d.line_no, d.id
                """)
        List<OpsOfferRow> opsOffers(@Param("rfqNo") String rfqNo);
    }

    @Getter
    @Setter
    public static class DispatchStatsRow {
        private Long sent;
        private Long viewed;
        private Long responded;
        private Long quoted;
    }

    /** 运营看到的一条派单及结果。字段与列别名一一对应 */
    @Getter
    @Setter
    public static class OpsOfferRow {
        private String dispatchNo;
        private Integer lineNo;
        private String supplierNo;
        private String companyName;
        private String contactPhone;
        private String via;
        private String dispatchStatus;
        private String declineReason;
        private LocalDateTime notifiedAt;
        private LocalDateTime respondedAt;
        private String quoteNo;
        private Long priceE6;
        private String currency;
        private Boolean taxIncluded;
        private Long qtyAvailable;
        private String dateCode;
        private Integer leadDays;
        private String condGrade;
        private String packing;
        private Integer moq;
        private java.time.LocalDate validUntil;
        private String remark;
        private String quoteStatus;
    }

    /** 供应商看到的一条待报价。**这里没有买家的任何字段** */
    @Getter
    @Setter
    public static class DispatchRow {
        private String dispatchNo;
        private String status;
        private LocalDateTime createdAt;
        private String mpnRaw;
        private String mfrRaw;
        private Long qty;
        private Long targetE6;
        private String dcReq;
        private String condReq;
        private String packingReq;
        private Integer needByDays;
        private Boolean allowAlt;
        private String needInvoice;
        private String deliverCity;
        private String rfqStatus;
    }

    public interface QuoteMapper extends BaseMapper<ElcQuote> {

        /**
         * 报价记录（运营端）。
         *
         * <p>「已过期」不是存下来的状态：有效期过了的 ACTIVE 就是过期。所以按状态筛时
         * ACTIVE 与 EXPIRED 要带上日期条件，其余状态原样比。
         */
        @Select("""
                <script>
                SELECT q.quote_no, q.rfq_no, q.line_no, l.mpn_raw AS mpn, l.qty AS qty_wanted,
                       q.supplier_no, s.company_name, q.price_e6, q.currency, q.tax_included,
                       q.qty_available, q.lead_days, q.valid_until, q.status, q.created_at
                  FROM elc_quote q
                  JOIN elc_rfq_line l ON l.rfq_no = q.rfq_no AND l.line_no = q.line_no
                  JOIN elc_supplier s ON s.supplier_no = q.supplier_no
                 WHERE 1 = 1
                   <if test="supplierNo != null">AND q.supplier_no = #{supplierNo}</if>
                   <if test="status == 'ACTIVE'">AND q.status = 'ACTIVE' AND q.valid_until &gt;= #{today}</if>
                   <if test="status == 'EXPIRED'">AND q.status = 'ACTIVE' AND q.valid_until &lt; #{today}</if>
                   <if test="status == 'WITHDRAWN' or status == 'ACCEPTED'">AND q.status = #{status}</if>
                 ORDER BY q.id DESC
                 LIMIT #{limit} OFFSET #{offset}
                </script>
                """)
        List<OpsQuoteRaw> opsList(@Param("supplierNo") String supplierNo, @Param("status") String status,
                                  @Param("today") java.time.LocalDate today, @Param("limit") int limit,
                                  @Param("offset") long offset);
    }

    @Getter
    @Setter
    public static class OpsQuoteRaw {
        private String quoteNo;
        private String rfqNo;
        private Integer lineNo;
        private String mpn;
        private Long qtyWanted;
        private String supplierNo;
        private String companyName;
        private Long priceE6;
        private String currency;
        private Boolean taxIncluded;
        private Long qtyAvailable;
        private Integer leadDays;
        private java.time.LocalDate validUntil;
        private String status;
        private LocalDateTime createdAt;
    }
}
