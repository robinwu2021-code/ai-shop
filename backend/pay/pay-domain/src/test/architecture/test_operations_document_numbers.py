from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2] / 'main/java/ai/neargo/shop/pay'
POINTS = {
'entitlement/service/impl/EntitlementEventDeliveryServiceImpl.java': ['ENTITLEMENT_EXCEPTION','ENTITLEMENT_EXCEPTION_ACTION'],
'migration/service/LegacyCouponIssueWriter.java': ['LEGACY_COUPON_ISSUE'],
'migration/service/EntitlementMigrationPlanWriter.java': ['ENTITLEMENT_MIGRATION_PLAN'],
'migration/service/EntitlementMigrationStageWriter.java': ['ENTITLEMENT_MIGRATION_STAGE'],
'migration/service/LegacyCouponMigrationImportWriter.java': ['ENTITLEMENT_MIGRATION_IMPORT'],
'entitlement/service/impl/EntitlementReconciliationServiceImpl.java': ['ENTITLEMENT_RECON_RUN', 'ENTITLEMENT_RECON_DIFF'],
'entitlement/service/impl/EntitlementExceptionServiceImpl.java': ['ENTITLEMENT_EXCEPTION'],
'entitlement/service/EntitlementExceptionActionWriter.java': ['ENTITLEMENT_EXCEPTION_ACTION'],
'service/impl/WithdrawServiceImpl.java': ['WITHDRAWAL'],
'impl/SettleBatchServiceImpl.java': ['SETTLEMENT_BATCH'],
'impl/SettleServiceImpl.java': ['SETTLEMENT_BILL', 'SETTLEMENT_INVOICE_REQUEST'],
'service/impl/PaymentLedgerServiceImpl.java': ['PAYMENT_ATTEMPT'],
}
class OperationsDocumentCreationTest(unittest.TestCase):
    def test_every_owned_fact_binds_inside_its_writer(self):
        for path, types in POINTS.items():
            source = (ROOT / path).read_text()
            for kind in types:
                with self.subTest(path=path, kind=kind):
                    self.assertRegex(source, r'(?s)documents\.bind\(\s*(?:DocumentType\.)?' + kind + r'\s*,')
if __name__ == '__main__': unittest.main()
