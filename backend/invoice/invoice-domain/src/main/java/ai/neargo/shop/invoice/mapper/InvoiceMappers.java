package ai.neargo.shop.invoice.mapper;

import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcBuyerPreference;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceLine;
import ai.neargo.shop.invoice.entity.IvcNumberRange;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.entity.IvcUserCarrier;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** invoice 域的 Mapper 集合。 */
public final class InvoiceMappers {

    private InvoiceMappers() {
    }

    public interface RegimeProfileMapper extends BaseMapper<IvcRegimeProfile> {
    }

    public interface NumberRangeMapper extends BaseMapper<IvcNumberRange> {

        /**
         * 锁住本期可用的一个号段：门店专用的优先，其次主体共用，同类按起号升序。
         * 必须在事务里调用 —— 锁持有到发票落库提交，这正是「不重号、不跳号」的全部依据。
         */
        @Select("SELECT * FROM ivc_number_range WHERE tenant_no=#{tenant} AND entity_no=#{entity}"
                + " AND regime_code=#{regime} AND period_code=#{period} AND status='ACTIVE'"
                + " AND store_no IN (#{store}, '*') ORDER BY (store_no='*'), start_no LIMIT 1 FOR UPDATE")
        IvcNumberRange lockUsable(@Param("tenant") String tenant, @Param("entity") String entity,
                @Param("regime") String regime, @Param("period") String period, @Param("store") String store);

        /** 推进到下一号；最后一号用掉时同句置 EXHAUSTED。 */
        @Update("UPDATE ivc_number_range SET next_no=next_no+1,"
                + " status=IF(next_no>end_no,'EXHAUSTED',status), updated_at=CURRENT_TIMESTAMP(3)"
                + " WHERE id=#{id} AND status='ACTIVE' AND next_no<=end_no")
        int advance(@Param("id") long id);

        /** 同主体同税制同期同字轨内是否有重叠号段。 */
        @Select("SELECT COUNT(*) FROM ivc_number_range WHERE tenant_no=#{tenant} AND entity_no=#{entity}"
                + " AND regime_code=#{regime} AND period_code=#{period} AND track=#{track}"
                + " AND start_no<=#{end} AND end_no>=#{start}")
        int countOverlap(@Param("tenant") String tenant, @Param("entity") String entity,
                @Param("regime") String regime, @Param("period") String period, @Param("track") String track,
                @Param("start") int start, @Param("end") int end);
    }

    public interface InvoiceMapper extends BaseMapper<IvcInvoice> {

        /**
         * 拿到号段锁之后的复查必须是锁定读：可重复读隔离下，普通 SELECT 读的是本事务第一次读时的快照，
         * 看不见排在前面的请求刚提交的发票，于是会去撞唯一键而不是返回原发票。
         */
        @Select("SELECT * FROM ivc_invoice WHERE tenant_no=#{tenant} AND entity_no=#{entity}"
                + " AND active_source_key=#{key} FOR SHARE")
        IvcInvoice lockActive(@Param("tenant") String tenant, @Param("entity") String entity,
                @Param("key") String key);

        /** 条件累加折让：超出剩余可折让时 0 行，由调用方报 INVOICE_ALLOWANCE_EXCEEDED。 */
        @Update("UPDATE ivc_invoice SET allowed_minor=allowed_minor+#{amount}, updated_at=CURRENT_TIMESTAMP(3)"
                + " WHERE id=#{id} AND status='ISSUED' AND allowed_minor+#{amount}<=total_minor")
        int addAllowance(@Param("id") long id, @Param("amount") long amount);
    }

    public interface InvoiceLineMapper extends BaseMapper<IvcInvoiceLine> {
    }

    public interface AllowanceMapper extends BaseMapper<IvcAllowance> {
    }

    public interface TransmissionMapper extends BaseMapper<IvcTransmission> {
    }

    public interface BuyerPreferenceMapper extends BaseMapper<IvcBuyerPreference> {
    }

    public interface UserCarrierMapper extends BaseMapper<IvcUserCarrier> {
    }
}
