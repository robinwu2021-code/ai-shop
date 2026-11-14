package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.entity.OrdOfflineRecordEvent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** App 外收款记录的两张表；查询一律带主体与门店，没有按号跨店取的口子。 */
public final class OfflineRecordMappers {
    private OfflineRecordMappers() {}

    /** 记录行：幂等键查重、更正与作废前锁行、按实收时间汇总。 */
    public interface RecordMapper extends BaseMapper<OrdOfflineRecord> {
        @Select(
                "SELECT * FROM ord_offline_record WHERE tenant_no='MAIN' AND entity_no=#{entity}"
                        + " AND store_no=#{store} AND idem_key_hash=#{key} AND deleted=0")
        OrdOfflineRecord findKey(
                @Param("entity") String entity, @Param("store") String store, @Param("key") String key);

        @Select(
                "SELECT * FROM ord_offline_record WHERE tenant_no='MAIN' AND entity_no=#{entity}"
                        + " AND store_no=#{store} AND record_no=#{no} AND deleted=0 FOR UPDATE")
        OrdOfflineRecord lock(
                @Param("entity") String entity, @Param("store") String store, @Param("no") String no);

        @Select(
                "SELECT COALESCE(SUM(amount_minor),0) FROM ord_offline_record WHERE tenant_no='MAIN'"
                        + " AND entity_no=#{entity} AND store_no=#{store} AND status='RECORDED'"
                        + " AND received_at>=#{from} AND received_at<#{to} AND deleted=0")
        long sumRecorded(
                @Param("entity") String entity,
                @Param("store") String store,
                @Param("from") LocalDateTime from,
                @Param("to") LocalDateTime to);
    }

    /** 留痕：只插、按记录号顺序读。 */
    public interface EventMapper extends BaseMapper<OrdOfflineRecordEvent> {}
}
