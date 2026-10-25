package ai.neargo.shop.inventory.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Native inventory factory owns every query; no business database or parallel balance table is
 * used.
 */
public interface InventoryExactSupportMapper {
    record PolicyRow(
            long version,
            String currency,
            String costMethod,
            Long unitCostMinor,
            int batchTracked) {}

    record HistoryRow(String requestHash, String afterSnapshot) {}

    record StockRow(BigDecimal onHand, BigDecimal reserved, long version) {}

    record BatchRow(int batchTracked, String currency) {}

    @Select(
            "<script>SELECT valuation_version AS version, cost_currency AS currency, cost_method AS"
                + " costMethod, default_cost_minor AS unitCostMinor, track_batch AS batchTracked"
                + " FROM inv_item WHERE owner_id=#{owner} AND item_id=#{item} AND"
                + " status='ACTIVE'<if test='lock'> FOR UPDATE</if></script>")
    List<PolicyRow> policy(
            @Param("owner") String owner, @Param("item") String item, @Param("lock") boolean lock);

    @Select(
            "SELECT request_hash AS requestHash,after_snapshot AS afterSnapshot FROM"
                    + " inv_item_valuation_history WHERE owner_id=#{owner} AND"
                    + " operation_key=#{operation}")
    List<HistoryRow> history(@Param("owner") String owner, @Param("operation") String operation);

    @Select("SELECT COUNT(*) FROM inv_ledger WHERE owner_id=#{owner} AND item_id=#{item}")
    long ledgerCount(@Param("owner") String owner, @Param("item") String item);

    @Update(
            "UPDATE inv_item SET"
                + " cost_currency=#{currency},cost_method=#{method},default_cost_minor=#{cost},track_batch=#{batch},valuation_version=valuation_version+1,updated_by=#{actor}"
                + " WHERE owner_id=#{owner} AND item_id=#{item} AND valuation_version=#{version}")
    int updatePolicy(
            @Param("owner") String owner,
            @Param("item") String item,
            @Param("actor") String actor,
            @Param("version") long version,
            @Param("currency") String currency,
            @Param("method") String method,
            @Param("cost") Long cost,
            @Param("batch") int batch);

    @Insert(
            "INSERT INTO"
                + " inv_item_valuation_history(owner_id,item_id,operation_key,request_hash,before_snapshot,after_snapshot,actor_no,created_by)"
                + " VALUES(#{owner},#{item},#{operation},#{hash},#{before},#{after},#{actor},#{actor})")
    int insertHistory(
            @Param("owner") String owner,
            @Param("item") String item,
            @Param("operation") String operation,
            @Param("hash") String hash,
            @Param("before") String before,
            @Param("after") String after,
            @Param("actor") String actor);

    @Select(
            "SELECT on_hand AS onHand,reserved,version FROM inv_stock_balance "
                    + "WHERE owner_id=#{owner} AND item_id=#{item} AND location_id=#{location}")
    List<StockRow> stock(
            @Param("owner") String owner,
            @Param("item") String item,
            @Param("location") String location);

    @Select(
            "SELECT track_batch AS batchTracked,cost_currency AS currency FROM inv_item WHERE"
                    + " owner_id=#{owner} AND item_id=#{item}")
    List<BatchRow> batch(@Param("owner") String owner, @Param("item") String item);

    @Update(
            "UPDATE inv_stock_lot SET qty=qty+#{delta} WHERE owner_id=#{owner} AND lot_ref=#{lot} "
                    + "AND item_id=#{item} AND location_id=#{location} AND qty+#{delta}>=0")
    int adjustLot(
            @Param("owner") String owner,
            @Param("item") String item,
            @Param("location") String location,
            @Param("lot") String lot,
            @Param("delta") BigDecimal delta);

    @Insert(
            "INSERT INTO"
                + " inv_stock_lot(lot_ref,owner_id,item_id,location_id,batch_no,qty,unit_cost_minor,currency,expire_at,source_doc_no,source_line_no,created_by)"
                + " VALUES(#{lot},#{owner},#{item},"
                + "#{location},#{batch},#{qty},#{cost},#{currency},#{expiry},#{doc},#{line},#{actor})")
    int insertLot(
            @Param("owner") String owner,
            @Param("item") String item,
            @Param("location") String location,
            @Param("lot") String lot,
            @Param("batch") String batch,
            @Param("qty") BigDecimal qty,
            @Param("cost") Long cost,
            @Param("currency") String currency,
            @Param("expiry") LocalDate expiry,
            @Param("doc") String doc,
            @Param("line") int line,
            @Param("actor") String actor);
}
