package ai.neargo.shop.invbridge.mapper;

import ai.neargo.shop.invbridge.entity.ExecutionRetentionMirrorEvent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Actual HOST historical rows only; no current managed set, delivery status, or synthetic mirror evidence. */
@Mapper
public interface ExecutionRetentionMirrorMapper {
    @Select("""
        <script>
        SELECT id,event_no,aggregate_type,aggregate_id,event_type,payload,created_at,
               source_system,source_event_no,schema_version,payload_hash,stream_key,stream_sequence,
               routing_mode,occurred_at
        FROM sys_outbox WHERE aggregate_type='INVENTORY' AND aggregate_id IN
        <foreach collection="refs" item="ref" open="(" separator="," close=")">#{ref}</foreach>
        AND event_type LIKE 'INV_MIRROR_%' ORDER BY id
        </script>
        """)
    List<ExecutionRetentionMirrorEvent> candidates(@Param("refs") List<String> refs);

}
