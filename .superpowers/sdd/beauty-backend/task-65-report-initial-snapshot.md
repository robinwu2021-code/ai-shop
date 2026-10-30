# Task65 design report

Status: DONE — complete bounded M2a/M2b/M2c design frozen for independent review; root identity snapshot ruling incorporated, no pending mandatory owner decision. No product implementation or runtime verification is claimed.

Baseline HEAD: `df39678f30f530388c3c7dbe4e5d0d6557d7755b`; branch `codex/beauty-foundation`; sole worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`. Accepted M1 code is `fb8fc86d50a394422be408b94278d50989d8ef8a`. Initial git status was clean. Only owned outputs are `bm05-m2-counter-source-design.md` and this report.

## Deliverable and decisions

Design SHA256: `33559659d148155151669c6f3e65d4fd65832a9a20ff6445322b5da694ed32ae`; bytes: 69065.

- USER and GUEST both receive explicit MERCHANT_RECORDED_IN_PERSON authority without C login; USER resolves canonical scoped member identity, not caller userNo. Existing C lane/hash bytes remain.
- Root ruled quote-snapshot semantics: claim after prepare observation does not invalidate an issued unexpired GUEST quote or rewrite its later acceptance/intent. New prepare reads current identity; already claimed requires canonical USER selection. Core confirmation alone owns acceptance. No new member purchase table/fence/acceptance protocol. New counter USER revision is null, GUEST uses actual member revision; primitive-long legacy constructor remains.
- V542 includes all six actual nullable-party aggregates, including V541 admission; original keys receive independent nonnull namespace defense. Actual maxima V541/V14/V8, candidates V542/V9 absent. Applied migration files remain untouched.
- Counter-only stage freezes order/suborder/manifest/authority once and reuses original intent/stock/persistence. STOCK_RESERVE_V1 is separate from existing STOCK_CONFIRM_V1; old scanner excludes counter before LIMIT, unknown is never proof for compensation, no human auth recreated.
- Beauty manual observation retains separate canonical type/revision/hash and manual READY; old financial pairs/READY remain unchanged. Existing binding quantities/lineage and canStart=false remain.
- Current C/MP155 entitlement is member-owned and rechecked outside locks, historical GUEST remains. CurrentClaims full set has a documented scaling risk, no partial-success pagination fiction.
- Supported discount subset is real legacy merchant FULL_CUT. Existing pmt user ledger/unified per-user/WAIT_PAY requirements are not weakened; unsupported choices/effects reject explicitly. Root agreed this bounded subset.
- Existing bill/order/submission/work-order readers gain actual manual declaration provenance/NOT_APPLICABLE_MANUAL, with separate subtotal and no Pay dependence.

## Actual checks performed

Read-only `git status --short` and `git rev-parse HEAD`; targeted rg/file reads of brief, accepted Task63 §§5/6/8, main TDD sections, local standards and listed current source; read-only migration directory version scan and candidate existence check; SHA256/byte pins below. No tests/build/compiler/native/Docker/HTTP/deploy or git mutations. Final read-only git status shows root-owned progress/brief/main-TDD edits only; these two new .superpowers outputs are ignored by repository status and were verified directly on disk. Root documents were not edited by this agent. Several early guessed source paths did not exist; actual paths were found with rg and are recorded below (OrdCreateIntent is trade/order/entity; RefundExecutionStore is trade/order/refund/service; BoundActionContext is shop-base-auth). No result from a failed path read is claimed as source evidence.

The source table pins the bytes available during audit; not every listed file was read in full. Targeted method/constraint inspection establishes the cited claim. No old preparatory /tmp notes were used as truth.

## Self-review and outstanding root review

Coverage maps requirements to sections/tests in design §7. Type domains and old constructors are explicit. Five high-risk inputs map to A3/A5/A6/A7/B2/C2/C3. Final text scan found no removed member-witness DTO/table/path or unresolved TBD contract. The manual source projection explicitly supports both old M1 USER/C and new counter authority from one RR owner snapshot. No product passing-test claims. Root owns main/ADR/schema/API/Java/generator reconciliation and new code release.

Root clarification was read from the updated brief and incorporated. The earlier member purchase-witness option was discarded completely from the final design/inventory. Existing constructor wiring is now pinned to BeautySourceConfiguration and BeautyReceptionConfiguration; actual regression methods are fullPointsRefundTerminalStillRecoversPreparedOriginalStock and fundedRefundRecoveryStillDispatchesAndReleasesOriginalOutsideCoreLocks. InternalRequestBinding requires null entity/store for CONSUMER, so current-claim full requested scope is in the digested body, not an invalid consumer binding. Remaining review risks: staged writer compatibility, full claim-set scale and conservative discount availability. These are design tradeoffs, not unmade mandatory decisions.

Deferred: M3 quantities/grants, M4 resource lifecycle, M5 tasks, M6 fulfillment, M7 calendar, Pay integration, frontend/deployment completion. Minor deadlines remain inventory lock order before M4, observability before operational completion, utility style/warnings before integration.

## Source bytes (SHA256)

| Path | Bytes | SHA256 |
|---|---:|---|
| `.claude/skills/ai-shop-dev-standards/SKILL.md` | 7271 | `7338ae6acbbea22a0ad16bdad05ea8b08d634ca93ee62431a6c073b9b5465308` |
| `.claude/skills/ai-shop-dev-standards/references/java.md` | 14689 | `cb951bb964a0817dffec5fd342a6d44bb7e737cdb9d085919f6ccaa5112fae98` |
| `.superpowers/sdd/beauty-backend/bm05-offline-core-design.md` | 74495 | `2f0d7105019fddd9f8c4a7383342e79d50613b44a16e0ee3a11d6c0bb922351b` |
| `.superpowers/sdd/beauty-backend/task-65-brief.md` | 11458 | `85f6d271559e2e47ac5b4bb710599bb520dca54a2d9dadb1fb7174a364655225` |
| `CLAUDE.md` | 3456 | `eb7a43e757cdcf73409c50e0ec0a9de97605f69b20724464d86e64c1c378ca6f` |
| `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/CurrencyBillVO.java` | 355 | `4baee5a5dbb4f38eab80cdfe13379db1f04512af4e19c50c52f864a3ad655f25` |
| `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/MyWorkOrderVO.java` | 392 | `35059042a7d4cb0e83b23d357de1e9a4d9e91c347011e4bf44de4f42580afff8` |
| `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ServiceSubmissionVO.java` | 861 | `b5d566756c8a56deecae71afe979a01736217c20e1d418f8a4cd5179a701353c` |
| `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/SourceBillVO.java` | 815 | `3659aed6c33cc4500d8e066c4bb7703e9f5a4d5ef7d90c3cbdf9c6830e54226d` |
| `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/SourceObservationVO.java` | 453 | `9be7a3116ae38ad1106f898c6d95b1d864d8f0d065f90e4d01fc56c3d7c44899` |
| `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/SubmitServicesReq.java` | 7072 | `89ce0d0372d949b69917cd8be8d451a7466502945e593f7ff17405b62578cc92` |
| `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/dto/WorkOrderBillVO.java` | 648 | `069b7e574f91362e2e80aa873e5e550aa4a4e12faaa733b9575bb501d4d2eb7e` |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteCustomerIdentityPort.java` | 2764 | `7ef03910b7a2f0434a73d6bafa7f289406dbaccfcca20318ba46a2e1fb06ed45` |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteGuestRegistrationPort.java` | 3936 | `4a50e86af8daf01b209742425480a3c235addd938ea8f6b145cd78944e61d1ce` |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteManualServiceAdmissionPort.java` | 5874 | `50eac048e2bd5df0f234f7a462e5a36aa6448f477504427f58dcd69ad500ab78` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/entity/BtySourceObservation.java` | 1774 | `ce8530f7c70bd1a7bc5466b3ce4e8c8095f90cff86c11ed73760947c2aae55ac` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/entity/BtyTradeBinding.java` | 1199 | `be6b586aa5e965b97940d54e39c2b64d0974a2a8870bbd7ec6681dd1aec3e278` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java` | 91359 | `11577a6d91e727fe03a6b5a7a670ad317d65cc953e4c54d5cd1ecb30b8ec034b` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ServiceSourceFacts.java` | 6690 | `c967c338d8c44e8dc753f64c382c6f258a9d62880e31d229225069745eab3e5f` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ServiceSubmissionAccess.java` | 5367 | `a0b8ec42a36a0a6826a3e3dcab1f6ffec6aa4d929de3a6793661540bacaab5ea` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSourceBinding.java` | 18130 | `1394a771cdda0640cc57dd0621cb19d920913313ec1d076d9ca764a2f50d1a2a` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSourceObservation.java` | 19816 | `1d904c9fdb5367ecc77caaa9b3a27c5b81e304bdaf5b0cad989c367a0f654df5` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java` | 57594 | `b0a68a54c365ceac3ea128a0a0cab3b7ddb5cca2eafef378678d76e19fc7ca76` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderBillServiceImpl.java` | 32376 | `416255b09b0459225f97f8f29b2519a5a7d3e9e011a22c89cf6fb99edb5f8d17` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderQuery.java` | 15032 | `ff0c833722340c46b6d5e78f2385b2eb0ee90e523589ac57eb3f965720cf1ab2` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderServiceImpl.java` | 3381 | `c010de3d064db9605c436ff043271518beb12fb5bed38112823aa46d3db4f102` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V6__beauty_reception_identity_guard.sql` | 445 | `220ed777c56a77c22cf835f2a5bcc42ccb41cc98a4348831df06e0e404b89f20` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V7__beauty_appointment_assignment_units.sql` | 417 | `8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql` | 4582 | `c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343` |
| `backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalGuestRegistrationController.java` | 4634 | `3027a39fe5ba8ed183322ede86aaed5075329f3b34d04a709ea1146962ecea45` |
| `backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalManualServiceAdmissionController.java` | 6202 | `fd9268b116af51d2828b8685b0aa86f46da2666569c234a8379a4d92b0e77bbe` |
| `backend/shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java` | 6944 | `951d8e7105e7c9a5062d99c6f5d040fac477a1a3742cf37e1884a769f752c8dd` |
| `backend/shop-app/src/main/resources/db/migration/V1__baseline.sql` | 81750 | `c8699fd01c8c1739867b7691adcc531f26abd9cce31d6ad8bb4c94d314d22958` |
| `backend/shop-app/src/main/resources/db/migration/V231__pmt_coupon.sql` | 10558 | `8d6d869014ba3f2a2586a2ee55dfc9e60846d0afc4ba6fb77b8b6266d9516ebe` |
| `backend/shop-app/src/main/resources/db/migration/V411__order_creation_recovery.sql` | 1003 | `1d9c5d79c8cd71bb1c048f9fa9bde3e87ff377217ade927c8c9f8f685d6c7852` |
| `backend/shop-app/src/main/resources/db/migration/V539__checkout_source_foundation.sql` | 8444 | `5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b` |
| `backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql` | 7477 | `ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745` |
| `backend/shop-app/src/main/resources/db/migration/V541__manual_service_admission.sql` | 7022 | `9d01ae07c112cfdb350f4950c88fe7bfba8297be2053b26f97961f31175f9400` |
| `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BoundActionContext.java` | 2119 | `3283d5f2ae913ffb428ba1a48a38b7cf7f7a22ef2594a753bf05593cd75532ed` |
| `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BoundDelegatedCalls.java` | 2000 | `ed5de1f506cefb2d8830d7f0d9b7daead8dd1ed8fbe884f9bec93ab78d2e2e53` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CampaignPort.java` | 22430 | `402500be3cd9406f705760759b797a6a7d9d6dfc25c0bd6f025a97f83998e062` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/CustomerIdentityInternalApi.java` | 1853 | `f3039fdf635bbee551377c728594261775bbdfbadb5f46ff23ac6152e8dd47aa` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/CustomerIdentityPort.java` | 1321 | `c20716c66177f20f1cd8dcb8355dd214bc68d97f49d426055029620fb80e797a` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/GuestRegistrationInternalApi.java` | 1686 | `1f76edb8753c31fc2caa2337b3f732d42558207ca7975d2acdc97385b25d5f19` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/GuestRegistrationPort.java` | 1544 | `18fc743c2fc7965aed9bfdc186249d689028fc9629aee0c7b694ca59ab071e71` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/product/StockDispositionContract.java` | 5465 | `6e0120fca7251632bc87cfb363442b992e8710e4dbd120a1d10d6649ed2fa152` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/product/StockDispositionPort.java` | 3878 | `ac01c73abcfc65a3eb7c24a71a20ba187cd04479819636c3113d598906524644` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/product/StoreSalesCatalogPort.java` | 6121 | `a0eff034e70e00ce32789bf45e67c152a903bb3649fbfc78bfcd20e5fe8ee98f` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java` | 18669 | `e7b9b5fe8960acccd6abd8d9001a3718aaea864495e2eecec9dab182b3bad54b` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ManualServiceAdmissionInternalApi.java` | 2372 | `f1a7896b0c3094779770b80ba5fa2c40d41ded0fb3ce0fbeb3f93b09ccebfe63` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ManualServiceAdmissionPort.java` | 10961 | `691b2535bbdc8c5e8e31cbc1818c884edf2027cd8724aa35266c7d75a2cea8a1` |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java` | 12408 | `863f52a3348febd8d7c02bba69270eec1bdedca7dcf6dc0db036d51f309bb106` |
| `backend/shop-core/src/main/java/ai/neargo/shop/member/mapper/MemberGuestRegistrationMapper.java` | 912 | `407b48317ce9de8a7be362eaa83702b293691d461381f723d98b8527bf500e16` |
| `backend/shop-core/src/main/java/ai/neargo/shop/member/port/MemberCustomerIdentityPortImpl.java` | 2767 | `9d93f0e14214dc7dabc54b72bce0461a2910efafd49765bbb51082e6b3dac623` |
| `backend/shop-core/src/main/java/ai/neargo/shop/member/service/impl/MemberGuestRegistrationServiceImpl.java` | 13111 | `528d69559ec2126f1e0309612488192211d0d9a125f3881e8d0d689419973b4b` |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/port/GoodsQueryPortImpl.java` | 26161 | `cffb7d4bbcefe7da5304b9549461a6098a519822da555fee64456a80ae34bf5a` |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/port/StockPortImpl.java` | 21837 | `1c45c7e66073a488a8934a4b070ed6fd9c8c792f33ccf6251d1ec9326372c0f5` |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/service/impl/StoreSalesCatalogServiceImpl.java` | 15616 | `89350c8e2b95634c6ce6fad7d920a0990a3d330b869ddc4a2984f85914dafe16` |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/ActivityPricingService.java` | 3859 | `3095cca223898c48a4c13c1c50fb443370f59cae74f1c33b24133fafc09bb397` |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/UnifiedOrderActivityPricingService.java` | 590 | `10a9b7b89a318374c19700aab6b4879603debf809818492f511e7d110d82de02` |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/ActivityPricingServiceImpl.java` | 44874 | `1bdb4e60610683a01f9b46f2541f0deeddbe9f9cc8e89162815771bd3fc17657` |
| `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/UnifiedOrderActivityPricingServiceImpl.java` | 15351 | `0111312bbfe279ee5f29acfbdb5748a208d9860fba6e3fcc046f0a7504b4b4ce` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizManualServiceAdmissionController.java` | 4412 | `57bee6f706d89d7f69a254e6e71d6692ef8b38702abbd70cf64dc82401c4c303` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/mp/MpCheckoutController.java` | 6023 | `0457ef23557947316b7681878707dddc16e0220362c2b3812502c89b380bf015` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/mp/MpTradeController.java` | 17346 | `36dd5b06c6539b02b572ec02f5dcca22491237c86f43ad4dc3b9a7e0790fad6c` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderPayMethodVO.java` | 1816 | `569c03d7c689f4c831398414eb803aae45b09376a8124b3505b0e7b04304c2e1` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderVO.java` | 48269 | `7b3e2b3dda13a45cee4bc548f49e0e19db5c6fabe787336b4397f7e84488c62f` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdCheckoutConfirmation.java` | 864 | `de1e5cb7027494d55d2f071c63db21fe5f27394381ff8975e4e412782ece9202` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdCheckoutOperation.java` | 1306 | `c513ed211b775efd4bcfd264761d273bb2e218be19cd0b3bb892d76380922575` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdManualServiceAdmission.java` | 1595 | `13331d1686485e8de904baed22d28ff57a73e491c15a83c4a7d2e348ab7e54ad` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/CheckoutMappers.java` | 5736 | `29abf39df7defca9c9819c6c533b925ff86171e48df4fe6cbd34a8346a8f4097` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/ManualAdmissionMapper.java` | 8520 | `ba7fb292b83ef8a12ef2a8816ab1a8e5124052f32a9fc432d9e62adab90a097e` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/entity/OrdCreateIntent.java` | 971 | `85926464ccaf63d2e23a1fe6cbc5d63208e3b35226687897c9364dc0d61ddd0e` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/mapper/CreateIntentMapper.java` | 566 | `2ee18957001cab5a542f278b44dacb942b3c76ff0c6f5a896c3de3075ff11af0` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/RefundExecutionStore.java` | 8249 | `ee9eac2f407cc31c54279f81d03c4f09f39037ab3f208a5cbbc7622c5b803103` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/PointsRefundResourceServiceImpl.java` | 18716 | `060ad37d4c85999257aed219fe916f6c218001a2cae40cb3d18d18ede94e28d3` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/RefundExecutionServiceImpl.java` | 21693 | `9a0c0fcf374c57f7f40322f74d1dce4fc3798de82dab2b8c8ea58e4fbb90f8d3` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/StoreOrderQueryService.java` | 823 | `0e3675238bdfd3bbf3cac2e910c64bf1c213715628bf920c4bccd41930cb8218` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutCommand.java` | 465 | `00ee57c62c5bb609c0425b568644cfd1171e62193f0f28846aff7d372fd28c8d` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutDigests.java` | 1341 | `83e955b2b0d60704d3e731f4f7a30021bd65b26eebfbb8596a7b7b81250eb8cf` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot.java` | 1651 | `f7efd1585e981c91393a09e2007319c9619d7a847facfb0dfcd856705eb48cae` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutPricingScope.java` | 1471 | `28f633d3b1ed4002cee309209034016b5e5dcd7e57ff05b1fddbd273eb86b629` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java` | 11856 | `03a5cb099225b7135d19ded6a416127075a11a8369dbb846d41ad86db74df7fa` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/ExpectedCheckoutQuote.java` | 954 | `80cb07dc4a546ca13aabf0866d391e5d818300952f569a867bd84c3d42f0e76d` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/OrderCreationRecoveryService.java` | 483 | `f2d03222c06042a43ceb7cc8156f5fa55cdc515df09b529b1b3c125813fc383b` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/OrderStockAllocator.java` | 2045 | `ef0872b6c4657db1b16b2db6a009e21c3753966e6b86ad6eed1b9a806a3390bf` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/OrderCreationRecoveryServiceImpl.java` | 8757 | `97ec25be6f4335aa36ecaf8c43f85f7b25bd95bc353ff6c9a5a5b5adeae84ef8` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/query/MybatisStoreOrderReadStore.java` | 5932 | `7b491b38588d8753baac4f27bd9efb86577a4842a4615e9234e59193428354ff` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/query/StoreOrderReadStore.java` | 474 | `ae4d10bb72779b0d6a214e6d2e1bfe1095ce173dfc314fb942b2c03e83ca96a0` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/policy/CheckoutAccessPolicy.java` | 8230 | `6ec2a4dcc6c9182151d70469bb21bd3f67c5111671c0a740f70476c07da443d2` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/policy/ManualServicePurchasePolicy.java` | 1418 | `113569cbeeccdddf128dd85b33c2a20837110f6c300b171caf74ff01c5df2b0e` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutOrderWriter.java` | 976 | `fbfb5fe0e57998304c2cee433818ca8f995b0ae21848ddd39e7c8993d181a82d` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutService.java` | 2944 | `46f69a21eee1e7d77019a5233df31ecd565091c5047b6237de6f1c9b0adbf775` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java` | 73157 | `c75f9a0830ee8e0bf906046218e180cd06dc1355af497dcf1f492ee579eda16f` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java` | 61988 | `ecb6f6afeb619d53e8af7756126734adb3cd7f9b6d484c4e0839aa9638e977f0` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java` | 277867 | `9fefbde16d6cd8b119cca546a7b98e117eb5b640f2bbd0a7d3ad646602a59477` |
| `backend/shop-inventory/src/main/resources/db/inventory/V14__original_stock_disposition.sql` | 935 | `0a84cc624f3dbd7a58eb38067eefa74778a79cc47d34d4127ba751fd3f4c8095` |
| `docs/requirements/美业系统-多端功能矩阵.md` | 45287 | `f11e29cc3f97b77e589c5d586079012ea2ef7595202388b9d719e228b2d4beb3` |
| `docs/technical/TDD-美业系统-后端开发计划.md` | 376193 | `a8358cdd752873e5499b6c33876ba49107f6be784480dac4c843afbadc07b815` |
| `docs/technical/design/美业系统-API清单.md` | 101362 | `8828fd6ce8ed37bdffe8477cc8f35a2771cdcebffcb4a6eadfa7478a96abd551` |
| `docs/technical/design/美业系统-Java项目结构与类职责.md` | 38015 | `813fc80a83a7ff274992662cd85a2b7945ba7a04224f06e6274b5585603ea2ff` |
| `docs/technical/design/美业系统-数据库结构设计.md` | 22362 | `b5983cd31f7fc19bf74f3472b7c3efd5f6e89641b23d9da41dd2ddd1af345502` |
| `docs/technical/design/美业系统-领域对象与聚合边界.md` | 30475 | `75251681a01d12afbbbf4c592bdd0dbf3d51c7de9dbd7ff4dcf95645625aa25a` |
| `scripts/verify-manual-service-admission.mjs` | 25602 | `91629fba57c3f002d64fabc9323e086324dd5b367478011c67d69d5a7c77cddb` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/security/InternalRequestBinding.java` | 1168 | `377dc84f17a824cd01e54aa9deddd8d179014da5427c15e2626061c395d123ed` |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualServiceFinancialIsolationIT.java` | 57135 | `98698147526606c99794df22eee85f2de89c27290b00af3b5a25df19d9b97485` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautySourceConfiguration.java` | 5596 | `ea7df63baba3cdc4ea3e9bf4787ff94014571f37b65099986e819567f52e437a` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautyReceptionConfiguration.java` | 9681 | `8cd9d86fcccd9e6b09b796728efe5e292722857a3269fe78553fb9cc902177f2` |
