package ai.neargo.shop.message.mapper;

import ai.neargo.shop.message.entity.StoredValueNotifyTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

/** Insert before reserving quota; the business unique key arbitrates concurrent event deliveries. */
public interface StoredValueNotifyTaskMapper extends BaseMapper<StoredValueNotifyTask> {
    @Insert("""
            INSERT IGNORE INTO notify_stored_value_task
              (tenant_no,usage_no,recipient,scene,event_no,store_no,document_no,values_json,status,attempts,updated_at)
            VALUES (#{tenantNo},#{usageNo},#{recipient},#{scene},#{eventNo},#{storeNo},#{documentNo},
                    #{valuesJson},'PREPARING',0,CURRENT_TIMESTAMP)
            """)
    int insertOnce(StoredValueNotifyTask task);
}
