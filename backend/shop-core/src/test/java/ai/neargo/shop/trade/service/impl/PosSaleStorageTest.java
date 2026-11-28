package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.trade.entity.OrdOrder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class PosSaleStorageTest {

    @Test
    void generalOrderReadsUseOnlyColumnsAvailableInCanonicalStorage() {
        var table = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "pos-sale-storage"), OrdOrder.class);
        assertThat(table.getAllSqlSelect())
                .doesNotContain("pos_activity_facts_version", "pos_member_no", "pos_member_at_payment");
    }
}
