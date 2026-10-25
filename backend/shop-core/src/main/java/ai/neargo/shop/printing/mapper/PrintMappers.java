package ai.neargo.shop.printing.mapper;

import ai.neargo.shop.printing.entity.PrnAttempt;
import ai.neargo.shop.printing.entity.PrnDevice;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.entity.PrnRoute;
import ai.neargo.shop.printing.entity.PrnTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Printing owns its schema independently from restaurant adapters. */
public final class PrintMappers {
    private PrintMappers() { }
    @Mapper public interface DeviceMapper extends BaseMapper<PrnDevice> { }
    @Mapper public interface TemplateMapper extends BaseMapper<PrnTemplate> { }
    @Mapper public interface RouteMapper extends BaseMapper<PrnRoute> { }
    @Mapper public interface JobMapper extends BaseMapper<PrnJob> {
        @org.apache.ibatis.annotations.Update("UPDATE prn_printer_runtime SET active_job_no=NULL, active_attempt_no=NULL, lease_owner=NULL, lease_token_hash=NULL, lease_until=NULL, blocked_reason=NULL, updated_at=CURRENT_TIMESTAMP, revision=revision+1 WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND printer_no=#{device} AND active_attempt_no=#{attempt}")
        int releaseRuntime(@org.apache.ibatis.annotations.Param("tenant") String tenant,
                @org.apache.ibatis.annotations.Param("entity") String entity,
                @org.apache.ibatis.annotations.Param("device") String device,
                @org.apache.ibatis.annotations.Param("attempt") String attempt);
    }
    @Mapper public interface AttemptMapper extends BaseMapper<PrnAttempt> { }
}
