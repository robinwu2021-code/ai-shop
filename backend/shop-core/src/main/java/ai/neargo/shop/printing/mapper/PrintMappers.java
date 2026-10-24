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
    @Mapper public interface JobMapper extends BaseMapper<PrnJob> { }
    @Mapper public interface AttemptMapper extends BaseMapper<PrnAttempt> { }
}
