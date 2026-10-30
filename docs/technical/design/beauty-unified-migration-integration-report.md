# Beauty/unified source migration mapping implementation

Applied source mapping: HOST beauty V532–546 → V592–606; inventory beauty V14 original_stock_disposition → V16 and V15 execution_stock_retention → V17. The exact file mapping and SQL hashes/Flyway CRC32 checksums are in [beauty-unified-migration-map.json](./schema/beauty-unified-migration-map.json). This manifest describes integration source, not applied database history.

All 521 unified native migration files in HOST/inventory match f0bb429ac byte-for-byte. All 17 renamed beauty migrations match their original 3615e19db SQL bytes. Numeric versions are unique per native location. Five source contracts/renderers and native descriptors have been updated; four runtime schema verifiers inspect the corresponding new version/script/resource and strict descriptor version. The H2 policy retains its native-owner omission semantics, guard classes, absent tables and retained-statement hashes; source/descriptor SHA256 pins have been refreshed. Historical .superpowers ledgers, gates and frozen migration manifests are unchanged.

Current fixture/test/runner source references have been mapped to the new filenames. Inventory native fixture upgrades now explicitly use V16 absence → V17 installed. The checkout pre-V539 structural renderer slices now end at V598, and their scratch filenames/messages follow that boundary. This includes immutable unified migrations through V591 before the beauty chain; no old unified SQL was edited to accommodate the import. Current beauty database metadata links the source mapping and explicitly preserves the original-version scope of historical execution evidence.

## Verification performed

- Five pure renderers completed; all five separate --check byte comparisons completed successfully.
- Node syntax checks passed for 20 affected renderer/runner/schema-test source files; no tests were executed.
- Static SHA256/CRC32/descriptor-policy consistency and native-location version uniqueness checks completed successfully.
- Pure scratch inventory H2 rendering completed: /private/tmp/beauty-unified-inventory-schema.sql (33 tables, 8 seeds).
- Pure scratch PRIMARY H2 rendering stopped on the unsupported SET @sv_offline_constraint statement from immutable unified V538__stored_value_offline_collection.sql. No parser guard was weakened, no statement was skipped, and no tracked fixture was replaced. Root owns generator compatibility resolution and tracked fixture refresh.
- Java source compilation and all runtime/unit/native/SQL/database/application/Maven/deployment execution are unperformed. These source changes do not establish runtime acceptance.

The first manual renderer attempt stopped because the shared ddl.mjs still contained an existing merge marker; root resolved that dependency and the subsequent five-renderer pass succeeded. No change to shared lexical/parser semantics was made by this migration task.

## Remaining boundaries

Actual target Flyway history must be read and reconciled before deployment. A target with previously applied old beauty version markers cannot be treated as a fresh consumer of this mapping. Do not repair/baseline/edit history, disable validation or manufacture checksum markers.

Tracked schema-test.sql and inventory-h2/V1__inventory_baseline.sql are left to root. The PRIMARY generator failure is an integration blocker for its fixture refresh. Historical task66/manual-counter runner gate properties (such as executesV542), historical fixture paths, pins and archived root evidence retain their original identity. Updating current migration paths does not revalidate those historical runners against the merged schema; their existing gates remain evidence for their original snapshots, not integrated acceptance. Review and re-admit any such runtime scenario in a later authorized phase.

There was no staging or commit. Business services and historical ledgers/frozen manifests were not modified.

Root follow-up: PRIMARY scratch rendering now succeeds (496 tables, 2494 seeds). The merged main loop had bypassed existing exact dynamic-constraint adaptation for ordinary migrations; restored replay() for non-policy migrations while preserving project_migration() for hash-bound native projections. Both tracked H2 fixtures refreshed from pure rendering. No SQL/database/test execution implied.
