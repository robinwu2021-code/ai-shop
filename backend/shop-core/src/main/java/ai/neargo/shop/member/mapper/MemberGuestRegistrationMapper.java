package ai.neargo.shop.member.mapper;

import ai.neargo.shop.member.entity.MbrGuestRegistration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Every claim serializes on the actual persisted guest root, including concurrent claims for different users. */
@Mapper
public interface MemberGuestRegistrationMapper extends BaseMapper<MbrGuestRegistration> {
    @Select("""
            SELECT * FROM mbr_guest_registration WHERE tenant_no=#{s.identity.tenantNo}
              AND entity_no=#{s.entityNo} AND store_no=#{s.storeNo} AND guest_no=#{guestNo}
              AND deleted=0 FOR UPDATE
            """)
    MbrGuestRegistration lockGuest(@Param("s") Scope scope, @Param("guestNo") String guestNo);
}
