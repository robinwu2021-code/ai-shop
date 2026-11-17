package ai.neargo.shop.merchant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper;
import ai.neargo.shop.merchant.service.MerchantGovernService.SaveQualificationCommand;
import ai.neargo.shop.merchant.service.impl.MerchantGovernServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** TDD-商户迁移同步与渠道关联 AC6：只新建接口不覆盖已有的同类型有效资质。 */
class QualificationCreateOnlyTest {
    private final QualificationMapper mapper = mock(QualificationMapper.class);
    private final MerchantGovernServiceImpl service = mock(MerchantGovernServiceImpl.class, CALLS_REAL_METHODS);

    QualificationCreateOnlyTest() {
        ReflectionTestUtils.setField(service, "qualificationMapper", mapper);
    }

    private static SaveQualificationCommand cmd(String qualNo) {
        return new SaveQualificationCommand(qualNo, "BUSINESS_LICENSE", "營業登記", "12345678", null, null);
    }

    @Test void conflictsWhenSameTypeIsAlreadyValid() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class, () -> service.createQualification("E1", cmd(null), "OP1"));
        verify(mapper, never()).insert(any(MchQualification.class));
        verify(mapper, never()).updateById(any(MchQualification.class));
    }

    @Test void createsWhenNoValidRecordOfThatType() {
        when(mapper.selectCount(any())).thenReturn(0L);
        var vo = service.createQualification("E1", cmd(null), "OP1");
        assertEquals("E1", vo.entityNo());
        assertEquals(MchQualification.VALID, vo.status());
        verify(mapper).insert(any(MchQualification.class));
        verify(mapper, never()).updateById(any(MchQualification.class));
    }

    @Test void refusesQualNoSoItCannotBeUsedAsAnUpdate() {
        assertThrows(BizException.class, () -> service.createQualification("E1", cmd("Q1"), "OP1"));
        verifyNoInteractions(mapper);
    }
}
