package ai.neargo.shop.invoice.mapper;

import ai.neargo.shop.invoice.entity.IvcInvoiceApplication;
import ai.neargo.shop.invoice.entity.IvcInvoiceApplicationRevision;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public final class InvoiceApplicationMappers {

    private InvoiceApplicationMappers() {
    }

    public interface ApplicationMapper extends BaseMapper<IvcInvoiceApplication> {

        /**
         * Reserve a real row before taking the source lock. Concurrent submissions for a source
         * serialize on the unique source key without an absent-row gap lock.
         */
        @Insert("""
                INSERT INTO ivc_invoice_application
                  (tenant_no,entity_no,store_no,application_no,source_type,source_no,
                   current_revision,status,created_by,created_at)
                VALUES
                  (#{tenantNo},#{entityNo},#{storeNo},#{applicationNo},#{sourceType},#{sourceNo},
                   #{currentRevision},#{status},#{createdBy},#{createdAt})
                ON DUPLICATE KEY UPDATE id=id
                """)
        void reserve(IvcInvoiceApplication application);
    }

    public interface RevisionMapper extends BaseMapper<IvcInvoiceApplicationRevision> {
    }

}
