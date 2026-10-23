#!/usr/bin/env python3
"""Run food JDBC tests on a fresh, loopback-only, tmpfs MySQL container; never use app credentials."""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NAME = 'food-primary-' + uuid.uuid4().hex[:12]
IMAGE = 'mysql:9.7.2'


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.STDOUT).strip()


def main():
    docker('image', 'inspect', IMAGE)
    created = False
    try:
        docker('run', '--pull', 'never', '-d', '--name', NAME, '-p', '127.0.0.1::3306',
               '--tmpfs', '/var/lib/mysql', '-e', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', IMAGE)
        created = True
        deadline = time.monotonic() + 60
        while True:
            try:
                # The entrypoint starts a temporary socket-only server while initializing.
                # JDBC needs the final TCP listener, not mysqladmin's socket ping.
                docker('exec', NAME, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot',
                       '--execute', 'SELECT 1')
                break
            except subprocess.CalledProcessError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Disposable MySQL startup timed out')
                time.sleep(1)
        binding = docker('port', NAME, '3306/tcp')
        if not binding.startswith('127.0.0.1:') or '\n' in binding:
            raise RuntimeError('Expected one loopback-only test port')
        # Apply actual new CORE resources in the native engine, independently of H2 compatibility.
        core_dir = ROOT / 'backend/shop-app/src/main/resources/db/migration'
        core_sql = 'CREATE DATABASE food_nonpay_contract; USE food_nonpay_contract;\n' + '\n'.join(
            (core_dir / name).read_text() for name in [
                'V556__shared_print_delivery.sql', 'V557__industry_fulfillment_receipt.sql'])
        subprocess.run(['docker', 'exec', '-i', NAME, 'mysql', '-uroot'], input=core_sql,
                       text=True, check=True, stdout=subprocess.DEVNULL)
        physical = docker('exec', NAME, 'mysql', '-N', '-uroot', '--execute',
                          "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='food_nonpay_contract' "
                          "AND table_name IN ('prn_device','prn_template','prn_route','prn_job','prn_attempt','ord_industry_fulfillment')")
        if physical != '6':
            raise RuntimeError('Native shared printing/original fulfillment migrations were not fully applied')
        print('Native CORE V556/V557: 6 real tables applied in disposable database')
        env = os.environ.copy()
        env.update(FOOD_TEST_DISPOSABLE='true', FOOD_TEST_JDBC_URL='jdbc:mysql://' + binding + '/mysql')
        with tempfile.NamedTemporaryFile(prefix='food-primary-tests-', suffix='.log', delete=False) as log:
            result = subprocess.run(['mvn', '-o', '-f', 'backend-plugins/shop-industry-food/pom.xml',
                                     '-pl', 'food-server', '-am', 'test', '-Dtest=ProductionConsumptionListMapperTest,KitchenQueueMapperTest,KitchenQuantityPolicyTest,KitchenDispatchServiceTest,KitchenReturnQuantityTest,DiningPickupVerificationServiceTest,FoodPrintDocumentAssemblerTest,BusinessCloseEvidenceTest,ProductionConsumptionTest,DiningQueueContractTest,DiningQueuePrimaryDatabaseTest,DiningTransactionRecoveryServiceTest,SalesQuotaClaimServiceTest,DiningOrderServiceTest,DiningCheckoutServiceTest,DiningReturnServiceTest,ClosedCartBuyerReadTest,DiningPricingServiceTest,DiningPriceSourcePolicyTest,DiningPricingFactsServiceTest,InternalPricingSourceApiTest,CartServiceTest,CartLinePolicyTest,FoodPrimaryDatabaseTest,FoodIdempotencyTest,FoodOutboxWiringTest,StoreOperationServiceTest,StorePolicyPublicationTest,ServiceCalendarServiceTest,TableServiceTest,TableConfigurationTest,DiningSessionTest,CheckServiceTest,ReservationServiceTest,DishServiceTest,OptionGroupServiceTest,OptionGroupItemsTest,SelectionRuleTest,ModifierServiceTest,FoodModifierPrimaryDatabaseTest,ConfigRevisionServiceTest,OptionPublicationPolicyTest,FoodConfigPublicationPrimaryDatabaseTest,BundleServiceTest,BundleDefinitionPolicyTest,FoodBundlePrimaryDatabaseTest,BundlePublicationServiceTest,RecipeServiceTest,RecipePublicationServiceTest,MenuServiceTest,MenuCompositionServiceTest,MenuContextServiceTest,MenuChannelBindingServiceTest,StationServiceTest,SalesRestrictionServiceTest,CatalogPublicationServiceTest,CatalogServiceTest,MenuPublicationSourceTest,MenuConflictPolicyTest,MenuScheduleServiceTest,MenuSchedulePolicyTest,FoodRecipePrimaryDatabaseTest,FoodBundlePublicationPrimaryDatabaseTest,FoodOptionGroupPrimaryDatabaseTest,FoodDishPrimaryDatabaseTest,FoodServerPrimaryDatabaseTest',
                                     '-Dsurefire.failIfNoSpecifiedTests=false'], cwd=ROOT, env=env,
                                    stdout=log, stderr=subprocess.STDOUT)
        print('Test log:', log.name)
        report = ROOT / 'backend-plugins/shop-industry-food/food-core/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodPrimaryDatabaseTest.xml'
        if result.returncode != 0:
            print('\n'.join(Path(log.name).read_text().splitlines()[-45:]))
            raise RuntimeError('Food primary integration tests failed')
        suite = ET.parse(report).getroot()
        if int(suite.attrib['tests']) < 20 or any(int(suite.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Expected all disposable integration tests to execute and pass')
        server_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodServerPrimaryDatabaseTest.xml'
        server = ET.parse(server_report).getroot()
        if int(server.attrib['tests']) != 1 or any(int(server.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone MySQL test did not execute and pass')
        dish_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodDishPrimaryDatabaseTest.xml'
        dish = ET.parse(dish_report).getroot()
        if int(dish.attrib['tests']) != 1 or any(int(dish.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone dish MySQL typed test did not execute and pass')
        group_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodOptionGroupPrimaryDatabaseTest.xml'
        group = ET.parse(group_report).getroot()
        if int(group.attrib['tests']) != 1 or any(int(group.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone option-group MySQL typed test did not execute and pass')
        modifier_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodModifierPrimaryDatabaseTest.xml'
        modifier = ET.parse(modifier_report).getroot()
        if int(modifier.attrib['tests']) != 1 or any(int(modifier.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone modifier MySQL typed test did not execute and pass')
        publication_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodConfigPublicationPrimaryDatabaseTest.xml'
        publication = ET.parse(publication_report).getroot()
        if int(publication.attrib['tests']) != 1 or any(int(publication.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone publication MySQL typed test did not execute and pass')
        bundle_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodBundlePrimaryDatabaseTest.xml'
        bundle = ET.parse(bundle_report).getroot()
        if int(bundle.attrib['tests']) != 1 or any(int(bundle.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone bundle MySQL typed test did not execute and pass')
        bundle_publication_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodBundlePublicationPrimaryDatabaseTest.xml'
        bundle_publication = ET.parse(bundle_publication_report).getroot()
        if int(bundle_publication.attrib['tests']) != 1 or any(int(bundle_publication.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone bundle publication MySQL typed test did not execute and pass')
        recipe_report = ROOT / ('backend-plugins/shop-industry-food/food-server/target/surefire-reports/'
                                'TEST-ai.neargo.shop.food.scenario.FoodRecipePrimaryDatabaseTest.xml')
        recipe = ET.parse(recipe_report).getroot()
        if int(recipe.attrib['tests']) != 1 or any(int(recipe.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone recipe MySQL typed test did not execute and pass')
        guards = 0
        for name, minimum in [('ProductionConsumptionListMapperTest', 3), ('KitchenQueueMapperTest', 1), ('KitchenQuantityPolicyTest', 7), ('KitchenDispatchServiceTest', 4), ('KitchenReturnQuantityTest', 4), ('DiningPickupVerificationServiceTest', 9), ('FoodPrintDocumentAssemblerTest', 6), ('BusinessCloseEvidenceTest', 7), ('ProductionConsumptionTest', 11), ('DiningQueueContractTest', 8), ('DiningQueuePrimaryDatabaseTest', 3), ('DiningTransactionRecoveryServiceTest', 4), ('SalesQuotaClaimServiceTest', 5), ('DiningOrderServiceTest', 5), ('DiningCheckoutServiceTest', 7), ('DiningReturnServiceTest', 4), ('ClosedCartBuyerReadTest', 2), ('DiningPricingServiceTest', 6), ('DiningPriceSourcePolicyTest', 4), ('DiningPricingFactsServiceTest', 9), ('CartServiceTest', 10), ('CartLinePolicyTest', 11), ('FoodIdempotencyTest', 7), ('FoodOutboxWiringTest', 7), ('StoreOperationServiceTest', 5), ('StorePolicyPublicationTest', 10), ('ServiceCalendarServiceTest', 6), ('TableServiceTest', 14), ('TableConfigurationTest', 18), ('DiningSessionTest', 10), ('CheckServiceTest', 22), ('ReservationServiceTest', 3), ('DishServiceTest', 4), ('OptionGroupServiceTest', 5), ('OptionGroupItemsTest', 6), ('SelectionRuleTest', 6), ('ModifierServiceTest', 7), ('ConfigRevisionServiceTest', 10), ('OptionPublicationPolicyTest', 4), ('BundleServiceTest', 7), ('BundleDefinitionPolicyTest', 4), ('BundlePublicationServiceTest', 8), ('RecipeServiceTest', 12), ('RecipePublicationServiceTest', 8), ('MenuServiceTest', 7), ('MenuCompositionServiceTest', 4), ('MenuContextServiceTest', 2), ('MenuChannelBindingServiceTest', 5), ('StationServiceTest', 4), ('SalesRestrictionServiceTest', 5), ('CatalogPublicationServiceTest', 10), ('CatalogServiceTest', 10), ('MenuPublicationSourceTest', 7), ('MenuConflictPolicyTest', 6), ('MenuScheduleServiceTest', 5), ('MenuSchedulePolicyTest', 4)]:
            report = ROOT / ('backend-plugins/shop-industry-food/food-core/target/surefire-reports/'
                             + 'TEST-ai.neargo.shop.food.scenario.' + name + '.xml')
            guard = ET.parse(report).getroot()
            if int(guard.attrib['tests']) < minimum or any(int(guard.attrib[k]) for k in ['failures', 'errors', 'skipped']):
                raise RuntimeError('Expected executed write guards: ' + name)
            guards += int(guard.attrib['tests'])
        price_report = ROOT / ('backend-plugins/shop-industry-food/food-server/target/surefire-reports/'
                               'TEST-ai.neargo.shop.food.scenario.InternalPricingSourceApiTest.xml')
        pricing = ET.parse(price_report).getroot()
        if int(pricing.attrib['tests']) < 1 or any(int(pricing.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Internal pricing source contract tests did not execute and pass')
        with tempfile.NamedTemporaryFile(prefix='food-spec-authority-tests-', suffix='.log', delete=False) as spec_log:
            spec_result = subprocess.run(['mvn', '-o', '-f', 'backend/pom.xml', '-pl', 'shop-core', '-am',
                                          'test', '-Dtest=SpecValueCatalogQueryPortTest',
                                          '-Dsurefire.failIfNoSpecifiedTests=false'], cwd=ROOT, env=env,
                                         stdout=spec_log, stderr=subprocess.STDOUT)
        print('Original spec authority log:', spec_log.name)
        spec_report = ROOT / 'backend/shop-core/target/surefire-reports/TEST-ai.neargo.shop.product.SpecValueCatalogQueryPortTest.xml'
        if spec_result.returncode != 0:
            print('\n'.join(Path(spec_log.name).read_text().splitlines()[-45:]))
            raise RuntimeError('Original spec authority MySQL test failed')
        spec = ET.parse(spec_report).getroot()
        if int(spec.attrib['tests']) < 4 or any(int(spec.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Expected executed original spec authority guards')
        print('Original spec authority MySQL:', spec.attrib['tests'], 'passed; no skipped tests')
        print('Food primary MySQL:', suite.attrib['tests'], '+ guards', guards,
              '+ standalone', int(server.attrib['tests']) + int(dish.attrib['tests']) + int(group.attrib['tests']) + int(modifier.attrib['tests']) + int(publication.attrib['tests']) + int(bundle.attrib['tests']) + int(bundle_publication.attrib['tests']) + int(recipe.attrib['tests']) + int(pricing.attrib['tests']), 'passed; no skipped tests')
    finally:
        if created:
            docker('rm', '-f', NAME)


if __name__ == '__main__':
    main()
