package ai.neargo.shop.member.mapper;

import ai.neargo.shop.member.entity.MbrGuestRegistration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Every claim serializes on the actual persisted guest root, including concurrent claims for different users. */
@Mapper
public interface MemberGuestRegistrationMapper extends BaseMapper<MbrGuestRegistration> {
    /** Exact current ownership is a read fact; merchant lifecycle get/verify stay separate. */
    @Select(
            """
            SELECT * FROM mbr_guest_registration WHERE tenant_no=#{tenant} AND claimed_user_no=#{user}
              AND entity_no=#{entity} AND store_no=#{store} AND guest_no=#{guest}
              AND state='CLAIMED' AND deleted=0
            """)
    MbrGuestRegistration findCurrentClaim(
            @Param("tenant") String tenant,
            @Param("user") String user,
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("guest") String guest);

    /** No LIMIT: a partial claim list would silently hide readable original guest orders. */
    @Select(
            """
            SELECT * FROM mbr_guest_registration WHERE tenant_no=#{tenant} AND claimed_user_no=#{user}
              AND state='CLAIMED' AND deleted=0 ORDER BY entity_no,store_no,guest_no
            """)
    List<MbrGuestRegistration> findCurrentClaims(
            @Param("tenant") String tenant, @Param("user") String user);

    @Select("""
            SELECT * FROM mbr_guest_registration WHERE tenant_no=#{s.identity.tenantNo}
              AND entity_no=#{s.entityNo} AND store_no=#{s.storeNo} AND guest_no=#{guestNo}
              AND deleted=0 FOR UPDATE
            """)
    MbrGuestRegistration lockGuest(@Param("s") Scope scope, @Param("guestNo") String guestNo);
}
