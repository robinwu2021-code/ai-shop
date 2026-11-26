package ai.neargo.shop.merchant.violation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ViolationLedgerImpl implements ViolationLedger {

    private final ViolationMapper violations;

    public ViolationLedgerImpl(ViolationMapper violations) {
        this.violations = violations;
    }

    @Override
    public void record(MchViolation violation) {
        violations.insert(violation);
    }

    @Override
    public List<MchViolation> list(String entityNo) {
        var query = Wrappers.<MchViolation>lambdaQuery();
        if (entityNo != null && !entityNo.isBlank()) {
            query.eq(MchViolation::getEntityNo, entityNo);
        }
        return violations.selectList(query.orderByDesc(MchViolation::getId));
    }
}
