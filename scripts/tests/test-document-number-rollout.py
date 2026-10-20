import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class NumberRolloutGuard(unittest.TestCase):
    def test_order_creation_never_calls_legacy_modulo_generator(self):
        text=(ROOT/'backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java').read_text()
        self.assertNotIn('orderNoGenerator.mainOrderNo(',text)
        self.assertNotIn('orderNoGenerator.subOrderNo(',text)
        self.assertIn('DocumentType.TRADE_ORDER',text)
        self.assertIn('DocumentType.TRADE_SUBORDER',text)
    def test_stock_facts_bind_before_business_action(self):
        text=(ROOT/'backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java').read_text()
        self.assertIn('DocumentType.INVENTORY_SYNC',text)
        self.assertIn('numbers.bind(',text)
        text=(ROOT/'backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockAlignmentService.java').read_text()
        self.assertIn('DocumentType.INVENTORY_ALIGNMENT',text)
    def test_stored_value_default_detail_does_not_show_internal_hash(self):
        text=(ROOT/'b-app/src/pages/stored-value-record/index.vue').read_text()
        self.assertNotIn('{{record.businessNo}}',text)
        self.assertIn('record.documentNo',text)
if __name__=='__main__': unittest.main()
