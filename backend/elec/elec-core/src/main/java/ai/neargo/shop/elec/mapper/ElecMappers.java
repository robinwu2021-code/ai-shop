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
                       t.qty, t.date_code, t.price_e6, t.currency, t.tax_included,
                       t.packing, t.cond_grade, t.lead_days, t.region
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
    }

    @Getter
    @Setter
    public static class SourceRow {
        private String supplierNo;
        private String companyName;
        private String contactPhone;
        private Long qty;
        private String dateCode;
        private Long priceE6;
        private String currency;
        private Boolean taxIncluded;
        private String packing;
        private String condGrade;
        private Integer leadDays;
        private String region;
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
        @Select("""
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
                """)
        @org.apache.ibatis.annotations.Lang(org.apache.ibatis.scripting.xmltags.XMLLanguageDriver.class)
        List<DispatchRow> mine(@Param("supplierNo") String supplierNo, @Param("status") String status,
                               @Param("limit") int limit, @Param("offset") long offset);
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
    }
}
