package ai.neargo.shop.merchant.mapper;

import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchResourceOccupation;
import ai.neargo.shop.merchant.entity.MchResourceOccupationSegment;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** All writes follow owner → occupation → personnel → resource → bucket ordering in one local transaction. */
@Mapper
public interface ResourceOccupationLifecycleMapper {
    @Select("SELECT * FROM mch_resource_occupation WHERE tenant_no=#{s.tenantNo} AND entity_no=#{s.entityNo} AND store_no=#{s.storeNo} AND occupation_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    MchResourceOccupation lock(@Param("s") ResourceCommandScopeRow scope, @Param("no") String occupationNo);

    @Select("SELECT * FROM mch_resource_occupation WHERE tenant_no=#{s.tenantNo} AND entity_no=#{s.entityNo} AND store_no=#{s.storeNo} AND occupation_no=#{no} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    MchResourceOccupation read(@Param("s") ResourceCommandScopeRow scope, @Param("no") String occupationNo);

    @Select("SELECT * FROM mch_resource_occupation_segment WHERE tenant_no=#{s.tenantNo} AND entity_no=#{s.entityNo} AND occupation_no=#{no} AND deleted=0 ORDER BY segment_no")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<MchResourceOccupationSegment> segments(@Param("s") ResourceCommandScopeRow scope, @Param("no") String occupationNo);

    @Update("UPDATE mch_resource_occupation SET state=#{state},ended_at=#{ended},current_segment_set_hash=#{hash},revision=revision+1,version=version+1,updated_at=#{at},updated_by=#{actor},stop_obligation=IF(#{clearStop},0,stop_obligation),conflict_obligation=IF(#{clearStop},0,conflict_obligation) WHERE tenant_no=#{s.tenantNo} AND entity_no=#{s.entityNo} AND store_no=#{s.storeNo} AND occupation_no=#{no} AND revision=#{revision} AND deleted=0 AND revision<9223372036854775807 AND version<9223372036854775807")
    int advance(@Param("s") ResourceCommandScopeRow scope, @Param("no") String occupationNo,
            @Param("revision") long revision, @Param("state") String state, @Param("ended") LocalDateTime ended,
            @Param("hash") String segmentSetHash, @Param("clearStop") boolean clearStop,
            @Param("at") LocalDateTime at, @Param("actor") String actor);

    @Update("UPDATE mch_resource_occupation_segment SET actual_end_at=#{at},close_command_no=#{command},cleaning_from=#{cleanFrom},cleaning_until=#{cleanUntil},version=version+1,updated_at=#{at},updated_by=#{actor} WHERE tenant_no=#{s.tenantNo} AND entity_no=#{s.entityNo} AND occupation_no=#{occupation} AND segment_no=#{segment} AND version=#{version} AND actual_end_at IS NULL AND deleted=0 AND version<9223372036854775807")
    int close(@Param("s") ResourceCommandScopeRow scope, @Param("occupation") String occupation,
            @Param("segment") String segment, @Param("version") long version, @Param("command") String command,
            @Param("cleanFrom") LocalDateTime cleanFrom, @Param("cleanUntil") LocalDateTime cleanUntil,
            @Param("at") LocalDateTime at, @Param("actor") String actor);

    @Update("UPDATE mch_resource_occupation_segment SET cleaning_completed_at=#{at},cleaning_command_no=#{command},version=version+1,updated_at=#{at},updated_by=#{actor} WHERE tenant_no=#{s.tenantNo} AND entity_no=#{s.entityNo} AND occupation_no=#{occupation} AND segment_no=#{segment} AND version=#{version} AND cleaning_from IS NOT NULL AND cleaning_completed_at IS NULL AND actual_end_at IS NOT NULL AND deleted=0 AND version<9223372036854775807")
    int clean(@Param("s") ResourceCommandScopeRow scope, @Param("occupation") String occupation,
            @Param("segment") String segment, @Param("version") long version, @Param("command") String command,
            @Param("at") LocalDateTime at, @Param("actor") String actor);

    @Update("UPDATE mch_resource_occupation_bucket SET retired_at=#{at},retire_command_no=#{command},version=version+1,updated_at=#{at},updated_by=#{actor} WHERE tenant_no=#{s.tenantNo} AND entity_no=#{s.entityNo} AND projection_no=#{projection} AND version=#{version} AND retired_at IS NULL AND deleted=0 AND version<9223372036854775807")
    int retire(@Param("s") ResourceCommandScopeRow scope, @Param("projection") String projection,
            @Param("version") long version, @Param("command") String command,
            @Param("at") LocalDateTime at, @Param("actor") String actor);

    @Update("UPDATE mch_resource_bucket SET occupied_qty=occupied_qty+#{occupied},cleaning_qty=cleaning_qty+#{cleaning},version=version+1,updated_at=#{at},updated_by=#{actor} WHERE tenant_no=#{s.tenantNo} AND entity_no=#{s.entityNo} AND bucket_no=#{bucket} AND version=#{version} AND deleted=0 AND version<9223372036854775807 AND CAST(occupied_qty AS DECIMAL(20,0))+#{occupied} BETWEEN 0 AND 2147483647 AND CAST(cleaning_qty AS DECIMAL(20,0))+#{cleaning} BETWEEN 0 AND 2147483647 AND CAST(held_qty AS DECIMAL(20,0))+confirmed_qty+occupied_qty+cleaning_qty+#{occupied}+#{cleaning}<=capacity")
    int delta(@Param("s") ResourceCommandScopeRow scope, @Param("bucket") String bucket,
            @Param("version") long version, @Param("occupied") long occupied, @Param("cleaning") long cleaning,
            @Param("at") LocalDateTime at, @Param("actor") String actor);
}
