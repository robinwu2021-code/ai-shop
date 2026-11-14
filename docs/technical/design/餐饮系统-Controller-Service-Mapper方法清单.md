## 当前源码实现清单（交付核验见记录）

> 状态：静态源码投影，编译、专项测试和部署证据见[本批交付验证](../TDD-餐饮系统-BIZ01-12-交付验证.md)。

云端基线为 FOOD V15，83 张业务表加 fnb_flyway_history 共84张；当前源码目标 FOOD V25 / CORE V581 / INVENTORY V14。实际三端注册数量以本节路由为准，未精确匹配的原ops专项目标继续保留规划。实际共享内部入口按原owner列出。本节仅静态读取源码，不表示测试通过、迁移已执行或已部署。后文目标规划及历史验收记录分别保留。


### 实际类与方法

| 源码 | 类 | 方法声明（源码） |
|---|---|---|
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ArchiveTableReq.java | ai.neargo.shop.food.dto.ArchiveTableReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/AuthorizeCartSessionReq.java | ai.neargo.shop.food.dto.AuthorizeCartSessionReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundleChoiceInput.java | ai.neargo.shop.food.dto.BundleChoiceInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundleConfigVO.java | ai.neargo.shop.food.dto.BundleConfigVO | public record Data( @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long configVersion, String publishedRevisionNo, List<BundleGroupInput> groups, List<BundleChoiceInput> choices) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundleGroupInput.java | ai.neargo.shop.food.dto.BundleGroupInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundlePublicationSnapshot.java | ai.neargo.shop.food.dto.BundlePublicationSnapshot | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java | ai.neargo.shop.food.dto.BusinessCloseContracts | public record DayVO( String dayNo, LocalDate businessDate, String policyNo, LocalDateTime startsAt, LocalDateTime endsAt, String status, String currentCloseRefs, long version)<br>public record Issue(String kind, String sourceRef)<br>public record ReportVO( LocalDate businessDate, String currency, long batchCount, Map<String, BigDecimal> quantitiesByUnit, Map<String, BigDecimal> originalOrderServiceQuantitiesByActionUnit, String returnAndServiceScope, long settlementAttemptCount, long requestedDueMinor, long returnCount, String salesCompleteness, String settlementCompleteness, String refundCompleteness, List<Issue> issues, String sourceHash, @JsonInclude(JsonInclude.Include.NON_NULL) List<OrderReceivable> originalOrderReceivables, @JsonInclude(JsonInclude.Include.NON_NULL) Long effectiveReceivableMinor, @JsonInclude(JsonInclude.Include.NON_NULL) Long receivableAdjustmentMinor)<br>public record OrderReceivable( String batchNo, String orderNo, String subOrderNo, String currency, long originalMinor, long effectiveMinor, long adjustmentMinor, String amendmentSourceHash, String evidenceHash)<br>public record CloseVO( String closeNo, String dayNo, String currency, int revision, String status, String snapshotHash, String previousCloseNo, String signedBy, LocalDateTime signedAt, ReportVO report, long version)<br>public record PreviewReq( @NotNull LocalDate businessDate, @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency)<br>public record SignReq( @NotBlank @Size(max = 32) String closeNo, @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotHash)<br>public record ExceptionVO( String exceptionNo, String closeNo, String kind, String sourceRef, String status, String ownerNo, String nextDayNo, String reason, String proposedBy, String approvedBy, LocalDateTime approvedAt, String approvalRef, long version)<br>public record ProposeCarryReq( @NotNull @Min(0) Long version, @NotBlank @Size(max = 32) String ownerNo, @NotBlank @Size(max = 32) String nextDayNo, @NotBlank @Size(max = 255) String reason)<br>public record ApproveCarryReq(@NotNull @Min(0) Long version)<br>public record AdjustmentReq( @NotBlank @Size(max = 32) String originalCloseNo, @NotBlank @Size(max = 32) String currentDayNo, @NotBlank @Size(max = 64) String coreReference, @NotBlank @Size(max = 255) String reason, @NotBlank @Size(max = 64) String approvalRef) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessOperationsContracts.java | ai.neargo.shop.food.dto.BusinessOperationsContracts | public record Source(@NotBlank String kind, @NotBlank String sourceNo)<br>public record Evidence( Source source, String status, long version, String sourceHash, boolean terminal, String currentOwner)<br>public record HandoverReq( @NotBlank String receiverNo, @NotEmpty @Size(max = 100) List<@Valid Source> sources, @NotBlank @Size(max = 255) String reason)<br>public record HandoverVO( String handoverNo, String fromActor, String receiverNo, String status, String sourceHash, long version)<br>public record CaseReq( @NotNull @Valid Source source, @NotBlank String ownerNo, @NotBlank @Size(max = 255) String reason)<br>public record CaseVO( String caseNo, Source source, String ownerNo, String status, String sourceHash, String resolutionHash, long version)<br>public record DecisionReq(@Min(0) long version, @NotBlank @Size(max = 255) String reason) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessReturnApprovalContracts.java | ai.neargo.shop.food.dto.BusinessReturnApprovalContracts | public record ApproveReq(Long version, String reason, String replacementCartNo)<br>public record ReplacementReq(Long version, String replacementBatchNo, String reason)<br>public record ApprovalVO( String approvalNo, String returnNo, long version, String status, boolean pending, boolean originalPayableChanged, Receipt originalAmendment, String replacementBatchNo, String replacementOrderNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessReturnContracts.java | ai.neargo.shop.food.dto.BusinessReturnContracts | public record Line( @NotBlank @Size(max = 32) String foodLineNo, @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty, String componentNo)<br>public void unknown(String name, Object value)<br>public record CreateReq( @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long batchVersion, @NotNull @Size(min = 1, max = 256) List<@Valid Line> lines, @NotBlank @Size(max = 255) String reason)<br>public void unknown(String name, Object value)<br>public record CancelReq( @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version, @NotBlank @Size(max = 255) String reason)<br>public void unknown(String name, Object value)<br>public record ComponentSource( String sourceComponentRef, String kind, String allocationNo, Long orderItemId, BigDecimal originalQty, String unit, String coreSnapshotHash, Long allocationVersion, String componentNo, Long componentVersion)<br>public record SourceItem( String foodLineNo, String sourceLineRef, String mainAllocationNo, Long mainOrderItemId, BigDecimal qty, BigDecimal originalQty, Long lineVersion, List<ComponentSource> components)<br>public record OwnerRead(String checkedAt, Result original)<br>public record Frozen( String kind, int schema, String entityNo, String storeNo, String batchNo, Long batchVersion, String cartNo, Long cartVersion, String bindingNo, Long bindingVersion, String buyerNo, String actorNo, String reason, String sourceHash, OwnerRead ownerRead, List<SourceItem> items)<br>public record ItemVO( String returnItemNo, String foodLineNo, String sourceLineRef, BigDecimal qty, String kitchenDisposition, String mainAllocationNo)<br>public record ReturnVO( String returnNo, String batchNo, String storeNo, long version, String status, String financialStatus, String kitchenStatus, String coreCaseRef, boolean refundRequested, boolean originalPayableChanged, String reason, String actorNo, List<ItemVO> items, Frozen source) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessShareContracts.java | ai.neargo.shop.food.dto.BusinessShareContracts | public record Person( @NotBlank @Size(max = 32) String personNo, @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor)<br>public void unknown(String name, Object value)<br>public record LineWeight( @NotBlank @Size(max = 32) String foodLineNo, @NotBlank @Size(max = 32) String personNo, @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long weight)<br>public void unknown(String name, Object value)<br>public record CreateReq( @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long checkVersion, @NotBlank String mode, @NotNull @Size(min = 1, max = 50) List<@Valid Person> people, @Size(max = 1000) List<@Valid LineWeight> lineWeights, @NotBlank @Size(max = 255) String reason)<br>public void unknown(String name, Object value)<br>public record ConfirmReq( @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version, @NotBlank @Size(min = 64, max = 64) String sourceHash, @NotBlank @Size(min = 64, max = 64) String planHash)<br>public void unknown(String name, Object value)<br>public record CancelReq( @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version, @NotBlank @Size(max = 255) String reason)<br>public void unknown(String name, Object value)<br>public record MembershipScope( String membershipNo, String checkNo, String batchNo, Long version, LocalDateTime attachedAt, String operationNo)<br>public record BatchSource( String batchNo, String originalCheckNo, Long version, String bindingNo, Long bindingVersion, String orderNo, String storeOrderNo, String frozenRequestHash, List<MembershipScope> memberships, @JsonInclude(Include.NON_NULL) String amendmentSourceHash)<br>public record AllocationSource( String allocationNo, String batchNo, String foodLineNo, String sourceLineRef, String sourceComponentRef, Long orderItemId, BigDecimal originalQty, String unit, String coreSnapshotHash, Long allocationVersion, Long lineVersion, Long selectionVersion, String lineSnapshotHash, String currency, long originalAmountMinor, @JsonInclude(Include.NON_NULL) BigDecimal effectiveQty, @JsonInclude(Include.NON_NULL) Long effectiveAmountMinor, @JsonInclude(Include.NON_NULL) String amendmentSourceHash)<br>public long businessAmountMinor()<br>public record SourceSnapshot( String entityNo, String storeNo, String checkNo, Long checkVersion, String currency, long originalAmountMinor, List<BatchSource> batches, List<AllocationSource> allocations, @JsonInclude(Include.NON_NULL) Long effectiveAmountMinor)<br>public record ShareCell( String allocationNo, String foodLineNo, Long orderItemId, String personNo, long amountMinor)<br>public record PersonTotal(String personNo, long amountMinor)<br>public record Division(List<PersonTotal> people, List<ShareCell> cells)<br>public record PlanVO( String planNo, String checkNo, long version, String status, String storedStatus, String mode, String currency, long originalAmountMinor, String sourceHash, String planHash, boolean localSourcesCurrent, boolean settlementEligible, boolean financialAuthorityPending, SourceSnapshot source, Division division, CreateReq definition, String reason, String createdBy, String confirmedBy, String cancelledBy, @JsonInclude(Include.NON_NULL) Long effectiveAmountMinor) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CancelDiningOrderReq.java | ai.neargo.shop.food.dto.CancelDiningOrderReq | public void unknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartAccess.java | ai.neargo.shop.food.dto.CartAccess | public void rejectUnknown(String name, Object value)<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartBundleChoiceInput.java | ai.neargo.shop.food.dto.CartBundleChoiceInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartSelectionInput.java | ai.neargo.shop.food.dto.CartSelectionInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartVersionReq.java | ai.neargo.shop.food.dto.CartVersionReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogItemVO.java | ai.neargo.shop.food.dto.CatalogItemVO | public CatalogItemVO( String menu, String item, String category, String categoryName, String goods, String title, String cover, DishVO dish, List<String> groups, List<CatalogSkuVO> skus) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogQuery.java | ai.neargo.shop.food.dto.CatalogQuery | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CheckCollaborationContracts.java | ai.neargo.shop.food.dto.CheckCollaborationContracts | public record CheckVersion(@NotBlank String checkNo, @Min(0) long version)<br>public record MoveReq( @NotNull @Valid CheckVersion source, @NotNull @Valid CheckVersion target, @NotEmpty @Size(max = 100) List<@NotBlank String> batchNos, @NotBlank @Size(max = 255) String reason)<br>public record MoveVO( String operationNo, String sourceCheckNo, String targetCheckNo, List<String> batchNos, long sourceVersion, long targetVersion, String status)<br>public record LineMoveReq( @NotNull @Valid CheckVersion source, @NotNull @Valid CheckVersion target, @NotEmpty @Size(max = 100) List<@NotBlank String> foodLineNos, @NotBlank @Size(max = 255) String reason)<br>public record LineMoveVO( String operationNo, String sourceCheckNo, String targetCheckNo, List<String> foodLineNos, String status)<br>public record ReceivableBatch( String batchNo, String orderNo, String subOrderNo, String currency, Long originalMinor, Long effectiveMinor, Long adjustmentMinor, String amendmentSourceHash)<br>public record ReceivablePreview( String checkNo, List<ReceivableBatch> batches, String sourceHash, boolean financialActionAllowed)<br>public record FreezeReq( @Min(0) long checkVersion, @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sourceHash, @NotBlank @Size(max = 255) String reason)<br>public record FreezeVO( String freezeNo, String checkNo, String sourceHash, String status, long version) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ClearCheckReq.java | ai.neargo.shop.food.dto.ClearCheckReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CombineTablesReq.java | ai.neargo.shop.food.dto.CombineTablesReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateBundleReq.java | ai.neargo.shop.food.dto.CreateBundleReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateCartReq.java | ai.neargo.shop.food.dto.CreateCartReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateCustomerCartReq.java | ai.neargo.shop.food.dto.CreateCustomerCartReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateDiningOrderReq.java | ai.neargo.shop.food.dto.CreateDiningOrderReq | public void unknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateDiningSettlementReq.java | ai.neargo.shop.food.dto.CreateDiningSettlementReq | public void unknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateMenuChannelBindingReq.java | ai.neargo.shop.food.dto.CreateMenuChannelBindingReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateMenuReq.java | ai.neargo.shop.food.dto.CreateMenuReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateModifierReq.java | ai.neargo.shop.food.dto.CreateModifierReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateOptionGroupReq.java | ai.neargo.shop.food.dto.CreateOptionGroupReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreatePolicyReq.java | ai.neargo.shop.food.dto.CreatePolicyReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateRecipeReq.java | ai.neargo.shop.food.dto.CreateRecipeReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateStaffDiningOrderReq.java | ai.neargo.shop.food.dto.CreateStaffDiningOrderReq | public CreateStaffDiningOrderReq( String buyer, String cart, long version, String quote, String digest, String mode)<br>public CreateDiningOrderReq order()<br>public void unknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateTableAreaReq.java | ai.neargo.shop.food.dto.CreateTableAreaReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateTableReq.java | ai.neargo.shop.food.dto.CreateTableReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningBatchSummaryVO.java | ai.neargo.shop.food.dto.DiningBatchSummaryVO | public record OriginalBindingVO(String bindingNo,String paymentRootNo,String storeOrderNo,String bindingStatus) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningDeliveryContracts.java | ai.neargo.shop.food.dto.DiningDeliveryContracts | public record SaveReq( @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long cartVersion, @NotBlank @Size(max = 64) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiverName, @NotBlank @Size(max = 32) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiverPhone, @NotBlank @Size(max = 512) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String address, @Size(max = 255) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String deliveryInstruction, @JsonInclude(Include.NON_NULL) Integer latE6, @JsonInclude(Include.NON_NULL) Integer lngE6)<br>public void unknown(String name, Object value)<br>public record DeliverySnapshot( String entityNo, String storeNo, String cartNo, long cartVersion, long revision, String receiverName, String receiverPhone, String address, String deliveryInstruction, @JsonInclude(Include.NON_NULL) Integer latE6, @JsonInclude(Include.NON_NULL) Integer lngE6)<br>public static void validateCoordinates(Integer latE6, Integer lngE6) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningIdentityContracts.java | ai.neargo.shop.food.dto.DiningIdentityContracts | public record RegisterDeviceReq( @NotBlank @Size(max = 32) String deviceNo, @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version, @NotBlank @Size(max = 64) String displayName)<br>public void unknown(String name, Object value)<br>public record VersionReq( @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version, @NotBlank @Size(max = 255) String reason)<br>public void unknown(String name, Object value)<br>public record DeviceVO( String entityNo, String storeNo, String deviceNo, String displayName, String status, long generation, long version)<br>public record DeviceCredentialVO(DeviceVO device, String rawCredential)<br>public String toString()<br>public record StaffPartyReq( @Size(max = 32) String checkNo, @NotBlank @Size(max = 64) String displayName, @Min(60) @Max(86400) int lifetimeSeconds)<br>public void unknown(String name, Object value)<br>public record DeviceSessionReq(@Min(60) @Max(86400) int lifetimeSeconds)<br>public void unknown(String name, Object value)<br>public record PartyVO( String kind, String entityNo, String storeNo, String sessionNo, String deviceNo, String displayName, Instant expiresAt, List<String> scope, String checkNo, String buyerNo, long deviceGeneration, long version)<br>public record SessionCredentialVO(PartyVO party, String accessToken)<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningPickupContracts.java | ai.neargo.shop.food.dto.DiningPickupContracts | public record VerifyPickupReq( @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long expectedVersion, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "[0-9]{6}") String verifyCode, String custodyOperationNo)<br>public void unknown(String name, Object value)<br>public String toString()<br>public record PickupVerificationVO( String batchNo, boolean localHandoverRecorded, Receipt originalFulfillment) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningPriceFacts.java | ai.neargo.shop.food.dto.DiningPriceFacts | public void unknown(String key, Object value)<br>public record Payload( @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String subjectNo, CartVO cart, List<MenuRef> menus, List<SkuIdentityFact> identities, List<Offer> offers, List<Restriction> restrictions, List<Unit> units, Composition composition, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency, @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor, @JsonInclude(Include.NON_NULL) DiningDeliveryContracts.DeliverySnapshot delivery, @JsonInclude(Include.NON_NULL) List<Source> feeSources)<br>public void unknown(String key, Object value)<br>public record Composition(List<CatalogMenuVO> menus, List<CatalogItemVO> items)<br>public void unknown(String key, Object value)<br>public record Unit( @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceLineNo, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String componentRef, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String priceRevision, @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity, @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long unitPriceMinor, @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor)<br>public void unknown(String key, Object value)<br>public record MenuRef( @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo, @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long revision, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String contentHash, List<DependencyRef> dependencies)<br>public void unknown(String key, Object value)<br>public record DependencyRef( @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String configNo, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo, @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long revision, @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String contentHash)<br>public void unknown(String key, Object value)<br>public record Restriction( @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo, @JsonDeserialize(using = StoreCardFacts.BooleanDeserializer.class) Boolean blocked, @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota, @JsonDeserialize(using = StoreCardFacts.BooleanDeserializer.class) Boolean originalStockKnown, List<SalesRestrictionVO> rules)<br>public void unknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningQuoteConfirmReq.java | ai.neargo.shop.food.dto.DiningQuoteConfirmReq | public void unknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningQuoteReq.java | ai.neargo.shop.food.dto.DiningQuoteReq | public void unknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningReturnApplyReq.java | ai.neargo.shop.food.dto.DiningReturnApplyReq | public void unknown(String name, Object value)<br>public record Line( @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceLineRef, @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity)<br>public void unknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningReturnVO.java | ai.neargo.shop.food.dto.DiningReturnVO | public record Line(String sourceLineRef, BigDecimal quantity, String kitchenDisposition) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningSessionTableVO.java | ai.neargo.shop.food.dto.DiningSessionTableVO | public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningSessionVO.java | ai.neargo.shop.food.dto.DiningSessionVO | public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningSettlementVersionReq.java | ai.neargo.shop.food.dto.DiningSettlementVersionReq | public void unknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DisableMenuReq.java | ai.neargo.shop.food.dto.DisableMenuReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DishPresentation.java | ai.neargo.shop.food.dto.DishPresentation | public void rejectUnknown(String name, Object value)<br>public record LocalizedName(String locale, String name)<br>public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ExceptionInput.java | ai.neargo.shop.food.dto.ExceptionInput | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodFeeSourceContracts.java | ai.neargo.shop.food.dto.FoodFeeSourceContracts | public record BindReq( long expectedVersion, String checkNo, @NotBlank String ruleNo, @NotBlank String revisionNo, @NotNull Kind kind, @NotBlank String unit, BigDecimal packageQuantity, List<String> basisSourceRefs, String waiverEvidence, @NotBlank String reason, boolean enabled, String contractScopeNo)<br>public record BindingVO( String bindingNo, String checkNo, String ruleNo, String revisionNo, Kind kind, String unit, BigDecimal packageQuantity, List<String> basisSourceRefs, String waiverEvidence, boolean enabled, long version, String contractScopeNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodKdsContracts.java | ai.neargo.shop.food.dto.FoodKdsContracts | public record GrantReq( @NotBlank @Size(max = 32) String deviceNo, @NotBlank @Size(max = 32) String deviceActorNo, @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 32) String> stationNos, @Min(60) @Max(86400) int lifetimeSeconds)<br>public void unknown(String name, Object value)<br>public record RevokeReq( @JsonProperty(value = "version", required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version, @NotBlank @Size(max = 255) String reason)<br>public void unknown(String name, Object value)<br>public record SubscriptionVO( String subscriptionNo, String entityNo, String storeNo, String deviceNo, String actorNo, List<String> stationNos, String expiresAt, String status, long version)<br>public record EventDraft( String eventNo, String entityNo, String storeNo, String stationNo, String eventType, String sourceNo, long sourceVersion, String payload, String occurredAt)<br>public record EventVO( long cursor, String eventNo, String stationNo, String eventType, String sourceNo, long sourceVersion, String payload, String occurredAt)<br>public record ReplayVO( String subscriptionNo, String entityNo, String storeNo, List<EventVO> events, long nextCursor, long watermark, boolean hasMore) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodStationRuntimeContracts.java | ai.neargo.shop.food.dto.FoodStationRuntimeContracts | public record ControlReq( @JsonProperty(value = "version", required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version, @NotBlank String action, @NotBlank @Size(max = 255) String reason, @Size(max = 32) String deviceNo, @Size(max = 128) String voiceText)<br>public void unknown(String name, Object value)<br>public record StateVO(String stationNo, String status, long version)<br>public record CommandVO( String commandNo, String stationNo, String action, String status, long sourceVersion, String reason)<br>public record VoiceClaim( String commandNo, String stationNo, String deviceNo, String voiceText, String leaseToken, String leaseUntil)<br>public record VoiceReceipt( @NotBlank @Size(max = 32) String commandNo, @NotBlank @Size(max = 64) String leaseToken, @NotBlank String status, @Size(max = 64) String responseCode)<br>public void unknown(String name, Object value)<br>public record VoicePoll(VoiceClaim claim) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodSupportContracts.java | ai.neargo.shop.food.dto.FoodSupportContracts | public record ReadinessVO( String deployment, boolean ready, boolean schemaValidated, String schemaVersion, List<String> missingCapabilities, Map<String, Integer> typedProviderCounts) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java | ai.neargo.shop.food.dto.KitchenContracts | public record FrozenRoute(String skuNo, String stationNo, String taskRole, long routeVersion, String revisionNo, String contentHash)<br>public record FrozenRoutes(List<FrozenRoute> routes)<br>public record TaskActionReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version, @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max=24) String action, @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty, @Size(max=255) String reason)<br>public void unknown(String name,Object value)<br>public record TaskDeltaReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version, @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty, @Size(max=255) String reason)<br>public void unknown(String name,Object value)<br>public record RemakeReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version, @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty, @NotBlank @Size(max=255) String reason)<br>public void unknown(String name,Object value)<br>public record HandoverLine(@NotBlank String foodLineNo, @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty)<br>public record HandoverReq(@NotBlank String kind, @NotNull List<HandoverLine> lines, @NotBlank @Size(max=255) String reason, @Size(max=64) String recipientNo, @Size(max=128) String recipientRef)<br>public void unknown(String name,Object value)<br>public record PackingReq(@NotNull List<HandoverLine> lines,@NotBlank @Size(max=255) String reason)<br>public void unknown(String name,Object value)<br>public record CustodyReceiptVO(String operationNo,String entityNo,String storeNo,String batchNo, String orderNo,String storeOrderNo,String bindingNo,Long bindingVersion,Long batchVersion, String frozenRequestHash,String action,String kind, List<HandoverLine> lines,List<CustodySourceLine> sources,String recipientNo, String recipientRef,String actorNo,String recordedAt,boolean complete,boolean coreDeliveryPending)<br>public record CustodySourceLine(String foodLineNo,String sourceLineRef,List<CoreLineScope> originalLines, List<KitchenTaskScope> tasks)<br>public record CoreLineScope(String allocationNo,Long orderItemId,String sourceComponentRef, BigDecimal originalQty,String unit,String coreSnapshotHash,Long version)<br>public record KitchenTaskScope(String taskNo,String componentNo,String stationNo,String taskRole, long routeVersion,long version)<br>public record HandoverVO(String operationNo,String batchNo,String status,boolean deliveryPending, String coreDeliveryRef)<br>public record CourseReq(@NotBlank @Size(max=64) String name, @NotNull Integer sequenceNo)<br>public record CourseActionReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version, @NotBlank String action,@Size(max=255) String reason,List<String> seatNos)<br>public record CourseFireReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version, @NotNull @Size(max=100) List<@NotBlank String> seatNos)<br>public record CourseVO(String courseNo, String checkNo, String name, int sequenceNo, String status,long version)<br>public record TaskVO(String taskNo, String ticketNo, String batchNo, String foodLineNo, String componentNo, String stationNo, String taskRole, long routeVersion, BigDecimal qty, BigDecimal makingQty, BigDecimal readyQty, BigDecimal servedQty, BigDecimal voidQty, String status, String seatNo, String courseNo, long version, String ticketKind, String originalTicketNo)<br>public record EventVO(String eventNo, String taskNo, String action, BigDecimal qty, String reason, String inventoryDocNo)<br>public record TicketVO(String ticketNo, String batchNo, String kind, String originalTicketNo, String status, List<TaskVO> tasks) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java | ai.neargo.shop.food.dto.KitchenReturnContracts | public record ItemDecision(@NotBlank String returnItemNo,@NotBlank String disposition)<br>public void unknown(String name,Object value)<br>public record HandleReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version, @NotNull @Size(min=1,max=1000) List<@Valid ItemDecision> decisions, @NotBlank @Size(max=255) String reason)<br>public void unknown(String name,Object value)<br>public record ItemVO(String returnItemNo,String foodLineNo,String componentNo,BigDecimal qty, String disposition)<br>public record ReturnKitchenVO(String returnNo,String batchNo,String kitchenStatus,long version,List<ItemVO> items)<br>public record HandledVO(String operationNo,String returnNo,String kitchenStatus) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListMenusQuery.java | ai.neargo.shop.food.dto.ListMenusQuery | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListRecipesQuery.java | ai.neargo.shop.food.dto.ListRecipesQuery | public void rejectUnknown(String name, Object value)<br>public Integer deserialize(JsonParser parser, DeserializationContext context) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MeasurementPolicy.java | ai.neargo.shop.food.dto.MeasurementPolicy | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuCategoryInput.java | ai.neargo.shop.food.dto.MenuCategoryInput | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuConfigResultVO.java | ai.neargo.shop.food.dto.MenuConfigResultVO | public MenuConfigResultVO(MenuVO config)<br>public MenuConfigResultVO(MenuVO config, MenuScheduleVO schedule)<br>public MenuConfigResultVO(MenuVO config, MenuScheduleVO schedule, MenuCompositionVO composition) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuContextInput.java | ai.neargo.shop.food.dto.MenuContextInput | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuItemInput.java | ai.neargo.shop.food.dto.MenuItemInput | public MenuItemInput( String item, String goods, Integer sort, Boolean enabled, String scope, List<String> skus, String mode, List<String> groups, List<MenuSkuRecipeInput> recipes)<br>public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuPublicationSnapshot.java | ai.neargo.shop.food.dto.MenuPublicationSnapshot | public void rejectUnknown(String key,Object value)<br>public record Captured(String entityNo,String storeNo,MenuConfigResultVO definition,List<DishVO> dishes, List<ItemOptions> itemOptions,List<Dependency> dependencies,List<MenuChannelBindingVO> channelBindings)<br>public void rejectUnknown(String key,Object value)<br>public record ItemOptions(String menuItemNo,List<String> optionGroupNos)<br>public void rejectUnknown(String key,Object value)<br>public record Dependency(String revisionNo,String configKind,String configNo, @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision, int contentSchema,String contentHash,String definitionSnapshot)<br>public void rejectUnknown(String key,Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuSkuPriceInput.java | ai.neargo.shop.food.dto.MenuSkuPriceInput | public void unknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuSkuRecipeInput.java | ai.neargo.shop.food.dto.MenuSkuRecipeInput | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ModifierComponentInput.java | ai.neargo.shop.food.dto.ModifierComponentInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ModifierConfigVO.java | ai.neargo.shop.food.dto.ModifierConfigVO | public record Data(String name, String salesSkuNo, String publishedRevisionNo, List<Component> components)<br>public record Component(String componentNo, String memberNo, BigDecimal quantity) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OpenCheckReq.java | ai.neargo.shop.food.dto.OpenCheckReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OpenSharedCheckReq.java | ai.neargo.shop.food.dto.OpenSharedCheckReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OptionGroupConfigVO.java | ai.neargo.shop.food.dto.OptionGroupConfigVO | public record Data(String name, BigDecimal minQty, BigDecimal maxQty, String publishedRevisionNo, Map<String, Object> selectionRules, List<Item> items)<br>public record Item(String optionItemNo, String sourceKind, String specValueNo, String modifierNo, BigDecimal minQty, BigDecimal maxQty, BigDecimal defaultQty, int sortNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OptionItemInput.java | ai.neargo.shop.food.dto.OptionItemInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OrderLineInput.java | ai.neargo.shop.food.dto.OrderLineInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PayDiningSettlementReq.java | ai.neargo.shop.food.dto.PayDiningSettlementReq | public void unknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PrintDocumentReq.java | ai.neargo.shop.food.dto.PrintDocumentReq | public void rejectUnknown(String field,Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ProductionContracts.java | ai.neargo.shop.food.dto.ProductionContracts | public record ListQuery( Integer page, Integer size, LocalDate from, LocalDate to, String skuNo, String itemRef, String status)<br>public record AdjustmentReq(Long version, String kind, BigDecimal qty, String reason)<br>public record ReverseReq(Long version, String reason)<br>public record LineVO( int lineNo, String componentPath, String itemRef, BigDecimal theoryQty, String unit, String recipeRevisionNo, String inventoryReceiptRef, BigDecimal inventoryIssuedQty, Long costMinor, String currency, String status)<br>public record ConsumptionVO( String consumptionNo, String sourceEventNo, String purpose, String status, String requestHash, String inventoryOperationRef, LocalDate businessDate, LocalDateTime occurredAt, List<LineVO> lines, String quantityEvidence, boolean consumptionPending, Long totalCostMinor, String costCurrency, long version) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PublicationSnapshot.java | ai.neargo.shop.food.dto.PublicationSnapshot | public record Dependency(String revisionNo, String configKind, String configNo, @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision, String contentHash) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PublishConfigReq.java | ai.neargo.shop.food.dto.PublishConfigReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java | ai.neargo.shop.food.dto.QueueContracts | public record IssueReq(@NotNull @Min(1) @Max(1000) Integer partySize,@Size(max=64) String contactRef)<br>public void unknown(String name,Object value)<br>public record Query(@NotNull LocalDate businessDate,String status,Integer page,Integer size)<br>public record ChangeReq(@NotNull @Min(0) Long version,@Size(max=255) String reason)<br>public void unknown(String name,Object value)<br>public record SeatQueueReq(@NotNull @Min(0) Long version,@NotNull @Valid OpenCheckReq check)<br>public void unknown(String name,Object value)<br>public record QueueVO(String ticketNo,LocalDate businessDate,int partySize,String status, LocalDateTime calledAt,String checkNo,long version,String notificationStatus) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeAdjustmentInput.java | ai.neargo.shop.food.dto.RecipeAdjustmentInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeLineInput.java | ai.neargo.shop.food.dto.RecipeLineInput | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipePublicationSnapshot.java | ai.neargo.shop.food.dto.RecipePublicationSnapshot | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeVO.java | ai.neargo.shop.food.dto.RecipeVO | public RecipeVO(String recipeNo, String storeNo, String skuNo, String recipeCode, BigDecimal yieldQty, String yieldUnit, String status, long version, long configVersion, List<RecipeLineVO> lines) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceBundleDefinitionReq.java | ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceCartLineReq.java | ai.neargo.shop.food.dto.ReplaceCartLineReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceMenuCompositionReq.java | ai.neargo.shop.food.dto.ReplaceMenuCompositionReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceMenuContextsReq.java | ai.neargo.shop.food.dto.ReplaceMenuContextsReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceMenuScheduleReq.java | ai.neargo.shop.food.dto.ReplaceMenuScheduleReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceOptionGroupItemsReq.java | ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceRecipeDefinitionReq.java | ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq | public ReplaceRecipeDefinitionReq(Long version, BigDecimal yieldQty, String yieldUnit, List<RecipeLineInput> lines)<br>public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RepublishConfigReq.java | ai.neargo.shop.food.dto.RepublishConfigReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReservationContracts.java | ai.neargo.shop.food.dto.ReservationContracts | public record CreateReq(@NotNull @Min(1) Integer partySize, @NotNull Instant startAt, @NotNull Instant endAt, @NotBlank @Size(max=64) String contactRef, @NotNull @Size(min=1,max=100) List<@Valid TableVersionReq> tables)<br>public void rejectUnknown(String name,Object value)<br>public record ChangeReq(@NotNull @Min(0) Long version, @NotNull @Valid CreateReq appointment, @NotBlank @Size(max=255) String reason)<br>public void rejectUnknown(String name,Object value)<br>public record TransitionReq(@NotNull @Min(0) Long version,@NotBlank @Size(max=255) String reason)<br>public void rejectUnknown(String name,Object value)<br>public record ArriveReq(@NotNull @Min(0) Long version, @NotNull @Size(min=1,max=100) List<@Valid TableVersionReq> tables)<br>public void rejectUnknown(String name,Object value)<br>public record DetailVO(ReservationVO reservation,List<String> tableNos) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReservationPreorderContracts.java | ai.neargo.shop.food.dto.ReservationPreorderContracts | public record SaveReq( @Min(0) long reservationVersion, @NotNull Instant cutoffAt, @NotEmpty @Size(max = 100) List<@Valid OrderLineInput> lines)<br>public record PreorderVO( String preorderNo, String reservationNo, String status, Instant cutoffAt, List<OrderLineInput> lines, String cartNo, String batchNo, long version)<br>public record ConvertReq( @Min(0) long version, @NotNull @Valid CreateStaffDiningOrderReq order) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RevokeTableTokenReq.java | ai.neargo.shop.food.dto.RevokeTableTokenReq | public void rejectUnknown(String name, Object value)<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RotateTableTokenReq.java | ai.neargo.shop.food.dto.RotateTableTokenReq | public void rejectUnknown(String name, Object value)<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SalesRestrictionQuery.java | ai.neargo.shop.food.dto.SalesRestrictionQuery | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SalesRestrictionReq.java | ai.neargo.shop.food.dto.SalesRestrictionReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveCalendarReq.java | ai.neargo.shop.food.dto.SaveCalendarReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveCheckSeatsReq.java | ai.neargo.shop.food.dto.SaveCheckSeatsReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveDishReq.java | ai.neargo.shop.food.dto.SaveDishReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveTableAreaReq.java | ai.neargo.shop.food.dto.SaveTableAreaReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveTableReq.java | ai.neargo.shop.food.dto.SaveTableReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SeatReq.java | ai.neargo.shop.food.dto.SeatReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SelectionRules.java | ai.neargo.shop.food.dto.SelectionRules | public void rejectUnknown(String name, Object value)<br>public record SelectionDependency(String optionItemNo, List<String> requiresItemNos)<br>public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ServiceFormContracts.java | ai.neargo.shop.food.dto.ServiceFormContracts | public record OrganizationReq( @NotBlank String name, @NotBlank String contactRef, @NotBlank String externalRef)<br>public record OrganizationVO( String organizationNo, String name, String contactRef, String externalRef, String status)<br>public record BookReq( @NotNull Kind kind, String reservationNo, String slotNo, String menuNo, String menuRevisionNo, @Min(1) int guaranteedPersons, @NotNull Instant startsAt, @NotNull Instant endsAt, String organizationNo, String contactRef, @Min(1) @Max(100) int maxEntriesPerPerson, @NotBlank String reason)<br>public record Guest( @NotBlank @Size(max = 64) String guestRef, String seatNo, @NotBlank String personClass)<br>public record EnterReq( @Min(0) long version, @NotBlank String checkNo, @Min(0) long checkVersion, @NotEmpty @Size(max = 1000) List<@Valid Guest> guests, @NotBlank String reason)<br>public record ReviseReq( @Min(0) long version, @Min(1) int guaranteedPersons, String menuNo, String menuRevisionNo, @NotBlank String reason)<br>public record VersionReq(@Min(0) long version, @NotBlank String reason)<br>public record BatchReq( @Min(0) long version, @NotBlank String batchNo, @NotBlank String reason)<br>public record FormVO( String formNo, Kind kind, String status, String reservationNo, String slotNo, String menuNo, String menuRevisionNo, int guaranteedPersons, Integer actualPersons, String checkNo, Instant startsAt, Instant endsAt, String organizationNo, String contactRef, int maxEntriesPerPerson, String sourceTransition, long version)<br>public record AttendeeVO( String attendeeNo, String guestRef, String seatNo, String personClass, String status, int entryCount, long version) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ServiceSlotContracts.java | ai.neargo.shop.food.dto.ServiceSlotContracts | public record CreateReq( @NotNull Instant startsAt, @NotNull Instant endsAt, @NotNull Instant orderCutoffAt, @NotNull Instant dispatchAt, @NotNull @DecimalMin("0.000001") BigDecimal capacity, @NotBlank String unit)<br>public record SlotVO( String slotNo, Instant startsAt, Instant endsAt, Instant orderCutoffAt, Instant dispatchAt, BigDecimal capacity, BigDecimal reserved, BigDecimal consumed, String unit, String status, long version)<br>public record ClaimReq( @NotBlank String slotNo, @NotBlank String sourceNo, @NotNull @DecimalMin("0.000001") BigDecimal quantity)<br>public record ClaimVO( String claimNo, String slotNo, String sourceNo, BigDecimal quantity, String status, long version) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SetStatusTableAreaReq.java | ai.neargo.shop.food.dto.SetStatusTableAreaReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SetStatusTableReq.java | ai.neargo.shop.food.dto.SetStatusTableReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/StartDiningSessionReq.java | ai.neargo.shop.food.dto.StartDiningSessionReq | public void rejectUnknown(String name, Object value)<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/StationContracts.java | ai.neargo.shop.food.dto.StationContracts | public record CreateStationReq( @NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name, @NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType)<br>public void rejectUnknown(String name, Object value)<br>public record UpdateStationReq(@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version, @NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name, @NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType, @NotNull @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean enabled)<br>public void rejectUnknown(String name, Object value)<br>public record StationRouteInput(@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo, @NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String taskRole)<br>public void rejectUnknown(String name, Object value)<br>public record ReplaceStationRoutesReq( @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version, @NotNull @Size(max=100) List<@Valid StationRouteInput> routes)<br>public void rejectUnknown(String name, Object value)<br>public record StationVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo, @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version, @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status, @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo, @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name, @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType, @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) boolean enabled, @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String publishedRevisionNo)<br>public record StationMutationVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo, @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version, @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status)<br>public record GetStationVO(StationVO config)<br>public record StationRoutesVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo, @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version, List<StationRouteInput> routes) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/StationPublicationSnapshot.java | ai.neargo.shop.food.dto.StationPublicationSnapshot | public record Route(String skuNo, String taskRole, long routeVersion) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TableTokenVO.java | ai.neargo.shop.food.dto.TableTokenVO | public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TableVersionReq.java | ai.neargo.shop.food.dto.TableVersionReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TransferCheckReq.java | ai.neargo.shop.food.dto.TransferCheckReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/UpdateMenuChannelBindingReq.java | ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/UpdateMenuReq.java | ai.neargo.shop.food.dto.UpdateMenuReq | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/UpdateModifierReq.java | ai.neargo.shop.food.dto.UpdateModifierReq | public void rejectUnknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/WeekScheduleInput.java | ai.neargo.shop.food.dto.WeekScheduleInput | public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/CartInputPolicy.java | ai.neargo.shop.food.policy.CartInputPolicy | public static void key(String value)<br>public static void version(Long value)<br>public static void replayKey(String value)<br>public static OrderLineInput line(OrderLineInput line)<br>public static void quantity(BigDecimal value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/CartLinePolicy.java | ai.neargo.shop.food.policy.CartLinePolicy | public static void validate(OrderLineInput line, CatalogVO catalog, ObjectMapper json) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/CatalogPolicy.java | ai.neargo.shop.food.policy.CatalogPolicy | public static void query(CatalogQuery q)<br>public static void publicScope(String fulfillment)<br>public static void receipt(CatalogVO v, String store, CatalogQuery q) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/DiningMeasurementPolicy.java | ai.neargo.shop.food.policy.DiningMeasurementPolicy | public static boolean valid(MeasurementPolicy policy)<br>public static boolean sameBaseUnit(MeasurementPolicy sales, String stockUnit)<br>public static MeasurementPolicy require(MeasurementPolicy policy)<br>public static void quantity(MeasurementPolicy policy, BigDecimal value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/DiningPriceFactsPolicy.java | ai.neargo.shop.food.policy.DiningPriceFactsPolicy | public static DiningPriceFacts validate( DiningPriceFacts result, String entity, String store, String cart, long version, String subject) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/DiningPriceSourcePolicy.java | ai.neargo.shop.food.policy.DiningPriceSourcePolicy | public static List<String> requiredSkus(CartVO cart, CatalogVO catalog, ObjectMapper json)<br>public static List<Unit> compose( CartVO cart, CatalogVO catalog, Map<String, Offer> offers, ObjectMapper json)<br>public static List<Unit> legacyCompose( CartVO cart, CatalogVO catalog, Map<String, Offer> offers, ObjectMapper json)<br>public static boolean finishedStock(String skuNo, List<Unit> units, List<CatalogMenuVO> menus) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/DiningPricingSourcePolicy.java | ai.neargo.shop.food.policy.DiningPricingSourcePolicy | public static void source(PricingSourceRef source)<br>public static Snapshot project(DiningPriceFacts result, Scope expectedScope, PricingSourceRef source, String subject) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuCompositionDefinitions.java | ai.neargo.shop.food.policy.MenuCompositionDefinitions | public static MenuCompositionVO normalize(ReplaceMenuCompositionReq req)<br>public static MenuCompositionVO normalize(MenuCompositionVO definition) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuContextDefinitions.java | ai.neargo.shop.food.policy.MenuContextDefinitions | public static boolean supportedSource(String value)<br>public static boolean supportedFulfillment(String value)<br>public static List<MenuContextInput> normalize(ReplaceMenuContextsReq req)<br>public static List<MenuContextInput> normalize(List<MenuContextInput> contexts) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuDefinitionPolicy.java | ai.neargo.shop.food.policy.MenuDefinitionPolicy | public static void key(String value)<br>public static void create(CreateMenuReq req)<br>public static void update(UpdateMenuReq req)<br>public static void query(ListMenusQuery q)<br>public static boolean valid(MenuVO v, String store)<br>public Boolean deserialize(JsonParser parser, DeserializationContext context)<br>public Integer deserialize(JsonParser parser, DeserializationContext context) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuPricePolicy.java | ai.neargo.shop.food.policy.MenuPricePolicy | public static List<MenuSkuPriceInput> normalize(List<MenuSkuPriceInput> prices)<br>public static MoneyMinor price( List<MenuSkuPriceInput> prices, String sku, MoneyMinor nativePrice) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuPublicationDocuments.java | ai.neargo.shop.food.policy.MenuPublicationDocuments | public static String freeze(ObjectMapper json, MenuPublicationSnapshot snapshot)<br>public static int validate(ObjectMapper json, String number, String document)<br>public static void validateCaptured(Captured c)<br>public static List<MenuItemInput> items(Captured c)<br>public static Set<String> goodsNos(Captured c)<br>public static Set<String> dependencySkus(ObjectMapper json, Captured c) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuScheduleDefinitions.java | ai.neargo.shop.food.policy.MenuScheduleDefinitions | public static MenuScheduleVO normalize(ReplaceMenuScheduleReq req)<br>public static MenuScheduleVO normalize(MenuScheduleVO v)<br>public static LocalDate date(String text) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/PublicationDocuments.java | ai.neargo.shop.food.policy.PublicationDocuments | public static String freeze(ObjectMapper json, PublicationSnapshot value)<br>public static String freeze(ObjectMapper json, BundlePublicationSnapshot value)<br>public static String freeze(ObjectMapper json, RecipePublicationSnapshot value)<br>public static String freeze(ObjectMapper json, StationPublicationSnapshot value)<br>public static String freeze(ObjectMapper json, MenuPublicationSnapshot value)<br>public static int validate(ObjectMapper json, String kind, String number, String document)<br>public static String hash(ObjectMapper json, String document) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/RecipePublicationDocuments.java | ai.neargo.shop.food.policy.RecipePublicationDocuments | public static String optionIdentity( String entity, String group, String option, String kind, String spec, String modifier)<br>public static RecipePublicationSnapshot validate( ObjectMapper json, String number, String document)<br>public static void scope(RecipePublicationSnapshot body, String entity, String store) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/SalesRestrictionPolicy.java | ai.neargo.shop.food.policy.SalesRestrictionPolicy | public static void command(SalesRestrictionReq r)<br>public static void query(SalesRestrictionQuery q)<br>public static void scope(String source, String fulfillment)<br>public static void optionalKey(String value)<br>public static boolean valid(SalesRestrictionVO v, String store)<br>public Long deserialize(JsonParser p, DeserializationContext c)<br>public Instant deserialize(JsonParser p, DeserializationContext c)<br>public BigDecimal deserialize(JsonParser p, DeserializationContext c) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/StationPublicationDocuments.java | ai.neargo.shop.food.policy.StationPublicationDocuments | public static StationPublicationSnapshot validate(ObjectMapper json, String number, String document) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/BundleService.java | ai.neargo.shop.food.service.BundleService | CreateBundleResultVO createBundle(String entityNo, String storeNo, String actorNo, CreateBundleReq req, String idempotencyKey)<br>BundleMutationVO replaceDefinition(String entityNo, String storeNo, String skuNo, String actorNo, ReplaceBundleDefinitionReq req, String idempotencyKey)<br>GetBundleVO getBundle(String entityNo, String storeNo, String skuNo)<br>PageData<BundleConfigVO> listBundles(String entityNo, String storeNo, ListBundlesQuery query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/BusinessCloseService.java | ai.neargo.shop.food.service.BusinessCloseService | DayVO open(String entity, String store, String actor, LocalDate date, String key)<br>ReportVO report(String entity, String store, String actor, PreviewReq request)<br>CloseVO preview(String entity, String store, String actor, PreviewReq request, String key)<br>CloseVO get(String entity, String store, String actor, String closeNo)<br>CloseVO sign(String entity, String store, String actor, SignReq request, String key)<br>List<ExceptionVO> exceptions(String entity, String store, String actor, String closeNo)<br>ExceptionVO proposeCarry(String entity, String store, String actor, String closeNo, String exceptionNo, ProposeCarryReq request, String key)<br>ExceptionVO approveCarry(String entity, String store, String actor, String closeNo, String exceptionNo, ApproveCarryReq request, String key)<br>OperationVO adjust(String entity, String store, String actor, AdjustmentReq request, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/BusinessOperationsService.java | ai.neargo.shop.food.service.BusinessOperationsService | Evidence evidence(String entity, String store, Source source)<br>HandoverVO propose(String entity, String store, String actor, HandoverReq request, String key)<br>HandoverVO handover(String entity, String store, String handover)<br>HandoverVO accept( String entity, String store, String actor, String handover, DecisionReq request, String key)<br>HandoverVO cancel( String entity, String store, String actor, String handover, DecisionReq request, String key)<br>CaseVO openCase(String entity, String store, String actor, CaseReq request, String key)<br>List<CaseVO> cases(String entity, String store, String status)<br>CaseVO recoverCase( String entity, String store, String actor, String caseNo, DecisionReq request, String key)<br>CaseVO resolveCase( String entity, String store, String actor, String caseNo, DecisionReq request, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/BusinessReturnApprovalService.java | ai.neargo.shop.food.service.BusinessReturnApprovalService | ApprovalVO approve( String entity, String store, String actor, String returnNo, ApproveReq request, String key)<br>ApprovalVO get(String entity, String store, String actor, String returnNo)<br>ApprovalVO linkReplacement( String entity, String store, String actor, String returnNo, ReplacementReq request, String key)<br>int recover(int limit) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/BusinessReturnService.java | ai.neargo.shop.food.service.BusinessReturnService | ReturnVO create(String entity,String store,String actor,String batch,CreateReq request,String key)<br>ReturnVO get(String entity,String store,String number)<br>ReturnVO cancel(String entity,String store,String actor,String number,CancelReq request,String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/BusinessShareService.java | ai.neargo.shop.food.service.BusinessShareService | PlanVO create(String entity,String store,String actor,String check,CreateReq request,String key)<br>PlanVO get(String entity,String store,String check,String plan)<br>PlanVO confirm(String entity,String store,String actor,String check,String plan,ConfirmReq request,String key)<br>PlanVO cancel(String entity,String store,String actor,String check,String plan,CancelReq request,String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/CatalogService.java | ai.neargo.shop.food.service.CatalogService | CatalogVO read(String entity, String store, CatalogQuery query)<br>default CatalogVO scheduledCart(String entity, String store, String cartNo)<br>CatalogVO publicCatalog(String store, String fulfillment)<br>CatalogVO guest(String store, String session, String access, String buyer) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/CheckBusinessGuardService.java | ai.neargo.shop.food.service.CheckBusinessGuardService | void assertPartyMutable(String entity, String store, String check)<br>void lockBatchChecks(String entity, String store, String batch)<br>void invalidateBatchShares( String entity, String store, String batch, String actor, String reason)<br>void assertNoPendingChanges(String entity, String store, String check)<br>void assertMutable(String entity, String store, String check)<br>void invalidateShares(String entity, String store, String check, String actor, String reason) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/CheckCartService.java | ai.neargo.shop.food.service.CheckCartService | CartResultVO createCart(String entity, String store, String actor, CreateCartReq req, String key)<br>CartResultVO getCart(String entity, String store, String cart)<br>default void requireDiningRoomCart(String entity, String store, String cart)<br>CartVersionVO replaceLine(String entity, String store, String cart, String source, String actor, ReplaceCartLineReq req, String key)<br>CartVersionVO archiveLine(String entity, String store, String cart, String source, String actor, CartVersionReq req, String key)<br>OperationResultVO archiveCart(String entity, String store, String cart, String actor, CartVersionReq req, String key)<br>CartSessionGrantVO authorizeSession(String entity, String store, String check, String actor, AuthorizeCartSessionReq req, String key)<br>CartResultVO customerCreateCart(String store, CartAccess access, CreateCustomerCartReq req, String key)<br>CartResultVO customerGetCart(String cart, CartAccess access)<br>CartVersionVO customerReplaceLine(String cart, String source, CartAccess access, ReplaceCartLineReq req, String key)<br>CartVersionVO customerArchiveLine(String cart, String source, CartAccess access, CartVersionReq req, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/CheckCollaborationService.java | ai.neargo.shop.food.service.CheckCollaborationService | ReceivablePreview previewReceivable(String entity, String store, String check)<br>String sourceHash(String entity, String store, String check)<br>OperationVO releaseService( String entity, String store, String actor, String check, long version, String reason, String key)<br>String currentLineOwner(String entity, String store, String foodLineNo)<br>LineMoveVO moveLines( String entity, String store, String actor, LineMoveReq request, String key)<br>MoveVO moveBatches(String entity, String store, String actor, MoveReq request, String key)<br>FreezeVO freeze( String entity, String store, String actor, String check, FreezeReq request, String key)<br>FreezeVO release( String entity, String store, String actor, String freeze, long version, String reason, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/CheckService.java | ai.neargo.shop.food.service.CheckService | CheckResultVO openCheck(String entityNo,String storeNo,String actorNo,OpenCheckReq req,String idempotencyKey)<br>default CheckResultVO openQueuedCheck(String entityNo,String storeNo,String actorNo,OpenCheckReq req,String idempotencyKey)<br>default CheckResultVO openReservedCheck(String entity, String store, String actor, OpenCheckReq request, String key, Instant reservedUntil)<br>PageData<CheckVO> listChecks(String entityNo,String storeNo,ListChecksQuery query)<br>CheckDetailVO getCheck(String entityNo,String storeNo,String checkNo)<br>CheckResultVO saveSeats(String entityNo,String storeNo,String checkNo,String actorNo,SaveCheckSeatsReq req,String key)<br>OperationVO clearCheck(String entityNo,String storeNo,String checkNo,String actorNo,ClearCheckReq req,String key)<br>OperationResultVO transferCheck(String entityNo,String storeNo,String checkNo,String actorNo,TransferCheckReq req,String key)<br>OperationResultVO combineTables(String entityNo,String storeNo,String checkNo,String actorNo,CombineTablesReq req,String key)<br>CheckResultVO openSharedCheck(String entityNo,String storeNo,String tableNo,String actorNo,OpenSharedCheckReq req,String key)<br>OperationResultVO getTableOperation(String entityNo,String storeNo,String operationNo)<br>PageData<ActionLogVO> listActions(String entityNo,String storeNo,String checkNo,ListCheckActionsQuery query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ConfigRevisionService.java | ai.neargo.shop.food.service.ConfigRevisionService | default PublishConfigResultVO republishRevision(String entity, String store, String revision, String actor, RepublishConfigReq req, String key)<br>default PublishConfigResultVO publishMenu(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>default PublishConfigResultVO publishStation(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>PublishConfigResultVO publishRecipe(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey)<br>PublishConfigResultVO publishBundle(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey)<br>PublishConfigResultVO publishModifier(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey)<br>PublishConfigResultVO publishOptionGroup(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey)<br>PageData<ConfigRevisionVO> listRevisions(String entityNo, String storeNo, ListConfigRevisionsQuery query)<br>ConfigRevisionContentVO getRevision(String entityNo, String storeNo, String revisionNo)<br>ConfigRevisionContentVO resolveRevision(String entityNo, String storeNo, String kind, String configNo, OffsetDateTime asOf) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningBatchQueryService.java | ai.neargo.shop.food.service.DiningBatchQueryService | PageData<DiningBatchSummaryVO> list(String entityNo,String storeNo,String actorNo,ListDiningBatchesQuery query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningCheckoutService.java | ai.neargo.shop.food.service.DiningCheckoutService | DiningSettlementVO customerCreate(CartAccess access, String batchNo, CreateDiningSettlementReq request, String key)<br>DiningSettlementVO customerGet(CartAccess access, String settlementNo)<br>DiningSettlementVO customerPay(CartAccess access, String settlementNo, PayDiningSettlementReq request, String key)<br>DiningSettlementVO customerQuery(CartAccess access, String settlementNo)<br>DiningSettlementVO customerFinalize(CartAccess access, String settlementNo)<br>DiningSettlementVO customerCancel(CartAccess access, String settlementNo, DiningSettlementVersionReq request)<br>DiningSettlementVO staffGet(String entity, String store, String actor, String settlementNo)<br>DiningSettlementVO recoverOriginal(String entity, String store, String number) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningDeliveryService.java | ai.neargo.shop.food.service.DiningDeliveryService | DeliverySnapshot saveStaff( String entity, String store, String actor, String cart, SaveReq request, String key)<br>DeliverySnapshot saveCustomer(String cart, CartAccess access, SaveReq request, String key)<br>DeliverySnapshot getStaff(String entity, String store, String cart)<br>DeliverySnapshot getCustomer(String cart, CartAccess access)<br>DeliverySnapshot get(String entity, String store, String cart) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningIdentityService.java | ai.neargo.shop.food.service.DiningIdentityService | DeviceVO device(String entity, String store, String deviceNo)<br>DeviceCredentialVO registerDevice( String entity, String store, String actor, RegisterDeviceReq request, String key)<br>DeviceVO revokeDevice( String entity, String store, String actor, String deviceNo, VersionReq request, String key)<br>SessionCredentialVO createDeviceSession( String entity, String store, String deviceNo, String credential, DeviceSessionReq request, String key)<br>SessionCredentialVO createStaffParty( String entity, String store, String actor, StaffPartyReq request, String key)<br>PartyVO revokeParty( String entity, String store, String actor, String sessionNo, VersionReq request, String key)<br>PartyVO party(String entity, String store, String sessionNo)<br>void validateDeviceSession(String sessionNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningOrderAuthorityService.java | ai.neargo.shop.food.service.DiningOrderAuthorityService | Party party(Command command)<br>Delivery delivery(Command command)<br>Plan demandPlan(Command command, String orderNo, String subOrderNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningOrderService.java | ai.neargo.shop.food.service.DiningOrderService | default DiningOrderBatchVO staffPlace(String entity, String store, String actor, CreateStaffDiningOrderReq request, String key)<br>default DiningOrderBatchVO staffCancel(String entity, String store, String actor, String batchNo, CancelDiningOrderReq request, String key)<br>default Snapshot authorizeCancellation(Command command, String key, String fulfillment)<br>DiningOrderBatchVO customerPlace(String store, CartAccess access, CreateDiningOrderReq request, String key)<br>DiningOrderBatchVO customerGet(CartAccess access, String batchNo)<br>DiningOrderBatchVO staffGet(String entity, String store, String actor, String batchNo)<br>DiningOrderBatchVO recoverOriginal(String entity, String store, String number) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningPickupVerificationService.java | ai.neargo.shop.food.service.DiningPickupVerificationService | PickupVerificationVO verify(String entity,String store,String actor,String batch,VerifyPickupReq req,String key)<br>PickupVerificationVO get(String entity,String store,String actor,String batch,String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningPricingFactsService.java | ai.neargo.shop.food.service.DiningPricingFactsService | DiningPriceFacts staff(String entity, String store, String cart, long expectedVersion)<br>DiningPriceFacts customer(String cart, CartAccess access, long expectedVersion) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningPricingService.java | ai.neargo.shop.food.service.DiningPricingService | QuoteVO staffQuote(String entity, String store, String actor, DiningQuoteReq request, String key)<br>QuoteVO customerQuote(String store, CartAccess access, DiningQuoteReq request, String key)<br>QuoteVO staffGet(String entity, String store, String actor, String cart, long version, String quote)<br>QuoteVO customerGet(CartAccess access, String cart, long version, String quote)<br>QuoteVO staffConfirm(String entity, String store, String actor, String quote, DiningQuoteConfirmReq request)<br>QuoteVO customerConfirm(CartAccess access, String quote, DiningQuoteConfirmReq request) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningReturnService.java | ai.neargo.shop.food.service.DiningReturnService | DiningReturnVO customerApply(String store, CartAccess access, DiningReturnApplyReq request, String key)<br>DiningReturnVO customerGet(CartAccess access, String returnNo)<br>DiningReturnVO customerCancel(CartAccess access, String returnNo)<br>DiningReturnVO staffGet(String entity, String store, String actor, String returnNo)<br>DiningReturnVO recoverOriginal(String entity, String store, String number) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningSessionService.java | ai.neargo.shop.food.service.DiningSessionService | TableTokenVO rotateTableToken(String entity, String store, String table, String actor, RotateTableTokenReq req, String key)<br>OperationResultVO revokeTableToken(String entity, String store, String table, String actor, RevokeTableTokenReq req, String key)<br>DiningSessionTableVO resolveTableToken(String tableToken)<br>DiningSessionVO startSession(String entity, String store, String buyer, StartDiningSessionReq req, String key)<br>OperationResultVO endSession(String session, String accessToken, String buyer, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningTransactionRecoveryService.java | ai.neargo.shop.food.service.DiningTransactionRecoveryService | Report recoverDue(int limit) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DishService.java | ai.neargo.shop.food.service.DishService | PageData<DishCatalogItemVO> listDishes(String entityNo, String storeNo, ListDishesQuery query)<br>DishDetailResultVO getDish(String entityNo, String storeNo, String goodsNo)<br>DishResultVO saveDish(String entityNo, String storeNo, String goodsNo, String actorNo, SaveDishReq req, String idempotencyKey) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodFeeSourceLifecycle.java | ai.neargo.shop.food.service.FoodFeeSourceLifecycle | DiningPriceFacts attach(String entity, String store, CartVO cart, DiningPriceFacts facts) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodFeeSourceService.java | ai.neargo.shop.food.service.FoodFeeSourceService | BindingVO bind(String entity, String store, String actor, BindReq request, String key)<br>List<BindingVO> bindings(String entity, String store, String check) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodKdsService.java | ai.neargo.shop.food.service.FoodKdsService | SubscriptionVO grant(String entity, String store, String actor, GrantReq request, String key)<br>SubscriptionVO revoke( String entity, String store, String actor, String subscription, RevokeReq request, String key)<br>ReplayVO replay( String entity, String store, String actor, String subscription, long after, int limit)<br>void append(EventDraft event) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodPrintService.java | ai.neargo.shop.food.service.FoodPrintService | List<JobVO> enqueue(Scope scope,PrintDocumentReq request,String requestKey) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodStationRuntimeService.java | ai.neargo.shop.food.service.FoodStationRuntimeService | StateVO state(String entity, String store, String station)<br>void requireRunning(String entity, String store, String station)<br>CommandVO control( String entity, String store, String actor, String station, ControlReq request, String key)<br>VoiceClaim claim(String entity, String store, String actor, String subscription)<br>CommandVO acknowledge( String entity, String store, String actor, String subscription, VoiceReceipt receipt) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodSupportService.java | ai.neargo.shop.food.service.FoodSupportService | ReadinessVO readiness()<br>List<CaseVO> cases(String entityNo, String storeNo, String status)<br>Evidence evidence(String entityNo, String storeNo, Source source) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/KitchenReturnService.java | ai.neargo.shop.food.service.KitchenReturnService | ReturnKitchenVO get(String entity,String store,String returnNo)<br>HandledVO handle(String entity,String store,String actor,String returnNo,HandleReq req,String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/KitchenTaskService.java | ai.neargo.shop.food.service.KitchenTaskService | FrozenRoutes freezeRoutes(String entity, String store, List<String> skus)<br>TicketVO accepted(String entity, String store, String batch, String dispatchKey)<br>List<TaskVO> queue(String entity, String store, String station, String batch)<br>EventVO act(String entity, String store, String actor, String task, TaskActionReq req, String key)<br>TicketVO remake(String entity, String store, String actor, String task, RemakeReq req, String key)<br>HandoverVO handover(String entity,String store,String actor,String batch,HandoverReq req,String key)<br>CustodyReceiptVO confirmPacking(String entity,String store,String actor,String batch,PackingReq req,String key)<br>List<CustodyReceiptVO> custodyReceipts(String entity,String store,String batch)<br>List<EventVO> events(String entity, String store, String task)<br>CourseVO createCourse(String entity, String store, String actor, String check, CourseReq req, String key)<br>List<CourseVO> courses(String entity, String store, String check)<br>CourseVO actCourse(String entity, String store, String actor, String course, CourseActionReq req, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/MenuChannelBindingService.java | ai.neargo.shop.food.service.MenuChannelBindingService | MenuChannelBindingVO createBinding(String entity,String store,String actor, CreateMenuChannelBindingReq req,String key)<br>MenuChannelBindingVO updateBinding(String entity,String store,String number,String actor, UpdateMenuChannelBindingReq req,String key)<br>MenuChannelBindingVO getBinding(String entity,String store,String number)<br>PageData<MenuChannelBindingVO> listBindings(String entity,String store,Integer page,Integer size, String provider,String menuNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/MenuService.java | ai.neargo.shop.food.service.MenuService | CreateMenuResultVO createMenu(String entity, String store, String actor, CreateMenuReq req, String key)<br>MenuResultVO updateMenu(String entity, String store, String menu, String actor, UpdateMenuReq req, String key)<br>MenuScheduleResultVO replaceSchedule(String entity, String store, String menu, String actor, ReplaceMenuScheduleReq req, String key)<br>MenuCompositionResultVO replaceComposition(String entity, String store, String menu, String actor, ReplaceMenuCompositionReq req, String key)<br>MenuContextsResultVO replaceContexts(String entity, String store, String menu, String actor, ReplaceMenuContextsReq req, String key)<br>MenuResultVO disableMenu(String entity, String store, String menu, String actor, DisableMenuReq req, String key)<br>MenuConfigResultVO getMenu(String entity, String store, String menu)<br>PageData<MenuVO> listMenus(String entity, String store, ListMenusQuery query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ModifierService.java | ai.neargo.shop.food.service.ModifierService | CreateModifierResultVO createModifier(String entityNo, String storeNo, String actorNo, CreateModifierReq req, String idempotencyKey)<br>ModifierMutationVO updateModifier(String entityNo, String storeNo, String modifierNo, String actorNo, UpdateModifierReq req, String idempotencyKey)<br>PageData<ModifierConfigVO> listModifiers(String entityNo, String storeNo, ListModifiersQuery query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/OptionGroupService.java | ai.neargo.shop.food.service.OptionGroupService | OptionGroupMutationVO replaceOptionGroupItems(String entityNo, String storeNo, String groupNo, String actorNo, ReplaceOptionGroupItemsReq req, String idempotencyKey)<br>CreateOptionGroupResultVO createOptionGroup(String entityNo, String storeNo, String actorNo, CreateOptionGroupReq req, String idempotencyKey)<br>OptionGroupResultVO getOptionGroup(String entityNo, String storeNo, String configNo)<br>PageData<OptionGroupConfigVO> listOptionGroups(String entityNo, String storeNo, ListOptionGroupsQuery query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ProductionConsumptionService.java | ai.neargo.shop.food.service.ProductionConsumptionService | ConsumptionVO consume(String entity, String store, String actor, String sourceEventNo)<br>default ConsumptionVO adjust( String entity, String store, String actor, String taskNo, ProductionContracts.AdjustmentReq req, String key)<br>default ConsumptionVO reverse( String entity, String store, String actor, String eventNo, ProductionContracts.ReverseReq req, String key)<br>default int recover(int limit)<br>PageData<ConsumptionVO> list(String entity, String store, String actor, ListQuery query)<br>ConsumptionVO get(String entity, String store, String actor, String sourceEventNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/QueueNotificationLifecycle.java | ai.neargo.shop.food.service.QueueNotificationLifecycle | void called( String entity, String store, String ticket, long ticketVersion, String contactRef, String actor) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/QueueNotificationService.java | ai.neargo.shop.food.service.QueueNotificationService | List<NoticeVO> list(String entity, String store, String ticket)<br>NoticeVO recover(String entity, String store, String actor, String notice, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/QueueService.java | ai.neargo.shop.food.service.QueueService | QueueVO issue(String entity,String store,String actor,IssueReq request,String key)<br>PageData<QueueVO> list(String entity,String store,String actor,Query query)<br>QueueVO get(String entity,String store,String actor,String ticketNo)<br>QueueVO callNext(String entity,String store,String actor,LocalDate businessDate,String key)<br>QueueVO change(String entity,String store,String actor,String ticketNo,String action,ChangeReq request,String key)<br>QueueVO seat(String entity,String store,String actor,String ticketNo,SeatQueueReq request)<br>QueueVO recoverSeating(String entity,String store,String actor,String ticketNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/RecipeService.java | ai.neargo.shop.food.service.RecipeService | RecipeResultVO createRecipe(String entityNo, String storeNo, String actorNo, CreateRecipeReq req, String idempotencyKey)<br>RecipeResultVO replaceDefinition(String entityNo, String storeNo, String recipeNo, String actorNo, ReplaceRecipeDefinitionReq req, String idempotencyKey)<br>RecipeResultVO getRecipe(String entityNo, String storeNo, String recipeNo)<br>PageData<RecipeVO> listRecipes(String entityNo, String storeNo, ListRecipesQuery query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ReservationPreorderService.java | ai.neargo.shop.food.service.ReservationPreorderService | PreorderVO save( String entity, String store, String actor, String reservation, SaveReq request, String key)<br>PreorderVO get(String entity, String store, String reservation, String preorder)<br>CartResultVO prepareCart( String entity, String store, String actor, String reservation, String preorder, long version, String key)<br>DiningOrderBatchVO convert( String entity, String store, String actor, String reservation, String preorder, ConvertReq request, String key)<br>PreorderVO cancel( String entity, String store, String actor, String reservation, String preorder, long version, String reason, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ReservationService.java | ai.neargo.shop.food.service.ReservationService | PageData<ReservationVO> listReservations(String entityNo,String storeNo,ListReservationsQuery query)<br>DetailVO get(String entityNo,String storeNo,String reservationNo)<br>DetailVO create(String entityNo,String storeNo,String actorNo,CreateReq req,String key)<br>DetailVO change(String entityNo,String storeNo,String actorNo,String reservationNo,ChangeReq req,String key)<br>DetailVO cancel(String entityNo,String storeNo,String actorNo,String reservationNo,TransitionReq req,String key)<br>DetailVO noShow(String entityNo,String storeNo,String actorNo,String reservationNo,TransitionReq req,String key)<br>DetailVO arrive(String entityNo,String storeNo,String actorNo,String reservationNo,ArriveReq req,String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/SalesQuotaClaimService.java | ai.neargo.shop.food.service.SalesQuotaClaimService | List<SalesQuotaClaimVO> reserve(String entityNo, String storeNo, String batchNo, String orderSource, String fulfillment, List<SalesQuotaClaimSource> sources)<br>List<SalesQuotaClaimVO> release(String entityNo, String storeNo, String batchNo)<br>List<SalesQuotaClaimVO> consume(String entityNo, String storeNo, String batchNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/SalesRestrictionService.java | ai.neargo.shop.food.service.SalesRestrictionService | SalesRestrictionVO manage(String entity, String store, String actor, SalesRestrictionReq req, String key)<br>PageData<SalesRestrictionVO> list(String entity, String store, SalesRestrictionQuery query)<br>SalesRestrictionEvaluationVO evaluate(String entity, String store, String sku, String source, String fulfillment) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ScheduledDiningDispatchService.java | ai.neargo.shop.food.service.ScheduledDiningDispatchService | Report runDue(int limit) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ServiceFormService.java | ai.neargo.shop.food.service.ServiceFormService | OrganizationVO registerOrganization( String entity, String store, String actor, OrganizationReq request, String key)<br>FormVO book(String entity, String store, String actor, BookReq request, String key)<br>FormVO get(String entity, String store, String form)<br>FormVO revise( String entity, String store, String actor, String form, ReviseReq request, String key)<br>FormVO enter( String entity, String store, String actor, String form, EnterReq request, String key)<br>FormVO leave( String entity, String store, String actor, String form, VersionReq request, String key)<br>FormVO cancel( String entity, String store, String actor, String form, VersionReq request, String key)<br>FormVO noShow( String entity, String store, String actor, String form, VersionReq request, String key)<br>FormVO bindBatch( String entity, String store, String actor, String form, BatchReq request, String key)<br>List<AttendeeVO> attendees(String entity, String store, String form)<br>AttendeeVO admit( String entity, String store, String actor, String form, String attendee, VersionReq request, String key)<br>AttendeeVO exit( String entity, String store, String actor, String form, String attendee, VersionReq request, String key) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ServiceSlotLifecycle.java | ai.neargo.shop.food.service.ServiceSlotLifecycle | void resizeSource( String entity, String store, String actor, String source, BigDecimal quantity)<br>void assertScheduledCart(String entity, String store, String cart, Instant scheduledAt)<br>ClaimVO reserveScheduledCart( String entity, String store, String actor, String cart, Instant scheduledAt, String key)<br>void releaseCart(String entity, String store, String actor, String cart)<br>void bindOrder(String entity, String store, String actor, String cart, String batch)<br>void releaseOrder(String entity, String store, String actor, String batch) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ServiceSlotService.java | ai.neargo.shop.food.service.ServiceSlotService | SlotVO create(String entity, String store, String actor, CreateReq request, String key)<br>SlotVO get(String entity, String store, String slot)<br>ClaimVO reserve(String entity, String store, String actor, ClaimReq request, String key)<br>ClaimVO transition( String entity, String store, String actor, String claim, long version, String action, String key)<br>boolean dispatchDue(String entity, String store, String sourceNo) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/StationService.java | ai.neargo.shop.food.service.StationService | StationMutationVO createStation(String entity, String store, String actor, CreateStationReq req, String key)<br>StationMutationVO updateStation(String entity, String store, String station, String actor, UpdateStationReq req, String key)<br>GetStationVO getStation(String entity, String store, String station)<br>PageData<StationVO> listStations(String entity, String store, Integer page, Integer size)<br>OperationVO replaceRoutes(String entity, String store, String sku, String actor, ReplaceStationRoutesReq req, String key)<br>StationRoutesVO getRoutes(String entity, String store, String sku) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/StoreOperationService.java | ai.neargo.shop.food.service.StoreOperationService | StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query)<br>OperationVO createPolicy(String entityNo, String storeNo, String actorNo, CreatePolicyReq req, String idempotencyKey)<br>OperationVO saveCalendar(String entityNo, String storeNo, LocalDate localDate, String actorNo, SaveCalendarReq req, String idempotencyKey) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/TableService.java | ai.neargo.shop.food.service.TableService | PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query)<br>TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo, CreateTableAreaReq req, String idempotencyKey)<br>TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SaveTableAreaReq req, String idempotencyKey)<br>TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SetStatusTableAreaReq req, String idempotencyKey)<br>PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query)<br>TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo)<br>TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req, String idempotencyKey)<br>TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SaveTableReq req, String idempotencyKey)<br>TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SetStatusTableReq req, String idempotencyKey)<br>OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, ArchiveTableReq req, String idempotencyKey) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BundleInternalApi.java | ai.neargo.shop.food.spi.BundleInternalApi | CreateBundleResultVO createBundle(@RequestBody CreateCommand command)<br>BundleMutationVO replaceDefinition(@RequestBody UpdateCommand command)<br>GetBundleVO getBundle(@RequestBody GetQuery query)<br>PageData<BundleConfigVO> listBundles(@RequestBody Lookup query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BusinessCloseInternalApi.java | ai.neargo.shop.food.spi.BusinessCloseInternalApi | DayVO open(@RequestBody OpenCommand command)<br>ReportVO report(@RequestBody ReportQuery command)<br>CloseVO preview(@RequestBody PreviewCommand command)<br>CloseVO get(@RequestBody GetQuery command)<br>CloseVO sign(@RequestBody SignCommand command)<br>OperationVO adjust(@RequestBody AdjustCommand command)<br>List<ExceptionVO> exceptions(@RequestBody GetQuery command)<br>ExceptionVO proposeCarry(@RequestBody ProposeCarryCommand command)<br>ExceptionVO approveCarry(@RequestBody ApproveCarryCommand command) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BusinessOperationsInternalApi.java | ai.neargo.shop.food.spi.BusinessOperationsInternalApi | ServiceFormContracts.OrganizationVO serviceFormRegisterOrganization( @RequestBody ServiceFormRegisterOrganizationCommand command)<br>ServiceFormContracts.FormVO serviceFormBook(@RequestBody ServiceFormBookCommand command)<br>ServiceFormContracts.FormVO serviceFormGet(@RequestBody ServiceFormGetCommand command)<br>ServiceFormContracts.FormVO serviceFormRevise(@RequestBody ServiceFormReviseCommand command)<br>ServiceFormContracts.FormVO serviceFormEnter(@RequestBody ServiceFormEnterCommand command)<br>ServiceFormContracts.FormVO serviceFormLeave(@RequestBody ServiceFormLeaveCommand command)<br>ServiceFormContracts.FormVO serviceFormCancel(@RequestBody ServiceFormCancelCommand command)<br>ServiceFormContracts.FormVO serviceFormNoShow(@RequestBody ServiceFormNoShowCommand command)<br>ServiceFormContracts.FormVO serviceFormBindBatch( @RequestBody ServiceFormBindBatchCommand command)<br>List<ServiceFormContracts.AttendeeVO> serviceFormAttendees( @RequestBody ServiceFormAttendeesCommand command)<br>ServiceFormContracts.AttendeeVO serviceFormAdmit(@RequestBody ServiceFormAdmitCommand command)<br>ServiceFormContracts.AttendeeVO serviceFormExit(@RequestBody ServiceFormExitCommand command)<br>FoodFeeSourceContracts.BindingVO foodFeeSourceBind( @RequestBody FoodFeeSourceBindCommand command)<br>List<FoodFeeSourceContracts.BindingVO> foodFeeSourceBindings( @RequestBody FoodFeeSourceBindingsCommand command)<br>List<QueueNotificationService.NoticeVO> queueNotificationList( @RequestBody QueueNotificationListCommand command)<br>QueueNotificationService.NoticeVO queueNotificationRecover( @RequestBody QueueNotificationRecoverCommand command)<br>CheckCollaborationContracts.ReceivablePreview checkCollaborationPreviewReceivable( @RequestBody CheckCollaborationPreviewReceivableCommand command)<br>String checkCollaborationSourceHash(@RequestBody CheckCollaborationSourceHashCommand command)<br>OperationVO checkCollaborationReleaseService( @RequestBody CheckCollaborationReleaseServiceCommand command)<br>String checkCollaborationCurrentLineOwner( @RequestBody CheckCollaborationCurrentLineOwnerCommand command)<br>CheckCollaborationContracts.MoveVO checkCollaborationMoveBatches( @RequestBody CheckCollaborationMoveBatchesCommand command)<br>CheckCollaborationContracts.LineMoveVO checkCollaborationMoveLines( @RequestBody CheckCollaborationMoveLinesCommand command)<br>CheckCollaborationContracts.FreezeVO checkCollaborationFreeze( @RequestBody CheckCollaborationFreezeCommand command)<br>CheckCollaborationContracts.FreezeVO checkCollaborationRelease( @RequestBody CheckCollaborationReleaseCommand command)<br>ReservationPreorderContracts.PreorderVO reservationPreorderSave( @RequestBody ReservationPreorderSaveCommand command)<br>ReservationPreorderContracts.PreorderVO reservationPreorderGet( @RequestBody ReservationPreorderGetCommand command)<br>CartResultVO reservationPreorderPrepareCart( @RequestBody ReservationPreorderPrepareCartCommand command)<br>DiningOrderBatchVO reservationPreorderConvert( @RequestBody ReservationPreorderConvertCommand command)<br>ReservationPreorderContracts.PreorderVO reservationPreorderCancel( @RequestBody ReservationPreorderCancelCommand command)<br>ServiceSlotContracts.SlotVO serviceSlotCreate(@RequestBody ServiceSlotCreateCommand command)<br>ServiceSlotContracts.SlotVO serviceSlotGet(@RequestBody ServiceSlotGetCommand command)<br>ServiceSlotContracts.ClaimVO serviceSlotReserve(@RequestBody ServiceSlotReserveCommand command)<br>ServiceSlotContracts.ClaimVO serviceSlotTransition( @RequestBody ServiceSlotTransitionCommand command)<br>boolean serviceSlotDispatchDue(@RequestBody ServiceSlotDispatchDueCommand command)<br>BusinessOperationsContracts.Evidence businessOperationsEvidence( @RequestBody BusinessOperationsEvidenceCommand command)<br>BusinessOperationsContracts.HandoverVO businessOperationsPropose( @RequestBody BusinessOperationsProposeCommand command)<br>BusinessOperationsContracts.HandoverVO businessOperationsHandover( @RequestBody BusinessOperationsHandoverCommand command)<br>BusinessOperationsContracts.HandoverVO businessOperationsAccept( @RequestBody BusinessOperationsAcceptCommand command)<br>BusinessOperationsContracts.HandoverVO businessOperationsCancel( @RequestBody BusinessOperationsCancelCommand command)<br>BusinessOperationsContracts.CaseVO businessOperationsOpenCase( @RequestBody BusinessOperationsOpenCaseCommand command)<br>List<BusinessOperationsContracts.CaseVO> businessOperationsCases( @RequestBody BusinessOperationsCasesCommand command)<br>BusinessOperationsContracts.CaseVO businessOperationsRecoverCase( @RequestBody BusinessOperationsRecoverCaseCommand command)<br>BusinessOperationsContracts.CaseVO businessOperationsResolveCase( @RequestBody BusinessOperationsResolveCaseCommand command) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BusinessReturnApprovalInternalApi.java | ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi | ApprovalVO approve(@RequestBody Approve c)<br>ApprovalVO get(@RequestBody Query c)<br>ApprovalVO replacement(@RequestBody Replacement c) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/CartInternalApi.java | ai.neargo.shop.food.spi.CartInternalApi | CartResultVO create(@RequestBody CreateCommand command)<br>CartResultVO get(@RequestBody Identity command)<br>CartVersionVO replaceLine(@RequestBody ReplaceLineCommand command)<br>CartVersionVO archiveLine(@RequestBody ArchiveLineCommand command)<br>OperationResultVO archive(@RequestBody ArchiveCommand command)<br>CartSessionGrantVO authorize(@RequestBody AuthorizeCommand command)<br>CartResultVO customerCreate(@RequestBody CustomerCreateCommand command)<br>CartResultVO customerGet(@RequestBody CustomerIdentity command)<br>CartVersionVO customerReplaceLine(@RequestBody CustomerReplaceLineCommand command)<br>CartVersionVO customerArchiveLine(@RequestBody CustomerArchiveLineCommand command)<br>public void rejectUnknown(String name, Object value)<br>public void rejectUnknown(String name, Object value)<br>public void rejectUnknown(String name, Object value)<br>public void rejectUnknown(String name, Object value)<br>public void rejectUnknown(String name, Object value)<br>public void rejectUnknown(String name, Object value)<br>public void rejectUnknown(String name, Object value)<br>public String toString()<br>public void rejectUnknown(String name, Object value)<br>public String toString()<br>public void rejectUnknown(String name, Object value)<br>public String toString()<br>public void rejectUnknown(String name, Object value)<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/CatalogInternalApi.java | ai.neargo.shop.food.spi.CatalogInternalApi | public void rejectUnknown(String key, Object value)<br>public void rejectUnknown(String key, Object value)<br>public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ConfigRevisionInternalApi.java | ai.neargo.shop.food.spi.ConfigRevisionInternalApi | PublishConfigResultVO republishRevision(@RequestBody RepublishCommand command)<br>PublishConfigResultVO publishMenu(@RequestBody Command command)<br>PublishConfigResultVO publishStation(@RequestBody Command command)<br>PublishConfigResultVO publishRecipe(@RequestBody Command command)<br>PublishConfigResultVO publishModifier(@RequestBody Command command)<br>PublishConfigResultVO publishBundle(@RequestBody Command command)<br>PublishConfigResultVO publishOptionGroup(@RequestBody Command command)<br>PageReceipt listRevisions(@RequestBody Lookup query)<br>ConfigRevisionContentVO getRevision(@RequestBody Identity query)<br>ConfigRevisionContentVO resolveRevision(@RequestBody Resolve query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningDeliveryInternalApi.java | ai.neargo.shop.food.spi.DiningDeliveryInternalApi | DeliverySnapshot saveStaff(@RequestBody StaffSave command)<br>DeliverySnapshot saveCustomer(@RequestBody CustomerSave command)<br>DeliverySnapshot getStaff(@RequestBody Source query)<br>DeliverySnapshot getCustomer(@RequestBody CustomerQuery query)<br>DeliverySnapshot get(@RequestBody Source query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningIdentityInternalApi.java | ai.neargo.shop.food.spi.DiningIdentityInternalApi | public String toString()<br>DeviceVO device(@RequestBody DeviceQuery query)<br>DeviceCredentialVO registerDevice(@RequestBody Register command)<br>DeviceVO revokeDevice(@RequestBody DeviceRevoke command)<br>SessionCredentialVO createDeviceSession(@RequestBody DeviceSession command)<br>SessionCredentialVO createStaffParty(@RequestBody StaffParty command)<br>PartyVO revokeParty(@RequestBody PartyRevoke command)<br>PartyVO party(@RequestBody PartyQuery query)<br>Boolean validateDeviceSession(@RequestBody SessionQuery query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningOrderAuthorityInternalApi.java | ai.neargo.shop.food.spi.DiningOrderAuthorityInternalApi | Delivery delivery(@RequestBody Command command)<br>Party party(@RequestBody Command command)<br>Plan demandPlan(@RequestBody PlanCommand command) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningOrderChanged.java | ai.neargo.shop.food.spi.DiningOrderChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningOrderInternalApi.java | ai.neargo.shop.food.spi.DiningOrderInternalApi | public String toString()<br>public String toString()<br>public String toString()<br>public String toString()<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningPickupVerificationInternalApi.java | ai.neargo.shop.food.spi.DiningPickupVerificationInternalApi | public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningPricingInternalApi.java | ai.neargo.shop.food.spi.DiningPricingInternalApi | public String toString()<br>public String toString()<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningReturnInternalApi.java | ai.neargo.shop.food.spi.DiningReturnInternalApi | public void unknown(String name, Object value)<br>public void unknown(String name, Object value)<br>public void unknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningSessionInternalApi.java | ai.neargo.shop.food.spi.DiningSessionInternalApi | public String toString()<br>public String toString() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningTransactionRecoveryInternalApi.java | ai.neargo.shop.food.spi.DiningTransactionRecoveryInternalApi | public void unknown(String name, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DishInternalApi.java | ai.neargo.shop.food.spi.DishInternalApi | DishResultVO saveDish(@RequestBody SaveCommand command)<br>PageData<DishCatalogItemVO> listDishes(@RequestBody Lookup query)<br>DishDetailResultVO getDish(@RequestBody Identity query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodKdsInternalApi.java | ai.neargo.shop.food.spi.FoodKdsInternalApi | SubscriptionVO grant(@RequestBody Grant request)<br>SubscriptionVO revoke(@RequestBody Revoke request)<br>ReplayVO replay(@RequestBody Replay request)<br>Boolean append(@RequestBody EventDraft event) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodStationRuntimeInternalApi.java | ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi | StateVO state(@RequestBody Scope query)<br>Boolean requireRunning(@RequestBody Scope query)<br>CommandVO control(@RequestBody Control command)<br>VoicePoll claim(@RequestBody Poll query)<br>CommandVO acknowledge(@RequestBody Receipt command) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodSupportInternalApi.java | ai.neargo.shop.food.spi.FoodSupportInternalApi | ReadinessVO readiness(@RequestBody ReadinessQuery request)<br>List<CaseVO> cases(@RequestBody CasesQuery request)<br>Evidence evidence(@RequestBody SourceQuery request) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/MenuChannelAuthorityPort.java | ai.neargo.shop.food.spi.MenuChannelAuthorityPort | boolean authorized(String entityNo, String storeNo, String provider, String accountRef, String externalStoreRef)<br>boolean pricePolicyAuthorized(String entityNo, String storeNo, String bindingNo, String pricePolicyRef) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/MenuInternalApi.java | ai.neargo.shop.food.spi.MenuInternalApi | CreateMenuResultVO createMenu(@RequestBody CreateCommand command)<br>MenuResultVO updateMenu(@RequestBody UpdateCommand command)<br>MenuScheduleResultVO replaceSchedule(@RequestBody ScheduleCommand command)<br>MenuCompositionResultVO replaceComposition(@RequestBody CompositionCommand command)<br>MenuContextsResultVO replaceContexts(@RequestBody ContextsCommand command)<br>MenuResultVO disableMenu(@RequestBody DisableCommand command)<br>MenuConfigResultVO getMenu(@RequestBody GetQuery query)<br>PageReceipt listMenus(@RequestBody Lookup query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ModifierInternalApi.java | ai.neargo.shop.food.spi.ModifierInternalApi | CreateModifierResultVO createModifier(@RequestBody CreateCommand command)<br>ModifierMutationVO updateModifier(@RequestBody UpdateCommand command)<br>PageData<ModifierConfigVO> listModifiers(@RequestBody Lookup query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/OptionGroupInternalApi.java | ai.neargo.shop.food.spi.OptionGroupInternalApi | CreateOptionGroupResultVO createOptionGroup(@RequestBody CreateCommand command)<br>OptionGroupResultVO getOptionGroup(@RequestBody Identity query)<br>PageData<OptionGroupConfigVO> listOptionGroups(@RequestBody Lookup query)<br>OptionGroupMutationVO replaceOptionGroupItems(@RequestBody ReplaceCommand command) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/PricingSourceInternalApi.java | ai.neargo.shop.food.spi.PricingSourceInternalApi | DiningPriceFacts staff(@RequestBody StaffCommand command)<br>DiningPriceFacts customer(@RequestBody CustomerCommand command)<br>public void unknown(String key, Object value)<br>public void unknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ProductionConsumptionInternalApi.java | ai.neargo.shop.food.spi.ProductionConsumptionInternalApi | ConsumptionVO consume(@RequestBody Command command)<br>ConsumptionVO get(@RequestBody Command command)<br>PageData<ConsumptionVO> list(@RequestBody ListCommand command)<br>ConsumptionVO adjust(@RequestBody AdjustmentCommand command)<br>ConsumptionVO reverse(@RequestBody ReverseCommand command) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/QueueNotificationPort.java | ai.neargo.shop.food.spi.QueueNotificationPort | Receipt lookup(Request request)<br>Receipt send(Request request) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/RecipeInternalApi.java | ai.neargo.shop.food.spi.RecipeInternalApi | RecipeResultVO createRecipe(@RequestBody CreateCommand command)<br>RecipeResultVO replaceDefinition(@RequestBody UpdateCommand command)<br>RecipeResultVO getRecipe(@RequestBody GetQuery query)<br>PageReceipt listRecipes(@RequestBody Lookup query) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/SalesRestrictionInternalApi.java | ai.neargo.shop.food.spi.SalesRestrictionInternalApi | public void rejectUnknown(String key, Object value)<br>public void rejectUnknown(String key, Object value)<br>public void rejectUnknown(String key, Object value) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ServiceCalendarInternalApi.java | ai.neargo.shop.food.spi.ServiceCalendarInternalApi | OperationVO saveCalendar(@RequestBody Command command) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/StoreOperationInternalApi.java | ai.neargo.shop.food.spi.StoreOperationInternalApi | StoreOperationPolicyResultVO getPolicy(@RequestBody Lookup request) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/StorePolicyPublicationInternalApi.java | ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi | OperationVO createPolicy(@RequestBody Command request) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/TableInternalApi.java | ai.neargo.shop.food.spi.TableInternalApi | PageData<TableAreaVO> listTableAreas(@RequestBody Lookup request)<br>TableAreaResultVO createTableArea(@RequestBody CreateCommand request)<br>TableAreaResultVO saveTableArea(@RequestBody SaveCommand request)<br>TableAreaResultVO setStatusTableArea(@RequestBody StatusCommand request)<br>PageData<TableVO> listTables(@RequestBody TableLookup request)<br>TableResultVO getTable(@RequestBody TableIdentity request)<br>TableResultVO createTable(@RequestBody TableCreateCommand request)<br>TableResultVO saveTable(@RequestBody TableSaveCommand request)<br>TableResultVO setStatusTable(@RequestBody TableStatusCommand request)<br>OperationResultVO archiveTable(@RequestBody TableArchiveCommand request) |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/BundleChanged.java | ai.neargo.shop.spi.food.BundleChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/CartChanged.java | ai.neargo.shop.spi.food.CartChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/CheckChanged.java | ai.neargo.shop.spi.food.CheckChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ConfigPublished.java | ai.neargo.shop.spi.food.ConfigPublished | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningDeviceChanged.java | ai.neargo.shop.spi.food.DiningDeviceChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningOrderAccepted.java | ai.neargo.shop.spi.food.DiningOrderAccepted | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningQueueChanged.java | ai.neargo.shop.spi.food.DiningQueueChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningServiceEvent.java | ai.neargo.shop.spi.food.DiningServiceEvent | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningSessionChanged.java | ai.neargo.shop.spi.food.DiningSessionChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningStationRuntimeChanged.java | ai.neargo.shop.spi.food.DiningStationRuntimeChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DishChanged.java | ai.neargo.shop.spi.food.DishChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/MenuChanged.java | ai.neargo.shop.spi.food.MenuChanged | public MenuChanged(String entity, String store, String menu, String action, long version, String operation, MenuVO after, MenuScheduleVO schedule, MenuCompositionVO composition)<br>public MenuChanged(String entityNo, String storeNo, String menuNo, String action, long version, String operationNo, MenuVO after, MenuScheduleVO schedule)<br>public MenuChanged(String entityNo, String storeNo, String menuNo, String action, long version, String operationNo, MenuVO after)<br>public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/MenuChannelBindingChanged.java | ai.neargo.shop.spi.food.MenuChannelBindingChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ModifierChanged.java | ai.neargo.shop.spi.food.ModifierChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/OptionGroupChanged.java | ai.neargo.shop.spi.food.OptionGroupChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/RecipeChanged.java | ai.neargo.shop.spi.food.RecipeChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ReservationChanged.java | ai.neargo.shop.spi.food.ReservationChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/SalesRestrictionChanged.java | ai.neargo.shop.spi.food.SalesRestrictionChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ServiceCalendarSaved.java | ai.neargo.shop.spi.food.ServiceCalendarSaved | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/StationChanged.java | ai.neargo.shop.spi.food.StationChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/StorePolicyPublished.java | ai.neargo.shop.spi.food.StorePolicyPublished | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/TableAreaChanged.java | ai.neargo.shop.spi.food.TableAreaChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/TableChanged.java | ai.neargo.shop.spi.food.TableChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreDishOfferQueryPortImpl.java | ai.neargo.shop.food.channel.port.CoreDishOfferQueryPortImpl | public CoreDishOfferQueryPortImpl(Supplier<DishOfferInternalApi> api)<br>public List<Offer> getOffers(String entity, String store, List<String> numbers) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreGoodsCatalogQueryPortImpl.java | ai.neargo.shop.food.channel.port.CoreGoodsCatalogQueryPortImpl | public CoreGoodsCatalogQueryPortImpl(Supplier<GoodsCatalogInternalApi> api)<br>public SkuIdentityFact getSku(String entity, String store, String sku)<br>public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers)<br>public GoodsFact getGoods(String entityNo, String storeNo, String goodsNo) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreIndustryAfterSalePortImpl.java | ai.neargo.shop.food.channel.port.CoreIndustryAfterSalePortImpl | public CoreIndustryAfterSalePortImpl(Supplier<IndustryAfterSaleInternalApi> api)<br>public Result apply(Apply request)<br>public Lookup queryApply(QueryApply query)<br>public Result get(Reference reference)<br>public Result cancel(Reference reference) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreIndustryCheckoutPortImpl.java | ai.neargo.shop.food.channel.port.CoreIndustryCheckoutPortImpl | public CoreIndustryCheckoutPortImpl(Supplier<IndustryCheckoutInternalApi> api)<br>public View get(Identity identity, String no)<br>public View pay(PayCommand command)<br>public View queryPayment(Identity identity, String no)<br>public View finalizeCheckout(Identity identity, String no) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreIndustryFulfillmentPortImpl.java | ai.neargo.shop.food.channel.port.CoreIndustryFulfillmentPortImpl | public CoreIndustryFulfillmentPortImpl(Supplier<IndustryFulfillmentInternalApi> api)<br>public Receipt fulfill(Fulfill c,String key)<br>public Receipt verifyPickup(VerifyPickup c,String key)<br>public Receipt query(Reference ref,String actor,String key) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreIndustryOrderAmendmentPortImpl.java | ai.neargo.shop.food.channel.port.CoreIndustryOrderAmendmentPortImpl | public CoreIndustryOrderAmendmentPortImpl(Supplier<IndustryOrderAmendmentInternalApi> api)<br>public Receipt amend(Command c, String key)<br>public Receipt query(Reference r, String actor, String key)<br>public Receipt adjustFee(FeeScopeAdjustment c, String key) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreIndustryOrderPortImpl.java | ai.neargo.shop.food.channel.port.CoreIndustryOrderPortImpl | public CoreIndustryOrderPortImpl(Supplier<IndustryOrderInternalApi> api)<br>public Result acceptUnpaid(Identity identity,String order)<br>public Result cancelForStore(Command command, String key, String fulfillment)<br>public Result cancel(Command command, String key)<br>public Result getForStore(Scope scope, String actor, String order)<br>public Result placeForDiningRoom(Command command, String key)<br>public Result placeForStore(Command command, String key)<br>public Result queryForStore(Identity identity, String key)<br>public Result placeForService(Command command, String key, String fulfillment, boolean staff)<br>public Result place(Command command, String key)<br>public Result query(Identity identity, String key)<br>public Result get(Identity identity, String order) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreInventoryDemandPlanPortImpl.java | ai.neargo.shop.food.channel.port.CoreInventoryDemandPlanPortImpl | public CoreInventoryDemandPlanPortImpl(Supplier<InventoryDemandPlanInternalApi> api)<br>public Receipt reserve(Plan p, long ttl, int version)<br>public Receipt query(Scope s)<br>public Receipt acceptUnpaid(Scope s)<br>public Receipt allocatePaid(Scope s)<br>public Receipt issue(Scope s, String op, String h, String phase, List<Target> ts)<br>public Receipt issueTo(Scope s, String op, String h, String phase, List<Target> ts)<br>public Receipt releaseTo(Scope s, String op, String h, List<Target> ts)<br>public Receipt supplement(Scope s, String op, String h, List<Target> ts)<br>public Receipt reverse(Scope s, String op, String original, String h) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreInventoryProductionPortImpl.java | ai.neargo.shop.food.channel.port.CoreInventoryProductionPortImpl | public CoreInventoryProductionPortImpl(Supplier<InventoryProductionInternalApi> api)<br>public Receipt consume(Command c)<br>public Receipt reverse(Reverse c)<br>public Receipt query(Command c) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreInventoryReservationPortImpl.java | ai.neargo.shop.food.channel.port.CoreInventoryReservationPortImpl | public CoreInventoryReservationPortImpl(Supplier<InventoryReservationInternalApi> api)<br>public Snapshot reserve(Scope scope, List<Line> lines, long ttlSeconds, int lifecycleVersion)<br>public Snapshot acceptUnpaid(Scope scope)<br>public Snapshot allocatePaid(Scope scope)<br>public Snapshot issue(Scope scope, String fulfillmentRef)<br>public Snapshot cancelAll(Scope scope)<br>public Snapshot query(Scope scope) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreMaterialCatalogQueryPortImpl.java | ai.neargo.shop.food.channel.port.CoreMaterialCatalogQueryPortImpl | public CoreMaterialCatalogQueryPortImpl(Supplier<MaterialCatalogInternalApi> api)<br>public List<SkuMaterialFact> getSkuMaterials( String entityNo, String storeNo, List<String> skuNos)<br>public List<MaterialFact> getMaterials(String entityNo, List<String> itemIds) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreMediaAssetQueryPortImpl.java | ai.neargo.shop.food.channel.port.CoreMediaAssetQueryPortImpl | public CoreMediaAssetQueryPortImpl(Supplier<MediaAssetInternalApi> api)<br>public List<AssetFact> getAssets(String entity, String store, List<String> keys) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CorePricingCheckoutPortImpl.java | ai.neargo.shop.food.channel.port.CorePricingCheckoutPortImpl | public CorePricingCheckoutPortImpl(Supplier<PricingCheckoutInternalApi> api)<br>public QuoteVO quoteStaff(Scope scope, String actor, PricingSourceRef source, Selection selection, String key)<br>public QuoteVO quoteCustomer(PricingSourceRef source, Credentials access, Selection selection, String key)<br>public QuoteVO getStaff(Scope scope, String actor, PricingSourceRef source, String quote)<br>public QuoteVO getCustomer(PricingSourceRef source, Credentials access, String quote)<br>public QuoteVO revalidateStaff(Scope scope, String actor, PricingSourceRef source, String quote, String digest)<br>public QuoteVO revalidateCustomer(PricingSourceRef source, Credentials access, String quote, String digest) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CorePrintPortImpl.java | ai.neargo.shop.food.channel.port.CorePrintPortImpl | public CorePrintPortImpl(Supplier<PrintInternalApi> api)<br>public Configuration configuration(Scope scope)<br>public DeviceVO bindDevice(Scope scope, DeviceReq req)<br>public DeviceVO revokeDevice(Scope scope, String no, String reason, String key)<br>public TemplateReq publishTemplate(Scope scope, TemplateReq req)<br>public RouteReq putRoute(Scope scope, RouteReq req)<br>public List<JobVO> enqueue(Scope scope, Enqueue req)<br>public List<JobVO> list(Scope scope, String after, int limit)<br>public JobVO get(Scope scope, String no)<br>public JobVO retry(Scope scope, String no)<br>public JobVO reprint(Scope scope, String no, String reason, String key)<br>public DeviceVO heartbeat(Scope scope, String device, boolean online)<br>public Claim claim(Scope scope, String device)<br>public JobVO acknowledge(Scope scope, String device, Acknowledge req) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreSpecValueCatalogQueryPortImpl.java | ai.neargo.shop.food.channel.port.CoreSpecValueCatalogQueryPortImpl | public CoreSpecValueCatalogQueryPortImpl(Supplier<SpecValueCatalogInternalApi> api)<br>public List<SpecValueFact> getValues(String entity, List<String> numbers) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/CoreStoreCardQueryPortImpl.java | ai.neargo.shop.food.channel.port.CoreStoreCardQueryPortImpl | public CoreStoreCardQueryPortImpl(Supplier<StoreCardInternalApi> api)<br>public Map<String, StoreDirectoryPort.StoreCard> cards(Collection<String> numbers) |
| backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/port/RemoteIndustryAmendmentSourcePortImpl.java | ai.neargo.shop.food.channel.port.RemoteIndustryAmendmentSourcePortImpl | public RemoteIndustryAmendmentSourcePortImpl(Supplier<IndustryAmendmentSourceInternalApi> api)<br>public Evidence get(Reference r, String number) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/assembler/FoodEventPrintDocumentAssembler.java | ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler | public FoodEventPrintDocumentAssembler(FoodKdsMapper events, ObjectMapper json)<br>public Enqueue freeze(Scope scope, PrintDocumentReq req, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/assembler/FoodPrintDocumentAssembler.java | ai.neargo.shop.food.assembler.FoodPrintDocumentAssembler | public void livePrebillSource(@Lazy CheckCollaborationService service)<br>public FoodPrintDocumentAssembler( OrderBatchMapper batches, OrderLineMapper lines, CoreLineAllocationMapper allocations, ReturnMapper returns, ReturnItemMapper returnItems, CheckMapper checks, KitchenTaskService kitchen, PlatformTransactionManager manager)<br>public Enqueue freeze(Scope scope, PrintDocumentReq req, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodAutoConfiguration.java | ai.neargo.shop.food.config.FoodAutoConfiguration | public MenuConfigResultVO capture(String entity, String store, String menu, boolean lock)<br>public long currentVersion(String entity, String store, String menu, boolean lock)<br>public void advance(String entity, String store, String menu, long version, FnbConfigRevision revision) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodBusinessAmendmentConfiguration.java | ai.neargo.shop.food.config.FoodBusinessAmendmentConfiguration | public void run() |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodDataScopeRegistration.java | ai.neargo.shop.food.config.FoodDataScopeRegistration | public void register(DataScopeTableRegistry registry) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodPersistenceConfiguration.java | ai.neargo.shop.food.config.FoodPersistenceConfiguration | public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodProductionRecoveryConfiguration.java | ai.neargo.shop.food.config.FoodProductionRecoveryConfiguration | public void recover() |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodProperties.java | ai.neargo.shop.food.config.FoodProperties | public FoodProperties(boolean enabled, Deployment deployment)<br>public record Migration(@DefaultValue("validate") MigrationMode mode) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodSchemaReady.java | ai.neargo.shop.food.config.FoodSchemaReady | public boolean ready()<br>public static FoodSchemaReady off() |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodSchemaValidator.java | ai.neargo.shop.food.config.FoodSchemaValidator | public FoodSchemaReady initialize(DataSource source, FoodProperties.MigrationMode mode)<br>public Connection getConnection()<br>public Connection getConnection(String user, String password) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/entity/FoodBusinessOperationRow.java | ai.neargo.shop.food.entity.FoodBusinessOperationRow | public FoodBusinessOperationRow(Map<String, Object> values)<br>public Map<String, Object> snapshot()<br>public Object get(String column)<br>public String text(String column)<br>public long number(String column)<br>public BigDecimal decimal(String column)<br>public Instant instant(String column) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/BusinessSharePlanMapper.java | ai.neargo.shop.food.mapper.BusinessSharePlanMapper | List<String> currentBatchNos( @Param("entity") String entity, @Param("store") String store, @Param("check") String check)<br>List<MembershipScope> liveMemberships( @Param("entity") String entity, @Param("batch") String batch)<br>long blockingReturns( @Param("entity") String entity, @Param("store") String store, @Param("batch") String batch)<br>List<String> lineOwners(@Param("entity") String entity, @Param("line") String line) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/DiningBatchQueryMapper.java | ai.neargo.shop.food.mapper.DiningBatchQueryMapper | long count( @Param("entity") String entity, @Param("store") String store, @Param("query") ListDiningBatchesQuery query)<br>List<SourceRow> page( @Param("entity") String entity, @Param("store") String store, @Param("query") ListDiningBatchesQuery query, @Param("offset") long offset, @Param("size") int size)<br>List<MembershipRow> currentChecks( @Param("entity") String entity, @Param("store") String store, @Param("numbers") List<String> numbers)<br>List<BindingRow> bindings( @Param("entity") String entity, @Param("store") String store, @Param("numbers") List<String> numbers) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java | ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper | public FoodBusinessOperationsMapper(DataSource source)<br>public List<FoodBusinessOperationRow> sourceEvidence( String kind, String entity, String store, String sourceNo, boolean lock)<br>public List<FoodBusinessOperationRow> operationCases( String entity, String store, String status)<br>public List<FoodBusinessOperationRow> checkBusinessGuardSelectOrderBatch( String e, String store, String batch, String e2, String store2, String batch2)<br>public long checkBusinessGuardPendingReturns(String entity, String store, String check)<br>public List<FoodBusinessOperationRow> checkBusinessGuardSelectCheck( String entity, String store, String check)<br>public Long checkBusinessGuardCountCheckFreeze(String entity, String store, String check)<br>public Long checkBusinessGuardCountSettlementItem( String entity, String store, String check, String check2)<br>public int checkBusinessGuardUpdateBusinessSharePlan( String value1, String actor, String entity, String store, String check)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectOrderLine( String e, String store, String check)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectOrderLine2( String e, String store, String line)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectOrderLine3( String e, String store, String check)<br>public Long checkCollaborationCountOrderBatch(String e, String store, String check)<br>public Long checkCollaborationCountKitchenTask(String e, String line)<br>public Long checkCollaborationCountReturnItem(String e, String line)<br>public Long checkCollaborationCountDiningEntitlement(String e, String check)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectTable( String e, String store, String check)<br>public Long checkCollaborationCountTableOccupancy(String e, String check)<br>public int checkCollaborationUpdateTableOccupancy(String actor, String e, String check)<br>public int checkCollaborationUpdateTable(String actor, String e, String store, String tableNo)<br>public int checkCollaborationUpdateGuestSession( String actor, String e, String store, String check)<br>public int checkCollaborationUpdateCheckCart( String actor, String e, String store, String check)<br>public int checkCollaborationUpdateCheck(String actor, String e, String store, String check)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectOrderBatch( String entity, String store, String batch)<br>public Long checkCollaborationCountCheckLineMembership(String entity, String batch)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectCheckBatchMembership( String entity, String batch)<br>public Long checkCollaborationCountReturn(String entity, String store, String batch)<br>public int checkCollaborationUpdateCheckBatchMembership( String actor, String entity, String batch)<br>public int checkCollaborationInsertCheckBatchMembership( String entity, String value2, String checkNo, String batch, String op, String actor, String actor2)<br>public int checkCollaborationUpdateCheck2( String actor, String entity, String store, String checkNo, long version)<br>public int checkCollaborationUpdateCheckCart2( String actor, String entity, String store, String checkNo)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectOrderLine4( String e, String store, String line)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectCheckLineMembership( String e, String line)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectCheckBatchMembership2( String e, String batchNo)<br>public Long checkCollaborationCountReturnItem2(String e, String line)<br>public Long checkCollaborationCountSettlementItem(String e, String batchNo)<br>public int checkCollaborationUpdateCheckLineMembership(String actor, String e, String line)<br>public int checkCollaborationInsertCheckLineMembership( String e, String store, String value3, String checkNo, String batchNo, String line, String line2, String seatNo, BigDecimal qty, String unit, String op, String actor, String actor2)<br>public int checkCollaborationUpdateCheck3( String actor, String e, String store, String checkNo, long version)<br>public int checkCollaborationUpdateCheckCart3( String actor, String e, String store, String checkNo)<br>public int checkCollaborationInsertCheckFreeze( String entity, String store, String no, String check, String check2, String sourceHash, String reason, String actor, String actor2)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectCheckFreeze( String entity, String store, String no)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectCheckFreeze2( String entity, String store, String no)<br>public Long checkCollaborationCountSettlementItem2(String entity, String check, String store)<br>public int checkCollaborationUpdateCheckFreeze(String actor, String no, String entity)<br>public List<FoodBusinessOperationRow> checkCollaborationSelectCheck( String e, String s, String c)<br>public int checkCollaborationInsertActionLog( String e, String s, String value3, String object, String action, String actor, String reason, String value8, String value9, String actor2, String actor3)<br>public Long reservationPreorderCountReservationPreorder( String e, String s, String reservation)<br>public int reservationPreorderInsertReservationPreorder( String e, String s, String no, String reservation, String reservation2, LocalDateTime value6, String value7, String a, String a2)<br>public int reservationPreorderUpdateReservationPreorder( String a, String a2, String e, String no)<br>public int reservationPreorderUpdateReservationPreorder2( String cartNo, String a, String e, String no)<br>public int reservationPreorderUpdateReservationPreorder3( String hash, String value2, String a, String a2, String e, String no)<br>public int reservationPreorderUpdateReservationPreorder4( String state, String batchNo, String state2, String a, String e, String no)<br>public int reservationPreorderUpdateReservationPreorder5( String reason, String a, String e, String no)<br>public List<FoodBusinessOperationRow> reservationPreorderSelectReservation( String e, String s, String no, boolean lock)<br>public List<FoodBusinessOperationRow> reservationPreorderSelectReservationPreorder( String e, String s, String reservation, String no, boolean lock)<br>public List<FoodBusinessOperationRow> serviceSlotSelectStorePolicy(String e, String s)<br>public int serviceSlotInsertServiceSlot( String e, String s, String no, LocalDateTime value4, LocalDateTime value5, LocalDateTime value6, LocalDateTime value7, BigDecimal capacity, String unit, String a, String a2)<br>public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim( String e, String s, String sourceNo)<br>public int serviceSlotInsertCapacityClaim( String e, String s, String no, String slotNo, String sourceNo, BigDecimal quantity, String a, String a2)<br>public int serviceSlotUpdateServiceSlot( BigDecimal quantity, String a, String e, String slotNo)<br>public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim2(String e, String no)<br>public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim3( String e, String s, String no)<br>public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim4( String e, String s, String no)<br>public int serviceSlotUpdateServiceSlot2( BigDecimal qty, BigDecimal value2, String a, String e, String slotNo, BigDecimal qty2)<br>public int serviceSlotUpdateCapacityClaim(String target, String a, String e, String no)<br>public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim5( String e, String s, String source)<br>public List<FoodBusinessOperationRow> serviceSlotSelectServiceSlot( String e, String s, String no, boolean lock)<br>public List<FoodBusinessOperationRow> businessOperationsSelectServiceResponsibility( String e, String s, String kind, String sourceNo)<br>public int businessOperationsInsertServiceHandover( String e, String s, String no, String a, String receiverNo, String hash, String reason, String a2, String a3)<br>public int businessOperationsInsertServiceHandoverItem( String e, String s, String value3, String no, String kind, String sourceNo, String sourceHash, String a, String a2)<br>public List<FoodBusinessOperationRow> businessOperationsSelectServiceHandoverItem( String e, String no)<br>public List<FoodBusinessOperationRow> businessOperationsSelectServiceResponsibility2( String e, String s, String kind, String sourceNo)<br>public int businessOperationsInsertServiceResponsibility( String e, String s, String value3, String kind, String sourceNo, String a, String no, String a2, String a3)<br>public int businessOperationsUpdateServiceResponsibility( String a, String no, String a2, String e, String s, String kind, String sourceNo)<br>public int businessOperationsUpdateCheck( String a, String a2, String e, String s, String sourceNo)<br>public int businessOperationsUpdateServiceHandover( String value1, String a, String a2, String e, String no)<br>public List<FoodBusinessOperationRow> businessOperationsSelectOperationCase( String e, String s, String active)<br>public int businessOperationsInsertOperationCase( String e, String s, String no, String kind, String sourceNo, String active, String ownerNo, String sourceHash, String reason, String a, String a2)<br>public int businessOperationsUpdateOperationCase(String a, String e, String no)<br>public int businessOperationsUpdateOperationCase2( String sourceHash, String reason, String a, String e, String no)<br>public List<FoodBusinessOperationRow> businessOperationsSelectServiceHandover( String e, String s, String no, boolean lock)<br>public List<FoodBusinessOperationRow> businessOperationsSelectOperationCase2( String e, String s, String no, boolean lock)<br>public int businessOperationsInsertActionLog( String e, String s, String value3, String no, String action, String a, String reason, String value8, String hash, String a2, String a3)<br>public int commandReceiptInsertBusinessCommand( String scopeHash, String keyHash, String actor, String hash)<br>public int commandReceiptUpdateBusinessCommand( String value1, String scopeHash, String keyHash)<br>public List<FoodBusinessOperationRow> commandReceiptSelectBusinessCommand( String scope, String key)<br>public List<FoodBusinessOperationRow> scheduledOrderSlots( String e, String store, LocalDateTime starts)<br>public List<FoodBusinessOperationRow> capacityBySource(String e, String store, String source)<br>public List<FoodBusinessOperationRow> capacityByBatch(String e, String store, String batch)<br>public List<FoodBusinessOperationRow> capacityOriginalBatch( String e, String store, String batch)<br>public int bindCapacityBatch( String e, String store, String source, String batch, String actor)<br>public int releaseConsumedCapacity(String e, String slot, BigDecimal qty, String actor)<br>public int markCapacityReleased(String e, String claim, String actor)<br>public int createScheduledDispatch( String entity, String store, String batch, LocalDateTime dispatchAt, String actor)<br>public List<FoodBusinessOperationRow> dueScheduledDispatch(LocalDateTime now, int limit)<br>public int claimScheduledDispatch( String entity, String store, String batch, long version, LocalDateTime now, LocalDateTime lease)<br>public int finishScheduledDispatch( String entity, String store, String batch, long version, String status, String error, LocalDateTime next)<br>public List<FoodBusinessOperationRow> reservationActivePreorders( String entity, String store, String reservation)<br>public int cancelReservationPreorder( String entity, String preorder, String reason, String actor)<br>public List<FoodBusinessOperationRow> closeOperationCases( String entity, String store, LocalDateTime from, LocalDateTime until)<br>public List<FoodBusinessOperationRow> closePendingHandovers( String entity, String store, LocalDateTime from, LocalDateTime until)<br>public List<FoodBusinessOperationRow> closeSourceReference( String entity, String store, String reference, LocalDate date)<br>public int recordQueueNotice( String e, String store, String no, String ticket, long version, String contact, String actor)<br>public List<FoodBusinessOperationRow> queueNotices(String e, String store, String ticket)<br>public List<FoodBusinessOperationRow> queueNotice(String e, String store, String no)<br>public List<FoodBusinessOperationRow> dueQueueNotices(LocalDateTime now, int limit)<br>public int claimQueueNotice( String e, String store, String no, long version, LocalDateTime now, LocalDateTime lease)<br>public int finishQueueNotice( String e, String store, String no, long version, String status, String receipt, String error, LocalDateTime next)<br>public List<FoodBusinessOperationRow> feeBindings(String e, String store, String check)<br>public List<FoodBusinessOperationRow> feeBinding( String e, String store, String rule, String scope, boolean lock)<br>public int insertFeeBinding( String e, String store, String no, String check, String rule, String revision, String scope, String kind, String unit, BigDecimal quantity, String refs, String waiver, boolean enabled, String actor)<br>public int updateFeeBinding( String e, String store, String rule, String scope, String revision, String kind, String unit, BigDecimal quantity, String refs, String waiver, boolean enabled, long version, String actor)<br>public List<FoodBusinessOperationRow> feeCheck(String e, String store, String check)<br>public int resizeReservedCapacity( String e, String store, String source, BigDecimal quantity, long version, String actor)<br>public int changeReservedCapacity(String e, String slot, BigDecimal delta, String actor)<br>public Long activeServiceForms(String entity, String store, String check)<br>public List<FoodBusinessOperationRow> checkFormEvidence( String entity, String store, String check)<br>public List<FoodBusinessOperationRow> closeServiceForms( String entity, String store, LocalDateTime from, LocalDateTime until)<br>public List<FoodBusinessOperationRow> currentCoreBindings( String e, String store, String check)<br>public List<FoodBusinessOperationRow> inheritedFeeContracts( String e, String store, String check, String rule)<br>public List<FoodBusinessOperationRow> cartFeeContract( String e, String store, String cart, long version, String rule)<br>public int cartFeeContract( String e, String store, String cart, long version, String rule, String revision, String scope, String kind, BigDecimal quantity, String hash)<br>public int bindingContract(String e, String store, String rule, String check, String contract) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodKdsMapper.java | ai.neargo.shop.food.mapper.FoodKdsMapper | public FoodKdsMapper(JdbcTemplate jdbc)<br>public void ensureCursor(String entity, String store)<br>public long head(String entity, String store, boolean lock)<br>public void advance(String entity, String store, long sequence)<br>public FnbKdsSubscription subscription(String entity, String store, String no, boolean lock)<br>public FnbKdsSubscription originalGrant(String entity, String store, String actor, String key)<br>public void insert(FnbKdsSubscription row)<br>public void revoke( String entity, String store, String no, long version, String actor, String reason, String key)<br>public FnbKdsEvent originalEvent(String entity, String store, String no)<br>public void insert(FnbKdsEvent row)<br>public List<FnbKdsEvent> replay( String entity, String store, List<String> stations, long after, long head, int limit) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodProductionEvidenceMapper.java | ai.neargo.shop.food.mapper.FoodProductionEvidenceMapper | String componentReference( @Param("entity") String entity, @Param("line") String line, @Param("component") String component) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodStationRuntimeMapper.java | ai.neargo.shop.food.mapper.FoodStationRuntimeMapper | public FoodStationRuntimeMapper(JdbcTemplate jdbc)<br>public boolean stationExists(String e, String s, String no)<br>public StateVO state(String e, String s, String station, boolean lock)<br>public void initialize(String e, String s, String station)<br>public void change(String e, String s, String no, String status, long version)<br>public Map<String, Object> command(String e, String s, String no, boolean lock)<br>public Map<String, Object> original(String e, String s, String actor, String key)<br>public void insert( String e, String s, String station, String no, ControlReq req, String actor, String key, String hash, long version, Instant at)<br>public List<Map<String, Object>> expired(String e, String s, String device, Instant now)<br>public boolean outstanding(String e, String s, String device)<br>public Map<String, Object> next(String e, String s, String device, List<String> stations)<br>public void claim(String e, String s, String no, String actor, String token, Instant until)<br>public void receipt(String e, String s, String no, String status, String code)<br>public static String text(Map<String, Object> row, String name)<br>public static long number(Map<String, Object> row, String name) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/ReservationTableMapper.java | ai.neargo.shop.food.mapper.ReservationTableMapper | int clear(@Param("entity") String entity,@Param("store") String store,@Param("reservation") String reservation)<br>long overlaps(@Param("entity") String entity,@Param("store") String store,@Param("table") String table, @Param("start") LocalDateTime start,@Param("end") LocalDateTime end,@Param("except") String except)<br>long pending(@Param("entity") String entity,@Param("store") String store,@Param("table") String table, @Param("now") LocalDateTime now) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/ServiceFormMapper.java | ai.neargo.shop.food.mapper.ServiceFormMapper | public ServiceFormMapper(DataSource source)<br>public int registerOrganization( String e, String store, String no, String name, String contact, String external, String actor)<br>public List<FoodBusinessOperationRow> organization(String e, String store, String no)<br>public List<FoodBusinessOperationRow> menu(String e, String revision)<br>public List<FoodBusinessOperationRow> reservation( String e, String store, String no, boolean lock)<br>public List<FoodBusinessOperationRow> check(String e, String store, String no, boolean lock)<br>public List<FoodBusinessOperationRow> form(String e, String store, String no, boolean lock)<br>public int book( String e, String store, String no, String kind, String reservation, String slot, String menu, String revision, String snapshot, int persons, LocalDateTime from, LocalDateTime until, String org, String contact, int max, String actor)<br>public int revise( String e, String store, String no, long version, int persons, String menu, String revision, String snapshot, String actor)<br>public int enter( String e, String store, String no, long version, String check, int persons, String actor)<br>public int addAttendee( String e, String store, String no, String form, String guest, String seat, String personClass, String actor)<br>public Long seatCount(String e, String check, String seat)<br>public List<FoodBusinessOperationRow> attendees( String e, String store, String form, boolean lock)<br>public int attendeeTransition( String e, String store, String form, String no, long version, String state, boolean enter, String actor)<br>public int leave(String e, String store, String no, long version, String actor)<br>public int closeAttendees(String e, String store, String no, String actor)<br>public int cancelIntent( String e, String store, String no, long version, String target, String reason, String actor)<br>public int cancelled( String e, String store, String no, String target, String source, String actor)<br>public List<FoodBusinessOperationRow> reservationForms( String e, String store, String reservation)<br>public List<FoodBusinessOperationRow> batch(String e, String store, String batch)<br>public List<FoodBusinessOperationRow> batchLineOwners(String e, String store, String batch)<br>public int bindBatch( String e, String store, String no, String batch, String hash, String actor)<br>public int advance(String e, String store, String no, long version, String actor)<br>public List<FoodBusinessOperationRow> batchBinding(String e, String store, String batch)<br>public int advanceCheck(String e, String store, String check, long version) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/BundleDefinitionPolicy.java | ai.neargo.shop.food.policy.BundleDefinitionPolicy | public static ReplaceBundleDefinitionReq normalize(ReplaceBundleDefinitionReq input)<br>public static void identifier(String value) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/BundlePublicationPolicy.java | ai.neargo.shop.food.policy.BundlePublicationPolicy | public static ReplaceBundleDefinitionReq validate(ReplaceBundleDefinitionReq input) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/BundlePublicationSource.java | ai.neargo.shop.food.policy.BundlePublicationSource | public BundlePublicationSource(BundleMapper heads, BundleGroupMapper groups, BundleChoiceMapper choices, BooleanSupplier enabled)<br>public record Captured(BundleConfigVO definition, List<String> skuNos)<br>public Captured capture(String entity, String number, boolean lock)<br>public long currentVersion(String entity, String store, String number, boolean lock)<br>public void advance(String entity, String number, long version, FnbConfigRevision revision) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/BusinessReturnDocuments.java | ai.neargo.shop.food.policy.BusinessReturnDocuments | public static Frozen read(FnbReturn row, ObjectMapper json) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/BusinessSharePolicy.java | ai.neargo.shop.food.policy.BusinessSharePolicy | public static Division divide(List<AllocationSource> sources, CreateReq request)<br>public static Map<String, Long> largestRemainder(long total, Map<String, Long> weights) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/CustodyFulfillmentPolicy.java | ai.neargo.shop.food.policy.CustodyFulfillmentPolicy | public static List<ItemTarget> targets( List<CustodyReceiptVO> all, CustodyReceiptVO selected, Map<String, BigDecimal> originalMain) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/FoodCommandFingerprint.java | ai.neargo.shop.food.policy.FoodCommandFingerprint | public static String scope(String operation, String entityNo, String storeNo)<br>public static String policy(CreatePolicyReq req)<br>public static String calendar(LocalDate date, SaveCalendarReq req, List<String> intervals)<br>public static String hash(String... values) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/KitchenQuantityPolicy.java | ai.neargo.shop.food.policy.KitchenQuantityPolicy | public static void positive(BigDecimal qty)<br>public static void apply( FnbKitchenTask task, String action, BigDecimal delta, BigDecimal loss)<br>public static void apply( FnbKitchenTask task, String action, BigDecimal delta, BigDecimal loss, BigDecimal readyLoss) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/MenuCompositionSource.java | ai.neargo.shop.food.policy.MenuCompositionSource | public MenuCompositionSource( MenuCategoryMapper categories, MenuItemMapper items, MenuItemSkuMapper skus, MenuItemOptionGroupMapper groups, GoodsCatalogQueryPort goods)<br>public Map<String, GoodsFact> facts(String entity, String store, MenuCompositionVO definition)<br>public MenuCompositionVO read(String entity, String number)<br>public MenuCompositionVO replace( String entity, String store, String menu, MenuCompositionVO definition, MenuCompositionVO previous, Map<String, GoodsFact> facts) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/MenuConflictPolicy.java | ai.neargo.shop.food.policy.MenuConflictPolicy | public static boolean conflicts(MenuConfigResultVO left, MenuConfigResultVO right, Instant fromInclusive, Instant toExclusive)<br>public static boolean conflicts(Captured left, Captured right, Instant fromInclusive, Instant toExclusive) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/MenuPublicationSource.java | ai.neargo.shop.food.policy.MenuPublicationSource | public MenuPublicationSource(MenuWorkingDefinitionAccess working,DishMapper dishes,DishOptionGroupMapper dishGroups, OptionGroupMapper optionGroups,BundleMapper bundles,RecipeMapper recipes, MenuChannelBindingMapper bindings,ObjectMapper json)<br>public Captured capture(String entity,String store,String number,boolean lock, BiFunction<String,String,FnbConfigRevision> selected)<br>public Captured capture(String entity,String store,String number,boolean lock, BiFunction<String,String,FnbConfigRevision> selected,Function<String,FnbConfigRevision> byRevision, List<GoodsFact> goodsFacts)<br>public List<String> goodsNos(Captured captured)<br>public List<String> skuNos(Captured captured)<br>public long currentVersion(String entity, String store, String number, boolean lock)<br>public void advance(String entity,String store,String number,long version,FnbConfigRevision revision) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/MenuSchedulePolicy.java | ai.neargo.shop.food.policy.MenuSchedulePolicy | public static boolean acceptsAt(MenuScheduleVO rules, String status, String hours, StoreOperationPolicyResultVO policy, Instant at, ServiceCalendarPolicy.DayRule day) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/MenuWorkingDefinitionAccess.java | ai.neargo.shop.food.policy.MenuWorkingDefinitionAccess | MenuConfigResultVO capture(String entity, String store, String menu, boolean lock)<br>default long currentVersion(String entity, String store, String menu, boolean lock)<br>void advance(String entity, String store, String menu, long version, FnbConfigRevision revision) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/OptionPublicationPolicy.java | ai.neargo.shop.food.policy.OptionPublicationPolicy | public static void validate(BigDecimal min, BigDecimal max, ReplaceOptionGroupItemsReq input) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/OptionSelectionPolicy.java | ai.neargo.shop.food.policy.OptionSelectionPolicy | public static ReplaceOptionGroupItemsReq normalize(ReplaceOptionGroupItemsReq req) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/ProductionDemandPolicy.java | ai.neargo.shop.food.policy.ProductionDemandPolicy | public static List<Line> evaluate( RecipePublicationSnapshot body, OrderLineInput selection, BigDecimal started)<br>public static List<Line> evaluateComponent( RecipePublicationSnapshot body, OrderLineInput selection, BigDecimal started) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/QueueSeatingPolicy.java | ai.neargo.shop.food.policy.QueueSeatingPolicy | public static OpenCheckReq normalize(OpenCheckReq request,int party) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/RecipeAdjustmentPolicy.java | ai.neargo.shop.food.policy.RecipeAdjustmentPolicy | public static String optionIdentity(String entity, String group, String option, String kind, String spec, String modifier)<br>public static List<RecipeAdjustmentInput> normalize(List<RecipeAdjustmentInput> inputs) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/RecipeDefinitionPolicy.java | ai.neargo.shop.food.policy.RecipeDefinitionPolicy | public static void identifier(String value)<br>public static BigDecimal quantity(BigDecimal value, boolean positive)<br>public static CreateRecipeReq normalize(CreateRecipeReq req)<br>public static ReplaceRecipeDefinitionReq normalize(ReplaceRecipeDefinitionReq req) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/RecipePublicationSource.java | ai.neargo.shop.food.policy.RecipePublicationSource | public RecipePublicationSource(RecipeMapper heads, RecipeLineMapper lines, RecipeAdjustmentMapper adjustments, OptionGroupItemMapper items, OptionGroupMapper groups, DishOptionGroupMapper bindings, BooleanSupplier enabled, ObjectMapper json)<br>public record Group(long workingVersion, String workingStatus, Dependency publication, PublicationSnapshot definition, List<String> goodsNos)<br>public record Captured(RecipeVO recipe, List<Group> groups)<br>public Captured capture(String entity, String store, String number, boolean lock, Function<String, FnbConfigRevision> selected)<br>public void validateAuthorities(String entity, Captured captured, List<SkuIdentityFact> skus, List<MaterialFact> materials)<br>public RecipePublicationSnapshot document(Captured captured, List<SkuIdentityFact> skus, List<MaterialFact> materials, String approval)<br>public long currentVersion(String entity, String store, String number, boolean lock)<br>public void advance(String entity, String store, String number, long version, FnbConfigRevision revision) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/ServiceCalendarPolicy.java | ai.neargo.shop.food.policy.ServiceCalendarPolicy | public record DayRule(LocalDate date, String mode, List<String> intervals)<br>public static LocalDate businessDate(Instant at, StoreOperationPolicyResultVO policy)<br>public static List<String> normalize(String mode, List<String> intervals)<br>public static boolean acceptsAt(String status, String hours, StoreOperationPolicyResultVO policy, Instant at, DayRule rule) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/StationPublicationSource.java | ai.neargo.shop.food.policy.StationPublicationSource | public StationPublicationSource(StationMapper heads,SkuStationMapper routes,BooleanSupplier enabled)<br>public record Route(String skuNo,String taskRole,long routeVersion)<br>public record Captured(StationVO definition,List<Route> routes,List<String> skuNos)<br>public Captured capture(String entity,String store,String number,boolean lock)<br>public long currentVersion(String entity, String store, String number, boolean lock)<br>public void advance(String entity,String store,String number,long version,FnbConfigRevision revision) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/TableOccupancyPolicy.java | ai.neargo.shop.food.policy.TableOccupancyPolicy | public static Map<String,Integer> allocate(List<FnbTable> tables, Map<String,List<FnbTableOccupancy>> active,int party,String mode,String ownCheck) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/TableResourceLocks.java | ai.neargo.shop.food.policy.TableResourceLocks | public TableResourceLocks(TableAreaMapper areas, TableMapper tables)<br>public List<FnbTable> lock(String entity,String store,Collection<String> numbers, Map<String,Long> versions,boolean active) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/port/LazyGoodsCatalogQueryPortImpl.java | ai.neargo.shop.food.port.LazyGoodsCatalogQueryPortImpl | public LazyGoodsCatalogQueryPortImpl(ObjectProvider<GoodsCatalogQueryPort> provider)<br>public SkuIdentityFact getSku(String entity, String store, String sku)<br>public GoodsFact getGoods(String entity, String store, String number)<br>public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/port/LazyGoodsMetadataQueryPortImpl.java | ai.neargo.shop.food.port.LazyGoodsMetadataQueryPortImpl | public LazyGoodsMetadataQueryPortImpl(ObjectProvider<GoodsCatalogQueryPort> provider)<br>public GoodsFact getGoods(String entity, String store, String number)<br>public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BundleServiceImpl.java | ai.neargo.shop.food.service.impl.BundleServiceImpl | public BundleServiceImpl(BundleMapper heads, BundleGroupMapper groups, BundleChoiceMapper choices, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort catalog)<br>public CreateBundleResultVO createBundle(String entity, String store, String actor, CreateBundleReq req, String key)<br>public BundleMutationVO replaceDefinition(String entity, String store, String number, String actor, ReplaceBundleDefinitionReq input, String key)<br>public GetBundleVO getBundle(String entity, String store, String number)<br>public PageData<BundleConfigVO> listBundles(String entity, String store, ListBundlesQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessCloseServiceImpl.java | ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl | public void effectiveSourceProvider(ObjectProvider<IndustryOrderPort> providers)<br>public void setOperationSources(FoodBusinessOperationsMapper mapper)<br>public BusinessCloseServiceImpl( BusinessDayMapper days, CloseRunMapper runs, CloseBalanceMapper balances, CloseExceptionMapper exceptions, CloseAdjustmentMapper adjustments, StorePolicyMapper policies, OrderBatchMapper batches, OrderLineMapper lines, SettlementMapper settlements, SettlementItemMapper settlementItems, CoreOrderBindingMapper bindings, ReturnMapper returns, KitchenTicketMapper tickets, KitchenTaskMapper tasks, ServiceEventMapper events, StoreCardQueryPort stores, IdempotencyService idem, ObjectMapper json, PlatformTransactionManager manager)<br>public DayVO open(String entity, String store, String actor, LocalDate date, String key)<br>public static List<LocalDateTime> boundaries(LocalDate date, String timezone, Integer cutover)<br>public ReportVO report(String entity, String store, String actor, PreviewReq req)<br>public CloseVO preview(String entity, String store, String actor, PreviewReq req, String key)<br>public CloseVO get(String entity, String store, String actor, String closeNo)<br>public CloseVO sign(String entity, String store, String actor, SignReq req, String key)<br>public List<ExceptionVO> exceptions(String entity, String store, String actor, String closeNo)<br>public ExceptionVO proposeCarry( String entity, String store, String actor, String closeNo, String exceptionNo, ProposeCarryReq req, String key)<br>public ExceptionVO approveCarry( String entity, String store, String actor, String closeNo, String exceptionNo, ApproveCarryReq req, String key)<br>public OperationVO adjust( String entity, String store, String actor, AdjustmentReq req, String key)<br>public static String snapshotHash(String day, String currency, ReportVO report) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessOperationsServiceImpl.java | ai.neargo.shop.food.service.impl.BusinessOperationsServiceImpl | public BusinessOperationsServiceImpl( FoodBusinessOperationsMapper mapper, PlatformTransactionManager manager, IdempotencyService idem, ObjectMapper json, DiningOrderService orders, DiningReturnService returns, QueueService queues)<br>public Evidence evidence(String e, String s, Source source)<br>public HandoverVO propose(String e, String s, String a, HandoverReq req, String key)<br>public HandoverVO handover(String e, String s, String no)<br>public HandoverVO accept(String e, String s, String a, String no, DecisionReq req, String key)<br>public HandoverVO cancel(String e, String s, String a, String no, DecisionReq req, String key)<br>public CaseVO openCase(String e, String s, String a, CaseReq req, String key)<br>public List<CaseVO> cases(String e, String s, String status)<br>public CaseVO recoverCase( String e, String s, String a, String no, DecisionReq req, String key)<br>public CaseVO resolveCase( String e, String s, String a, String no, DecisionReq req, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessReturnApprovalServiceImpl.java | ai.neargo.shop.food.service.impl.BusinessReturnApprovalServiceImpl | public BusinessReturnApprovalServiceImpl( FoodReturnAmendmentMapper intents, ReturnMapper returns, OrderBatchMapper batches, CoreOrderBindingMapper bindings, CheckCartMapper carts, IndustryAmendmentSourcePort sources, ObjectProvider<IndustryOrderAmendmentPort> owner, ObjectMapper json, PlatformTransactionManager manager)<br>public ApprovalVO approve( String e, String s, String actor, String number, ApproveReq req, String key)<br>public ApprovalVO get(String e, String s, String actor, String number)<br>public ApprovalVO linkReplacement( String e, String s, String actor, String number, ReplacementReq req, String key)<br>public int recover(int limit) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessReturnServiceImpl.java | ai.neargo.shop.food.service.impl.BusinessReturnServiceImpl | public void orderChangePublisher(@Qualifier("foodOutboxPublisher") OutboxPublisher publisher)<br>public BusinessReturnServiceImpl( OrderBatchMapper batches, CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations, OrderComponentMapper components, ReturnMapper returns, ReturnItemMapper items, ActionLogMapper actions, Supplier<IndustryOrderPort> orders, ObjectMapper json, Clock clock, PlatformTransactionManager manager)<br>public ReturnVO create( String entity, String store, String actor, String batchNo, CreateReq request, String key)<br>public ReturnVO get(String entity, String store, String number)<br>public ReturnVO cancel( String entity, String store, String actor, String number, CancelReq request, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/BusinessShareServiceImpl.java | ai.neargo.shop.food.service.impl.BusinessShareServiceImpl | public void setBusinessGuard(CheckBusinessGuardService guard)<br>public void setOrderSources(ObjectProvider<IndustryOrderPort> owners)<br>public BusinessShareServiceImpl( BusinessSharePlanMapper plans, CheckMapper checks, OrderBatchMapper batches, OrderLineMapper lines, CoreOrderBindingMapper bindings, CoreLineAllocationMapper allocations, IdempotencyService idem, ObjectMapper json, PlatformTransactionManager manager)<br>public PlanVO create( String entity, String store, String actor, String check, CreateReq request, String key)<br>public PlanVO get(String entity, String store, String check, String plan)<br>public PlanVO confirm( String entity, String store, String actor, String check, String plan, ConfirmReq request, String key)<br>public PlanVO cancel( String entity, String store, String actor, String check, String plan, CancelReq request, String key)<br>public record PlanReference(String planNo) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CatalogServiceImpl.java | ai.neargo.shop.food.service.impl.CatalogServiceImpl | public void scheduledSources(CheckCartMapper cartRows, @Lazy ServiceSlotLifecycle slots)<br>public CatalogVO scheduledCart(String entity, String store, String cartNo)<br>public CatalogServiceImpl( MenuMapper menus, MenuStoreMapper bindings, ServiceCalendarMapper calendars, GuestSessionMapper sessions, ConfigRevisionService revisions, StoreOperationService operation, SalesRestrictionService restrictions, StoreCardQueryPort stores, GoodsCatalogQueryPort goods, DishOfferQueryPort offers, ObjectMapper json, PlatformTransactionManager manager, Clock clock)<br>public CatalogServiceImpl( MenuMapper menus, MenuStoreMapper bindings, ServiceCalendarMapper calendars, GuestSessionMapper sessions, ConfigRevisionService revisions, StoreOperationService operation, SalesRestrictionService restrictions, StoreDirectoryPort stores, GoodsCatalogQueryPort goods, DishOfferQueryPort offers, ObjectMapper json, PlatformTransactionManager manager, Clock clock)<br>public CatalogVO publicCatalog(String store, String fulfillment)<br>public CatalogVO guest(String store, String session, String access, String buyer)<br>public CatalogVO read(String entity, String store, CatalogQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckBusinessGuardServiceImpl.java | ai.neargo.shop.food.service.impl.CheckBusinessGuardServiceImpl | public CheckBusinessGuardServiceImpl(FoodBusinessOperationsMapper mapper)<br>public void assertPartyMutable(String e, String store, String check)<br>public void lockBatchChecks(String e, String store, String batch)<br>public void invalidateBatchShares( String e, String store, String batch, String actor, String reason)<br>public void assertNoPendingChanges(String entity, String store, String check)<br>public void assertMutable(String entity, String store, String check)<br>public void invalidateShares( String entity, String store, String check, String actor, String reason) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckCartServiceImpl.java | ai.neargo.shop.food.service.impl.CheckCartServiceImpl | public void setDiningIdentity(DiningIdentityService identity)<br>public void setServiceSlots(ServiceSlotLifecycle slots)<br>public void setBusinessGuard(CheckBusinessGuardService guard)<br>public CheckCartServiceImpl( CheckCartMapper carts, CheckCartItemMapper items, CheckMapper checks, GuestSessionMapper sessions, SeatMapper seats, TableOccupancyMapper occupancies, TableAreaMapper areas, TableMapper tables, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, CatalogService catalog, StoreCardQueryPort stores, ObjectMapper json, PlatformTransactionManager manager, Clock clock)<br>public CartResultVO createCart( String entity, String store, String actor, CreateCartReq req, String key)<br>public CartResultVO getCart(String entity, String store, String number)<br>public void requireDiningRoomCart(String entity, String store, String number)<br>public CartVersionVO replaceLine( String entity, String store, String number, String source, String actor, ReplaceCartLineReq req, String key)<br>public CartVersionVO archiveLine( String entity, String store, String number, String source, String actor, CartVersionReq req, String key)<br>public OperationResultVO archiveCart( String entity, String store, String number, String actor, CartVersionReq req, String key)<br>public CartSessionGrantVO authorizeSession( String entity, String store, String check, String actor, AuthorizeCartSessionReq req, String key)<br>public CartResultVO customerCreateCart( String store, CartAccess access, CreateCustomerCartReq req, String key)<br>public CartResultVO customerGetCart(String number, CartAccess access)<br>public CartVersionVO customerReplaceLine( String number, String source, CartAccess access, ReplaceCartLineReq req, String key)<br>public CartVersionVO customerArchiveLine( String number, String source, CartAccess access, CartVersionReq req, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckCollaborationServiceImpl.java | ai.neargo.shop.food.service.impl.CheckCollaborationServiceImpl | public void setOrderSources(ObjectProvider<IndustryOrderPort> provider)<br>public CheckCollaborationServiceImpl( FoodBusinessOperationsMapper mapper, CheckBusinessGuardService guard, IdempotencyService idem, ObjectMapper json, PlatformTransactionManager manager)<br>public String sourceHash(String e, String store, String check)<br>public ReceivablePreview previewReceivable(String e, String store, String check)<br>public String currentLineOwner(String e, String store, String line)<br>public OperationVO releaseService( String e, String store, String actor, String check, long expected, String reason, String key)<br>public MoveVO moveBatches(String entity, String store, String actor, MoveReq req, String key)<br>public LineMoveVO moveLines(String e, String store, String actor, LineMoveReq req, String key)<br>public FreezeVO freeze( String entity, String store, String actor, String check, FreezeReq req, String key)<br>public FreezeVO release( String entity, String store, String actor, String no, long expected, String reason, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/CheckServiceImpl.java | ai.neargo.shop.food.service.impl.CheckServiceImpl | public void setBusinessGuard(CheckBusinessGuardService guard)<br>public CheckServiceImpl( CheckMapper checks, TableAreaMapper areas, TableMapper tables, TableOccupancyMapper occupancies, SeatMapper seats, TableOperationMapper operations, GuestSessionMapper sessions, ActionLogMapper actions, StoreOperationService policies, IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager)<br>public CheckServiceImpl( CheckMapper checks, TableAreaMapper areas, TableMapper tables, TableOccupancyMapper occupancies, SeatMapper seats, TableOperationMapper operations, GuestSessionMapper sessions, ActionLogMapper actions, StoreOperationService policies, IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager, ReservationTableMapper reservationTables)<br>public CheckResultVO openCheck( String entity, String store, String actor, OpenCheckReq req, String key)<br>public CheckResultVO openQueuedCheck( String entity, String store, String actor, OpenCheckReq req, String key)<br>public CheckResultVO openReservedCheck( String entity, String store, String actor, OpenCheckReq req, String key, Instant reservedUntil)<br>public CheckResultVO openSharedCheck( String entity, String store, String table, String actor, OpenSharedCheckReq req, String key)<br>public PageData<CheckVO> listChecks(String entity, String store, ListChecksQuery query)<br>public CheckDetailVO getCheck(String entity, String store, String number)<br>public CheckResultVO saveSeats( String entity, String store, String number, String actor, SaveCheckSeatsReq req, String key)<br>public OperationVO clearCheck( String entity, String store, String number, String actor, ClearCheckReq req, String key)<br>public OperationResultVO transferCheck( String entity, String store, String number, String actor, TransferCheckReq req, String key)<br>public OperationResultVO combineTables( String entity, String store, String number, String actor, CombineTablesReq req, String key)<br>public OperationResultVO getTableOperation(String entity, String store, String operation)<br>public PageData<ActionLogVO> listActions( String entity, String store, String number, ListCheckActionsQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ConfigRevisionServiceImpl.java | ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl | public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers, ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs)<br>public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers, ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs, Clock clock)<br>public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers, ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs, Clock clock, BundlePublicationSource bundles)<br>public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers, ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs, Clock clock, BundlePublicationSource bundles, RecipePublicationSource recipes, MaterialCatalogQueryPort materials)<br>public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers, ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs, Clock clock, BundlePublicationSource bundles, RecipePublicationSource recipes, MaterialCatalogQueryPort materials, MenuPublicationSource menus, StationPublicationSource stations, Supplier<MenuChannelAuthorityPort> channelAuthority)<br>public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers, ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs, Clock clock, BundlePublicationSource bundles, RecipePublicationSource recipes, MaterialCatalogQueryPort materials, MenuPublicationSource menus, StationPublicationSource stations, Supplier<MenuChannelAuthorityPort> channelAuthority, MenuChannelBindingMapper channelBindings)<br>public PublishConfigResultVO publishMenu(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishStation(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishRecipe(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishBundle(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishModifier(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishOptionGroup(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO republishRevision(String entity, String store, String sourceNo, String actor, RepublishConfigReq req, String key)<br>public PageData<ConfigRevisionVO> listRevisions(String entity, String store, ListConfigRevisionsQuery query)<br>public ConfigRevisionContentVO getRevision(String entity, String store, String number)<br>public ConfigRevisionContentVO resolveRevision(String entity, String store, String kind, String number, OffsetDateTime asOf) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningBatchQueryServiceImpl.java | ai.neargo.shop.food.service.impl.DiningBatchQueryServiceImpl | public DiningBatchQueryServiceImpl(DiningBatchQueryMapper batches,StoreCardQueryPort stores, @Qualifier("transactionManager") PlatformTransactionManager manager)<br>public PageData<DiningBatchSummaryVO> list(String entity,String store,String actor, ListDiningBatchesQuery input) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningCheckoutServiceImpl.java | ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl | public void setBusinessSources(ObjectProvider<IndustryOrderPort> sources)<br>public void setBusinessGuard(CheckBusinessGuardService guard)<br>public DiningCheckoutServiceImpl( CheckCartService carts, OrderBatchMapper batches, CoreOrderBindingMapper bindings, SettlementMapper settlements, SettlementItemMapper items, ChargeIntentMapper intents, SalesQuotaClaimService quotas, Supplier<IndustryCheckoutPort> core, OutboxPublisher publisher, ObjectMapper json, Clock clock, PlatformTransactionManager manager)<br>public DiningSettlementVO customerCreate( CartAccess access, String batchNo, CreateDiningSettlementReq request, String key)<br>public DiningSettlementVO customerGet(CartAccess access, String number)<br>public DiningSettlementVO customerPay( CartAccess access, String number, PayDiningSettlementReq request, String key)<br>public DiningSettlementVO customerQuery(CartAccess access, String number)<br>public DiningSettlementVO customerFinalize(CartAccess access, String number)<br>public DiningSettlementVO customerCancel( CartAccess access, String number, DiningSettlementVersionReq request)<br>public DiningSettlementVO staffGet(String entity, String store, String actor, String number)<br>public DiningSettlementVO recoverOriginal(String entity, String store, String number) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningDeliveryServiceImpl.java | ai.neargo.shop.food.service.impl.DiningDeliveryServiceImpl | public DiningDeliveryServiceImpl( CheckCartService carts, CheckCartMapper cartRows, DiningDeliveryMapper addresses, OutboxPublisher outbox, ObjectMapper json, PlatformTransactionManager manager, Clock clock)<br>public DeliverySnapshot saveStaff( String e, String s, String actor, String cart, SaveReq req, String key)<br>public DeliverySnapshot saveCustomer(String cart, CartAccess access, SaveReq req, String key)<br>public DeliverySnapshot getStaff(String e, String s, String cart)<br>public DeliverySnapshot getCustomer(String cart, CartAccess access)<br>public DeliverySnapshot get(String e, String s, String cart) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningIdentityServiceImpl.java | ai.neargo.shop.food.service.impl.DiningIdentityServiceImpl | public DiningIdentityServiceImpl( DeviceMapper devices, AuthorizationMapper authorizations, OperationMapper operations, GuestSessionMapper sessions, CheckMapper checks, OutboxPublisher outbox, PlatformTransactionManager manager, ObjectMapper json, Clock clock)<br>public DeviceVO device(String e, String s, String no)<br>public DeviceCredentialVO registerDevice( String e, String s, String actor, RegisterDeviceReq req, String key)<br>public DeviceVO revokeDevice( String e, String s, String actor, String no, VersionReq req, String key)<br>public SessionCredentialVO createDeviceSession( String e, String s, String no, String credential, DeviceSessionReq req, String key)<br>public SessionCredentialVO createStaffParty( String e, String s, String actor, StaffPartyReq req, String key)<br>public PartyVO revokeParty( String e, String s, String actor, String no, VersionReq req, String key)<br>public PartyVO party(String e, String s, String no)<br>public void validateDeviceSession(String no) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningOrderAuthorityServiceImpl.java | ai.neargo.shop.food.service.impl.DiningOrderAuthorityServiceImpl | public DiningOrderAuthorityServiceImpl( DiningPricingFactsService facts, DiningIdentityService identities, ConfigRevisionService revisions, MaterialCatalogQueryPort materials, ObjectMapper json)<br>public Party party(Command command)<br>public Delivery delivery(Command command)<br>public Plan demandPlan(Command command, String orderNo, String subOrderNo) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningOrderServiceImpl.java | ai.neargo.shop.food.service.impl.DiningOrderServiceImpl | public void businessLifecycles( @Lazy CheckBusinessGuardService checkGuard, @Lazy ServiceSlotLifecycle slots)<br>public DiningOrderServiceImpl( CheckCartService carts, DiningPricingFactsService facts, StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows, StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders, Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager)<br>public DiningOrderServiceImpl( CheckCartService carts, DiningPricingFactsService facts, StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows, StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders, Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager, KitchenTaskService kitchen)<br>public DiningOrderServiceImpl( CheckCartService carts, DiningPricingFactsService facts, StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows, StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders, Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager, KitchenTaskService kitchen, OutboxPublisher publisher)<br>public DiningOrderServiceImpl( CheckCartService carts, DiningPricingFactsService facts, StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows, StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders, Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager, KitchenTaskService kitchen, OutboxPublisher publisher, OrderComponentMapper components)<br>public DiningOrderServiceImpl( CheckCartService carts, DiningPricingFactsService facts, StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows, StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders, Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager, KitchenTaskService kitchen, OutboxPublisher publisher, OrderComponentMapper components, OrderModifierMapper modifiers)<br>public DiningOrderServiceImpl( CheckCartService carts, DiningPricingFactsService facts, StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows, StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders, Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager, KitchenTaskService kitchen, OutboxPublisher publisher, OrderComponentMapper components, OrderModifierMapper modifiers, ActionLogMapper actions)<br>public DiningOrderBatchVO staffPlace( String entity, String store, String actor, CreateStaffDiningOrderReq req, String key)<br>public DiningOrderBatchVO staffCancel( String entity, String store, String actor, String number, CancelDiningOrderReq request, String key)<br>public Snapshot authorizeCancellation(Command command, String key, String fulfillment)<br>public DiningOrderBatchVO customerPlace( String store, CartAccess access, CreateDiningOrderReq req, String key)<br>public DiningOrderBatchVO customerGet(CartAccess access, String number)<br>public DiningOrderBatchVO staffGet(String entity, String store, String actor, String number)<br>public DiningOrderBatchVO recoverOriginal(String entity, String store, String number) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningPickupVerificationServiceImpl.java | ai.neargo.shop.food.service.impl.DiningPickupVerificationServiceImpl | public DiningPickupVerificationServiceImpl( OrderBatchMapper batches, CoreOrderBindingMapper bindings, KitchenTaskService kitchen, Supplier<IndustryFulfillmentPort> authority)<br>public DiningPickupVerificationServiceImpl( OrderBatchMapper batches, CoreOrderBindingMapper bindings, KitchenTaskService kitchen, Supplier<IndustryFulfillmentPort> authority, OrderLineMapper lines, OrderComponentMapper components)<br>public PickupVerificationVO verify( String entity, String store, String actor, String batch, VerifyPickupReq req, String key)<br>public PickupVerificationVO get( String entity, String store, String actor, String batch, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningPricingFactsServiceImpl.java | ai.neargo.shop.food.service.impl.DiningPricingFactsServiceImpl | public void setFeeSources(FoodFeeSourceLifecycle fees)<br>public void setDiningDelivery(DiningDeliveryService delivery)<br>public DiningPricingFactsServiceImpl( CheckCartService carts, CatalogService catalog, ConfigRevisionService revisions, DishOfferQueryPort offers, SalesRestrictionService restrictions, StoreCardQueryPort stores, ObjectMapper json)<br>public DiningPriceFacts staff(String entity, String store, String cart, long expectedVersion)<br>public DiningPriceFacts customer(String cart, CartAccess access, long expectedVersion) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningPricingServiceImpl.java | ai.neargo.shop.food.service.impl.DiningPricingServiceImpl | public DiningPricingServiceImpl(CheckCartService carts, PricingCheckoutPort pricing)<br>public DiningPricingServiceImpl(CheckCartService carts, Supplier<PricingCheckoutPort> pricing)<br>public QuoteVO staffQuote(String entity, String store, String actor, DiningQuoteReq r, String key)<br>public QuoteVO customerQuote(String store, CartAccess access, DiningQuoteReq r, String key)<br>public QuoteVO staffGet(String entity, String store, String actor, String cart, long version, String quote)<br>public QuoteVO customerGet(CartAccess access, String cart, long version, String quote)<br>public QuoteVO staffConfirm(String entity, String store, String actor, String quote, DiningQuoteConfirmReq r)<br>public QuoteVO customerConfirm(CartAccess access, String quote, DiningQuoteConfirmReq r) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningReturnServiceImpl.java | ai.neargo.shop.food.service.impl.DiningReturnServiceImpl | public DiningReturnServiceImpl( CheckCartService carts, OrderBatchMapper batches, CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations, ReturnMapper returns, ReturnItemMapper items, Supplier<IndustryAfterSalePort> authority, ObjectMapper json, Clock clock, PlatformTransactionManager manager)<br>public DiningReturnVO customerApply( String store, CartAccess access, DiningReturnApplyReq request, String key)<br>public DiningReturnVO customerGet(CartAccess access, String number)<br>public DiningReturnVO customerCancel(CartAccess access, String number)<br>public DiningReturnVO staffGet(String entity, String store, String actor, String number)<br>public DiningReturnVO recoverOriginal(String entity, String store, String number) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningSessionServiceImpl.java | ai.neargo.shop.food.service.impl.DiningSessionServiceImpl | public DiningSessionServiceImpl(TableAreaMapper areas,TableMapper tables,TableTokenMapper tokens, GuestSessionMapper sessions,ActionLogMapper actions,IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, @Qualifier("transactionManager") PlatformTransactionManager manager)<br>public TableTokenVO rotateTableToken(String entity,String store,String table,String actor, RotateTableTokenReq req,String key)<br>public OperationResultVO revokeTableToken(String entity,String store,String table,String actor, RevokeTableTokenReq req,String key)<br>public DiningSessionTableVO resolveTableToken(String raw)<br>public DiningSessionVO startSession(String entity,String store,String buyer,StartDiningSessionReq req, String key)<br>public OperationResultVO endSession(String session,String access,String buyer,String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DiningTransactionRecoveryServiceImpl.java | ai.neargo.shop.food.service.impl.DiningTransactionRecoveryServiceImpl | public DiningTransactionRecoveryServiceImpl(OrderBatchMapper batches, SettlementMapper settlements, ReturnMapper returns, DiningOrderService orders, DiningCheckoutService checkout, DiningReturnService afterSales, Clock clock, PlatformTransactionManager manager)<br>public Report recoverDue(int limit) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/DishServiceImpl.java | ai.neargo.shop.food.service.impl.DishServiceImpl | public DishServiceImpl(DishMapper dishes, ActionLogMapper actions, IdempotencyService idempotency, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort catalog, DishOptionGroupMapper bindings, OptionGroupMapper groups, OptionGroupItemMapper items)<br>public DishServiceImpl(DishMapper dishes, ActionLogMapper actions, IdempotencyService idempotency, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort catalog, DishOptionGroupMapper bindings, OptionGroupMapper groups, OptionGroupItemMapper items, MediaAssetQueryPort media)<br>public PageData<DishCatalogItemVO> listDishes(String entity, String store, ListDishesQuery query)<br>public DishDetailResultVO getDish(String entity, String store, String goods)<br>public DishResultVO saveDish(String entity, String store, String goods, String actor, SaveDishReq req, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodAmendmentSourcePortImpl.java | ai.neargo.shop.food.service.impl.FoodAmendmentSourcePortImpl | public FoodAmendmentSourcePortImpl( ReturnMapper returns, ReturnItemMapper items, OrderBatchMapper batches, CoreOrderBindingMapper bindings, FoodReturnAmendmentMapper approvals, ObjectMapper json)<br>public Evidence get(Reference ref, String number) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodFeeSourceServiceImpl.java | ai.neargo.shop.food.service.impl.FoodFeeSourceServiceImpl | public FoodFeeSourceServiceImpl(FoodBusinessOperationsMapper mapper, ObjectMapper json)<br>public BindingVO bind(String e, String store, String actor, BindReq req, String key)<br>public List<BindingVO> bindings(String e, String store, String check)<br>public DiningPriceFacts attach(String e, String store, CartVO cart, DiningPriceFacts facts) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodKdsServiceImpl.java | ai.neargo.shop.food.service.impl.FoodKdsServiceImpl | public FoodKdsServiceImpl( FoodKdsMapper mapper, PrintPort printing, ObjectMapper json, PlatformTransactionManager manager, Clock clock)<br>public SubscriptionVO grant( String entity, String store, String actor, GrantReq req, String key)<br>public SubscriptionVO revoke( String entity, String store, String actor, String no, RevokeReq req, String key)<br>public ReplayVO replay( String entity, String store, String actor, String no, long after, int limit)<br>public void append(EventDraft event) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodPrintServiceImpl.java | ai.neargo.shop.food.service.impl.FoodPrintServiceImpl | public FoodPrintServiceImpl( FoodPrintDocumentAssembler documents, Supplier<PrintPort> printing)<br>public FoodPrintServiceImpl( FoodPrintDocumentAssembler documents, Supplier<PrintPort> printing, FoodEventPrintDocumentAssembler eventDocuments)<br>public List<JobVO> enqueue(Scope scope, PrintDocumentReq request, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodStationRuntimeServiceImpl.java | ai.neargo.shop.food.service.impl.FoodStationRuntimeServiceImpl | public FoodStationRuntimeServiceImpl( FoodStationRuntimeMapper mapper, FoodKdsMapper kdsMapper, FoodKdsService kds, PrintPort printing, OutboxPublisher outbox, PlatformTransactionManager manager, Clock clock, ObjectMapper json)<br>public StateVO state(String e, String s, String station)<br>public void requireRunning(String e, String s, String station)<br>public CommandVO control( String e, String s, String actor, String station, ControlReq req, String key)<br>public VoiceClaim claim(String e, String s, String actor, String subscription)<br>public CommandVO acknowledge( String e, String s, String actor, String subscription, VoiceReceipt req) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/FoodSupportServiceImpl.java | ai.neargo.shop.food.service.impl.FoodSupportServiceImpl | public FoodSupportServiceImpl( FoodProperties properties, ObjectProvider<FoodSchemaReady> schemas, Map<String, ObjectProvider<?>> providers, BusinessOperationsService operations)<br>public ReadinessVO readiness()<br>public List<CaseVO> cases(String entityNo, String storeNo, String status)<br>public Evidence evidence(String entityNo, String storeNo, Source source) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/KitchenReturnServiceImpl.java | ai.neargo.shop.food.service.impl.KitchenReturnServiceImpl | public KitchenReturnServiceImpl( ReturnMapper returns, ReturnItemMapper items, OrderBatchMapper batches, OrderLineMapper lines, CoreLineAllocationMapper allocations, ActionLogMapper actions, KitchenTaskService kitchen, ObjectMapper json, PlatformTransactionManager manager, KitchenTicketMapper tickets, OrderComponentMapper components)<br>public ReturnKitchenVO get(String entity, String store, String number)<br>public HandledVO handle( String entity, String store, String actor, String number, HandleReq req, String key)<br>public static BigDecimal quantity(BigDecimal returning, BigDecimal target, BigDecimal line) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/KitchenTaskServiceImpl.java | ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl | public void configureStationRuntime(ObjectProvider<FoodStationRuntimeService> stationRuntime)<br>public void configureSlots(ObjectProvider<ServiceSlotService> slots)<br>public void configureFulfillment(ObjectProvider<IndustryFulfillmentPort> fulfillment)<br>public KitchenTaskServiceImpl( OrderBatchMapper batches, OrderLineMapper lines, OrderComponentMapper components, ConfigRevisionMapper revisions, KitchenTicketMapper tickets, KitchenTaskMapper tasks, ServiceEventMapper events, CourseMapper courses, CheckMapper checks, IdempotencyService idem, ObjectMapper json, PlatformTransactionManager manager, OutboxPublisher outbox)<br>public KitchenTaskServiceImpl( OrderBatchMapper batches, OrderLineMapper lines, OrderComponentMapper components, ConfigRevisionMapper revisions, KitchenTicketMapper tickets, KitchenTaskMapper tasks, ServiceEventMapper events, CourseMapper courses, CheckMapper checks, IdempotencyService idem, ObjectMapper json, PlatformTransactionManager manager, OutboxPublisher outbox, CoreOrderBindingMapper bindings, CoreLineAllocationMapper allocations)<br>public FrozenRoutes freezeRoutes(String entity, String store, List<String> skus)<br>public TicketVO accepted(String entity, String store, String batchNo, String dispatchKey)<br>public List<TaskVO> queue(String entity, String store, String station, String batchNo)<br>public EventVO act( String entity, String store, String actor, String taskNo, TaskActionReq req, String key)<br>public TicketVO remake( String entity, String store, String actor, String taskNo, RemakeReq req, String key)<br>public List<EventVO> events(String entity, String store, String taskNo)<br>public CourseVO createCourse( String entity, String store, String actor, String checkNo, CourseReq req, String key)<br>public List<CourseVO> courses(String entity, String store, String checkNo)<br>public CourseVO actCourse( String entity, String store, String actor, String courseNo, CourseActionReq req, String key)<br>public CustodyReceiptVO confirmPacking( String entity, String store, String actor, String batchNo, PackingReq req, String key)<br>public HandoverVO handover( String entity, String store, String actor, String batchNo, HandoverReq req, String key)<br>public List<CustodyReceiptVO> custodyReceipts(String entity, String store, String batchNo) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/MenuChannelBindingServiceImpl.java | ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl | public MenuChannelBindingServiceImpl(MenuChannelBindingMapper bindings,MenuMapper menus,MenuStoreMapper stores, ActionLogMapper actions,IdempotencyService idem,OutboxPublisher outbox,ObjectMapper json, PlatformTransactionManager manager,Supplier<MenuChannelAuthorityPort> authority)<br>public MenuChannelBindingVO createBinding(String entity,String store,String actor, CreateMenuChannelBindingReq req,String key)<br>public MenuChannelBindingVO updateBinding(String entity,String store,String number,String actor, UpdateMenuChannelBindingReq req,String key)<br>public MenuChannelBindingVO getBinding(String entity,String store,String number)<br>public PageData<MenuChannelBindingVO> listBindings(String entity,String store,Integer p,Integer s, String provider,String menuNo) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/MenuServiceImpl.java | ai.neargo.shop.food.service.impl.MenuServiceImpl | public MenuServiceImpl(MenuMapper heads, MenuStoreMapper bindings, StorePolicyMapper policies, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, Clock clock, MenuScheduleMapper weeks, MenuExceptionMapper exceptions, MenuCompositionSource composition, MenuContextMapper contexts, MenuChannelBindingMapper channels, Supplier<MenuChannelAuthorityPort> channelAuthority)<br>public CreateMenuResultVO createMenu(String entity, String store, String actor, CreateMenuReq req, String key)<br>public MenuResultVO updateMenu(String entity, String store, String number, String actor, UpdateMenuReq req, String key)<br>public MenuScheduleResultVO replaceSchedule(String entity, String store, String number, String actor, ReplaceMenuScheduleReq req, String key)<br>public MenuCompositionResultVO replaceComposition(String entity, String store, String number, String actor, ReplaceMenuCompositionReq req, String key)<br>public MenuContextsResultVO replaceContexts(String entity, String store, String number, String actor, ReplaceMenuContextsReq req, String key)<br>public MenuResultVO disableMenu(String entity, String store, String number, String actor, DisableMenuReq req, String key)<br>public MenuConfigResultVO capture(String entity, String store, String number, boolean lock)<br>public long currentVersion(String entity, String store, String number, boolean lock)<br>public void advance(String entity, String store, String number, long version, FnbConfigRevision revision)<br>public MenuConfigResultVO getMenu(String entity, String store, String number)<br>public PageData<MenuVO> listMenus(String entity, String store, ListMenusQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ModifierServiceImpl.java | ai.neargo.shop.food.service.impl.ModifierServiceImpl | public ModifierServiceImpl(ModifierMapper heads, ModifierComponentMapper components, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort catalog)<br>public CreateModifierResultVO createModifier(String entity, String store, String actor, CreateModifierReq input, String key)<br>public ModifierMutationVO updateModifier(String entity, String store, String number, String actor, UpdateModifierReq input, String key)<br>public PageData<ModifierConfigVO> listModifiers(String entity, String store, ListModifiersQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/OptionGroupServiceImpl.java | ai.neargo.shop.food.service.impl.OptionGroupServiceImpl | public OptionGroupServiceImpl(OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions, IdempotencyService idempotency, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, DishMapper dishes, DishOptionGroupMapper bindings, ModifierMapper modifiers, GoodsCatalogQueryPort catalog, SpecValueCatalogQueryPort specs)<br>public OptionGroupMutationVO replaceOptionGroupItems(String entity, String store, String group, String actor, ReplaceOptionGroupItemsReq input, String key)<br>public CreateOptionGroupResultVO createOptionGroup(String entity, String store, String actor, CreateOptionGroupReq req, String key)<br>public OptionGroupResultVO getOptionGroup(String entity, String store, String config)<br>public PageData<OptionGroupConfigVO> listOptionGroups(String entity, String store, ListOptionGroupsQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ProductionConsumptionServiceImpl.java | ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl | public void setOutbox(@Qualifier("foodOutboxPublisher") OutboxPublisher outbox)<br>public void setEvidenceMapper(FoodProductionEvidenceMapper evidence)<br>public ProductionConsumptionServiceImpl( ProductionConsumptionMapper heads, ProductionConsumptionLineMapper lines, OrderBatchMapper batches, OrderLineMapper orderLines, KitchenTicketMapper tickets, KitchenTaskMapper tasks, ServiceEventMapper events, ConfigRevisionMapper revisions, StoreCardQueryPort stores, InventoryProductionPort inventory, ObjectMapper json, PlatformTransactionManager manager)<br>public ConsumptionVO consume(String entity, String store, String actor, String eventNo)<br>public ConsumptionVO adjust( String entity, String store, String actor, String taskNo, ProductionContracts.AdjustmentReq req, String key)<br>public ConsumptionVO reverse( String entity, String store, String actor, String event, ProductionContracts.ReverseReq req, String key)<br>public int recover(int limit)<br>public PageData<ConsumptionVO> list( String entity, String store, String actor, ListQuery query)<br>public ConsumptionVO get(String entity, String store, String actor, String eventNo)<br>public static String fingerprint(Command command)<br>public static void validate(Command command, Receipt receipt) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/QueueNotificationServiceImpl.java | ai.neargo.shop.food.service.impl.QueueNotificationServiceImpl | public QueueNotificationServiceImpl( FoodBusinessOperationsMapper mapper, PlatformTransactionManager manager, Supplier<QueueNotificationPort> provider, Clock clock)<br>public void called( String e, String store, String ticket, long ticketVersion, String contact, String actor)<br>public List<NoticeVO> list(String e, String store, String ticket)<br>public NoticeVO recover(String e, String store, String actor, String no, String key)<br>public int runDue(int limit) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/QueueServiceImpl.java | ai.neargo.shop.food.service.impl.QueueServiceImpl | public void setNotifications(QueueNotificationLifecycle notices)<br>public QueueServiceImpl( QueueTicketMapper tickets, StorePolicyMapper policyRows, ActionLogMapper actions, StoreOperationService policies, CheckService checks, StoreCardQueryPort stores, IdempotencyService idem, OutboxPublisher outbox, ObjectMapper json, PlatformTransactionManager manager)<br>public QueueVO issue(String entity, String store, String actor, IssueReq request, String key)<br>public PageData<QueueVO> list(String entity, String store, String actor, Query query)<br>public QueueVO get(String entity, String store, String actor, String number)<br>public QueueVO callNext(String entity, String store, String actor, LocalDate date, String key)<br>public QueueVO change( String entity, String store, String actor, String number, String action, ChangeReq request, String key)<br>public QueueVO seat( String entity, String store, String actor, String number, SeatQueueReq request)<br>public QueueVO recoverSeating(String entity, String store, String actor, String number)<br>public static String seatKey(String number) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/RecipeServiceImpl.java | ai.neargo.shop.food.service.impl.RecipeServiceImpl | public RecipeServiceImpl( RecipeMapper heads, RecipeLineMapper lines, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort catalog, MaterialCatalogQueryPort materials, RecipeAdjustmentMapper adjustments, OptionGroupItemMapper optionItems, OptionGroupMapper optionGroups)<br>public RecipeResultVO createRecipe( String entity, String store, String actor, CreateRecipeReq input, String key)<br>public RecipeResultVO replaceDefinition( String entity, String store, String number, String actor, ReplaceRecipeDefinitionReq input, String key)<br>public RecipeResultVO getRecipe(String entity, String store, String number)<br>public PageData<RecipeVO> listRecipes(String entity, String store, ListRecipesQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ReservationPreorderLifecycle.java | ai.neargo.shop.food.service.impl.ReservationPreorderLifecycle | public ReservationPreorderLifecycle( FoodBusinessOperationsMapper mapper, ServiceSlotLifecycle slots)<br>public void cancelled( String entity, String store, String reservation, String actor, String reason) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ReservationPreorderServiceImpl.java | ai.neargo.shop.food.service.impl.ReservationPreorderServiceImpl | public ReservationPreorderServiceImpl( FoodBusinessOperationsMapper mapper, PlatformTransactionManager manager, IdempotencyService idem, CheckCartService carts, DiningOrderService orders, ObjectMapper json, Clock clock)<br>public PreorderVO save( String e, String s, String a, String reservation, SaveReq req, String key)<br>public PreorderVO get(String e, String s, String reservation, String no)<br>public CartResultVO prepareCart( String e, String s, String a, String reservation, String no, long expected, String key)<br>public DiningOrderBatchVO convert( String e, String s, String a, String reservation, String no, ConvertReq req, String key)<br>public PreorderVO cancel( String e, String s, String a, String reservation, String no, long expected, String reason, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ReservationServiceImpl.java | ai.neargo.shop.food.service.impl.ReservationServiceImpl | public void setServiceForms(ServiceFormServiceImpl forms)<br>public void setPreorderLifecycle(ReservationPreorderLifecycle lifecycle)<br>public ReservationServiceImpl( ReservationMapper reservations, ReservationTableMapper memberships, TableAreaMapper areas, TableMapper tables, TableOccupancyMapper occupancies, ActionLogMapper actions, CheckService checks, StoreOperationService policies, IdempotencyService idem, ObjectMapper json, StoreCardQueryPort stores, @Qualifier("foodOutboxPublisher") OutboxPublisher outbox, @Qualifier("transactionManager") PlatformTransactionManager manager)<br>public DetailVO get(String entity, String store, String number)<br>public DetailVO create(String entity, String store, String actor, CreateReq req, String key)<br>public DetailVO change( String entity, String store, String actor, String number, ChangeReq req, String key)<br>public DetailVO cancel( String entity, String store, String actor, String number, TransitionReq req, String key)<br>public DetailVO noShow( String entity, String store, String actor, String number, TransitionReq req, String key)<br>public DetailVO arrive( String entity, String store, String actor, String number, ArriveReq req, String key)<br>public PageData<ReservationVO> listReservations( String entity, String store, ListReservationsQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/SalesQuotaClaimServiceImpl.java | ai.neargo.shop.food.service.impl.SalesQuotaClaimServiceImpl | public SalesQuotaClaimServiceImpl(SalesRestrictionMapper restrictions, StorePolicyMapper policies, SalesQuotaClaimMapper claims, Clock clock)<br>public List<SalesQuotaClaimVO> reserve(String entity, String store, String batch, String source, String fulfillment, List<SalesQuotaClaimSource> sources)<br>public List<SalesQuotaClaimVO> release(String entity, String store, String batch)<br>public List<SalesQuotaClaimVO> consume(String entity, String store, String batch) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/SalesRestrictionServiceImpl.java | ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl | public SalesRestrictionServiceImpl(SalesRestrictionMapper rows, StorePolicyMapper policies, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, Clock clock, GoodsCatalogQueryPort goods)<br>public SalesRestrictionVO manage(String entity, String store, String actor, SalesRestrictionReq req, String key)<br>public PageData<SalesRestrictionVO> list(String entity, String store, SalesRestrictionQuery query)<br>public SalesRestrictionEvaluationVO evaluate(String entity, String store, String sku, String source, String fulfillment) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ScheduledDiningDispatchJob.java | ai.neargo.shop.food.service.impl.ScheduledDiningDispatchJob | public ScheduledDiningDispatchJob( ScheduledDiningDispatchService dispatch, QueueNotificationServiceImpl notices)<br>public void dispatch()<br>public void notifications() |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ScheduledDiningDispatchServiceImpl.java | ai.neargo.shop.food.service.impl.ScheduledDiningDispatchServiceImpl | public ScheduledDiningDispatchServiceImpl( FoodBusinessOperationsMapper mapper, PlatformTransactionManager manager, KitchenTaskService kitchen, Supplier<PrintPort> printing, FoodPrintService documents, Clock clock)<br>public Report runDue(int limit) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ServiceFormServiceImpl.java | ai.neargo.shop.food.service.impl.ServiceFormServiceImpl | public ServiceFormServiceImpl( ServiceFormMapper mapper, FoodBusinessOperationsMapper operations, PlatformTransactionManager manager, ServiceSlotService slots, ServiceSlotLifecycle capacity, Supplier<ReservationService> reservations, StoreCardQueryPort stores, ObjectMapper json, Clock clock)<br>public OrganizationVO registerOrganization( String e, String store, String actor, OrganizationReq request, String key)<br>public FormVO book(String e, String store, String actor, BookReq request, String key)<br>public FormVO get(String e, String store, String no)<br>public FormVO revise( String e, String store, String actor, String no, ReviseReq request, String key)<br>public FormVO enter( String e, String store, String actor, String no, EnterReq request, String key)<br>public FormVO leave( String e, String store, String actor, String no, VersionReq request, String key)<br>public FormVO cancel( String e, String store, String actor, String no, VersionReq request, String key)<br>public FormVO noShow( String e, String store, String actor, String no, VersionReq request, String key)<br>public void reservationTransition( String e, String store, String reservation, String target, String actor)<br>public FormVO bindBatch( String e, String store, String actor, String no, BatchReq request, String key)<br>public List<AttendeeVO> attendees(String e, String store, String no)<br>public AttendeeVO admit( String e, String store, String actor, String no, String guest, VersionReq req, String key)<br>public AttendeeVO exit( String e, String store, String actor, String no, String guest, VersionReq req, String key) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ServiceSlotServiceImpl.java | ai.neargo.shop.food.service.impl.ServiceSlotServiceImpl | public ServiceSlotServiceImpl( FoodBusinessOperationsMapper mapper, IdempotencyService idem, Clock clock, ObjectMapper json)<br>public void resizeSource( String e, String store, String actor, String source, BigDecimal quantity)<br>public void assertScheduledCart(String e, String store, String cart, Instant scheduledAt)<br>public ClaimVO reserveScheduledCart( String e, String store, String actor, String cart, Instant scheduledAt, String key)<br>public void releaseCart(String e, String store, String actor, String cart)<br>public void bindOrder(String e, String store, String actor, String cart, String batch)<br>public void releaseOrder(String e, String store, String actor, String batch)<br>public SlotVO create(String e, String s, String a, CreateReq req, String key)<br>public SlotVO get(String e, String s, String no)<br>public ClaimVO reserve(String e, String s, String a, ClaimReq req, String key)<br>public ClaimVO transition( String e, String s, String a, String no, long expected, String action, String key)<br>public boolean dispatchDue(String e, String s, String source) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/StationServiceImpl.java | ai.neargo.shop.food.service.impl.StationServiceImpl | public StationServiceImpl(StationMapper heads, SkuStationMapper routes, ActionLogMapper actions, IdempotencyService idem, OutboxPublisher outbox, ObjectMapper json, PlatformTransactionManager manager, GoodsCatalogQueryPort catalog)<br>public StationMutationVO createStation(String entity,String store,String actor, CreateStationReq input,String key)<br>public StationMutationVO updateStation(String entity,String store,String number,String actor, UpdateStationReq input,String key)<br>public GetStationVO getStation(String entity,String store,String number)<br>public PageData<StationVO> listStations(String entity,String store,Integer inputPage,Integer inputSize)<br>public OperationVO replaceRoutes(String entity,String store,String sku,String actor, ReplaceStationRoutesReq input,String key)<br>public StationRoutesVO getRoutes(String entity,String store,String sku)<br>public static ReplaceStationRoutesReq normalize(ReplaceStationRoutesReq req) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/StoreOperationServiceImpl.java | ai.neargo.shop.food.service.impl.StoreOperationServiceImpl | public StoreOperationServiceImpl(StorePolicyMapper policies, ActionLogMapper actions, ServiceCalendarMapper calendars, IdempotencyService idempotency, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager)<br>public StoreOperationServiceImpl(StorePolicyMapper policies, ActionLogMapper actions, ServiceCalendarMapper calendars, IdempotencyService idempotency, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager, Clock clock)<br>public OperationVO createPolicy(String entityNo, String storeNo, String actorNo, CreatePolicyReq req, String key)<br>public OperationVO saveCalendar(String entityNo, String storeNo, LocalDate date, String actorNo, SaveCalendarReq req, String key)<br>public StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query) |
| backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/TableServiceImpl.java | ai.neargo.shop.food.service.impl.TableServiceImpl | public TableServiceImpl(TableAreaMapper areas, TableMapper tables, ActionLogMapper actions, IdempotencyService idempotency, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, @Qualifier("transactionManager") PlatformTransactionManager manager)<br>public TableServiceImpl(TableAreaMapper areas, TableMapper tables, ActionLogMapper actions, IdempotencyService idempotency, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, @Qualifier("transactionManager") PlatformTransactionManager manager, ReservationTableMapper reservationTables)<br>public PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query)<br>public TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo, CreateTableAreaReq req, String key)<br>public TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SaveTableAreaReq req, String key)<br>public TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SetStatusTableAreaReq req, String key)<br>public PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query)<br>public TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo)<br>public TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req, String key)<br>public TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SaveTableReq req, String key)<br>public TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SetStatusTableReq req, String key)<br>public OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, ArchiveTableReq req, String key) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/FoodApplication.java | ai.neargo.shop.food.server.FoodApplication | public static void main(String[] args) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalBundleController.java | ai.neargo.shop.food.server.api.InternalBundleController | public InternalBundleController(BundleService service)<br>public CreateBundleResultVO createBundle(@Valid @RequestBody BundleInternalApi.CreateCommand c)<br>public BundleMutationVO replaceDefinition(@Valid @RequestBody BundleInternalApi.UpdateCommand c)<br>public GetBundleVO getBundle(@Valid @RequestBody BundleInternalApi.GetQuery c)<br>public PageData<BundleConfigVO> listBundles(@Valid @RequestBody BundleInternalApi.Lookup c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalBusinessCloseController.java | ai.neargo.shop.food.server.api.InternalBusinessCloseController | public InternalBusinessCloseController(BusinessCloseService service)<br>public DayVO open(@Valid @RequestBody OpenCommand c)<br>public ReportVO report(@Valid @RequestBody ReportQuery c)<br>public CloseVO preview(@Valid @RequestBody PreviewCommand c)<br>public CloseVO get(@Valid @RequestBody GetQuery c)<br>public CloseVO sign(@Valid @RequestBody SignCommand c)<br>public OperationVO adjust(@Valid @RequestBody AdjustCommand c)<br>public List<ExceptionVO> exceptions(@Valid @RequestBody GetQuery c)<br>public ExceptionVO proposeCarry(@Valid @RequestBody ProposeCarryCommand c)<br>public ExceptionVO approveCarry(@Valid @RequestBody ApproveCarryCommand c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalBusinessOperationsController.java | ai.neargo.shop.food.server.api.InternalBusinessOperationsController | public InternalBusinessOperationsController( ServiceFormService serviceForm, FoodFeeSourceService foodFeeSource, QueueNotificationService queueNotification, CheckCollaborationService checkCollaboration, ReservationPreorderService reservationPreorder, ServiceSlotService serviceSlot, BusinessOperationsService businessOperations)<br>public ServiceFormContracts.OrganizationVO serviceFormRegisterOrganization( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormRegisterOrganizationCommand c)<br>public ServiceFormContracts.FormVO serviceFormBook( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormBookCommand c)<br>public ServiceFormContracts.FormVO serviceFormGet( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormGetCommand c)<br>public ServiceFormContracts.FormVO serviceFormRevise( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormReviseCommand c)<br>public ServiceFormContracts.FormVO serviceFormEnter( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormEnterCommand c)<br>public ServiceFormContracts.FormVO serviceFormLeave( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormLeaveCommand c)<br>public ServiceFormContracts.FormVO serviceFormCancel( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormCancelCommand c)<br>public ServiceFormContracts.FormVO serviceFormNoShow( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormNoShowCommand c)<br>public ServiceFormContracts.FormVO serviceFormBindBatch( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormBindBatchCommand c)<br>public List<ServiceFormContracts.AttendeeVO> serviceFormAttendees( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormAttendeesCommand c)<br>public ServiceFormContracts.AttendeeVO serviceFormAdmit( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormAdmitCommand c)<br>public ServiceFormContracts.AttendeeVO serviceFormExit( @Valid @RequestBody BusinessOperationsInternalApi.ServiceFormExitCommand c)<br>public FoodFeeSourceContracts.BindingVO foodFeeSourceBind( @Valid @RequestBody BusinessOperationsInternalApi.FoodFeeSourceBindCommand c)<br>public List<FoodFeeSourceContracts.BindingVO> foodFeeSourceBindings( @Valid @RequestBody BusinessOperationsInternalApi.FoodFeeSourceBindingsCommand c)<br>public List<QueueNotificationService.NoticeVO> queueNotificationList( @Valid @RequestBody BusinessOperationsInternalApi.QueueNotificationListCommand c)<br>public QueueNotificationService.NoticeVO queueNotificationRecover( @Valid @RequestBody BusinessOperationsInternalApi.QueueNotificationRecoverCommand c)<br>public CheckCollaborationContracts.ReceivablePreview checkCollaborationPreviewReceivable( @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationPreviewReceivableCommand c)<br>public String checkCollaborationSourceHash( @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationSourceHashCommand c)<br>public OperationVO checkCollaborationReleaseService( @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationReleaseServiceCommand c)<br>public String checkCollaborationCurrentLineOwner( @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationCurrentLineOwnerCommand c)<br>public CheckCollaborationContracts.MoveVO checkCollaborationMoveBatches( @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationMoveBatchesCommand c)<br>public CheckCollaborationContracts.LineMoveVO checkCollaborationMoveLines( @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationMoveLinesCommand c)<br>public CheckCollaborationContracts.FreezeVO checkCollaborationFreeze( @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationFreezeCommand c)<br>public CheckCollaborationContracts.FreezeVO checkCollaborationRelease( @Valid @RequestBody BusinessOperationsInternalApi.CheckCollaborationReleaseCommand c)<br>public ReservationPreorderContracts.PreorderVO reservationPreorderSave( @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderSaveCommand c)<br>public ReservationPreorderContracts.PreorderVO reservationPreorderGet( @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderGetCommand c)<br>public CartResultVO reservationPreorderPrepareCart( @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderPrepareCartCommand c)<br>public DiningOrderBatchVO reservationPreorderConvert( @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderConvertCommand c)<br>public ReservationPreorderContracts.PreorderVO reservationPreorderCancel( @Valid @RequestBody BusinessOperationsInternalApi.ReservationPreorderCancelCommand c)<br>public ServiceSlotContracts.SlotVO serviceSlotCreate( @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotCreateCommand c)<br>public ServiceSlotContracts.SlotVO serviceSlotGet( @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotGetCommand c)<br>public ServiceSlotContracts.ClaimVO serviceSlotReserve( @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotReserveCommand c)<br>public ServiceSlotContracts.ClaimVO serviceSlotTransition( @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotTransitionCommand c)<br>public boolean serviceSlotDispatchDue( @Valid @RequestBody BusinessOperationsInternalApi.ServiceSlotDispatchDueCommand c)<br>public BusinessOperationsContracts.Evidence businessOperationsEvidence( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsEvidenceCommand c)<br>public BusinessOperationsContracts.HandoverVO businessOperationsPropose( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsProposeCommand c)<br>public BusinessOperationsContracts.HandoverVO businessOperationsHandover( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsHandoverCommand c)<br>public BusinessOperationsContracts.HandoverVO businessOperationsAccept( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsAcceptCommand c)<br>public BusinessOperationsContracts.HandoverVO businessOperationsCancel( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsCancelCommand c)<br>public BusinessOperationsContracts.CaseVO businessOperationsOpenCase( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsOpenCaseCommand c)<br>public List<BusinessOperationsContracts.CaseVO> businessOperationsCases( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsCasesCommand c)<br>public BusinessOperationsContracts.CaseVO businessOperationsRecoverCase( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsRecoverCaseCommand c)<br>public BusinessOperationsContracts.CaseVO businessOperationsResolveCase( @Valid @RequestBody BusinessOperationsInternalApi.BusinessOperationsResolveCaseCommand c)<br>public ResponseEntity<Void> failure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalBusinessReturnApprovalController.java | ai.neargo.shop.food.server.api.InternalBusinessReturnApprovalController | public InternalBusinessReturnApprovalController( BusinessReturnApprovalService service, IndustryAmendmentSourcePort source)<br>public ApprovalVO approve(@RequestBody Approve c)<br>public ApprovalVO get(@RequestBody Query c)<br>public ApprovalVO replacement(@RequestBody Replacement c)<br>public IndustryAmendmentSourcePort.Evidence source( @RequestBody IndustryAmendmentSourceInternalApi.Query c) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalBusinessReturnController.java | ai.neargo.shop.food.server.api.InternalBusinessReturnController | public InternalBusinessReturnController(BusinessReturnService service)<br>public ReturnVO create(@Valid @RequestBody BusinessReturnInternalApi.CreateCommand command)<br>public ReturnVO get(@Valid @RequestBody BusinessReturnInternalApi.Lookup query)<br>public ReturnVO cancel(@Valid @RequestBody BusinessReturnInternalApi.CancelCommand command)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalBusinessShareController.java | ai.neargo.shop.food.server.api.InternalBusinessShareController | public InternalBusinessShareController(BusinessShareService service)<br>public PlanVO create(@Valid @RequestBody BusinessShareInternalApi.CreateCommand command)<br>public PlanVO get(@Valid @RequestBody BusinessShareInternalApi.Lookup query)<br>public PlanVO confirm(@Valid @RequestBody BusinessShareInternalApi.ConfirmCommand command)<br>public PlanVO cancel(@Valid @RequestBody BusinessShareInternalApi.CancelCommand command)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalCartController.java | ai.neargo.shop.food.server.api.InternalCartController | public InternalCartController(CheckCartService service)<br>public CartResultVO create(@Valid @RequestBody CartInternalApi.CreateCommand c)<br>public CartResultVO get(@Valid @RequestBody CartInternalApi.Identity c)<br>public CartVersionVO replaceLine(@Valid @RequestBody CartInternalApi.ReplaceLineCommand c)<br>public CartVersionVO archiveLine(@Valid @RequestBody CartInternalApi.ArchiveLineCommand c)<br>public OperationResultVO archive(@Valid @RequestBody CartInternalApi.ArchiveCommand c)<br>public CartSessionGrantVO authorize(@Valid @RequestBody CartInternalApi.AuthorizeCommand c)<br>public CartResultVO customerCreate(@Valid @RequestBody CartInternalApi.CustomerCreateCommand c)<br>public CartResultVO customerGet(@Valid @RequestBody CartInternalApi.CustomerIdentity c)<br>public CartVersionVO customerReplaceLine(@Valid @RequestBody CartInternalApi.CustomerReplaceLineCommand c)<br>public CartVersionVO customerArchiveLine(@Valid @RequestBody CartInternalApi.CustomerArchiveLineCommand c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalCatalogController.java | ai.neargo.shop.food.server.api.InternalCatalogController | public InternalCatalogController(CatalogService service)<br>public CatalogVO scheduledCart(@RequestBody CatalogInternalApi.ScheduledQuery query)<br>public CatalogVO read(@Valid @RequestBody CatalogInternalApi.Query q)<br>public CatalogVO publicCatalog(@Valid @RequestBody CatalogInternalApi.PublicQuery q)<br>public CatalogVO guest(@Valid @RequestBody CatalogInternalApi.GuestQuery q)<br>public ResponseEntity<Void> infrastructureFailure(RuntimeException failure)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalCheckController.java | ai.neargo.shop.food.server.api.InternalCheckController | public InternalCheckController(CheckService service)<br>public CheckResultVO open(@Valid @RequestBody CheckInternalApi.OpenCommand c)<br>public PageData<CheckVO> list(@Valid @RequestBody CheckInternalApi.Lookup c)<br>public CheckDetailVO get(@Valid @RequestBody CheckInternalApi.Identity c)<br>public CheckResultVO seats(@Valid @RequestBody CheckInternalApi.SeatsCommand c)<br>public OperationVO clear(@Valid @RequestBody CheckInternalApi.ClearCommand c)<br>public OperationResultVO transfer(@Valid @RequestBody CheckInternalApi.TransferCommand c)<br>public OperationResultVO combine(@Valid @RequestBody CheckInternalApi.CombineCommand c)<br>public PageData<ActionLogVO> actions(@Valid @RequestBody CheckInternalApi.ActionsLookup c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalConfigRevisionController.java | ai.neargo.shop.food.server.api.InternalConfigRevisionController | public InternalConfigRevisionController(ConfigRevisionService service)<br>public PublishConfigResultVO republishRevision(@Valid @RequestBody ConfigRevisionInternalApi.RepublishCommand c)<br>public PublishConfigResultVO publishMenu(@Valid @RequestBody ConfigRevisionInternalApi.Command c)<br>public PublishConfigResultVO publishStation(@Valid @RequestBody ConfigRevisionInternalApi.Command c)<br>public PublishConfigResultVO publishRecipe(@Valid @RequestBody ConfigRevisionInternalApi.Command c)<br>public PublishConfigResultVO publishBundle(@Valid @RequestBody ConfigRevisionInternalApi.Command c)<br>public PublishConfigResultVO publishModifier(@Valid @RequestBody ConfigRevisionInternalApi.Command c)<br>public PublishConfigResultVO publishOptionGroup(@Valid @RequestBody ConfigRevisionInternalApi.Command c)<br>public PageData<ConfigRevisionVO> listRevisions(@Valid @RequestBody ConfigRevisionInternalApi.Lookup c)<br>public ConfigRevisionContentVO getRevision(@Valid @RequestBody ConfigRevisionInternalApi.Identity c)<br>public ConfigRevisionContentVO resolveRevision(@Valid @RequestBody ConfigRevisionInternalApi.Resolve c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningBatchQueryController.java | ai.neargo.shop.food.server.api.InternalDiningBatchQueryController | public InternalDiningBatchQueryController(DiningBatchQueryService service)<br>public PageData<DiningBatchSummaryVO> list(@Valid @RequestBody DiningBatchQueryInternalApi.Listing request)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningCheckoutController.java | ai.neargo.shop.food.server.api.InternalDiningCheckoutController | public InternalDiningCheckoutController(DiningCheckoutService service)<br>public DiningSettlementVO create(@RequestBody DiningCheckoutInternalApi.Create r)<br>public DiningSettlementVO get(@RequestBody DiningCheckoutInternalApi.Reference r)<br>public DiningSettlementVO pay(@RequestBody DiningCheckoutInternalApi.Pay r)<br>public DiningSettlementVO query(@RequestBody DiningCheckoutInternalApi.Reference r)<br>public DiningSettlementVO finalizeCheckout(@RequestBody DiningCheckoutInternalApi.Reference r)<br>public DiningSettlementVO cancel(@RequestBody DiningCheckoutInternalApi.Cancel r)<br>public DiningSettlementVO staffGet(@RequestBody DiningCheckoutInternalApi.StaffGet r)<br>public DiningSettlementVO recoverOriginal(@RequestBody DiningCheckoutInternalApi.Original request)<br>public ResponseEntity<Void> failure(BizException error) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningDeliveryController.java | ai.neargo.shop.food.server.api.InternalDiningDeliveryController | public InternalDiningDeliveryController( DiningDeliveryService service, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> saveStaff( @RequestBody DiningDeliveryInternalApi.StaffSave c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> saveCustomer( @RequestBody DiningDeliveryInternalApi.CustomerSave c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> getStaff( @RequestBody DiningDeliveryInternalApi.Source c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> getCustomer( @RequestBody DiningDeliveryInternalApi.CustomerQuery c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> source( @RequestBody DiningDeliveryInternalApi.Source c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningIdentityController.java | ai.neargo.shop.food.server.api.InternalDiningIdentityController | public InternalDiningIdentityController( DiningIdentityService service, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> device( @RequestBody DiningIdentityInternalApi.DeviceQuery c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> register( @RequestBody DiningIdentityInternalApi.Register c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> revokeDevice( @RequestBody DiningIdentityInternalApi.DeviceRevoke c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> deviceSession( @RequestBody DiningIdentityInternalApi.DeviceSession c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> staffParty( @RequestBody DiningIdentityInternalApi.StaffParty c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> revokeParty( @RequestBody DiningIdentityInternalApi.PartyRevoke c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> party( @RequestBody DiningIdentityInternalApi.PartyQuery c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> validate( @RequestBody DiningIdentityInternalApi.SessionQuery c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningOrderAuthorityController.java | ai.neargo.shop.food.server.api.InternalDiningOrderAuthorityController | public InternalDiningOrderAuthorityController(DiningOrderAuthorityService service)<br>public Delivery delivery(@RequestBody Command command)<br>public Party party(@RequestBody Command command)<br>public Plan plan(@RequestBody DiningOrderAuthorityInternalApi.PlanCommand command) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningOrderController.java | ai.neargo.shop.food.server.api.InternalDiningOrderController | public InternalDiningOrderController(DiningOrderService service)<br>public Snapshot authorizeCancellation(@RequestBody DiningOrderInternalApi.CancellationSource request)<br>public DiningOrderBatchVO staffCancel(@RequestBody DiningOrderInternalApi.StaffCancel request)<br>public DiningOrderBatchVO staffPlace(@RequestBody DiningOrderInternalApi.StaffPlace request)<br>public DiningOrderBatchVO place(@RequestBody DiningOrderInternalApi.Place r)<br>public DiningOrderBatchVO customerGet(@RequestBody DiningOrderInternalApi.CustomerGet r)<br>public DiningOrderBatchVO staffGet(@RequestBody DiningOrderInternalApi.StaffGet r)<br>public DiningOrderBatchVO recoverOriginal(@RequestBody DiningOrderInternalApi.Original request)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningPickupVerificationController.java | ai.neargo.shop.food.server.api.InternalDiningPickupVerificationController | public InternalDiningPickupVerificationController(DiningPickupVerificationService service)<br>public PickupVerificationVO get(@Valid @RequestBody DiningPickupVerificationInternalApi.Query c)<br>public PickupVerificationVO verify(@Valid @RequestBody DiningPickupVerificationInternalApi.Command c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningPricingController.java | ai.neargo.shop.food.server.api.InternalDiningPricingController | public InternalDiningPricingController(DiningPricingService service)<br>public QuoteVO staffQuote(@RequestBody DiningPricingInternalApi.StaffQuote r)<br>public QuoteVO staffGet(@RequestBody DiningPricingInternalApi.StaffGet r)<br>public QuoteVO staffConfirm(@RequestBody DiningPricingInternalApi.StaffConfirm r)<br>public QuoteVO customerQuote(@RequestBody DiningPricingInternalApi.CustomerQuote r)<br>public QuoteVO customerGet(@RequestBody DiningPricingInternalApi.CustomerGet r)<br>public QuoteVO customerConfirm(@RequestBody DiningPricingInternalApi.CustomerConfirm r)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningReturnController.java | ai.neargo.shop.food.server.api.InternalDiningReturnController | public InternalDiningReturnController(DiningReturnService service)<br>public DiningReturnVO apply(@RequestBody DiningReturnInternalApi.CustomerApply r)<br>public DiningReturnVO get(@RequestBody DiningReturnInternalApi.CustomerReference r)<br>public DiningReturnVO cancel(@RequestBody DiningReturnInternalApi.CustomerReference r)<br>public DiningReturnVO staff(@RequestBody DiningReturnInternalApi.StaffReference r)<br>public DiningReturnVO recoverOriginal(@RequestBody DiningReturnInternalApi.Original request)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningSessionController.java | ai.neargo.shop.food.server.api.InternalDiningSessionController | public InternalDiningSessionController(DiningSessionService service)<br>public TableTokenVO rotate(@Valid @RequestBody DiningSessionInternalApi.RotateCommand c)<br>public OperationResultVO revoke(@Valid @RequestBody DiningSessionInternalApi.RevokeCommand c)<br>public DiningSessionTableVO resolve(@Valid @RequestBody DiningSessionInternalApi.ResolveCommand c)<br>public DiningSessionVO start(@Valid @RequestBody DiningSessionInternalApi.StartCommand c)<br>public OperationResultVO end(@Valid @RequestBody DiningSessionInternalApi.EndCommand c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDiningTransactionRecoveryController.java | ai.neargo.shop.food.server.api.InternalDiningTransactionRecoveryController | public InternalDiningTransactionRecoveryController(DiningTransactionRecoveryService recovery)<br>public DiningTransactionRecoveryService.Report recover(@RequestBody DiningTransactionRecoveryInternalApi.Scan request)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalDishController.java | ai.neargo.shop.food.server.api.InternalDishController | public InternalDishController(DishService service)<br>public DishResultVO saveDish(@Valid @RequestBody DishInternalApi.SaveCommand c)<br>public PageData<DishCatalogItemVO> listDishes(@Valid @RequestBody DishInternalApi.Lookup query)<br>public DishDetailResultVO getDish(@Valid @RequestBody DishInternalApi.Identity query)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalFoodKdsController.java | ai.neargo.shop.food.server.api.InternalFoodKdsController | public InternalFoodKdsController( FoodKdsService service, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> grant( @RequestBody FoodKdsInternalApi.Grant c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> revoke( @RequestBody FoodKdsInternalApi.Revoke c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> replay( @RequestBody FoodKdsInternalApi.Replay c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> append( @RequestBody EventDraft c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalFoodPrintController.java | ai.neargo.shop.food.server.api.InternalFoodPrintController | public InternalFoodPrintController(FoodPrintService service,@Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> enqueue(@RequestBody FoodPrintInternalApi.Command command, @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalFoodStationRuntimeController.java | ai.neargo.shop.food.server.api.InternalFoodStationRuntimeController | public InternalFoodStationRuntimeController( FoodStationRuntimeService service, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> state( @RequestBody FoodStationRuntimeInternalApi.Scope c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> running( @RequestBody FoodStationRuntimeInternalApi.Scope c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> control( @RequestBody FoodStationRuntimeInternalApi.Control c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> claim( @RequestBody FoodStationRuntimeInternalApi.Poll c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> acknowledge( @RequestBody FoodStationRuntimeInternalApi.Receipt c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalFoodSupportController.java | ai.neargo.shop.food.server.api.InternalFoodSupportController | public InternalFoodSupportController( FoodSupportService service, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> readiness( @Valid @RequestBody FoodSupportInternalApi.ReadinessQuery request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> cases( @Valid @RequestBody FoodSupportInternalApi.CasesQuery request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> evidence( @Valid @RequestBody FoodSupportInternalApi.SourceQuery request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalKitchenController.java | ai.neargo.shop.food.server.api.InternalKitchenController | public InternalKitchenController(KitchenTaskService service)<br>public FrozenRoutes freezeRoutes(@Valid @RequestBody KitchenInternalApi.FreezeQuery c)<br>public TicketVO accepted(@Valid @RequestBody KitchenInternalApi.DispatchCommand c)<br>public List<TaskVO> queue(@Valid @RequestBody KitchenInternalApi.QueueQuery c)<br>public EventVO act(@Valid @RequestBody KitchenInternalApi.TaskCommand c)<br>public TicketVO remake(@Valid @RequestBody KitchenInternalApi.RemakeCommand c)<br>public List<EventVO> events(@Valid @RequestBody KitchenInternalApi.TaskQuery c)<br>public CourseVO createCourse(@Valid @RequestBody KitchenInternalApi.CreateCourseCommand c)<br>public List<CourseVO> courses(@Valid @RequestBody KitchenInternalApi.CourseQuery c)<br>public CourseVO actCourse(@Valid @RequestBody KitchenInternalApi.CourseCommand c)<br>public HandoverVO handover(@Valid @RequestBody KitchenInternalApi.HandoverCommand c)<br>public CustodyReceiptVO confirmPacking(@Valid @RequestBody KitchenInternalApi.PackingCommand c)<br>public List<CustodyReceiptVO> custodyReceipts(@Valid @RequestBody KitchenInternalApi.ReceiptQuery c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalKitchenReturnController.java | ai.neargo.shop.food.server.api.InternalKitchenReturnController | public InternalKitchenReturnController(KitchenReturnService service)<br>public ReturnKitchenVO get(@Valid @RequestBody KitchenReturnInternalApi.Query c)<br>public HandledVO handle(@Valid @RequestBody KitchenReturnInternalApi.Command c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalMenuChannelBindingController.java | ai.neargo.shop.food.server.api.InternalMenuChannelBindingController | public InternalMenuChannelBindingController(MenuChannelBindingService service)<br>public MenuChannelBindingVO createBinding(@Valid @RequestBody MenuChannelBindingInternalApi.CreateCommand c)<br>public MenuChannelBindingVO updateBinding(@Valid @RequestBody MenuChannelBindingInternalApi.UpdateCommand c)<br>public MenuChannelBindingVO getBinding(@Valid @RequestBody MenuChannelBindingInternalApi.GetQuery c)<br>public PageData<MenuChannelBindingVO> listBindings(@Valid @RequestBody MenuChannelBindingInternalApi.Lookup c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalMenuController.java | ai.neargo.shop.food.server.api.InternalMenuController | public InternalMenuController(MenuService service)<br>public CreateMenuResultVO createMenu(@Valid @RequestBody MenuInternalApi.CreateCommand c)<br>public MenuResultVO updateMenu(@Valid @RequestBody MenuInternalApi.UpdateCommand c)<br>public MenuScheduleResultVO replaceSchedule(@Valid @RequestBody MenuInternalApi.ScheduleCommand c)<br>public MenuCompositionResultVO replaceComposition(@Valid @RequestBody MenuInternalApi.CompositionCommand c)<br>public MenuContextsResultVO replaceContexts(@Valid @RequestBody MenuInternalApi.ContextsCommand c)<br>public MenuResultVO disableMenu(@Valid @RequestBody MenuInternalApi.DisableCommand c)<br>public MenuConfigResultVO getMenu(@Valid @RequestBody MenuInternalApi.GetQuery c)<br>public PageData<MenuVO> listMenus(@Valid @RequestBody MenuInternalApi.Lookup c)<br>public ResponseEntity<Void> infrastructureFailure(RuntimeException failure)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalModifierController.java | ai.neargo.shop.food.server.api.InternalModifierController | public InternalModifierController(ModifierService service)<br>public CreateModifierResultVO createModifier(@Valid @RequestBody ModifierInternalApi.CreateCommand c)<br>public ModifierMutationVO updateModifier(@Valid @RequestBody ModifierInternalApi.UpdateCommand c)<br>public PageData<ModifierConfigVO> listModifiers(@Valid @RequestBody ModifierInternalApi.Lookup c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalOptionGroupController.java | ai.neargo.shop.food.server.api.InternalOptionGroupController | public InternalOptionGroupController(OptionGroupService service)<br>public OptionGroupMutationVO replaceOptionGroupItems( @Valid @RequestBody OptionGroupInternalApi.ReplaceCommand c)<br>public CreateOptionGroupResultVO createOptionGroup(@Valid @RequestBody OptionGroupInternalApi.CreateCommand c)<br>public PageData<OptionGroupConfigVO> listOptionGroups(@Valid @RequestBody OptionGroupInternalApi.Lookup query)<br>public OptionGroupResultVO getOptionGroup(@Valid @RequestBody OptionGroupInternalApi.Identity query)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalPricingSourceController.java | ai.neargo.shop.food.server.api.InternalPricingSourceController | public InternalPricingSourceController(DiningPricingFactsService service)<br>public DiningPriceFacts staff(@Valid @RequestBody PricingSourceInternalApi.StaffCommand c)<br>public DiningPriceFacts customer(@Valid @RequestBody PricingSourceInternalApi.CustomerCommand c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalProductionConsumptionController.java | ai.neargo.shop.food.server.api.InternalProductionConsumptionController | public InternalProductionConsumptionController(ProductionConsumptionService service)<br>public ConsumptionVO consume(@Valid @RequestBody Command c)<br>public ConsumptionVO get(@Valid @RequestBody Command c)<br>public PageData<ConsumptionVO> list(@Valid @RequestBody ListCommand c)<br>public ConsumptionVO adjust(@RequestBody ProductionConsumptionInternalApi.AdjustmentCommand c)<br>public ConsumptionVO reverse(@RequestBody ProductionConsumptionInternalApi.ReverseCommand c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalQueueController.java | ai.neargo.shop.food.server.api.InternalQueueController | public InternalQueueController(QueueService service)<br>public QueueVO issue(@Valid @RequestBody IssueCommand c)<br>public PageData<QueueVO> list(@Valid @RequestBody ListQuery c)<br>public QueueVO get(@Valid @RequestBody Identity c)<br>public QueueVO callNext(@Valid @RequestBody CallNextCommand c)<br>public QueueVO change(@Valid @RequestBody ChangeCommand c)<br>public QueueVO seat(@Valid @RequestBody SeatCommand c)<br>public QueueVO recoverSeating(@Valid @RequestBody Identity c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalRecipeController.java | ai.neargo.shop.food.server.api.InternalRecipeController | public InternalRecipeController(RecipeService service)<br>public RecipeResultVO createRecipe(@Valid @RequestBody RecipeInternalApi.CreateCommand c)<br>public RecipeResultVO replaceDefinition(@Valid @RequestBody RecipeInternalApi.UpdateCommand c)<br>public RecipeResultVO getRecipe(@Valid @RequestBody RecipeInternalApi.GetQuery c)<br>public PageData<RecipeVO> listRecipes(@Valid @RequestBody RecipeInternalApi.Lookup c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalReservationController.java | ai.neargo.shop.food.server.api.InternalReservationController | public InternalReservationController(ReservationService service)<br>public PageData<ReservationVO> list(@Valid @RequestBody ReservationInternalApi.Lookup c)<br>public ResponseEntity<Void> businessFailure(BizException failure)<br>public DetailVO get(@Valid @RequestBody ReservationInternalApi.NumberLookup c)<br>public DetailVO create(@Valid @RequestBody ReservationInternalApi.CreateCommand c)<br>public DetailVO change(@Valid @RequestBody ReservationInternalApi.ChangeCommand c)<br>public DetailVO cancel(@Valid @RequestBody ReservationInternalApi.TransitionCommand c)<br>public DetailVO noShow(@Valid @RequestBody ReservationInternalApi.TransitionCommand c)<br>public DetailVO arrive(@Valid @RequestBody ReservationInternalApi.ArriveCommand c) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalSalesRestrictionController.java | ai.neargo.shop.food.server.api.InternalSalesRestrictionController | public InternalSalesRestrictionController(SalesRestrictionService service)<br>public SalesRestrictionVO manage(@Valid @RequestBody SalesRestrictionInternalApi.Command c)<br>public PageData<SalesRestrictionVO> list(@Valid @RequestBody SalesRestrictionInternalApi.Lookup c)<br>public SalesRestrictionEvaluationVO evaluate(@Valid @RequestBody SalesRestrictionInternalApi.Evaluation c)<br>public ResponseEntity<Void> infrastructureFailure(RuntimeException failure)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalServiceCalendarController.java | ai.neargo.shop.food.server.api.InternalServiceCalendarController | public InternalServiceCalendarController(StoreOperationService service)<br>public OperationVO saveCalendar(@Valid @RequestBody ServiceCalendarInternalApi.Command command)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalStationController.java | ai.neargo.shop.food.server.api.InternalStationController | public InternalStationController(StationService service)<br>public StationMutationVO createStation(@Valid @RequestBody StationInternalApi.CreateCommand c)<br>public StationMutationVO updateStation(@Valid @RequestBody StationInternalApi.UpdateCommand c)<br>public GetStationVO getStation(@Valid @RequestBody StationInternalApi.GetQuery c)<br>public PageData<StationVO> listStations(@Valid @RequestBody StationInternalApi.Lookup c)<br>public OperationVO replaceRoutes(@Valid @RequestBody StationInternalApi.RoutesCommand c)<br>public StationRoutesVO getRoutes(@Valid @RequestBody StationInternalApi.GetQuery c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalStoreOperationPolicyController.java | ai.neargo.shop.food.server.api.InternalStoreOperationPolicyController | public InternalStoreOperationPolicyController(StoreOperationService service)<br>public StoreOperationPolicyResultVO getPolicy(@Valid @RequestBody StoreOperationInternalApi.Lookup request)<br>public OperationVO createPolicy(@Valid @RequestBody StorePolicyPublicationInternalApi.Command command)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalTableAreaController.java | ai.neargo.shop.food.server.api.InternalTableAreaController | public InternalTableAreaController(TableService service)<br>public PageData<TableAreaVO> listTableAreas(@Valid @RequestBody TableInternalApi.Lookup c)<br>public TableAreaResultVO createTableArea(@Valid @RequestBody TableInternalApi.CreateCommand c)<br>public TableAreaResultVO saveTableArea(@Valid @RequestBody TableInternalApi.SaveCommand c)<br>public TableAreaResultVO setStatusTableArea(@Valid @RequestBody TableInternalApi.StatusCommand c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalTableController.java | ai.neargo.shop.food.server.api.InternalTableController | public InternalTableController(TableService service)<br>public PageData<TableVO> listTables(@Valid @RequestBody TableInternalApi.TableLookup c)<br>public TableResultVO getTable(@Valid @RequestBody TableInternalApi.TableIdentity c)<br>public TableResultVO createTable(@Valid @RequestBody TableInternalApi.TableCreateCommand c)<br>public TableResultVO saveTable(@Valid @RequestBody TableInternalApi.TableSaveCommand c)<br>public TableResultVO setStatusTable(@Valid @RequestBody TableInternalApi.TableStatusCommand c)<br>public OperationResultVO archiveTable(@Valid @RequestBody TableInternalApi.TableArchiveCommand c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalTableOperationController.java | ai.neargo.shop.food.server.api.InternalTableOperationController | public InternalTableOperationController(CheckService service)<br>public OperationResultVO operation(@Valid @RequestBody CheckInternalApi.OperationLookup c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalTableSharingController.java | ai.neargo.shop.food.server.api.InternalTableSharingController | public InternalTableSharingController(CheckService service)<br>public CheckResultVO share(@Valid @RequestBody CheckInternalApi.ShareCommand c)<br>public ResponseEntity<Void> businessFailure(BizException failure) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/FoodBizDataScopeInterceptor.java | ai.neargo.shop.food.api.FoodBizDataScopeInterceptor | public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)<br>public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler, Exception error) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/FoodBizStoreScope.java | ai.neargo.shop.food.api.FoodBizStoreScope | public FoodBizStoreScope(StoreDirectoryPort stores,FoodRequestScopeValidator validator)<br>public BizContext current(HttpServletRequest request)<br>public BizContext path(String store,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/FoodRequestScopeValidator.java | ai.neargo.shop.food.api.FoodRequestScopeValidator | public void requireStoreTarget(String trustedEntityNo, String trustedStoreNo, String targetEntityNo, String targetStoreNo, String requestedStoreNo, Set<String> authorizedStoreNos) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBundleController.java | ai.neargo.shop.food.api.biz.BizBundleController | public BizBundleController(BundleService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public CreateBundleResultVO createBundle(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateBundleReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public BundleMutationVO replaceDefinition(@PathVariable("skuNo") @NotBlank @Size(max=32) String skuNo, @Valid @RequestBody ReplaceBundleDefinitionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public GetBundleVO getBundle(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String number, HttpServletRequest request)<br>public PageData<BundleConfigVO> listBundles(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListBundlesQuery query, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java | ai.neargo.shop.food.api.biz.BizBusinessCloseController | public BizBusinessCloseController(BusinessCloseService service,StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public DayVO open(@PathVariable LocalDate date,@RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public ReportVO report(@RequestParam LocalDate businessDate,@RequestParam String currency, HttpServletRequest request)<br>public CloseVO preview(@Valid @RequestBody PreviewReq body,@RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public CloseVO get(@PathVariable @NotBlank @Size(max=32) String closeNo,HttpServletRequest request)<br>public CloseVO sign(@Valid @RequestBody SignReq body,@RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public OperationVO adjust(@Valid @RequestBody AdjustmentReq body,@RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public List<ExceptionVO> exceptions(@PathVariable @NotBlank @Size(max=32) String closeNo, HttpServletRequest request)<br>public ExceptionVO proposeCarry(@PathVariable @NotBlank @Size(max=32) String closeNo, @PathVariable @NotBlank @Size(max=32) String exceptionNo, @Valid @RequestBody ProposeCarryReq body, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public ExceptionVO approveCarry(@PathVariable @NotBlank @Size(max=32) String closeNo, @PathVariable @NotBlank @Size(max=32) String exceptionNo, @Valid @RequestBody ApproveCarryReq body, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessOperationsController.java | ai.neargo.shop.food.api.biz.BizBusinessOperationsController | public BizBusinessOperationsController( ServiceFormService serviceForm, FoodFeeSourceService foodFeeSource, QueueNotificationService queueNotification, CheckCollaborationService checkCollaboration, ReservationPreorderService reservationPreorder, ServiceSlotService serviceSlot, BusinessOperationsService businessOperations, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public record ServiceFormRegisterOrganizationRequest( @Valid @NotNull ServiceFormContracts.OrganizationReq request)<br>public ServiceFormContracts.OrganizationVO serviceFormRegisterOrganization( @Valid @RequestBody ServiceFormRegisterOrganizationRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormBookRequest(@Valid @NotNull ServiceFormContracts.BookReq request)<br>public ServiceFormContracts.FormVO serviceFormBook( @Valid @RequestBody ServiceFormBookRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormGetRequest(@NotBlank String form)<br>public ServiceFormContracts.FormVO serviceFormGet( @Valid @RequestBody ServiceFormGetRequest r, HttpServletRequest http)<br>public record ServiceFormReviseRequest( @NotBlank String form, @Valid @NotNull ServiceFormContracts.ReviseReq request)<br>public ServiceFormContracts.FormVO serviceFormRevise( @Valid @RequestBody ServiceFormReviseRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormEnterRequest( @NotBlank String form, @Valid @NotNull ServiceFormContracts.EnterReq request)<br>public ServiceFormContracts.FormVO serviceFormEnter( @Valid @RequestBody ServiceFormEnterRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormLeaveRequest( @NotBlank String form, @Valid @NotNull ServiceFormContracts.VersionReq request)<br>public ServiceFormContracts.FormVO serviceFormLeave( @Valid @RequestBody ServiceFormLeaveRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormCancelRequest( @NotBlank String form, @Valid @NotNull ServiceFormContracts.VersionReq request)<br>public ServiceFormContracts.FormVO serviceFormCancel( @Valid @RequestBody ServiceFormCancelRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormNoShowRequest( @NotBlank String form, @Valid @NotNull ServiceFormContracts.VersionReq request)<br>public ServiceFormContracts.FormVO serviceFormNoShow( @Valid @RequestBody ServiceFormNoShowRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormBindBatchRequest( @NotBlank String form, @Valid @NotNull ServiceFormContracts.BatchReq request)<br>public ServiceFormContracts.FormVO serviceFormBindBatch( @Valid @RequestBody ServiceFormBindBatchRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormAttendeesRequest(@NotBlank String form)<br>public List<ServiceFormContracts.AttendeeVO> serviceFormAttendees( @Valid @RequestBody ServiceFormAttendeesRequest r, HttpServletRequest http)<br>public record ServiceFormAdmitRequest( @NotBlank String form, @NotBlank String attendee, @Valid @NotNull ServiceFormContracts.VersionReq request)<br>public ServiceFormContracts.AttendeeVO serviceFormAdmit( @Valid @RequestBody ServiceFormAdmitRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceFormExitRequest( @NotBlank String form, @NotBlank String attendee, @Valid @NotNull ServiceFormContracts.VersionReq request)<br>public ServiceFormContracts.AttendeeVO serviceFormExit( @Valid @RequestBody ServiceFormExitRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record FoodFeeSourceBindRequest( @Valid @NotNull FoodFeeSourceContracts.BindReq request)<br>public FoodFeeSourceContracts.BindingVO foodFeeSourceBind( @Valid @RequestBody FoodFeeSourceBindRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record FoodFeeSourceBindingsRequest(@NotBlank String check)<br>public List<FoodFeeSourceContracts.BindingVO> foodFeeSourceBindings( @Valid @RequestBody FoodFeeSourceBindingsRequest r, HttpServletRequest http)<br>public record QueueNotificationListRequest(@NotBlank String ticket)<br>public List<QueueNotificationService.NoticeVO> queueNotificationList( @Valid @RequestBody QueueNotificationListRequest r, HttpServletRequest http)<br>public record QueueNotificationRecoverRequest(@NotBlank String notice)<br>public QueueNotificationService.NoticeVO queueNotificationRecover( @Valid @RequestBody QueueNotificationRecoverRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record CheckCollaborationPreviewReceivableRequest(@NotBlank String check)<br>public CheckCollaborationContracts.ReceivablePreview checkCollaborationPreviewReceivable( @Valid @RequestBody CheckCollaborationPreviewReceivableRequest r, HttpServletRequest http)<br>public record CheckCollaborationSourceHashRequest(@NotBlank String check)<br>public String checkCollaborationSourceHash( @Valid @RequestBody CheckCollaborationSourceHashRequest r, HttpServletRequest http)<br>public record CheckCollaborationReleaseServiceRequest( @NotBlank String check, long version, @NotBlank String reason)<br>public OperationVO checkCollaborationReleaseService( @Valid @RequestBody CheckCollaborationReleaseServiceRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record CheckCollaborationCurrentLineOwnerRequest(@NotBlank String foodLineNo)<br>public String checkCollaborationCurrentLineOwner( @Valid @RequestBody CheckCollaborationCurrentLineOwnerRequest r, HttpServletRequest http)<br>public record CheckCollaborationMoveBatchesRequest( @Valid @NotNull CheckCollaborationContracts.MoveReq request)<br>public CheckCollaborationContracts.MoveVO checkCollaborationMoveBatches( @Valid @RequestBody CheckCollaborationMoveBatchesRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record CheckCollaborationMoveLinesRequest( @Valid @NotNull CheckCollaborationContracts.LineMoveReq request)<br>public CheckCollaborationContracts.LineMoveVO checkCollaborationMoveLines( @Valid @RequestBody CheckCollaborationMoveLinesRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record CheckCollaborationFreezeRequest( @NotBlank String check, @Valid @NotNull CheckCollaborationContracts.FreezeReq request)<br>public CheckCollaborationContracts.FreezeVO checkCollaborationFreeze( @Valid @RequestBody CheckCollaborationFreezeRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record CheckCollaborationReleaseRequest( @NotBlank String freeze, long version, @NotBlank String reason)<br>public CheckCollaborationContracts.FreezeVO checkCollaborationRelease( @Valid @RequestBody CheckCollaborationReleaseRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ReservationPreorderSaveRequest( @NotBlank String reservation, @Valid @NotNull ReservationPreorderContracts.SaveReq request)<br>public ReservationPreorderContracts.PreorderVO reservationPreorderSave( @Valid @RequestBody ReservationPreorderSaveRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ReservationPreorderGetRequest( @NotBlank String reservation, @NotBlank String preorder)<br>public ReservationPreorderContracts.PreorderVO reservationPreorderGet( @Valid @RequestBody ReservationPreorderGetRequest r, HttpServletRequest http)<br>public record ReservationPreorderPrepareCartRequest( @NotBlank String reservation, @NotBlank String preorder, long version)<br>public CartResultVO reservationPreorderPrepareCart( @Valid @RequestBody ReservationPreorderPrepareCartRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ReservationPreorderConvertRequest( @NotBlank String reservation, @NotBlank String preorder, @Valid @NotNull ReservationPreorderContracts.ConvertReq request)<br>public DiningOrderBatchVO reservationPreorderConvert( @Valid @RequestBody ReservationPreorderConvertRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ReservationPreorderCancelRequest( @NotBlank String reservation, @NotBlank String preorder, long version, @NotBlank String reason)<br>public ReservationPreorderContracts.PreorderVO reservationPreorderCancel( @Valid @RequestBody ReservationPreorderCancelRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceSlotCreateRequest( @Valid @NotNull ServiceSlotContracts.CreateReq request)<br>public ServiceSlotContracts.SlotVO serviceSlotCreate( @Valid @RequestBody ServiceSlotCreateRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceSlotGetRequest(@NotBlank String slot)<br>public ServiceSlotContracts.SlotVO serviceSlotGet( @Valid @RequestBody ServiceSlotGetRequest r, HttpServletRequest http)<br>public record ServiceSlotReserveRequest( @Valid @NotNull ServiceSlotContracts.ClaimReq request)<br>public ServiceSlotContracts.ClaimVO serviceSlotReserve( @Valid @RequestBody ServiceSlotReserveRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceSlotTransitionRequest( @NotBlank String claim, long version, @NotBlank String action)<br>public ServiceSlotContracts.ClaimVO serviceSlotTransition( @Valid @RequestBody ServiceSlotTransitionRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record ServiceSlotDispatchDueRequest(@NotBlank String sourceNo)<br>public boolean serviceSlotDispatchDue( @Valid @RequestBody ServiceSlotDispatchDueRequest r, HttpServletRequest http)<br>public record BusinessOperationsEvidenceRequest( @Valid @NotNull BusinessOperationsContracts.Source source)<br>public BusinessOperationsContracts.Evidence businessOperationsEvidence( @Valid @RequestBody BusinessOperationsEvidenceRequest r, HttpServletRequest http)<br>public record BusinessOperationsProposeRequest( @Valid @NotNull BusinessOperationsContracts.HandoverReq request)<br>public BusinessOperationsContracts.HandoverVO businessOperationsPropose( @Valid @RequestBody BusinessOperationsProposeRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record BusinessOperationsHandoverRequest(@NotBlank String handover)<br>public BusinessOperationsContracts.HandoverVO businessOperationsHandover( @Valid @RequestBody BusinessOperationsHandoverRequest r, HttpServletRequest http)<br>public record BusinessOperationsAcceptRequest( @NotBlank String handover, @Valid @NotNull BusinessOperationsContracts.DecisionReq request)<br>public BusinessOperationsContracts.HandoverVO businessOperationsAccept( @Valid @RequestBody BusinessOperationsAcceptRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record BusinessOperationsCancelRequest( @NotBlank String handover, @Valid @NotNull BusinessOperationsContracts.DecisionReq request)<br>public BusinessOperationsContracts.HandoverVO businessOperationsCancel( @Valid @RequestBody BusinessOperationsCancelRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record BusinessOperationsOpenCaseRequest( @Valid @NotNull BusinessOperationsContracts.CaseReq request)<br>public BusinessOperationsContracts.CaseVO businessOperationsOpenCase( @Valid @RequestBody BusinessOperationsOpenCaseRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record BusinessOperationsCasesRequest(String status)<br>public List<BusinessOperationsContracts.CaseVO> businessOperationsCases( @Valid @RequestBody BusinessOperationsCasesRequest r, HttpServletRequest http)<br>public record BusinessOperationsRecoverCaseRequest( @NotBlank String caseNo, @Valid @NotNull BusinessOperationsContracts.DecisionReq request)<br>public BusinessOperationsContracts.CaseVO businessOperationsRecoverCase( @Valid @RequestBody BusinessOperationsRecoverCaseRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http)<br>public record BusinessOperationsResolveCaseRequest( @NotBlank String caseNo, @Valid @NotNull BusinessOperationsContracts.DecisionReq request)<br>public BusinessOperationsContracts.CaseVO businessOperationsResolveCase( @Valid @RequestBody BusinessOperationsResolveCaseRequest r, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest http) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessReturnApprovalController.java | ai.neargo.shop.food.api.biz.BizBusinessReturnApprovalController | public BizBusinessReturnApprovalController( BusinessReturnApprovalService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public ApprovalVO approve( @PathVariable String returnNo, @RequestBody ApproveReq req, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http)<br>public ApprovalVO get(@PathVariable String returnNo, HttpServletRequest http)<br>public ApprovalVO link( @PathVariable String returnNo, @RequestBody ReplacementReq req, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessReturnController.java | ai.neargo.shop.food.api.biz.BizBusinessReturnController | public BizBusinessReturnController(BusinessReturnService service,StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public ReturnVO create(@PathVariable @NotBlank @Size(max=32) String batchNo,@Valid @RequestBody CreateReq request, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http)<br>public ReturnVO get(@PathVariable @NotBlank @Size(max=32) String returnNo,HttpServletRequest http)<br>public ReturnVO cancel(@PathVariable @NotBlank @Size(max=32) String returnNo,@Valid @RequestBody CancelReq request, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessShareController.java | ai.neargo.shop.food.api.biz.BizBusinessShareController | public BizBusinessShareController(BusinessShareService service,StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public PlanVO create(@PathVariable @NotBlank @Size(max=32) String checkNo,@Valid @RequestBody CreateReq request, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http)<br>public PlanVO get(@PathVariable @NotBlank @Size(max=32) String checkNo, @PathVariable @NotBlank @Size(max=32) String planNo,HttpServletRequest http)<br>public PlanVO confirm(@PathVariable @NotBlank @Size(max=32) String checkNo, @PathVariable @NotBlank @Size(max=32) String planNo,@Valid @RequestBody ConfirmReq request, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http)<br>public PlanVO cancel(@PathVariable @NotBlank @Size(max=32) String checkNo, @PathVariable @NotBlank @Size(max=32) String planNo,@Valid @RequestBody CancelReq request, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCartController.java | ai.neargo.shop.food.api.biz.BizCartController | public BizCartController(CheckCartService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public CartResultVO getCart( @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo, HttpServletRequest request)<br>public CartResultVO createCart( @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo, @Valid @RequestBody CreateCartReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public CartVersionVO replaceLine( @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo, @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef, @Valid @RequestBody ReplaceCartLineReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public CartVersionVO archiveLine( @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo, @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public OperationResultVO archiveCart( @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public CartSessionGrantVO authorizeSession( @PathVariable("checkNo") @NotBlank @Size(max = 32) String checkNo, @Valid @RequestBody AuthorizeCartSessionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCatalogController.java | ai.neargo.shop.food.api.biz.BizCatalogController | public BizCatalogController(CatalogService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public CatalogVO read(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute CatalogQuery query, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCheckActionController.java | ai.neargo.shop.food.api.biz.BizCheckActionController | public BizCheckActionController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator)<br>public PageData<ActionLogVO> listActions(@PathVariable("checkNo") @NotBlank @Size(max=32) String check, @Valid @ModelAttribute ListCheckActionsQuery query,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCheckController.java | ai.neargo.shop.food.api.biz.BizCheckController | public BizCheckController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator)<br>public PageData<CheckVO> listChecks(@PathVariable("storeNo") @NotBlank @Size(max=32) String store, @Valid @ModelAttribute ListChecksQuery query,HttpServletRequest request)<br>public CheckDetailVO getCheck(@PathVariable("checkNo") @NotBlank @Size(max=32) String check,HttpServletRequest request)<br>public CheckResultVO openCheck(@PathVariable("storeNo") @NotBlank @Size(max=32) String store, @Valid @RequestBody OpenCheckReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public OperationVO clearCheck(@PathVariable("checkNo") @NotBlank @Size(max=32) String check, @Valid @RequestBody ClearCheckReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public OperationResultVO transferCheck(@PathVariable("checkNo") @NotBlank @Size(max=32) String check, @Valid @RequestBody TransferCheckReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public OperationResultVO combineTables(@PathVariable("checkNo") @NotBlank @Size(max=32) String check, @Valid @RequestBody CombineTablesReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCheckSeatController.java | ai.neargo.shop.food.api.biz.BizCheckSeatController | public BizCheckSeatController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator)<br>public CheckResultVO saveSeats(@PathVariable("checkNo") @NotBlank @Size(max=32) String check, @Valid @RequestBody SaveCheckSeatsReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizConfigRevisionController.java | ai.neargo.shop.food.api.biz.BizConfigRevisionController | public BizConfigRevisionController(ConfigRevisionService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public PublishConfigResultVO republishRevision( @PathVariable("revisionNo") @NotBlank @Size(max=32) String revisionNo, @Valid @RequestBody RepublishConfigReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PublishConfigResultVO publishRecipe( @PathVariable("recipeNo") @NotBlank @Size(max=32) String recipeNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PublishConfigResultVO publishBundle( @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PublishConfigResultVO publishMenu( @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PublishConfigResultVO publishStation( @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PublishConfigResultVO publishOptionGroup( @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PublishConfigResultVO publishModifier( @PathVariable("modifierNo") @NotBlank @Size(max=32) String modifierNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PageData<ConfigRevisionVO> listRevisions(@Valid @ModelAttribute ListConfigRevisionsQuery query, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java | ai.neargo.shop.food.api.biz.BizCourseController | public BizCourseController(KitchenTaskService service,StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public CourseVO create(@PathVariable @NotBlank @Size(max=32) String checkNo,@Valid @RequestBody CourseReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public List<CourseVO> courses(@PathVariable @NotBlank @Size(max=32) String checkNo,HttpServletRequest request)<br>public CourseVO act(@PathVariable @NotBlank @Size(max=32) String courseNo,@Valid @RequestBody CourseActionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public CourseVO fire(@PathVariable @NotBlank @Size(max=32) String courseNo,@Valid @RequestBody CourseFireReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningBatchQueryController.java | ai.neargo.shop.food.api.biz.BizDiningBatchQueryController | public BizDiningBatchQueryController(DiningBatchQueryService service,StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public PageData<DiningBatchSummaryVO> list(@PathVariable @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListDiningBatchesQuery query,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningDeliveryController.java | ai.neargo.shop.food.api.biz.BizDiningDeliveryController | public BizDiningDeliveryController( DiningDeliveryService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public DeliverySnapshot get( @PathVariable @NotBlank @Size(max = 32) String cartNo, HttpServletRequest request)<br>public DeliverySnapshot save( @PathVariable @NotBlank @Size(max = 32) String cartNo, @Valid @RequestBody SaveReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningHandoverController.java | ai.neargo.shop.food.api.biz.BizDiningHandoverController | public BizDiningHandoverController(KitchenTaskService service,StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public HandoverVO handover(@PathVariable @NotBlank @Size(max=32) String batchNo, @Valid @RequestBody HandoverReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public CustodyReceiptVO confirmPacking(@PathVariable @NotBlank @Size(max=32) String batchNo, @Valid @RequestBody PackingReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public List<CustodyReceiptVO> custodyReceipts(@PathVariable @NotBlank @Size(max=32) String batchNo, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningIdentityController.java | ai.neargo.shop.food.api.biz.BizDiningIdentityController | public BizDiningIdentityController( DiningIdentityService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public DeviceCredentialVO register( @Valid @RequestBody RegisterDeviceReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public DeviceVO device( @PathVariable @NotBlank @Size(max = 32) String deviceNo, HttpServletRequest request)<br>public DeviceVO revokeDevice( @PathVariable @NotBlank @Size(max = 32) String deviceNo, @Valid @RequestBody VersionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public SessionCredentialVO createParty( @Valid @RequestBody StaffPartyReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public PartyVO party( @PathVariable @NotBlank @Size(max = 32) String sessionNo, HttpServletRequest request)<br>public PartyVO revokeParty( @PathVariable @NotBlank @Size(max = 32) String sessionNo, @Valid @RequestBody VersionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningPickupVerificationController.java | ai.neargo.shop.food.api.biz.BizDiningPickupVerificationController | public BizDiningPickupVerificationController(DiningPickupVerificationService service,StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public PickupVerificationVO get(@PathVariable @NotBlank @Size(max=32) String batchNo, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public PickupVerificationVO verify(@PathVariable @NotBlank @Size(max=32) String batchNo, @Valid @RequestBody VerifyPickupReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningPricingController.java | ai.neargo.shop.food.api.biz.BizDiningPricingController | public BizDiningPricingController(DiningPricingService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public QuoteVO quote(@PathVariable @NotBlank @Size(max = 32) String storeNo, @Valid @RequestBody DiningQuoteReq body, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public QuoteVO get(@PathVariable @NotBlank @Size(max = 32) String quoteNo, @RequestParam @NotBlank @Size(max = 32) String cartNo, @RequestParam @Min(0) long version, HttpServletRequest request)<br>public QuoteVO confirm(@PathVariable @NotBlank @Size(max = 32) String quoteNo, @Valid @RequestBody DiningQuoteConfirmReq body, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningReturnController.java | ai.neargo.shop.food.api.biz.BizDiningReturnController | public BizDiningReturnController(DiningReturnService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public DiningReturnVO get(@PathVariable @NotBlank @Size(max = 32) String returnNo, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDishController.java | ai.neargo.shop.food.api.biz.BizDishController | public BizDishController(DishService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public DishResultVO saveDish(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("goodsNo") @NotBlank @Size(max=32) String goodsNo, @Valid @RequestBody SaveDishReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PageData<DishCatalogItemVO> listDishes(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListDishesQuery query, HttpServletRequest request)<br>public DishDetailResultVO getDish(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("goodsNo") @NotBlank @Size(max=32) String goodsNo, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizFoodKdsController.java | ai.neargo.shop.food.api.biz.BizFoodKdsController | public BizFoodKdsController( FoodKdsService service, FoodKdsStreamService streams, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public SubscriptionVO grant( @Valid @RequestBody GrantReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public SubscriptionVO revoke( @PathVariable @NotBlank @Size(max = 32) String subscriptionNo, @Valid @RequestBody RevokeReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public ReplayVO replay( @RequestParam @NotBlank @Size(max = 32) String subscriptionNo, @RequestParam(defaultValue = "0") @Min(0) long after, @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit, HttpServletRequest request)<br>public SseEmitter stream( @RequestParam @NotBlank @Size(max = 32) String subscriptionNo, @RequestParam(defaultValue = "0") @Min(0) long after, @RequestHeader(value = "Last-Event-ID", required = false) String last, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizFoodStationRuntimeController.java | ai.neargo.shop.food.api.biz.BizFoodStationRuntimeController | public BizFoodStationRuntimeController( FoodStationRuntimeService service, StoreDirectoryPort directory, FoodRequestScopeValidator validator)<br>public StateVO state( @PathVariable @NotBlank @Size(max = 32) String stationNo, HttpServletRequest request)<br>public CommandVO control( @PathVariable @NotBlank @Size(max = 32) String stationNo, @Valid @RequestBody ControlReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public VoicePoll claim( @PathVariable @NotBlank @Size(max = 32) String subscriptionNo, HttpServletRequest request)<br>public CommandVO acknowledge( @PathVariable @NotBlank @Size(max = 32) String subscriptionNo, @Valid @RequestBody VoiceReceipt req, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenReturnController.java | ai.neargo.shop.food.api.biz.BizKitchenReturnController | public BizKitchenReturnController(KitchenReturnService service,StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public ReturnKitchenVO get(@PathVariable @NotBlank @Size(max=32) String returnNo,HttpServletRequest request)<br>public HandledVO handle(@PathVariable @NotBlank @Size(max=32) String returnNo,@Valid @RequestBody HandleReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java | ai.neargo.shop.food.api.biz.BizKitchenTaskController | public BizKitchenTaskController(KitchenTaskService service,StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public PageData<TaskVO> queue(@PathVariable @NotBlank @Size(max=32) String storeNo, @RequestParam @NotBlank @Size(max=32) String stationNo, @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,HttpServletRequest request)<br>public EventVO start(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody TaskDeltaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public EventVO ready(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody TaskDeltaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public EventVO serve(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody TaskDeltaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public EventVO hold(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody TaskDeltaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public EventVO fire(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody TaskDeltaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public EventVO urge(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody TaskDeltaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public EventVO voidTask(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody TaskDeltaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public EventVO loss(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody TaskDeltaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public TicketVO remake(@PathVariable @NotBlank @Size(max=32) String taskNo, @Valid @RequestBody RemakeReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public List<EventVO> events(@PathVariable @NotBlank @Size(max=32) String taskNo,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizMenuChannelBindingController.java | ai.neargo.shop.food.api.biz.BizMenuChannelBindingController | public BizMenuChannelBindingController(MenuChannelBindingService service,StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public MenuChannelBindingVO createBinding(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateMenuChannelBindingReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public PageData<MenuChannelBindingVO> listBindings( @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @RequestParam(value="page",required=false) Integer page, @RequestParam(value="size",required=false) Integer size, @RequestParam(value="provider",required=false) String provider, @RequestParam(value="menuNo",required=false) String menuNo,HttpServletRequest request)<br>public MenuChannelBindingVO getBinding(@PathVariable("bindingNo") @NotBlank @Size(max=32) String number, HttpServletRequest request)<br>public MenuChannelBindingVO updateBinding(@PathVariable("bindingNo") @NotBlank @Size(max=32) String number, @Valid @RequestBody UpdateMenuChannelBindingReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizMenuController.java | ai.neargo.shop.food.api.biz.BizMenuController | public BizMenuController(MenuService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public CreateMenuResultVO createMenu(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateMenuReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public MenuResultVO updateMenu(@PathVariable("menuNo") @NotBlank @Size(max=32) String menuNo, @Valid @RequestBody UpdateMenuReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public Object replaceDefinition(@PathVariable("menuNo") @NotBlank @Size(max=32) String number, @Valid @RequestBody MenuDefinitionCommand req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public MenuResultVO disableMenu(@PathVariable("menuNo") @NotBlank @Size(max=32) String number, @Valid @RequestBody DisableMenuReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public MenuConfigResultVO getMenu(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String number, HttpServletRequest request)<br>public PageData<MenuVO> listMenus(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListMenusQuery query, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizModifierController.java | ai.neargo.shop.food.api.biz.BizModifierController | public BizModifierController(ModifierService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public CreateModifierResultVO createModifier(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateModifierReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public ModifierMutationVO updateModifier(@PathVariable("modifierNo") @NotBlank @Size(max=32) String modifierNo, @Valid @RequestBody UpdateModifierReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PageData<ModifierConfigVO> listModifiers(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListModifiersQuery query, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizOptionGroupController.java | ai.neargo.shop.food.api.biz.BizOptionGroupController | public BizOptionGroupController(OptionGroupService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public OptionGroupMutationVO replaceOptionGroupItems( @PathVariable("groupNo") @NotBlank @Size(max=32) String groupNo, @Valid @RequestBody ReplaceOptionGroupItemsReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public CreateOptionGroupResultVO createOptionGroup( @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateOptionGroupReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PageData<OptionGroupConfigVO> listOptionGroups( @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListOptionGroupsQuery query, HttpServletRequest request)<br>public OptionGroupResultVO getOptionGroup(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String configNo, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizOrderBatchController.java | ai.neargo.shop.food.api.biz.BizOrderBatchController | public BizOrderBatchController(DiningOrderService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public DiningOrderBatchVO place(@PathVariable @NotBlank @Size(max = 32) String storeNo, @Valid @RequestBody CreateStaffDiningOrderReq body, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public DiningOrderBatchVO cancel(@PathVariable @NotBlank @Size(max = 32) String batchNo, @Valid @RequestBody CancelDiningOrderReq body, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public DiningOrderBatchVO get(@PathVariable @NotBlank @Size(max = 32) String batchNo, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintConfigurationController.java | ai.neargo.shop.food.api.biz.BizPrintConfigurationController | public BizPrintConfigurationController(PrintPort printing,StoreDirectoryPort directory, FoodRequestScopeValidator validator)<br>public Configuration configuration(HttpServletRequest request)<br>public TemplateReq publish(@Valid @RequestBody TemplateReq req,HttpServletRequest request)<br>public RouteReq route(@Valid @RequestBody RouteReq req,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java | ai.neargo.shop.food.api.biz.BizPrintDeviceController | public BizPrintDeviceController( PrintPort printing, StoreDirectoryPort directory, FoodRequestScopeValidator validator)<br>public DeviceVO bind(@Valid @RequestBody DeviceReq req, HttpServletRequest request)<br>public record RevokeReq(@NotBlank @Size(max = 255) String reason)<br>public DeviceVO revoke( @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no, @Valid @RequestBody RevokeReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public record HeartbeatReq(boolean online)<br>public DeviceVO heartbeat( @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no, @Valid @RequestBody HeartbeatReq req, HttpServletRequest request)<br>public Claim claim( @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no, HttpServletRequest request)<br>public JobVO acknowledge( @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no, @Valid @RequestBody Acknowledge req, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java | ai.neargo.shop.food.api.biz.BizPrintJobController | public BizPrintJobController(FoodPrintService documents,PrintPort printing,StoreDirectoryPort directory, FoodRequestScopeValidator validator)<br>public List<JobVO> enqueue(@Valid @RequestBody PrintDocumentReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public List<JobVO> list(@RequestParam(value="afterJobNo",required=false) String after, @RequestParam(value="limit",defaultValue="20") int limit,HttpServletRequest request)<br>public JobVO get(@PathVariable("jobNo") @NotBlank @Size(max=32) String no,HttpServletRequest request)<br>public JobVO retry(@PathVariable("jobNo") @NotBlank @Size(max=32) String no,HttpServletRequest request)<br>public record ReprintReq(@NotBlank @Size(max=256) String reason)<br>public JobVO reprint(@PathVariable("jobNo") @NotBlank @Size(max=32) String no, @Valid @RequestBody ReprintReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizProductionConsumptionController.java | ai.neargo.shop.food.api.biz.BizProductionConsumptionController | public BizProductionConsumptionController( ProductionConsumptionService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public PageData<ConsumptionVO> list( @PathVariable @NotBlank @Size(max = 32) String storeNo, @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, @RequestParam(required = false) String skuNo, @RequestParam(required = false) String itemRef, @RequestParam(required = false) String status, HttpServletRequest request)<br>public ConsumptionVO consume( @PathVariable @NotBlank @Size(max = 32) String eventNo, HttpServletRequest request)<br>public ConsumptionVO adjust( @PathVariable @NotBlank @Size(max = 32) String taskNo, @RequestBody ProductionContracts.AdjustmentReq body, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public ConsumptionVO reverse( @PathVariable @NotBlank @Size(max = 32) String eventNo, @RequestBody ProductionContracts.ReverseReq body, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public ConsumptionVO get( @PathVariable @NotBlank @Size(max = 32) String eventNo, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java | ai.neargo.shop.food.api.biz.BizQueueController | public BizQueueController(QueueService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator)<br>public QueueVO issue(@Valid @RequestBody IssueReq body,@RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public PageData<QueueVO> list(@RequestParam LocalDate businessDate,@RequestParam(required=false) String status, @RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,HttpServletRequest request)<br>public QueueVO get(@PathVariable @NotBlank @Size(max=32) String ticketNo,HttpServletRequest request)<br>public QueueVO callNext(@RequestParam LocalDate businessDate,@RequestHeader("Idempotency-Key") String key, HttpServletRequest request)<br>public QueueVO call(@PathVariable @NotBlank @Size(max=32) String ticketNo, @Valid @RequestBody ChangeReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request)<br>public QueueVO cancel(@PathVariable @NotBlank @Size(max=32) String ticketNo, @Valid @RequestBody ChangeReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request)<br>public QueueVO noShow(@PathVariable @NotBlank @Size(max=32) String ticketNo, @Valid @RequestBody ChangeReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request)<br>public QueueVO seat(@PathVariable @NotBlank @Size(max=32) String ticketNo,@Valid @RequestBody SeatQueueReq body, HttpServletRequest request)<br>public QueueVO recover(@PathVariable @NotBlank @Size(max=32) String ticketNo,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizRecipeController.java | ai.neargo.shop.food.api.biz.BizRecipeController | public BizRecipeController(RecipeService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public RecipeResultVO createRecipe(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateRecipeReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public RecipeResultVO replaceDefinition(@PathVariable("recipeNo") @NotBlank @Size(max=32) String recipeNo, @Valid @RequestBody ReplaceRecipeDefinitionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public RecipeResultVO getRecipe(@PathVariable("recipeNo") @NotBlank @Size(max=32) String number, HttpServletRequest request)<br>public PageData<RecipeVO> listRecipes(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListRecipesQuery query, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizReservationController.java | ai.neargo.shop.food.api.biz.BizReservationController | public BizReservationController(ReservationService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator)<br>public PageData<ReservationVO> listReservations(@PathVariable("storeNo") @NotBlank @Size(max=32) String store, @Valid @ModelAttribute ListReservationsQuery query,HttpServletRequest request)<br>public DetailVO get(@PathVariable @NotBlank @Size(max=32) String reservationNo,HttpServletRequest request)<br>public DetailVO create(@PathVariable @NotBlank @Size(max=32) String storeNo,@Valid @RequestBody CreateReq body, @RequestHeader("Idempotency-Key") String key,HttpServletRequest request)<br>public DetailVO change(@PathVariable @NotBlank @Size(max=32) String reservationNo, @Valid @RequestBody ChangeReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request)<br>public DetailVO cancel(@PathVariable @NotBlank @Size(max=32) String reservationNo, @Valid @RequestBody TransitionReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request)<br>public DetailVO noShow(@PathVariable @NotBlank @Size(max=32) String reservationNo, @Valid @RequestBody TransitionReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request)<br>public DetailVO arrive(@PathVariable @NotBlank @Size(max=32) String reservationNo, @Valid @RequestBody ArriveReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizSalesRestrictionController.java | ai.neargo.shop.food.api.biz.BizSalesRestrictionController | public BizSalesRestrictionController(SalesRestrictionService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public SalesRestrictionVO manage(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody SalesRestrictionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public PageData<SalesRestrictionVO> list(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute SalesRestrictionQuery query, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizServiceCalendarController.java | ai.neargo.shop.food.api.biz.BizServiceCalendarController | public BizServiceCalendarController(StoreOperationService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public OperationVO saveCalendar(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("localDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date, @Valid @RequestBody SaveCalendarReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizSettlementController.java | ai.neargo.shop.food.api.biz.BizSettlementController | public BizSettlementController(DiningCheckoutService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator)<br>public DiningSettlementVO get(@PathVariable @NotBlank @Size(max = 32) String settlementNo, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizStationController.java | ai.neargo.shop.food.api.biz.BizStationController | public BizStationController(StationService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public StationMutationVO createStation(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateStationReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public StationMutationVO updateStation(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String number,@Valid @RequestBody UpdateStationReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public GetStationVO getStation(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("configNo") @NotBlank @Size(max=32) String number,HttpServletRequest request)<br>public PageData<StationVO> listStations(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @RequestParam(value="page",required=false) Integer page, @RequestParam(value="size",required=false) Integer size, HttpServletRequest request)<br>public OperationVO replaceRoutes(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("skuNo") @NotBlank @Size(max=32) String sku,@Valid @RequestBody ReplaceStationRoutesReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request)<br>public StationRoutesVO getRoutes(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @PathVariable("skuNo") @NotBlank @Size(max=32) String sku,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizStoreOperationPolicyController.java | ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController | public BizStoreOperationPolicyController(StoreOperationService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public StoreOperationPolicyResultVO getPolicy(@PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo, @Valid @ModelAttribute GetPolicyQuery query, HttpServletRequest request)<br>public OperationVO createPolicy(@PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo, @Valid @RequestBody CreatePolicyReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableAreaController.java | ai.neargo.shop.food.api.biz.BizTableAreaController | public BizTableAreaController(TableService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public PageData<TableAreaVO> listTableAreas(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListTableAreasQuery query, HttpServletRequest request)<br>public TableAreaResultVO createTableArea(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateTableAreaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public TableAreaResultVO saveTableArea(@PathVariable("areaNo") @NotBlank @Size(max=32) String areaNo, @Valid @RequestBody SaveTableAreaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public TableAreaResultVO setStatusTableArea(@PathVariable("areaNo") @NotBlank @Size(max=32) String areaNo, @Valid @RequestBody SetStatusTableAreaReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableController.java | ai.neargo.shop.food.api.biz.BizTableController | public BizTableController(TableService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public PageData<TableVO> listTables(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @ModelAttribute ListTablesQuery query, HttpServletRequest request)<br>public TableResultVO getTable(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo, HttpServletRequest request)<br>public TableResultVO createTable(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo, @Valid @RequestBody CreateTableReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public TableResultVO saveTable(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo, @Valid @RequestBody SaveTableReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public TableResultVO setStatusTable(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo, @Valid @RequestBody SetStatusTableReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public OperationResultVO archiveTable(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo, @Valid @RequestBody ArchiveTableReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableOperationController.java | ai.neargo.shop.food.api.biz.BizTableOperationController | public BizTableOperationController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator)<br>public OperationResultVO getTableOperation(@PathVariable("operationNo") @NotBlank @Size(max=32) String number, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableSharingController.java | ai.neargo.shop.food.api.biz.BizTableSharingController | public BizTableSharingController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator)<br>public CheckResultVO openSharedCheck(@PathVariable("tableNo") @NotBlank @Size(max=32) String table, @Valid @RequestBody OpenSharedCheckReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizTableTokenController.java | ai.neargo.shop.food.api.biz.BizTableTokenController | public BizTableTokenController(DiningSessionService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope)<br>public TableTokenVO rotateTableToken(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo, @Valid @RequestBody RotateTableTokenReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request)<br>public OperationResultVO revokeTableToken(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo, @Valid @RequestBody RevokeTableTokenReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpCartController.java | ai.neargo.shop.food.api.mp.MpCartController | public MpCartController(CheckCartService service)<br>public CartResultVO customerGetCart( @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo, @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request)<br>public CartResultVO customerCreateCart( @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo, @Valid @RequestBody CreateCustomerCartReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request)<br>public CartVersionVO customerReplaceLine( @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo, @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef, @Valid @RequestBody ReplaceCartLineReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request)<br>public CartVersionVO customerArchiveLine( @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo, @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpCatalogController.java | ai.neargo.shop.food.api.mp.MpCatalogController | public MpCatalogController(CatalogService service)<br>public CatalogVO read(@RequestParam("storeNo") @NotBlank @Size(max=32) String store, @RequestParam("orderSource") @NotBlank String source, @RequestParam("fulfillment") @NotBlank String fulfillment, @RequestParam(value="sessionNo", required=false) @Size(max=32) String session, @RequestHeader(value="X-Food-Session", required=false) @Size(max=128) String access, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningDeliveryController.java | ai.neargo.shop.food.api.mp.MpDiningDeliveryController | public MpDiningDeliveryController(DiningDeliveryService service)<br>public DeliverySnapshot get( @PathVariable @NotBlank @Size(max = 32) String cartNo, @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String session, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token)<br>public DeliverySnapshot save( @PathVariable @NotBlank @Size(max = 32) String cartNo, @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String session, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, @Valid @RequestBody SaveReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningDeviceSessionController.java | ai.neargo.shop.food.api.mp.MpDiningDeviceSessionController | public MpDiningDeviceSessionController( DiningIdentityService service, StoreDirectoryPort stores)<br>public SessionCredentialVO create( @PathVariable @NotBlank @Size(max = 32) String storeNo, @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device, @RequestHeader("X-Food-Device-Credential") @NotBlank @Size(max = 128) String credential, @Valid @RequestBody DeviceSessionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningPricingController.java | ai.neargo.shop.food.api.mp.MpDiningPricingController | public MpDiningPricingController(DiningPricingService service)<br>public QuoteVO quote(@PathVariable @NotBlank @Size(max = 32) String storeNo, @Valid @RequestBody DiningQuoteReq body, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, @RequestParam(required = false) @Size(max = 32) String sessionNo, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request)<br>public QuoteVO get(@PathVariable @NotBlank @Size(max = 32) String quoteNo, @RequestParam @NotBlank @Size(max = 32) String cartNo, @RequestParam @Min(0) long version, @RequestParam(required = false) @Size(max = 32) String sessionNo, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request)<br>public QuoteVO confirm(@PathVariable @NotBlank @Size(max = 32) String quoteNo, @Valid @RequestBody DiningQuoteConfirmReq body, @RequestParam(required = false) @Size(max = 32) String sessionNo, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningReturnController.java | ai.neargo.shop.food.api.mp.MpDiningReturnController | public MpDiningReturnController(DiningReturnService service)<br>public DiningReturnVO apply(@PathVariable @NotBlank @Size(max = 32) String storeNo, @Valid @RequestBody DiningReturnApplyReq body, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public DiningReturnVO get(@PathVariable @NotBlank @Size(max = 32) String returnNo, HttpServletRequest request)<br>public DiningReturnVO cancel(@PathVariable @NotBlank @Size(max = 32) String returnNo, @RequestBody(required = false) byte[] body, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpDiningSessionController.java | ai.neargo.shop.food.api.mp.MpDiningSessionController | public MpDiningSessionController(DiningSessionService service,StoreDirectoryPort stores)<br>public DiningSessionVO startSession(@Valid @RequestBody StartDiningSessionReq req, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key)<br>public OperationResultVO endSession(@PathVariable("sessionNo") @NotBlank @Size(max=32) String session, @RequestHeader("X-Food-Session") @NotBlank @Size(max=128) String access, @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpOrderBatchController.java | ai.neargo.shop.food.api.mp.MpOrderBatchController | public MpOrderBatchController(DiningOrderService service)<br>public DiningOrderBatchVO place(@PathVariable @NotBlank @Size(max = 32) String storeNo, @Valid @RequestBody CreateDiningOrderReq body, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, @RequestParam(required = false) @Size(max = 32) String sessionNo, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request)<br>public DiningOrderBatchVO get(@PathVariable @NotBlank @Size(max = 32) String batchNo, @RequestParam(required = false) @Size(max = 32) String sessionNo, @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/mp/MpSettlementController.java | ai.neargo.shop.food.api.mp.MpSettlementController | public MpSettlementController(DiningCheckoutService service)<br>public DiningSettlementVO create(@PathVariable @NotBlank @Size(max = 32) String batchNo, @Valid @RequestBody CreateDiningSettlementReq body, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public DiningSettlementVO get(@PathVariable @NotBlank @Size(max = 32) String settlementNo, HttpServletRequest request)<br>public DiningSettlementVO pay(@PathVariable @NotBlank @Size(max = 32) String settlementNo, @Valid @RequestBody PayDiningSettlementReq body, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request)<br>public DiningSettlementVO finalizeCheckout(@PathVariable @NotBlank @Size(max = 32) String settlementNo, HttpServletRequest request)<br>public DiningSettlementVO cancel(@PathVariable @NotBlank @Size(max = 32) String settlementNo, @Valid @RequestBody DiningSettlementVersionReq body, HttpServletRequest request) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/ops/OpsFoodSupportController.java | ai.neargo.shop.food.api.ops.OpsFoodSupportController | public OpsFoodSupportController(FoodSupportService service, StoreDirectoryPort stores)<br>public ReadinessVO readiness( @RequestParam @NotBlank @Size(max = 32) String entityNo, @RequestParam @NotBlank @Size(max = 32) String storeNo)<br>public List<CaseVO> cases( @RequestParam @NotBlank @Size(max = 32) String entityNo, @RequestParam @NotBlank @Size(max = 32) String storeNo, @RequestParam(required = false) String status)<br>public Evidence evidence( @RequestParam @NotBlank @Size(max = 32) String entityNo, @RequestParam @NotBlank @Size(max = 32) String storeNo, @RequestParam @NotBlank @Size(max = 16) String kind, @RequestParam @NotBlank @Size(max = 64) String sourceNo) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/config/FoodWebAutoConfiguration.java | ai.neargo.shop.food.config.FoodWebAutoConfiguration | public void addInterceptors(InterceptorRegistry registry)<br>public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/event/DiningKdsEventConsumer.java | ai.neargo.shop.food.event.DiningKdsEventConsumer | public DiningKdsEventConsumer(FoodKdsService service, ObjectMapper json)<br>public DiningKdsEventConsumer( FoodKdsService service, ObjectMapper json, Supplier<PrintPort> printing, Supplier<FoodPrintService> documents)<br>public String handlerId()<br>public boolean externalWorkflow()<br>public boolean supports(String type)<br>public DeliveryResult handle(OutboxEnvelope envelope) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/event/DiningKitchenAcceptedConsumer.java | ai.neargo.shop.food.event.DiningKitchenAcceptedConsumer | public DiningKitchenAcceptedConsumer(KitchenTaskService kitchen, ObjectMapper json)<br>public DiningKitchenAcceptedConsumer( KitchenTaskService kitchen, ObjectMapper json, Supplier<PrintPort> printing, Supplier<FoodPrintService> documents)<br>public String handlerId()<br>public boolean externalWorkflow()<br>public boolean supports(String type)<br>public DeliveryResult handle(OutboxEnvelope event) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/event/DiningProductionConsumer.java | ai.neargo.shop.food.event.DiningProductionConsumer | public DiningProductionConsumer(ProductionConsumptionService production, ObjectMapper json)<br>public String handlerId()<br>public boolean externalWorkflow()<br>public boolean supports(String type)<br>public DeliveryResult handle(OutboxEnvelope event) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBundleService.java | ai.neargo.shop.food.foodclient.RemoteBundleService | public RemoteBundleService(BundleInternalApi api)<br>public CreateBundleResultVO createBundle(String entity, String store, String actor, CreateBundleReq req, String key)<br>public BundleMutationVO replaceDefinition(String entity, String store, String number, String actor, ReplaceBundleDefinitionReq req, String key)<br>public GetBundleVO getBundle(String entity, String store, String number)<br>public PageData<BundleConfigVO> listBundles(String entity, String store, ListBundlesQuery query) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessCloseService.java | ai.neargo.shop.food.foodclient.RemoteBusinessCloseService | public RemoteBusinessCloseService(BusinessCloseInternalApi api)<br>public DayVO open(String entity,String store,String actor,LocalDate date,String key)<br>public ReportVO report(String entity,String store,String actor,PreviewReq request)<br>public CloseVO preview(String entity,String store,String actor,PreviewReq request,String key)<br>public CloseVO get(String entity,String store,String actor,String closeNo)<br>public CloseVO sign(String entity,String store,String actor,SignReq request,String key)<br>public OperationVO adjust(String entity,String store,String actor,AdjustmentReq request,String key)<br>public List<ExceptionVO> exceptions(String entity,String store,String actor,String closeNo)<br>public ExceptionVO proposeCarry(String entity,String store,String actor,String closeNo, String exceptionNo,ProposeCarryReq request,String key)<br>public ExceptionVO approveCarry(String entity,String store,String actor,String closeNo, String exceptionNo,ApproveCarryReq request,String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessOperationsService.java | ai.neargo.shop.food.foodclient.RemoteBusinessOperationsService | public RemoteBusinessOperationsService(BusinessOperationsInternalApi api)<br>public BusinessOperationsContracts.Evidence evidence( String entity, String store, BusinessOperationsContracts.Source source)<br>public BusinessOperationsContracts.HandoverVO propose( String entity, String store, String actor, BusinessOperationsContracts.HandoverReq request, String key)<br>public BusinessOperationsContracts.HandoverVO handover( String entity, String store, String handover)<br>public BusinessOperationsContracts.HandoverVO accept( String entity, String store, String actor, String handover, BusinessOperationsContracts.DecisionReq request, String key)<br>public BusinessOperationsContracts.HandoverVO cancel( String entity, String store, String actor, String handover, BusinessOperationsContracts.DecisionReq request, String key)<br>public BusinessOperationsContracts.CaseVO openCase( String entity, String store, String actor, BusinessOperationsContracts.CaseReq request, String key)<br>public List<BusinessOperationsContracts.CaseVO> cases( String entity, String store, String status)<br>public BusinessOperationsContracts.CaseVO recoverCase( String entity, String store, String actor, String caseNo, BusinessOperationsContracts.DecisionReq request, String key)<br>public BusinessOperationsContracts.CaseVO resolveCase( String entity, String store, String actor, String caseNo, BusinessOperationsContracts.DecisionReq request, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessReturnApprovalService.java | ai.neargo.shop.food.foodclient.RemoteBusinessReturnApprovalService | public RemoteBusinessReturnApprovalService(BusinessReturnApprovalInternalApi api)<br>public ApprovalVO approve(String e, String s, String a, String n, ApproveReq r, String k)<br>public ApprovalVO get(String e, String s, String a, String n)<br>public ApprovalVO linkReplacement( String e, String s, String a, String n, ReplacementReq r, String k)<br>public int recover(int limit) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessReturnService.java | ai.neargo.shop.food.foodclient.RemoteBusinessReturnService | public RemoteBusinessReturnService(BusinessReturnInternalApi api)<br>public ReturnVO create(String entity,String store,String actor,String batch,CreateReq request,String key)<br>public ReturnVO get(String entity,String store,String number)<br>public ReturnVO cancel(String entity,String store,String actor,String number,CancelReq request,String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteBusinessShareService.java | ai.neargo.shop.food.foodclient.RemoteBusinessShareService | public RemoteBusinessShareService(BusinessShareInternalApi api)<br>public PlanVO create(String entity,String store,String actor,String check,CreateReq request,String key)<br>public PlanVO get(String entity,String store,String check,String plan)<br>public PlanVO confirm(String entity,String store,String actor,String check,String plan, ConfirmReq request,String key)<br>public PlanVO cancel(String entity,String store,String actor,String check,String plan, CancelReq request,String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCatalogService.java | ai.neargo.shop.food.foodclient.RemoteCatalogService | public RemoteCatalogService(CatalogInternalApi api)<br>public CatalogVO scheduledCart(String entity, String store, String cartNo)<br>public CatalogVO read(String entity, String store, CatalogQuery query)<br>public CatalogVO publicCatalog(String store, String fulfillment)<br>public CatalogVO guest(String store, String session, String access, String buyer) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckCartService.java | ai.neargo.shop.food.foodclient.RemoteCheckCartService | public RemoteCheckCartService(CartInternalApi api)<br>public CartResultVO createCart(String entity, String store, String actor, CreateCartReq req, String key)<br>public CartResultVO getCart(String entity, String store, String cart)<br>public CartVersionVO replaceLine(String entity, String store, String cart, String source, String actor, ReplaceCartLineReq req, String key)<br>public CartVersionVO archiveLine(String entity, String store, String cart, String source, String actor, CartVersionReq req, String key)<br>public OperationResultVO archiveCart(String entity, String store, String cart, String actor, CartVersionReq req, String key)<br>public CartSessionGrantVO authorizeSession(String entity, String store, String check, String actor, AuthorizeCartSessionReq req, String key)<br>public CartResultVO customerCreateCart(String store, CartAccess access, CreateCustomerCartReq req, String key)<br>public CartResultVO customerGetCart(String cart, CartAccess access)<br>public CartVersionVO customerReplaceLine(String cart, String source, CartAccess access, ReplaceCartLineReq req, String key)<br>public CartVersionVO customerArchiveLine(String cart, String source, CartAccess access, CartVersionReq req, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckCollaborationService.java | ai.neargo.shop.food.foodclient.RemoteCheckCollaborationService | public RemoteCheckCollaborationService(BusinessOperationsInternalApi api)<br>public CheckCollaborationContracts.ReceivablePreview previewReceivable( String entity, String store, String check)<br>public String sourceHash(String entity, String store, String check)<br>public OperationVO releaseService( String entity, String store, String actor, String check, long version, String reason, String key)<br>public String currentLineOwner(String entity, String store, String foodLineNo)<br>public CheckCollaborationContracts.MoveVO moveBatches( String entity, String store, String actor, CheckCollaborationContracts.MoveReq request, String key)<br>public CheckCollaborationContracts.LineMoveVO moveLines( String entity, String store, String actor, CheckCollaborationContracts.LineMoveReq request, String key)<br>public CheckCollaborationContracts.FreezeVO freeze( String entity, String store, String actor, String check, CheckCollaborationContracts.FreezeReq request, String key)<br>public CheckCollaborationContracts.FreezeVO release( String entity, String store, String actor, String freeze, long version, String reason, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteCheckService.java | ai.neargo.shop.food.foodclient.RemoteCheckService | public RemoteCheckService(CheckInternalApi api)<br>public CheckResultVO openCheck(String entity,String store,String actor,OpenCheckReq req,String key)<br>public PageData<CheckVO> listChecks(String entity,String store,ListChecksQuery query)<br>public CheckDetailVO getCheck(String entity,String store,String check)<br>public CheckResultVO saveSeats(String entity,String store,String check,String actor,SaveCheckSeatsReq req,String key)<br>public OperationVO clearCheck(String entity,String store,String check,String actor,ClearCheckReq req,String key)<br>public OperationResultVO transferCheck(String entity,String store,String check,String actor,TransferCheckReq req,String key)<br>public OperationResultVO combineTables(String entity,String store,String check,String actor,CombineTablesReq req,String key)<br>public CheckResultVO openSharedCheck(String entity,String store,String table,String actor,OpenSharedCheckReq req,String key)<br>public OperationResultVO getTableOperation(String entity,String store,String number)<br>public PageData<ActionLogVO> listActions(String entity,String store,String check,ListCheckActionsQuery query) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteConfigRevisionService.java | ai.neargo.shop.food.foodclient.RemoteConfigRevisionService | public RemoteConfigRevisionService(ConfigRevisionInternalApi api)<br>public PublishConfigResultVO republishRevision(String entity, String store, String revision, String actor, RepublishConfigReq req, String key)<br>public PublishConfigResultVO publishMenu(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishStation(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishRecipe(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishBundle(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishModifier(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PublishConfigResultVO publishOptionGroup(String entity, String store, String number, String actor, PublishConfigReq req, String key)<br>public PageData<ConfigRevisionVO> listRevisions(String entity, String store, ListConfigRevisionsQuery query)<br>public ConfigRevisionContentVO getRevision(String entity, String store, String number)<br>public ConfigRevisionContentVO resolveRevision(String entity, String store, String kind, String number, OffsetDateTime asOf) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningBatchQueryService.java | ai.neargo.shop.food.foodclient.RemoteDiningBatchQueryService | public RemoteDiningBatchQueryService(DiningBatchQueryInternalApi api)<br>public PageData<DiningBatchSummaryVO> list(String entity,String store,String actor, ListDiningBatchesQuery input) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningCheckoutService.java | ai.neargo.shop.food.foodclient.RemoteDiningCheckoutService | public RemoteDiningCheckoutService(DiningCheckoutInternalApi api)<br>public DiningSettlementVO recoverOriginal(String entity, String store, String number)<br>public DiningSettlementVO customerCreate(CartAccess a, String batch, CreateDiningSettlementReq r, String key)<br>public DiningSettlementVO customerGet(CartAccess a, String no)<br>public DiningSettlementVO customerPay(CartAccess a, String no, PayDiningSettlementReq r, String key)<br>public DiningSettlementVO customerQuery(CartAccess a, String no)<br>public DiningSettlementVO customerFinalize(CartAccess a, String no)<br>public DiningSettlementVO customerCancel(CartAccess a, String no, DiningSettlementVersionReq r)<br>public DiningSettlementVO staffGet(String entity, String store, String actor, String no) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningDeliveryService.java | ai.neargo.shop.food.foodclient.RemoteDiningDeliveryService | public RemoteDiningDeliveryService(DiningDeliveryInternalApi api)<br>public DeliverySnapshot saveStaff(String e, String s, String a, String c, SaveReq r, String k)<br>public DeliverySnapshot saveCustomer(String c, CartAccess a, SaveReq r, String k)<br>public DeliverySnapshot getStaff(String e, String s, String c)<br>public DeliverySnapshot getCustomer(String c, CartAccess a)<br>public DeliverySnapshot get(String e, String s, String c) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningIdentityService.java | ai.neargo.shop.food.foodclient.RemoteDiningIdentityService | public RemoteDiningIdentityService(DiningIdentityInternalApi api)<br>public DeviceVO device(String e, String s, String no)<br>public DeviceCredentialVO registerDevice( String e, String s, String a, RegisterDeviceReq r, String k)<br>public DeviceVO revokeDevice(String e, String s, String a, String no, VersionReq r, String k)<br>public SessionCredentialVO createDeviceSession( String e, String s, String no, String credential, DeviceSessionReq r, String k)<br>public SessionCredentialVO createStaffParty( String e, String s, String a, StaffPartyReq r, String k)<br>public PartyVO revokeParty(String e, String s, String a, String no, VersionReq r, String k)<br>public PartyVO party(String e, String s, String no)<br>public void validateDeviceSession(String no) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningOrderAuthorityService.java | ai.neargo.shop.food.foodclient.RemoteDiningOrderAuthorityService | public RemoteDiningOrderAuthorityService(DiningOrderAuthorityInternalApi api)<br>public Delivery delivery(Command command)<br>public Party party(Command command)<br>public Plan demandPlan(Command command, String orderNo, String subOrderNo) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningOrderService.java | ai.neargo.shop.food.foodclient.RemoteDiningOrderService | public RemoteDiningOrderService(DiningOrderInternalApi api)<br>public DiningOrderBatchVO recoverOriginal(String entity, String store, String number)<br>public Snapshot authorizeCancellation(Command command, String key, String fulfillment)<br>public DiningOrderBatchVO staffCancel(String entity, String store, String actor, String batch, CancelDiningOrderReq request, String key)<br>public DiningOrderBatchVO staffPlace(String entity, String store, String actor, CreateStaffDiningOrderReq request, String key)<br>public DiningOrderBatchVO customerPlace(String store, CartAccess access, CreateDiningOrderReq r, String key)<br>public DiningOrderBatchVO customerGet(CartAccess access, String batch)<br>public DiningOrderBatchVO staffGet(String entity, String store, String actor, String batch) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningPickupVerificationService.java | ai.neargo.shop.food.foodclient.RemoteDiningPickupVerificationService | public RemoteDiningPickupVerificationService(DiningPickupVerificationInternalApi api)<br>public PickupVerificationVO verify(String entity,String store,String actor,String batch, VerifyPickupReq req,String key)<br>public PickupVerificationVO get(String entity,String store,String actor,String batch,String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningPricingFactsService.java | ai.neargo.shop.food.foodclient.RemoteDiningPricingFactsService | public RemoteDiningPricingFactsService(PricingSourceInternalApi api)<br>public DiningPriceFacts staff(String entity, String store, String cart, long version)<br>public DiningPriceFacts customer(String cart, CartAccess access, long version) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningPricingService.java | ai.neargo.shop.food.foodclient.RemoteDiningPricingService | public RemoteDiningPricingService(DiningPricingInternalApi api)<br>public QuoteVO staffQuote(String entity, String store, String actor, DiningQuoteReq r, String key)<br>public QuoteVO customerQuote(String store, CartAccess access, DiningQuoteReq r, String key)<br>public QuoteVO staffGet(String entity, String store, String actor, String cart, long version, String quote)<br>public QuoteVO customerGet(CartAccess access, String cart, long version, String quote)<br>public QuoteVO staffConfirm(String entity, String store, String actor, String quote, DiningQuoteConfirmReq r)<br>public QuoteVO customerConfirm(CartAccess access, String quote, DiningQuoteConfirmReq r) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningReturnService.java | ai.neargo.shop.food.foodclient.RemoteDiningReturnService | public RemoteDiningReturnService(DiningReturnInternalApi api)<br>public DiningReturnVO recoverOriginal(String entity, String store, String number)<br>public DiningReturnVO customerApply(String store, CartAccess access, DiningReturnApplyReq r, String key)<br>public DiningReturnVO customerGet(CartAccess access, String number)<br>public DiningReturnVO customerCancel(CartAccess access, String number)<br>public DiningReturnVO staffGet(String entity, String store, String actor, String number) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningSessionService.java | ai.neargo.shop.food.foodclient.RemoteDiningSessionService | public RemoteDiningSessionService(DiningSessionInternalApi api)<br>public TableTokenVO rotateTableToken(String e,String s,String t,String a,RotateTableTokenReq r,String k)<br>public OperationResultVO revokeTableToken(String e,String s,String t,String a,RevokeTableTokenReq r,String k)<br>public DiningSessionTableVO resolveTableToken(String token)<br>public DiningSessionVO startSession(String e,String s,String b,StartDiningSessionReq r,String k)<br>public OperationResultVO endSession(String session,String token,String buyer,String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDiningTransactionRecoveryService.java | ai.neargo.shop.food.foodclient.RemoteDiningTransactionRecoveryService | public RemoteDiningTransactionRecoveryService(DiningTransactionRecoveryInternalApi api)<br>public Report recoverDue(int limit) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteDishService.java | ai.neargo.shop.food.foodclient.RemoteDishService | public RemoteDishService(DishInternalApi api)<br>public DishResultVO saveDish(String entity, String store, String goods, String actor, SaveDishReq req, String key)<br>public PageData<DishCatalogItemVO> listDishes(String entity, String store, ListDishesQuery query)<br>public DishDetailResultVO getDish(String entity, String store, String goods) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodFeeSourceService.java | ai.neargo.shop.food.foodclient.RemoteFoodFeeSourceService | public RemoteFoodFeeSourceService(BusinessOperationsInternalApi api)<br>public FoodFeeSourceContracts.BindingVO bind( String entity, String store, String actor, FoodFeeSourceContracts.BindReq request, String key)<br>public List<FoodFeeSourceContracts.BindingVO> bindings( String entity, String store, String check) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodKdsService.java | ai.neargo.shop.food.foodclient.RemoteFoodKdsService | public RemoteFoodKdsService(FoodKdsInternalApi api)<br>public SubscriptionVO grant(String e, String s, String a, GrantReq r, String k)<br>public SubscriptionVO revoke(String e, String s, String a, String no, RevokeReq r, String k)<br>public ReplayVO replay(String e, String s, String a, String no, long after, int limit)<br>public void append(EventDraft e) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodPrintService.java | ai.neargo.shop.food.foodclient.RemoteFoodPrintService | public RemoteFoodPrintService(FoodPrintInternalApi api)<br>public List<JobVO> enqueue(Scope scope,PrintDocumentReq request,String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodStationRuntimeService.java | ai.neargo.shop.food.foodclient.RemoteFoodStationRuntimeService | public RemoteFoodStationRuntimeService(FoodStationRuntimeInternalApi api)<br>public StateVO state(String e, String s, String station)<br>public void requireRunning(String e, String s, String station)<br>public CommandVO control( String e, String s, String a, String station, ControlReq req, String key)<br>public VoiceClaim claim(String e, String s, String a, String subscription)<br>public CommandVO acknowledge( String e, String s, String a, String subscription, VoiceReceipt receipt) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteFoodSupportService.java | ai.neargo.shop.food.foodclient.RemoteFoodSupportService | public RemoteFoodSupportService(FoodSupportInternalApi api)<br>public ReadinessVO readiness()<br>public List<CaseVO> cases(String entityNo, String storeNo, String status)<br>public Evidence evidence(String entityNo, String storeNo, Source source) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteKitchenReturnService.java | ai.neargo.shop.food.foodclient.RemoteKitchenReturnService | public RemoteKitchenReturnService(KitchenReturnInternalApi api)<br>public ReturnKitchenVO get(String entity,String store,String number)<br>public HandledVO handle(String entity,String store,String actor,String number,HandleReq req,String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteKitchenTaskService.java | ai.neargo.shop.food.foodclient.RemoteKitchenTaskService | public RemoteKitchenTaskService(KitchenInternalApi api)<br>public FrozenRoutes freezeRoutes(String entity,String store,List<String> skus)<br>public TicketVO accepted(String entity,String store,String batch,String key)<br>public List<TaskVO> queue(String entity,String store,String station,String batch)<br>public EventVO act(String entity,String store,String actor,String task,TaskActionReq req,String key)<br>public TicketVO remake(String entity,String store,String actor,String task,RemakeReq req,String key)<br>public List<EventVO> events(String entity,String store,String task)<br>public CourseVO createCourse(String entity,String store,String actor,String check,CourseReq req,String key)<br>public List<CourseVO> courses(String entity,String store,String check)<br>public CourseVO actCourse(String entity,String store,String actor,String course,CourseActionReq req,String key)<br>public HandoverVO handover(String entity,String store,String actor,String batch,HandoverReq req, String key)<br>public CustodyReceiptVO confirmPacking(String entity,String store,String actor,String batch, PackingReq req,String key)<br>public List<CustodyReceiptVO> custodyReceipts(String entity,String store,String batch) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteMenuChannelBindingService.java | ai.neargo.shop.food.foodclient.RemoteMenuChannelBindingService | public RemoteMenuChannelBindingService(MenuChannelBindingInternalApi api)<br>public MenuChannelBindingVO createBinding(String entity,String store,String actor, CreateMenuChannelBindingReq req,String key)<br>public MenuChannelBindingVO updateBinding(String entity,String store,String number,String actor, UpdateMenuChannelBindingReq req,String key)<br>public MenuChannelBindingVO getBinding(String entity,String store,String number)<br>public PageData<MenuChannelBindingVO> listBindings(String entity,String store,Integer page,Integer size, String provider,String menuNo) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteMenuService.java | ai.neargo.shop.food.foodclient.RemoteMenuService | public RemoteMenuService(MenuInternalApi api)<br>public CreateMenuResultVO createMenu(String entity, String store, String actor, CreateMenuReq req, String key)<br>public MenuResultVO updateMenu(String entity, String store, String menu, String actor, UpdateMenuReq req, String key)<br>public MenuScheduleResultVO replaceSchedule(String entity, String store, String menu, String actor, ReplaceMenuScheduleReq req, String key)<br>public MenuCompositionResultVO replaceComposition(String entity, String store, String menu, String actor, ReplaceMenuCompositionReq req, String key)<br>public MenuContextsResultVO replaceContexts(String entity, String store, String menu, String actor, ReplaceMenuContextsReq req, String key)<br>public MenuResultVO disableMenu(String entity, String store, String menu, String actor, DisableMenuReq req, String key)<br>public MenuConfigResultVO getMenu(String entity, String store, String menu)<br>public PageData<MenuVO> listMenus(String entity, String store, ListMenusQuery query) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteModifierService.java | ai.neargo.shop.food.foodclient.RemoteModifierService | public RemoteModifierService(ModifierInternalApi api)<br>public CreateModifierResultVO createModifier(String entity, String store, String actor, CreateModifierReq req, String key)<br>public ModifierMutationVO updateModifier(String entity, String store, String number, String actor, UpdateModifierReq req, String key)<br>public PageData<ModifierConfigVO> listModifiers(String entity, String store, ListModifiersQuery query) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteOptionGroupService.java | ai.neargo.shop.food.foodclient.RemoteOptionGroupService | public RemoteOptionGroupService(OptionGroupInternalApi api)<br>public OptionGroupMutationVO replaceOptionGroupItems(String entity, String store, String group, String actor, ReplaceOptionGroupItemsReq req, String key)<br>public CreateOptionGroupResultVO createOptionGroup(String entity, String store, String actor, CreateOptionGroupReq req, String key)<br>public OptionGroupResultVO getOptionGroup(String entity, String store, String config)<br>public PageData<OptionGroupConfigVO> listOptionGroups(String entity, String store, ListOptionGroupsQuery query) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteProductionConsumptionService.java | ai.neargo.shop.food.foodclient.RemoteProductionConsumptionService | public RemoteProductionConsumptionService(ProductionConsumptionInternalApi api)<br>public ConsumptionVO consume(String entity, String store, String actor, String event)<br>public PageData<ConsumptionVO> list( String entity, String store, String actor, ListQuery query)<br>public ConsumptionVO get(String entity, String store, String actor, String event)<br>public ConsumptionVO adjust( String e, String s, String a, String task, ProductionContracts.AdjustmentReq req, String key)<br>public ConsumptionVO reverse( String e, String s, String a, String event, ProductionContracts.ReverseReq req, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteQueueNotificationService.java | ai.neargo.shop.food.foodclient.RemoteQueueNotificationService | public RemoteQueueNotificationService(BusinessOperationsInternalApi api)<br>public List<QueueNotificationService.NoticeVO> list( String entity, String store, String ticket)<br>public QueueNotificationService.NoticeVO recover( String entity, String store, String actor, String notice, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteQueueService.java | ai.neargo.shop.food.foodclient.RemoteQueueService | public RemoteQueueService(QueueInternalApi api)<br>public QueueVO issue(String entity,String store,String actor,IssueReq request,String key)<br>public PageData<QueueVO> list(String entity,String store,String actor,Query query)<br>public QueueVO get(String entity,String store,String actor,String number)<br>public QueueVO callNext(String entity,String store,String actor,LocalDate date,String key)<br>public QueueVO change(String entity,String store,String actor,String number,String action,ChangeReq request,String key)<br>public QueueVO seat(String entity,String store,String actor,String number,SeatQueueReq request)<br>public QueueVO recoverSeating(String entity,String store,String actor,String number) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteRecipeService.java | ai.neargo.shop.food.foodclient.RemoteRecipeService | public RemoteRecipeService(RecipeInternalApi api)<br>public RecipeResultVO createRecipe(String entity, String store, String actor, CreateRecipeReq req, String key)<br>public RecipeResultVO replaceDefinition(String entity, String store, String number, String actor, ReplaceRecipeDefinitionReq req, String key)<br>public RecipeResultVO getRecipe(String entity, String store, String number)<br>public PageData<RecipeVO> listRecipes(String entity, String store, ListRecipesQuery query) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteReservationPreorderService.java | ai.neargo.shop.food.foodclient.RemoteReservationPreorderService | public RemoteReservationPreorderService(BusinessOperationsInternalApi api)<br>public ReservationPreorderContracts.PreorderVO save( String entity, String store, String actor, String reservation, ReservationPreorderContracts.SaveReq request, String key)<br>public ReservationPreorderContracts.PreorderVO get( String entity, String store, String reservation, String preorder)<br>public CartResultVO prepareCart( String entity, String store, String actor, String reservation, String preorder, long version, String key)<br>public DiningOrderBatchVO convert( String entity, String store, String actor, String reservation, String preorder, ReservationPreorderContracts.ConvertReq request, String key)<br>public ReservationPreorderContracts.PreorderVO cancel( String entity, String store, String actor, String reservation, String preorder, long version, String reason, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteReservationService.java | ai.neargo.shop.food.foodclient.RemoteReservationService | public RemoteReservationService(ReservationInternalApi api)<br>public PageData<ReservationVO> listReservations(String entity,String store,ListReservationsQuery query)<br>public DetailVO get(String entity,String store,String number)<br>public DetailVO create(String entity,String store,String actor,CreateReq request,String key)<br>public DetailVO change(String entity,String store,String actor,String number,ChangeReq request,String key)<br>public DetailVO cancel(String entity,String store,String actor,String number,TransitionReq request,String key)<br>public DetailVO noShow(String entity,String store,String actor,String number,TransitionReq request,String key)<br>public DetailVO arrive(String entity,String store,String actor,String number,ArriveReq request,String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteSalesRestrictionService.java | ai.neargo.shop.food.foodclient.RemoteSalesRestrictionService | public RemoteSalesRestrictionService(SalesRestrictionInternalApi api)<br>public SalesRestrictionVO manage(String entity, String store, String actor, SalesRestrictionReq req, String key)<br>public PageData<SalesRestrictionVO> list(String entity, String store, SalesRestrictionQuery q)<br>public SalesRestrictionEvaluationVO evaluate(String entity, String store, String sku, String source, String fulfillment) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteServiceFormService.java | ai.neargo.shop.food.foodclient.RemoteServiceFormService | public RemoteServiceFormService(BusinessOperationsInternalApi api)<br>public ServiceFormContracts.OrganizationVO registerOrganization( String entity, String store, String actor, ServiceFormContracts.OrganizationReq request, String key)<br>public ServiceFormContracts.FormVO book( String entity, String store, String actor, ServiceFormContracts.BookReq request, String key)<br>public ServiceFormContracts.FormVO get(String entity, String store, String form)<br>public ServiceFormContracts.FormVO revise( String entity, String store, String actor, String form, ServiceFormContracts.ReviseReq request, String key)<br>public ServiceFormContracts.FormVO enter( String entity, String store, String actor, String form, ServiceFormContracts.EnterReq request, String key)<br>public ServiceFormContracts.FormVO leave( String entity, String store, String actor, String form, ServiceFormContracts.VersionReq request, String key)<br>public ServiceFormContracts.FormVO cancel( String entity, String store, String actor, String form, ServiceFormContracts.VersionReq request, String key)<br>public ServiceFormContracts.FormVO noShow( String entity, String store, String actor, String form, ServiceFormContracts.VersionReq request, String key)<br>public ServiceFormContracts.FormVO bindBatch( String entity, String store, String actor, String form, ServiceFormContracts.BatchReq request, String key)<br>public List<ServiceFormContracts.AttendeeVO> attendees( String entity, String store, String form)<br>public ServiceFormContracts.AttendeeVO admit( String entity, String store, String actor, String form, String attendee, ServiceFormContracts.VersionReq request, String key)<br>public ServiceFormContracts.AttendeeVO exit( String entity, String store, String actor, String form, String attendee, ServiceFormContracts.VersionReq request, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteServiceSlotService.java | ai.neargo.shop.food.foodclient.RemoteServiceSlotService | public RemoteServiceSlotService(BusinessOperationsInternalApi api)<br>public ServiceSlotContracts.SlotVO create( String entity, String store, String actor, ServiceSlotContracts.CreateReq request, String key)<br>public ServiceSlotContracts.SlotVO get(String entity, String store, String slot)<br>public ServiceSlotContracts.ClaimVO reserve( String entity, String store, String actor, ServiceSlotContracts.ClaimReq request, String key)<br>public ServiceSlotContracts.ClaimVO transition( String entity, String store, String actor, String claim, long version, String action, String key)<br>public boolean dispatchDue(String entity, String store, String sourceNo) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteStationService.java | ai.neargo.shop.food.foodclient.RemoteStationService | public RemoteStationService(StationInternalApi api)<br>public StationMutationVO createStation(String entity,String store,String actor, CreateStationReq req,String key)<br>public StationMutationVO updateStation(String entity,String store,String station,String actor, UpdateStationReq req,String key)<br>public GetStationVO getStation(String entity,String store,String station)<br>public PageData<StationVO> listStations(String entity,String store,Integer page,Integer size)<br>public OperationVO replaceRoutes(String entity,String store,String sku,String actor, ReplaceStationRoutesReq req,String key)<br>public StationRoutesVO getRoutes(String entity,String store,String sku) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteStoreOperationService.java | ai.neargo.shop.food.foodclient.RemoteStoreOperationService | public RemoteStoreOperationService(StoreOperationInternalApi api, StorePolicyPublicationInternalApi writes, ServiceCalendarInternalApi calendars)<br>public StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query)<br>public OperationVO createPolicy(String entityNo, String storeNo, String actorNo, CreatePolicyReq req, String key)<br>public OperationVO saveCalendar(String entityNo, String storeNo, LocalDate date, String actorNo, SaveCalendarReq req, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteTableService.java | ai.neargo.shop.food.foodclient.RemoteTableService | public RemoteTableService(TableInternalApi api)<br>public PageData<TableAreaVO> listTableAreas(String entity, String store, ListTableAreasQuery query)<br>public TableAreaResultVO createTableArea(String entity, String store, String actor, CreateTableAreaReq req, String key)<br>public TableAreaResultVO saveTableArea(String entity, String store, String area, String actor, SaveTableAreaReq req, String key)<br>public TableAreaResultVO setStatusTableArea(String entity, String store, String area, String actor, SetStatusTableAreaReq req, String key)<br>public PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query)<br>public TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo)<br>public TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req, String key)<br>public TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SaveTableReq req, String key)<br>public TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SetStatusTableReq req, String key)<br>public OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, ArchiveTableReq req, String key) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/job/DiningTransactionRecoveryJob.java | ai.neargo.shop.food.job.DiningTransactionRecoveryJob | public DiningTransactionRecoveryJob(DiningTransactionRecoveryService recovery)<br>public String name()<br>public JobResult run(JobInvocation invocation)<br>public JobDeclaration diningTransactionRecoveryDeclaration() |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/port/FoodPricingSourceFactsPortImpl.java | ai.neargo.shop.food.port.FoodPricingSourceFactsPortImpl | public FoodPricingSourceFactsPortImpl(DiningPricingFactsService service)<br>public FoodPricingSourceFactsPortImpl( DiningPricingFactsService service, Supplier<DiningOrderService> orders)<br>public FoodPricingSourceFactsPortImpl( DiningPricingFactsService service, Supplier<DiningOrderService> orders, Supplier<DiningOrderAuthorityService> authority)<br>public Delivery delivery(Command command)<br>public Party party(Command command)<br>public Plan demandPlan(Command command, String orderNo, String subOrderNo)<br>public Snapshot cancellationForStore(Command command, String key, String fulfillment)<br>public String sourceSystem()<br>public Snapshot staff(Scope scope, PricingSourceRef source)<br>public Snapshot customer(PricingSourceRef source, Credentials credentials) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/service/FoodKdsStreamService.java | ai.neargo.shop.food.service.FoodKdsStreamService | SseEmitter subscribe( String entity, String store, String actor, String subscription, long cursor) |
| backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/service/impl/FoodKdsStreamServiceImpl.java | ai.neargo.shop.food.service.impl.FoodKdsStreamServiceImpl | public FoodKdsStreamServiceImpl(FoodKdsService service)<br>public SseEmitter subscribe( String entity, String store, String actor, String subscription, long cursor)<br>public void shutdown() |
| backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPricingFeeRuleController.java | ai.neargo.shop.portal.biz.BizPricingFeeRuleController | public BizPricingFeeRuleController(PricingFeeRuleService rules)<br>public Rule get(@PathVariable String ruleNo, HttpServletRequest http)<br>public Rule save( @PathVariable String ruleNo, @Valid @RequestBody PricingFeeRuleService.SaveReq req, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http) |
| backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPricingFeeScopeController.java | ai.neargo.shop.portal.biz.BizPricingFeeScopeController | public record ApplyReq(long expectedSubVersion, String sourceHash, String reason)<br>public BizPricingFeeScopeController( PricingFeeScopePort fees, IndustryOrderAmendmentPort amendments)<br>public List<PricingFeeScopePort.AdjustmentAuthority> pending( @PathVariable String scopeNo, HttpServletRequest http)<br>public IndustryOrderAmendmentPort.Receipt apply( @PathVariable String scopeNo, @PathVariable String applicationNo, @RequestBody ApplyReq req, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http) |
| backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalIndustryAfterSaleController.java | ai.neargo.shop.portal.internal.InternalIndustryAfterSaleController | public InternalIndustryAfterSaleController(IndustryAfterSalePort afterSales, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> apply(@RequestBody IndustryAfterSalePort.Apply request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> queryApply(@RequestBody IndustryAfterSalePort.QueryApply query, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> get(@RequestBody IndustryAfterSalePort.Reference reference, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> cancel(@RequestBody IndustryAfterSalePort.Reference reference, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalIndustryCheckoutEndpoint.java | ai.neargo.shop.portal.internal.InternalIndustryCheckoutEndpoint | public InternalIndustryCheckoutEndpoint(IndustryCheckoutPort checkout, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> get(@RequestBody IndustryCheckoutInternalApi.Reference request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> pay(@RequestBody IndustryCheckoutPort.PayCommand command, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> query(@RequestBody IndustryCheckoutInternalApi.Reference request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> finalizeCheckout(@RequestBody IndustryCheckoutInternalApi.Reference request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalIndustryFulfillmentEndpoint.java | ai.neargo.shop.portal.internal.InternalIndustryFulfillmentEndpoint | public InternalIndustryFulfillmentEndpoint(IndustryFulfillmentPort owner, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> fulfill(@RequestBody IndustryFulfillmentInternalApi.Partial c, @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given)<br>public ResponseEntity<?> verify(@RequestBody IndustryFulfillmentInternalApi.Verify c, @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given)<br>public ResponseEntity<?> query(@RequestBody IndustryFulfillmentInternalApi.Query c, @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) |
| backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalIndustryOrderAmendmentEndpoint.java | ai.neargo.shop.portal.internal.InternalIndustryOrderAmendmentEndpoint | public InternalIndustryOrderAmendmentEndpoint( IndustryOrderAmendmentPort owner, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> amend( @RequestBody IndustryOrderAmendmentInternalApi.Amend c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> fee( @RequestBody IndustryOrderAmendmentInternalApi.Fee c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> query( @RequestBody IndustryOrderAmendmentInternalApi.Query c, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalIndustryOrderEndpoint.java | ai.neargo.shop.portal.internal.InternalIndustryOrderEndpoint | public InternalIndustryOrderEndpoint(IndustryOrderPort orders, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> acceptUnpaid(@RequestBody IndustryOrderInternalApi.Get request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> cancelForStore(@RequestBody IndustryOrderInternalApi.StoreCancel request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> cancel(@RequestBody IndustryOrderInternalApi.Place request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> getForStore(@RequestBody IndustryOrderInternalApi.StoreGet request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> placeForDiningRoom(@RequestBody IndustryOrderInternalApi.Place request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> placeForStore(@RequestBody IndustryOrderInternalApi.Place request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> queryForStore(@RequestBody IndustryOrderInternalApi.Query request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> placeForService(@RequestBody IndustryOrderInternalApi.ServicePlace request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> place(@RequestBody IndustryOrderInternalApi.Place request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> query(@RequestBody IndustryOrderInternalApi.Query request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> get(@RequestBody IndustryOrderInternalApi.Get request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalPricingFeeScopeEndpoint.java | ai.neargo.shop.portal.internal.InternalPricingFeeScopeEndpoint | public InternalPricingFeeScopeEndpoint(PricingFeeScopePort fees)<br>public List<PricingFeeScopePort.AdjustmentAuthority> pending( @Valid @RequestBody PricingFeeScopeInternalApi.Query q)<br>public IndustryFulfillmentPort.Reference original( @Valid @RequestBody PricingFeeScopeInternalApi.OriginalQuery q) |
| backend/shop-app/src/main/java/ai/neargo/shop/printing/api/internal/InternalPrintController.java | ai.neargo.shop.printing.api.internal.InternalPrintController | public InternalPrintController( PrintPort service, @Value("${shop.services.internal-token:}") String token)<br>public ResponseEntity<?> configuration( @RequestBody Scope scope, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> bindDevice( @RequestBody PrintInternalApi.DeviceCommand request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> revokeDevice( @RequestBody PrintInternalApi.RevokeDeviceCommand request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> publishTemplate( @RequestBody PrintInternalApi.TemplateCommand request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> putRoute( @RequestBody PrintInternalApi.RouteCommand request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> enqueue( @RequestBody PrintInternalApi.EnqueueCommand request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> list( @RequestBody PrintInternalApi.ListQuery request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> get( @RequestBody PrintInternalApi.JobQuery request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> retry( @RequestBody PrintInternalApi.JobQuery request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> reprint( @RequestBody PrintInternalApi.ReprintCommand request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> heartbeat( @RequestBody PrintInternalApi.HeartbeatCommand request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> claim( @RequestBody PrintInternalApi.DeviceQuery request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> acknowledge( @RequestBody PrintInternalApi.AckCommand request, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/PricingFeeAllocation.java | ai.neargo.shop.common.pricing.PricingFeeAllocation | public static List<Long> allocate(long amount, List<Long> originalWeights) |
| backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/PricingFeeEngine.java | ai.neargo.shop.common.pricing.PricingFeeEngine | public Resolved calculate( PricingFacts prices, Source source, Rule rule, ScopeBasis previous, List<Application> applications)<br>public void validatePlan(Plan plan, PricingFacts prices) |
| backend/shop-base/src/main/java/ai/neargo/shop/common/pricing/PricingFeeTaxEngine.java | ai.neargo.shop.common.pricing.PricingFeeTaxEngine | public record Result(Map<PricingPortion, Long> fees, Map<PricingPortion, Long> taxes)<br>public Result calculate( List<TaxEngine.Line> goods, Plan plan, MerchantTaxSnapshot tax, String sourceHash, long version) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/inventory/InventoryDemandPlanFacts.java | ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts | public static BigDecimal quantity(BigDecimal value, boolean zero)<br>public static String hash(Plan plan)<br>public static Plan validate(Plan plan) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/inventory/InventoryDemandPlanInternalApi.java | ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi | Receipt reserve(@RequestBody Reserve command)<br>Receipt query(@RequestBody Reference command)<br>Receipt acceptUnpaid(@RequestBody Reference command)<br>Receipt allocatePaid(@RequestBody Reference command)<br>Receipt issueDelta(@RequestBody Change command)<br>Receipt issue(@RequestBody Change command)<br>Receipt release(@RequestBody Change command)<br>Receipt supplement(@RequestBody Change command)<br>Receipt reverse(@RequestBody Reverse command) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/inventory/InventoryDemandPlanPort.java | ai.neargo.shop.spi.inventory.InventoryDemandPlanPort | Receipt reserve(Plan plan, long ttlSeconds, int lifecycleVersion)<br>Receipt query(Scope scope)<br>Receipt acceptUnpaid(Scope scope)<br>Receipt allocatePaid(Scope scope)<br>Receipt issue( Scope scope, String operationRef, String planHash, String phase, List<Target> quantities)<br>Receipt issueTo( Scope scope, String operationRef, String planHash, String phase, List<Target> targets)<br>Receipt releaseTo(Scope scope, String operationRef, String planHash, List<Target> targets)<br>Receipt supplement(Scope scope, String operationRef, String planHash, List<Target> quantities)<br>Receipt reverse(Scope scope, String operationRef, String originalOperationRef, String planHash) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/inventory/InventoryProductionFacts.java | ai.neargo.shop.spi.inventory.InventoryProductionFacts | public static Command normalize(Command c)<br>public static String hash(Command c)<br>public static int integerTotal(Command c) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/inventory/InventoryProductionInternalApi.java | ai.neargo.shop.spi.inventory.InventoryProductionInternalApi | InventoryProductionPort.Receipt consume(@RequestBody InventoryProductionPort.Command command)<br>InventoryProductionPort.Receipt reverse(@RequestBody InventoryProductionPort.Reverse command)<br>InventoryProductionPort.Receipt query(@RequestBody InventoryProductionPort.Command command) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/inventory/InventoryProductionPort.java | ai.neargo.shop.spi.inventory.InventoryProductionPort | public void rejectUnknown(String name, Object value)<br>public void rejectUnknown(String name, Object value)<br>Receipt consume(Command command)<br>Receipt query(Command command)<br>default Receipt reverse(Reverse command) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintInternalApi.java | ai.neargo.shop.spi.printing.PrintInternalApi | Configuration configuration(@RequestBody Scope scope)<br>DeviceVO bindDevice(@RequestBody DeviceCommand request)<br>DeviceVO revokeDevice(@RequestBody RevokeDeviceCommand request)<br>TemplateReq publishTemplate(@RequestBody TemplateCommand request)<br>RouteReq putRoute(@RequestBody RouteCommand request)<br>List<JobVO> enqueue(@RequestBody EnqueueCommand request)<br>List<JobVO> list(@RequestBody ListQuery request)<br>JobVO get(@RequestBody JobQuery request)<br>JobVO retry(@RequestBody JobQuery request)<br>JobVO reprint(@RequestBody ReprintCommand request)<br>DeviceVO heartbeat(@RequestBody HeartbeatCommand request)<br>ClaimResult claim(@RequestBody DeviceQuery request)<br>JobVO acknowledge(@RequestBody AckCommand request) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintJobChanged.java | ai.neargo.shop.spi.printing.PrintJobChanged | public String aggregateType()<br>public String aggregateId()<br>public String eventType() |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java | ai.neargo.shop.spi.printing.PrintPort | Configuration configuration(Scope scope)<br>DeviceVO bindDevice(Scope scope, DeviceReq request)<br>DeviceVO revokeDevice(Scope scope, String deviceNo, String reason, String requestKey)<br>TemplateReq publishTemplate(Scope scope, TemplateReq request)<br>RouteReq putRoute(Scope scope, RouteReq request)<br>List<JobVO> enqueue(Scope scope, Enqueue request)<br>List<JobVO> list(Scope scope, String afterJobNo, int limit)<br>JobVO get(Scope scope, String jobNo)<br>JobVO retry(Scope scope, String jobNo)<br>JobVO reprint(Scope scope, String jobNo, String reason, String requestKey)<br>DeviceVO heartbeat(Scope scope, String deviceNo, boolean online)<br>Claim claim(Scope scope, String deviceNo)<br>JobVO acknowledge(Scope scope, String deviceNo, Acknowledge request) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryAfterSaleInternalApi.java | ai.neargo.shop.spi.trade.IndustryAfterSaleInternalApi | IndustryAfterSalePort.Result apply(@RequestBody IndustryAfterSalePort.Apply request)<br>IndustryAfterSalePort.Lookup queryApply(@RequestBody IndustryAfterSalePort.QueryApply query)<br>IndustryAfterSalePort.Result get(@RequestBody IndustryAfterSalePort.Reference reference)<br>IndustryAfterSalePort.Result cancel(@RequestBody IndustryAfterSalePort.Reference reference) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryAfterSalePort.java | ai.neargo.shop.spi.trade.IndustryAfterSalePort | Result apply(Apply request)<br>Lookup queryApply(QueryApply query)<br>Result get(Reference reference)<br>Result cancel(Reference reference) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryAmendmentSourceInternalApi.java | ai.neargo.shop.spi.trade.IndustryAmendmentSourceInternalApi | IndustryAmendmentSourcePort.Evidence get(@RequestBody Query query) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryAmendmentSourcePort.java | ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort | Evidence get(Reference reference, String sourceReturnNo) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryCheckoutPort.java | ai.neargo.shop.spi.trade.IndustryCheckoutPort | View get(Identity identity, String orderNo)<br>View pay(PayCommand command)<br>View queryPayment(Identity identity, String orderNo)<br>View finalizeCheckout(Identity identity, String orderNo) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryFulfillmentInternalApi.java | ai.neargo.shop.spi.trade.IndustryFulfillmentInternalApi | public String toString() |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryFulfillmentPort.java | ai.neargo.shop.spi.trade.IndustryFulfillmentPort | public void unknown(String name,Object value)<br>public void unknown(String name,Object value)<br>public String toString()<br>public String toString()<br>default Receipt fulfill(Fulfill command,String key)<br>Receipt verifyPickup(VerifyPickup command,String key)<br>Receipt query(Reference reference,String actorNo,String key) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryOrderAmendmentInternalApi.java | ai.neargo.shop.spi.trade.IndustryOrderAmendmentInternalApi | IndustryOrderAmendmentPort.Receipt amend(@RequestBody Amend command)<br>IndustryOrderAmendmentPort.Receipt fee(@RequestBody Fee command)<br>IndustryOrderAmendmentPort.Receipt query(@RequestBody Query command) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryOrderAmendmentPort.java | ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort | Receipt amend(Command command, String key)<br>Receipt query(Reference reference, String actorNo, String key)<br>Receipt adjustFee(FeeScopeAdjustment command, String key) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryOrderCompositionFacts.java | ai.neargo.shop.spi.trade.IndustryOrderCompositionFacts | public static void require(Snapshot source, Mode mode) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryOrderInternalApi.java | ai.neargo.shop.spi.trade.IndustryOrderInternalApi | public String toString()<br>public String toString()<br>public String toString() |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryOrderPort.java | ai.neargo.shop.spi.trade.IndustryOrderPort | public List<Allocation> actualAllocations()<br>public Long actualReceivableMinor()<br>Result acceptUnpaid(Identity identity, String orderNo)<br>default Result placeForStore(Command command, String key)<br>default Result placeForDiningRoom(Command command, String key)<br>default Result queryForStore(Identity identity, String key)<br>default Result placeForService(Command command, String key, String fulfillment, boolean staff)<br>Result place(Command command, String key)<br>default Result cancelForStore(Command command, String key, String fulfillment)<br>Result cancel(Command command, String key)<br>Result query(Identity identity, String key)<br>Result get(Identity identity, String orderNo)<br>Result getForStore(Scope scope, String actorNo, String orderNo) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/PricingFeeFacts.java | ai.neargo.shop.spi.trade.PricingFeeFacts | public record Source( String ruleNo, String revisionNo, String scopeNo, Kind kind, BigDecimal quantity, String unit, List<String> basisSourceRefs, String waiverEvidence)<br>public record Definition( Kind kind, Method method, String unit, String currency, long unitAmountMinor, Integer rateBp, TaxClass taxClass)<br>public record Rule( Scope scope, String ruleNo, String revisionNo, long version, String status, Definition definition)<br>public record ScopeBasis( String scopeNo, String ruleNo, String currency, long goodsBasisMinor, long feeMinor, long version)<br>public record Entry( PricingPortion portion, long amountMinor, TaxClass taxClass, String invoiceRef, String ruleNo, String revisionNo, String scopeNo)<br>public record Correction( String applicationNo, long currentFeeMinor, long targetFeeMinor, String sourceHash)<br>public record Resolved( Source source, Rule rule, ScopeBasis previous, long currentGoodsBasisMinor, long targetFeeMinor, long additionalFeeMinor, List<Entry> entries, List<Correction> corrections)<br>public record Plan(List<Resolved> fees, String planHash)<br>public record Application( String applicationNo, String orderNo, String subOrderNo, String quoteNo, String ruleNo, String revisionNo, String scopeNo, String currency, long feeMinor, long adjustedFeeMinor, String sourceHash) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/PricingFeeRulePort.java | ai.neargo.shop.spi.trade.PricingFeeRulePort | Plan resolve(Snapshot source)<br>void applyPlan( PricingSourceFactsPort.Scope scope, String actor, String orderNo, String subOrderNo, String quoteNo, Plan plan) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/PricingFeeScopeInternalApi.java | ai.neargo.shop.spi.trade.PricingFeeScopeInternalApi | List<PricingFeeScopePort.AdjustmentAuthority> pending(@RequestBody Query query)<br>IndustryFulfillmentPort.Reference original(@RequestBody OriginalQuery query) |
| backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/PricingFeeScopePort.java | ai.neargo.shop.spi.trade.PricingFeeScopePort | void reconcileOnAmendment( IndustryFulfillmentPort.Reference reference, String amendmentNo, String sourceHash)<br>IndustryFulfillmentPort.Reference originalReference(Scope scope, String applicationNo)<br>List<AdjustmentAuthority> pendingAdjustments(Scope scope, String scopeNo)<br>AdjustmentAuthority adjustment( Scope scope, String scopeNo, String applicationNo, String sourceHash)<br>AdjustmentAuthority authorizeAdjustment( Scope scope, String applicationNo, long targetFeeMinor, String sourceHash)<br>void completeAdjustment( Scope scope, String applicationNo, long targetFeeMinor, String sourceHash, String actor, String key) |
| backend/shop-core/src/main/java/ai/neargo/shop/platform/IndustryService.java | ai.neargo.shop.platform.IndustryService | List<IndustryVO> list()<br>List<IndustryVO> enabled()<br>boolean microAllowed(String industry, String payChannel)<br>IndustryVO setMicroAllowed(String industry, String payChannel, boolean allowed, String remark)<br>IndustryVO setEnabled(String industry, boolean enabled)<br>IndustryVO setPointsForced(String industry, boolean forced) |
| backend/shop-core/src/main/java/ai/neargo/shop/platform/impl/IndustryServiceImpl.java | ai.neargo.shop.platform.impl.IndustryServiceImpl | public IndustryServiceImpl(IndustryMapper mapper, MerchantQueryPort merchantQuery)<br>public List<IndustryVO> list()<br>public List<IndustryVO> enabled()<br>public boolean microAllowed(String industry, String payChannel)<br>public IndustryVO setMicroAllowed(String industry, String payChannel, boolean allowed, String remark)<br>public IndustryVO setEnabled(String industry, boolean enabled)<br>public IndustryVO setPointsForced(String industry, boolean forced) |
| backend/shop-core/src/main/java/ai/neargo/shop/printing/config/PrintDataScopeRegistration.java | ai.neargo.shop.printing.config.PrintDataScopeRegistration | public void register(DataScopeTableRegistry registry) |
| backend/shop-core/src/main/java/ai/neargo/shop/printing/policy/PrintDocumentPolicy.java | ai.neargo.shop.printing.policy.PrintDocumentPolicy | public static void scope(Scope value)<br>public static void key(String value)<br>public static void scene(String value)<br>public static void text(String value, int max)<br>public static void template(String content)<br>public static void document(Document value)<br>public static String render( Document document, String template, String original, String reason) |
| backend/shop-core/src/main/java/ai/neargo/shop/printing/port/PrintPortImpl.java | ai.neargo.shop.printing.port.PrintPortImpl | public PrintPortImpl(PrintService service)<br>public Configuration configuration(Scope scope)<br>public DeviceVO bindDevice(Scope scope, DeviceReq request)<br>public DeviceVO revokeDevice(Scope scope, String no, String reason, String key)<br>public TemplateReq publishTemplate(Scope scope, TemplateReq request)<br>public RouteReq putRoute(Scope scope, RouteReq request)<br>public List<JobVO> enqueue(Scope scope, Enqueue request)<br>public List<JobVO> list(Scope scope, String afterJobNo, int limit)<br>public JobVO get(Scope scope, String jobNo)<br>public JobVO retry(Scope scope, String jobNo)<br>public JobVO reprint(Scope scope, String jobNo, String reason, String requestKey)<br>public DeviceVO heartbeat(Scope scope, String deviceNo, boolean online)<br>public Claim claim(Scope scope, String deviceNo)<br>public JobVO acknowledge(Scope scope, String deviceNo, Acknowledge request) |
| backend/shop-core/src/main/java/ai/neargo/shop/printing/service/PrintService.java | ai.neargo.shop.printing.service.PrintService | Configuration configuration(Scope scope)<br>DeviceVO bindDevice(Scope scope, DeviceReq request)<br>DeviceVO revokeDevice(Scope scope, String deviceNo, String reason, String requestKey)<br>TemplateReq publishTemplate(Scope scope, TemplateReq request)<br>RouteReq putRoute(Scope scope, RouteReq request)<br>List<JobVO> enqueue(Scope scope, Enqueue request)<br>List<JobVO> list(Scope scope, String afterJobNo, int limit)<br>JobVO get(Scope scope, String jobNo)<br>JobVO retry(Scope scope, String jobNo)<br>JobVO reprint(Scope scope, String jobNo, String reason, String requestKey)<br>DeviceVO heartbeat(Scope scope, String deviceNo, boolean online)<br>Claim claim(Scope scope, String deviceNo)<br>JobVO acknowledge(Scope scope, String deviceNo, Acknowledge request) |
| backend/shop-core/src/main/java/ai/neargo/shop/printing/service/impl/PrintServiceImpl.java | ai.neargo.shop.printing.service.impl.PrintServiceImpl | public PrintServiceImpl( DeviceMapper devices, TemplateMapper templates, RouteMapper routes, JobMapper jobs, AttemptMapper attempts, ObjectMapper json, Clock clock, OutboxPublisher events, PlatformTransactionManager manager)<br>public Configuration configuration(Scope scope)<br>public DeviceVO bindDevice(Scope scope, DeviceReq req)<br>public DeviceVO revokeDevice(Scope scope, String no, String reason, String key)<br>public TemplateReq publishTemplate(Scope scope, TemplateReq req)<br>public RouteReq putRoute(Scope scope, RouteReq req)<br>public List<JobVO> enqueue(Scope scope, Enqueue req)<br>public List<JobVO> list(Scope scope, String after, int limit)<br>public JobVO get(Scope scope, String no)<br>public JobVO retry(Scope scope, String no)<br>public JobVO reprint(Scope scope, String no, String reason, String key)<br>public DeviceVO heartbeat(Scope scope, String no, boolean online)<br>public Claim claim(Scope scope, String no)<br>public JobVO acknowledge(Scope scope, String no, Acknowledge req) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/config/IndustryFulfillmentDataScopeRegistration.java | ai.neargo.shop.trade.config.IndustryFulfillmentDataScopeRegistration | public void register(DataScopeTableRegistry registry) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/PricingFeeEvidenceRow.java | ai.neargo.shop.trade.entity.PricingFeeEvidenceRow | public PricingFeeEvidenceRow(Map<String, Object> source)<br>public Object get(String name) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/job/IndustryNonPaymentRecoveryJob.java | ai.neargo.shop.trade.job.IndustryNonPaymentRecoveryJob | public IndustryNonPaymentRecoveryJob( IndustryFulfillmentService fulfillment, IndustryOrderAmendmentService amendments)<br>public void run() |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/job/IndustryTransactionRecoveryJob.java | ai.neargo.shop.trade.job.IndustryTransactionRecoveryJob | public IndustryTransactionRecoveryJob(IndustryTransactionRecoveryService recovery,JobSupport jobs)<br>public void scan()<br>public String name()<br>public JobResult run(JobInvocation invocation)<br>public JobDeclaration industryTransactionRecoveryDeclaration() |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/IndustryOrderAmendmentMapper.java | ai.neargo.shop.trade.mapper.IndustryOrderAmendmentMapper | public IndustryOrderAmendmentMapper(@Qualifier("dataSource") DataSource ds)<br>public Journal byKey(Reference ref, String operation)<br>public List<Journal> pending(int limit)<br>public List<Line> lines(String no)<br>public BigDecimal fulfilled(Reference ref, Long item, String component)<br>public BigDecimal reduced(Reference ref, Long item, ReductionColumn type)<br>public long moneyReduced(Reference ref, Long item)<br>public long netReduced(Reference ref, Long item)<br>public void returnMoney(String no, long net, long tax)<br>public long receivableAdjustment(String tenantNo, String entityNo, String orderNo)<br>public long pendingAmendments(String tenant, String sub)<br>public long pendingFulfillments(String tenant, String sub)<br>public void insert(ReturnDraft d)<br>public void insertLine(String no, Line d, String actor)<br>public void confirm(Long id, String inventory, String actor)<br>public void unknown(Long id)<br>public void feeMoney(String no, long net, long tax, String evidence)<br>public void appendDebt(Debt d)<br>public FeeApplication feeApplication(Reference ref, String no)<br>public String quoteResult(Reference ref, String quote)<br>public void insertFeeLine(String no, FeeLine line, String actor)<br>public long feeLineAdjustment(Reference ref, Long item)<br>public List<Applied> applied(Reference ref) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/IndustryOrderAmendmentRows.java | ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows | public record Journal( Long id, String tenantNo, String entityNo, String storeNo, String orderNo, String subOrderNo, String amendmentNo, String operationKey, String requestHash, String kind, String sourceReturnNo, String sourceHash, String commandSnapshot, String evidenceSnapshot, String inventorySnapshot, String status, String actorNo, long expectedSubVersion, String currency, long receivableDeltaMinor, String replacementCartNo, Long netDeltaMinor, Long taxDeltaMinor)<br>public record Line( Long orderItemId, String sourceComponentRef, BigDecimal quantity, BigDecimal physicalReductionQty, BigDecimal cancelQty, String unit, String disposition, long receivableReductionMinor, long netReductionMinor, long taxReductionMinor)<br>public record FeeApplication( String quoteNo, String ruleNo, String revisionNo, String scopeNo, long feeMinor)<br>public record FeeLine(Long orderItemId, String sourceComponentRef, long amountMinor)<br>public record Applied( String amendmentNo, String sourceHash, String kind, String inventorySnapshot, long receivableDeltaMinor)<br>public record ReturnDraft( String tenantNo, String entityNo, String storeNo, String orderNo, String subOrderNo, String amendmentNo, String operationKey, String requestHash, String kind, String sourceReturnNo, String sourceHash, String commandSnapshot, String evidenceSnapshot, String status, String actorNo, long expectedSubVersion, String currency, long receivableDeltaMinor, String replacementCartNo)<br>public record Debt( String tenantNo, String entityNo, String storeNo, String orderNo, String subOrderNo, String amendmentNo, String currency, long amountMinor, String sourceHash, String reason, String actorNo, Long netDeltaMinor, Long taxDeltaMinor) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/IndustryPartialFulfillmentMapper.java | ai.neargo.shop.trade.mapper.IndustryPartialFulfillmentMapper | List<OrdIndustryFulfillment> pending(@Param("limit") int limit)<br>BigDecimal physicalReduction( @Param("tenant") String tenant, @Param("entity") String entity, @Param("sub") String sub, @Param("item") Long item)<br>long pendingAmendments( @Param("tenant") String tenant, @Param("entity") String entity, @Param("sub") String sub)<br>BigDecimal quantity( @Param("tenant") String tenant, @Param("entity") String entity, @Param("sub") String sub, @Param("item") Long item, @Param("component") String component)<br>int advance( @Param("tenant") String tenant, @Param("entity") String entity, @Param("sub") String sub, @Param("item") Long item, @Param("component") String component, @Param("qty") BigDecimal qty, @Param("unit") String unit, @Param("hash") String hash, @Param("actor") String actor) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/PricingFeeRuleMapper.java | ai.neargo.shop.trade.mapper.PricingFeeRuleMapper | public PricingFeeRuleMapper(@Qualifier("dataSource") DataSource source)<br>public List<PricingFeeEvidenceRow> rule(String e, String s, String rule, boolean lock)<br>public List<PricingFeeEvidenceRow> revision(String e, String s, String revision)<br>public List<PricingFeeEvidenceRow> command(String e, String s, String key)<br>public int insertRule( String e, String s, String rule, String revision, String status, String actor)<br>public int updateRule( String e, String s, String rule, String revision, String status, long version, String actor)<br>public int insertRevision( String e, String s, String rule, String revision, String definition, String key, String hash, String actor, long version, String reason)<br>public List<PricingFeeEvidenceRow> scope( String e, String s, String no, String rule, boolean lock)<br>public int createScope(String e, String s, String no, String rule, String currency)<br>public List<PricingFeeEvidenceRow> applications(String e, String s, String no, String rule)<br>public List<PricingFeeEvidenceRow> quoteApplications(String e, String s, String quote)<br>public int application( String app, String e, String s, String order, String sub, String quote, String rule, String revision, String scope, String currency, long fee, String hash)<br>public int updateScope( String e, String s, String no, String rule, long version, long goods, long fee)<br>public int advice( String e, String s, String app, String quote, long current, long target, String hash)<br>public List<PricingFeeEvidenceRow> adjustment( String e, String s, String app, String hash, boolean lock)<br>public int completeAdvice(String e, String s, String app, String hash)<br>public int adjustApplication(String e, String s, String app, long current, long target)<br>public int reduceScope(String e, String s, String no, String rule, long amount)<br>public List<PricingFeeEvidenceRow> pendingAdjustments(String e, String store, String scope)<br>public List<PricingFeeEvidenceRow> originalReference( String e, String store, String application)<br>public List<PricingFeeEvidenceRow> amendmentSource( Reference ref, String amendment, String hash)<br>public List<PricingFeeEvidenceRow> orderApplications(Reference ref)<br>public List<PricingFeeEvidenceRow> confirmedReductions(Reference ref)<br>public List<PricingFeeEvidenceRow> contribution(String app)<br>public int setContribution(String app, long remaining)<br>public int changeGoodsBasis(String e, String store, String scope, String rule, long delta)<br>public List<PricingFeeEvidenceRow> reconciliation(String e, String app, String amendment)<br>public int recordReconciliation( String e, String store, String app, String amendment, String hash, long before, long after)<br>public int initializeContribution(String app, long goods, BigDecimal quantity)<br>public int supersedeAdvice(String e, String store, String scope, String rule)<br>public int quantityContribution(String app, BigDecimal quantity) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/port/IndustryAfterSalePortImpl.java | ai.neargo.shop.trade.port.IndustryAfterSalePortImpl | public IndustryAfterSalePortImpl(IndustryAfterSaleService service)<br>public Result apply(Apply request)<br>public Lookup queryApply(QueryApply query)<br>public Result get(Reference reference)<br>public Result cancel(Reference reference) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/port/IndustryCheckoutPortImpl.java | ai.neargo.shop.trade.port.IndustryCheckoutPortImpl | public IndustryCheckoutPortImpl(IndustryCheckoutService service)<br>public View get(Identity identity, String orderNo)<br>public View pay(PayCommand command)<br>public View queryPayment(Identity identity, String orderNo)<br>public View finalizeCheckout(Identity identity, String orderNo) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/port/IndustryFulfillmentPortImpl.java | ai.neargo.shop.trade.port.IndustryFulfillmentPortImpl | public IndustryFulfillmentPortImpl(IndustryFulfillmentService service)<br>public Receipt fulfill(Fulfill command,String key)<br>public Receipt verifyPickup(VerifyPickup command,String key)<br>public Receipt query(Reference reference,String actor,String key) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/port/IndustryOrderAmendmentPortImpl.java | ai.neargo.shop.trade.port.IndustryOrderAmendmentPortImpl | public IndustryOrderAmendmentPortImpl(IndustryOrderAmendmentService service)<br>public Receipt amend(Command c, String key)<br>public Receipt query(Reference r, String actor, String key)<br>public Receipt adjustFee(FeeScopeAdjustment c, String key) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/port/IndustryOrderPortImpl.java | ai.neargo.shop.trade.port.IndustryOrderPortImpl | public IndustryOrderPortImpl(IndustryOrderService service)<br>public Result acceptUnpaid(Identity identity,String orderNo)<br>public Result placeForDiningRoom(Command command, String key)<br>public Result placeForService(Command command, String key, String fulfillment, boolean staff)<br>public Result placeForStore(Command command, String key)<br>public Result queryForStore(Identity identity, String key)<br>public Result place(Command command, String key)<br>public Result cancelForStore(Command command, String key, String fulfillment)<br>public Result cancel(Command command, String key)<br>public Result getForStore(Scope scope, String actor, String orderNo)<br>public Result query(Identity identity, String key)<br>public Result get(Identity identity, String orderNo) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/IndustryAfterSaleService.java | ai.neargo.shop.trade.service.IndustryAfterSaleService | IndustryAfterSalePort.Result apply(IndustryAfterSalePort.Apply request)<br>IndustryAfterSalePort.Lookup queryApply(IndustryAfterSalePort.QueryApply query)<br>IndustryAfterSalePort.Result get(IndustryAfterSalePort.Reference reference)<br>IndustryAfterSalePort.Result cancel(IndustryAfterSalePort.Reference reference) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/IndustryCheckoutService.java | ai.neargo.shop.trade.service.IndustryCheckoutService | View get(Identity identity, String orderNo)<br>View pay(PayCommand command)<br>View queryPayment(Identity identity, String orderNo)<br>View finalizeCheckout(Identity identity, String orderNo) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/IndustryFulfillmentService.java | ai.neargo.shop.trade.service.IndustryFulfillmentService | int recover(int limit)<br>Receipt fulfill(IndustryFulfillmentPort.Fulfill command, String key)<br>Receipt verifyPickup(VerifyPickup command, String key)<br>Receipt query(Reference reference, String actorNo, String key) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/IndustryOrderAmendmentService.java | ai.neargo.shop.trade.service.IndustryOrderAmendmentService | Receipt amend(Command command, String key)<br>Receipt query(Reference reference, String actor, String key)<br>Receipt adjustFee(FeeScopeAdjustment command, String key)<br>EffectiveProjection effectiveAllocations( Reference reference, List<IndustryOrderPort.Allocation> original)<br>int recover(int limit) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/IndustryOrderService.java | ai.neargo.shop.trade.service.IndustryOrderService | Result acceptUnpaid(Identity identity, String orderNo)<br>Result placeForStore(Command command, String key)<br>default Result placeForDiningRoom(Command command, String key)<br>default Result placeForService(Command command, String key, String fulfillment, boolean staff)<br>Result queryForStore(Identity identity, String key)<br>Result place(Command command, String key)<br>Result cancel(Command command, String key)<br>Result cancelForStore(Command command, String key, String fulfillment)<br>Result query(Identity identity, String key)<br>Result get(Identity identity, String orderNo)<br>Result getForStore(Scope scope, String actorNo, String orderNo) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/IndustryTransactionRecoveryService.java | ai.neargo.shop.trade.service.IndustryTransactionRecoveryService | Batch recoverDue(long now, int limit) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/PricingFeeRuleService.java | ai.neargo.shop.trade.service.PricingFeeRuleService | Rule save(Scope scope, String actor, String ruleNo, SaveReq request, String key)<br>Rule get(Scope scope, String ruleNo) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/IndustryAfterSaleServiceImpl.java | ai.neargo.shop.trade.service.impl.IndustryAfterSaleServiceImpl | public IndustryAfterSaleServiceImpl(IndustryOrderSourceMapper sources, StoreOrderMapper orders, PaymentMapper payments, OrderItemMapper items, AfterSaleService afterSales, ObjectMapper json, AfterSaleMapper cases)<br>public IndustryAfterSalePort.Result apply(IndustryAfterSalePort.Apply request)<br>public IndustryAfterSalePort.Lookup queryApply(IndustryAfterSalePort.QueryApply query)<br>public IndustryAfterSalePort.Result get(IndustryAfterSalePort.Reference reference)<br>public IndustryAfterSalePort.Result cancel(IndustryAfterSalePort.Reference reference) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/IndustryCheckoutServiceImpl.java | ai.neargo.shop.trade.service.impl.IndustryCheckoutServiceImpl | public IndustryCheckoutServiceImpl( IndustryOrderSourceMapper bindings, PaymentMapper payments, StoreOrderMapper subOrders, PricingQuoteService quotes, MerchantQueryPort merchants, MerchantScopeFactsPort ownerScopes, StoreDirectoryPort stores, UserScopeFactsPort users, PayChannelMasterPort channels, ObjectProvider<OrdinaryPaymentPort> paymentProviders, ObjectProvider<OrdinaryPaymentInitiationPort> initiationProviders, ObjectProvider<InventoryReservationPort> inventoryProviders, OrderService orders, PaymentResolutionService resolutions, @Qualifier("transactionManager") PlatformTransactionManager transactionManager)<br>public View get(Identity identity, String orderNo)<br>public View pay(PayCommand command)<br>public View queryPayment(Identity identity, String orderNo)<br>public View finalizeCheckout(Identity identity, String orderNo) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/IndustryFulfillmentServiceImpl.java | ai.neargo.shop.trade.service.impl.IndustryFulfillmentServiceImpl | public void configurePartial( IndustryPartialFulfillmentMapper partials, ObjectProvider<InventoryDemandPlanPort> exactInventory)<br>public IndustryFulfillmentServiceImpl( IndustryFulfillmentMapper journal, IndustryOrderSourceMapper sources, PaymentMapper orders, StoreOrderMapper subs, OrderItemMapper items, CreateIntentMapper intents, MerchantScopeFactsPort owners, ObjectProvider<InventoryReservationPort> inventory, TradeStatusWriter writer, OutboxEventBus events, ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager, StatusCasMapper locks)<br>public Receipt fulfill(IndustryFulfillmentPort.Fulfill c, String key)<br>public int recover(int limit)<br>public Receipt verifyPickup(VerifyPickup command, String key)<br>public Receipt query(Reference ref, String actor, String key) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/IndustryOrderAmendmentServiceImpl.java | ai.neargo.shop.trade.service.impl.IndustryOrderAmendmentServiceImpl | public IndustryOrderAmendmentServiceImpl( IndustryOrderAmendmentMapper persistence, TradeStatusWriter writer, OutboxEventBus events, @Qualifier("transactionManager") PlatformTransactionManager manager, ObjectMapper json, IndustryOrderSourceMapper sources, PaymentMapper orders, StoreOrderMapper subs, OrderItemMapper items, MerchantScopeFactsPort merchants, ObjectProvider<InventoryDemandPlanPort> inventory, ObjectProvider<IndustryAmendmentSourcePort> evidence, ObjectProvider<PricingFeeScopePort> fees)<br>public Receipt amend(Command c, String key)<br>public Receipt query(Reference ref, String actor, String key)<br>public Receipt adjustFee(FeeScopeAdjustment c, String key)<br>public int recover(int limit)<br>public EffectiveProjection effectiveAllocations( Reference ref, List<IndustryOrderPort.Allocation> original) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/IndustryOrderServiceImpl.java | ai.neargo.shop.trade.service.impl.IndustryOrderServiceImpl | public void amendmentSourceProjection(IndustryOrderAmendmentService service)<br>public void amendmentProjection(IndustryOrderAmendmentMapper mapper)<br>public void pricingFeeProviders(ObjectProvider<PricingFeeRulePort> providers)<br>public void demandPlanProviders(ObjectProvider<InventoryDemandPlanPort> providers)<br>public IndustryOrderServiceImpl( CreateIntentMapper intents, IndustryOrderSourceMapper bindings, PaymentMapper payments, StoreOrderMapper subOrders, OrderItemMapper items, OrderStatusLogMapper statusLogs, PricingPreparationService preparations, PricingCheckoutService checkout, PricingQuoteService quotes, PricingSourceService sources, OutboxEventBus events, ObjectProvider<InventoryReservationPort> inventoryProviders, @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers, GoodsQueryPort goods, MerchantScopeFactsPort ownerScopes, StoreDirectoryPort stores, MerchantQueryPort merchants, UserScopeFactsPort users, UserQueryPort userDetails, AdmissionPort admission, AttributionPort attribution, PurchaseLimitGuard purchaseLimits, CloseRuleService closeRules, @Qualifier("transactionManager") PlatformTransactionManager transactionManager)<br>public Result place(Command command, String key)<br>public Result placeForStore(Command command, String key)<br>public Result placeForDiningRoom(Command command, String key)<br>public Result placeForService(Command command, String key, String fulfillment, boolean staff)<br>public Result acceptUnpaid(Identity identity, String orderNo)<br>public Result cancel(Command command, String key)<br>public Result cancelForStore(Command command, String key, String fulfillment)<br>public Result getForStore(Scope scope, String actorNo, String orderNo)<br>public Result query(Identity identity, String key)<br>public Result queryForStore(Identity identity, String key)<br>public Result get(Identity identity, String orderNo) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/IndustryTransactionRecoveryServiceImpl.java | ai.neargo.shop.trade.service.impl.IndustryTransactionRecoveryServiceImpl | public IndustryTransactionRecoveryServiceImpl(IndustryOrderSourceMapper sources, CreateIntentMapper intents, IndustryOrderService orders, IndustryCheckoutService checkout, @Qualifier("transactionManager") PlatformTransactionManager manager)<br>public Batch recoverDue(long now, int limit) |
| backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/PricingFeeRuleServiceImpl.java | ai.neargo.shop.trade.service.impl.PricingFeeRuleServiceImpl | public PricingFeeRuleServiceImpl(PricingFeeRuleMapper mapper, ObjectMapper json)<br>public Rule save(Scope scope, String actor, String no, SaveReq req, String key)<br>public Rule get(Scope scope, String no)<br>public Plan resolve(Snapshot source)<br>public void applyPlan( Scope scope, String actor, String order, String sub, String quote, Plan plan)<br>public IndustryFulfillmentPort.Reference originalReference(Scope scope, String app)<br>public List<AdjustmentAuthority> pendingAdjustments(Scope scope, String scopeNo)<br>public AdjustmentAuthority adjustment(Scope scope, String scopeNo, String app, String hash)<br>public AdjustmentAuthority authorizeAdjustment( Scope scope, String app, long target, String hash)<br>public void completeAdjustment( Scope scope, String app, long target, String hash, String actor, String key)<br>public void reconcileOnAmendment( IndustryFulfillmentPort.Reference ref, String amendment, String sourceHash) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/api/internal/InternalInventoryDemandPlanController.java | ai.neargo.shop.inventory.api.internal.InternalInventoryDemandPlanController | public InternalInventoryDemandPlanController( InventoryDemandPlanPort owner, @Value("${shop.services.internal-token:}") String token, JsonMapper json)<br>public ResponseEntity<?> reserve( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t)<br>public ResponseEntity<?> query( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t)<br>public ResponseEntity<?> accept( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t)<br>public ResponseEntity<?> allocate( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t)<br>public ResponseEntity<?> issueDelta( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t)<br>public ResponseEntity<?> issue( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t)<br>public ResponseEntity<?> release( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t)<br>public ResponseEntity<?> supplement( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t)<br>public ResponseEntity<?> reverse( @RequestBody byte[] b, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/api/internal/InternalInventoryProductionController.java | ai.neargo.shop.inventory.api.internal.InternalInventoryProductionController | public InternalInventoryProductionController( InventoryProductionPort production, @Value("${shop.services.internal-token:}") String token, JsonMapper json)<br>public ResponseEntity<?> consume( @RequestBody(required = false) byte[] body, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> query( @RequestBody(required = false) byte[] body, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given)<br>public ResponseEntity<?> reverse( @RequestBody(required = false) byte[] body, @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvInboundLine.java | ai.neargo.shop.inventory.entity.InvInboundLine | public Integer getQty()<br>public void setQty(Integer value) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvInboundOrder.java | ai.neargo.shop.inventory.entity.InvInboundOrder | public Integer getTotalQty()<br>public void setTotalQty(Integer value) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvLedger.java | ai.neargo.shop.inventory.entity.InvLedger | public Integer getQtyDelta()<br>public void setQtyDelta(Integer value)<br>public Integer getBalanceAfter()<br>public void setBalanceAfter(Integer value) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvOutboundLine.java | ai.neargo.shop.inventory.entity.InvOutboundLine | public Integer getQty()<br>public void setQty(Integer value) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvOutboundOrder.java | ai.neargo.shop.inventory.entity.InvOutboundOrder | public Integer getTotalQty()<br>public void setTotalQty(Integer value) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvStockBalance.java | ai.neargo.shop.inventory.entity.InvStockBalance | public Integer getOnHand()<br>public void setOnHand(Integer value)<br>public Integer getReserved()<br>public void setReserved(Integer value) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/mapper/InventoryDemandPlanMapper.java | ai.neargo.shop.inventory.mapper.InventoryDemandPlanMapper | public InventoryDemandPlanMapper(@Qualifier("invDataSource") DataSource source)<br>public record Head( Long id, String planNo, String planHash, int lifecycleVersion, String status, LocalDateTime expiresAt)<br>public record Operation( String planNo, String requestHash, int reversed, String kind, String deltaSnapshot, String receiptSnapshot)<br>public record Leaf( Long id, String leafRef, String componentRef, String fulfillmentRef, String branchKind, String itemId, String locationId, BigDecimal qty, BigDecimal issuedQty, BigDecimal releasedQty, String unit, String definitionHash, String documentNo, String ledgerRef, Long costMinor, String currency)<br>public record Valuation(Long defaultCostMinor, String costCurrency, int trackBatch)<br>public record Lot( BigDecimal qty, Long unitCostMinor, String currency, String batchNo, String lotRef)<br>public List<Long> legacyReservations(String value0, String value1)<br>public int insertPlan( String value0, String value1, String value2, String value3, String value4, int value5, LocalDateTime value6)<br>public int reserveBalance( BigDecimal value0, String value1, String value2, String value3, BigDecimal value4)<br>public int insertLeaf( String value0, String value1, String value2, String value3, String value4, String value5, String value6, String value7, BigDecimal value8, String value9, String value10)<br>public int acceptUnpaid(String value0, String value1)<br>public int releaseCompletedHold(String owner, String plan)<br>public int allocatePaid(String value0, String value1)<br>public int unreserveBalance( BigDecimal value0, String value1, String value2, String value3, BigDecimal value4)<br>public Valuation valuation(String value0, String value1)<br>public List<Lot> availableLots(String value0, String value1, String value2)<br>public int insertOutboundLine( String value0, String value1, int value2, String value3, BigDecimal value4, String value5, Long value6, String value7)<br>public int updateProgress( boolean release, BigDecimal value0, String value1, String value2, Long value3, String value4, String value5, String value6, String value7)<br>public int insertOutbound( String value0, String value1, String value2, String value3, String value4, BigDecimal value5)<br>public int insertOperation( String value0, String value1, String value2, String value3, String value4, String value5, String value6)<br>public int reserveSupplement( BigDecimal value0, String value1, String value2, String value3, BigDecimal value4)<br>public int supplementLeaf(BigDecimal value0, Long value1)<br>public int insertSupplement( String value0, String value1, String value2, String value3, String value4, String value5)<br>public int insertInboundLine( String value0, String value1, int value2, String value3, BigDecimal value4, String value5, Long value6, String value7)<br>public int markLeafDocument(String value0, String value1, Long value2)<br>public int restoreReservation(BigDecimal value0, String value1, String value2, String value3)<br>public int reverseLeaf( BigDecimal value0, String value1, String value2, String value3, BigDecimal value4)<br>public int insertInbound( String value0, String value1, String value2, String value3, BigDecimal value4)<br>public int markReversed(String value0, String value1)<br>public int insertReversal( String value0, String value1, String value2, String value3, String value4, String value5, String value6)<br>public List<Leaf> leaves(String value0, String value1)<br>public int expireUnreserve( BigDecimal value0, String value1, String value2, String value3, BigDecimal value4)<br>public int expireLeaf(Long value0)<br>public int expirePlan(Long value0)<br>public List<String> lockOwner(String value0)<br>public List<Head> heads(String value0, String value1)<br>public List<Operation> operations(String value0, String value1)<br>public List<Leaf> findLeaves(String value0, String value1, String value2)<br>public List<Leaf> orderedLeaves(String value0, String value1) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/mapper/InventoryProductionMapper.java | ai.neargo.shop.inventory.mapper.InventoryProductionMapper | public InventoryProductionMapper(@Qualifier("invDataSource") DataSource source)<br>public record Operation( Long id, String requestHash, String receiptSnapshot, String reverseEventNo, String reverseHash, String operationRef)<br>public record Delta(String deltaSnapshot)<br>public record Ledger( BigDecimal qtyDelta, Long unitCostMinor, String costCurrency, String docNo, String batchNo)<br>public List<String> activeOwners(boolean queryOnly, String value0)<br>public List<Operation> operations(String value0, String value1, String value2)<br>public Delta demandDelta(String value0, String value1)<br>public Ledger ledger(String value0, Long value1)<br>public int insertOperation( String value0, String value1, String value2, String value3, String value4, String value5, String value6)<br>public List<String> lockOwner(String value0)<br>public List<Operation> originalOperations(String value0, String value1, String value2)<br>public int confirmReverse(String value0, String value1, String value2, Long value3) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/port/InventoryDemandPlanPortImpl.java | ai.neargo.shop.inventory.port.InventoryDemandPlanPortImpl | public InventoryDemandPlanPortImpl( InventoryDemandPlanMapper mapper, InventoryAclService acl, MaterialCatalogQueryPort materials, StockPostingService posting, JsonMapper json)<br>public Receipt reserve(Plan p, long ttl, int lifecycle)<br>public Receipt query(Scope scope)<br>public Receipt acceptUnpaid(Scope scope)<br>public Receipt allocatePaid(Scope scope)<br>public Receipt issue( Scope scope, String op, String hash, String phase, List<Target> quantities)<br>public Receipt issueTo( Scope scope, String op, String hash, String phase, List<Target> targets)<br>public Receipt releaseTo(Scope scope, String op, String hash, List<Target> targets)<br>public record Delta( String leafRef, BigDecimal qty, String item, String location, Long unitCost, String currency, String batchNo, String lotRef, String ledgerRef)<br>public Receipt supplement(Scope scope, String op, String hash, List<Target> quantities)<br>public Receipt reverse(Scope scope, String op, String original, String hash) |
| backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/port/InventoryProductionPortImpl.java | ai.neargo.shop.inventory.port.InventoryProductionPortImpl | public void configureExact( InventoryDemandPlanPort plans, InventoryProductionMapper persistence, JsonMapper json)<br>public InventoryProductionPortImpl( InventoryAclService acl, MaterialCatalogQueryPort materials, OwnerMapper owners, OutboundOrderMapper documents, OutboundLineMapper documentLines, OutboundService outbound)<br>public Receipt consume(Command input)<br>public Receipt query(Command input)<br>public Receipt reverse(Reverse reversal)<br>public static Command normalized(Command c) |

## 保留的目标规划及历史记录

# 餐饮系统Controller、Service与Mapper方法清单

> 状态：目标与已实现后端方法对照 · 2026-10-21。按165个目标与实际API逐项登记56个Controller、38个Service及对应Impl、119个持久化接口/DAO；已实现范围以逐项状态及CAT-ALL验收记录为准，未实现目标不等于运行能力。
> 由 `node scripts/gen-food-code-inventory.mjs` 统一生成；输入为[方法用例计划](./schema/food-method-plan.json)、[类清单](./schema/food-module-code-inventory.json)。[API参数](./餐饮系统-API设计.md) · [模块代码清单](./餐饮系统-按业务模块代码清单.md) · [方法机器清单](./schema/food-method-inventory.json)。

## 一、签名及调用边界

Java片段为方法声明，省略import、HTTP Mapping及权限注解（HTTP方法/路径在标题，完整类型在机器清单）；Controller类应按实际方法加对应Mapping与既有权限规则。

Controller仅有路径、Body/Query和协议头；Service签名另加可信主体/当前门店/操作者或会话范围，不带HTTP注解。显式storeNo必须与已有BizContext获准目标匹配；非门店路径由对象归属或获准工单解析范围。entityNo不是客户端Body字段，顾客会话也不能自报替代认证。平台不得伪装商家身份，多店报表额外携带获准门店集合。公开目录与桌码建会话是启动例外：目录由StoreDirectory解析请求店，桌码由唯一哈希查验后得到主体门店，不要求调用方预先伪造BizContext。匿名主体未获权威合同支持时禁止下交易单。

下列Service接口与ServiceImpl具有相同签名；Impl负责事务、对象归属、状态/金额/数量守卫、转换及Port编排。外层沿ApiResponseWrapper，不在Controller返回类型再包一层ApiResult。

Mapper的insert/select/update是继承BaseMapper，不重写空方法；Wrapper只能由Service构建并追加主体、门店或受控父引用，UPDATE必须带版本/状态。物理id仅内部使用，不能将外部业务号传selectById。查询分页返回IPage并由MybatisPages转换PageData。报告DAO使用既有JdbcClient技术栈。

调用图列出一个用例的候选分支及恢复步骤，不表示一次调用必须全执行。create/upsert分支须按API语义新增或受控更新；已发布快照、成交源行与审计事实只追加。方法名不等于数据库已具备并发安全；LOCK必须命中稳定仲裁行，空查询不能锁出钱箱/租约唯一写者。

## 二、按模块的Controller与Service方法

| 模块 | 业务 | 本模块API方法数 |
|---|---|---|
| MOD-01 | 桌台与就餐管理 | 41 |
| MOD-02 | 菜品与餐牌管理 | 45 |
| MOD-03 | 点餐与订单管理 | 19 |
| MOD-04 | 厨房与履约管理 | 14 |
| MOD-05 | 收银与结账管理 | 9 |
| MOD-06 | 退餐与售后管理 | 5 |
| MOD-07 | 打印、通知与设备管理 | 3 |
| MOD-08 | 班次与现金管理 | 4 |
| MOD-09 | 营业日、日结与对账管理 | 5 |
| MOD-10 | 经营报表管理 | 4 |
| MOD-11 | 专项业态管理 | 7 |
| MOD-12 | 第三方接入与离线营业 | 5 |
| MOD-13 | 营业配置与权限治理 | 4 |

### MOD-01 桌台与就餐管理

#### FOOD-API-024 · POST /biz/x/food/stores/{storeNo}/tables

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
TableResultVO createTable(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`StoreDirectoryPort`、`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-025 · POST /biz/x/food/tables/{tableNo}:rotate-token

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
RotateTokenTableResultVO rotateTokenTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody RotateTokenTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
RotateTokenTableResultVO rotateTokenTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, RotateTokenTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableTokenMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableTokenMapper.insert`（WRITE）；`FoodMappers.TableTokenMapper.update`（WRITE）；`FoodMappers.GuestSessionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-026 · POST /mp/x/food/sessions

Controller：`ai.neargo.shop.food.api.mp.MpDiningSessionController`

```java
DiningSessionResultVO createSession(@Valid @RequestBody CustomerCreateSessionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningSessionService` / `ai.neargo.shop.food.service.impl.DiningSessionServiceImpl`

```java
DiningSessionResultVO customerCreateSession(CustomerCreateSessionReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableTokenMapper.selectValidByTokenHash`（READ）；`FoodMappers.GuestSessionMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 桌码只授予桌级短会话，不自动授予同桌所有台账。 启动会话尚无BizContext，先以不可逆桌码哈希作唯一令牌查验，再从有效记录解析主体/门店；不接受Body自报entityNo。

#### FOOD-API-027 · POST /biz/x/food/stores/{storeNo}/reservations

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
ReservationResultVO createReservation(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateReservationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
ReservationResultVO createReservation(String entityNo, String storeNo, String actorNo, CreateReservationReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectList`（READ）；`FoodMappers.ReservationMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 资源hold与订金/预付款分别恢复；不伪造资源或资金成功。

#### FOOD-API-028 · POST /biz/x/food/reservations/{reservationNo}:cancel

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
OperationResultVO cancelReservation(@PathVariable("reservationNo") String reservationNo, @Valid @RequestBody CancelReservationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
OperationResultVO cancelReservation(String entityNo, String currentStoreNo, String reservationNo, String actorNo, CancelReservationReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectOne`（READ）；`FoodMappers.ReservationMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`AfterSalePort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-029 · POST /biz/x/food/reservations/{reservationNo}:no-show

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
OperationResultVO noShowReservation(@PathVariable("reservationNo") String reservationNo, @Valid @RequestBody NoShowReservationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
OperationResultVO noShowReservation(String entityNo, String currentStoreNo, String reservationNo, String actorNo, NoShowReservationReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectOne`（READ）；`FoodMappers.ReservationMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`AfterSalePort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-030 · POST /biz/x/food/reservations/{reservationNo}:arrive

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
ArriveReservationResultVO arriveReservation(@PathVariable("reservationNo") String reservationNo, @Valid @RequestBody ArriveReservationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
ArriveReservationResultVO arriveReservation(String entityNo, String currentStoreNo, String reservationNo, String actorNo, ArriveReservationReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.ReservationMapper.update`（WRITE）；`FoodMappers.CheckMapper.insert`（WRITE）；`FoodMappers.SeatMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.TableOperationMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 到店只生成一个台账，新占用失败保留原预约。

#### FOOD-API-031 · POST /biz/x/food/stores/{storeNo}/checks

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
CheckResultVO openCheck(@PathVariable("storeNo") String storeNo, @Valid @RequestBody OpenCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
CheckResultVO openCheck(String entityNo, String storeNo, String actorNo, OpenCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableOccupancyMapper.selectActiveByTableForUpdate`（LOCK）；`FoodMappers.CheckMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.SeatMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-032 · GET /biz/x/food/stores/{storeNo}/checks

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
PageData<CheckVO> listChecks(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListChecksQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
PageData<CheckVO> listChecks(String entityNo, String storeNo, ListChecksQuery query);
```

持久化：`FoodMappers.CheckMapper.selectPage`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-033 · POST /biz/x/food/checks/{checkNo}:transfer

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationResultVO transferCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody TransferCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO transferCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, TransferCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SettlementMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableOccupancyMapper.endOccupancyIfActive`（WRITE）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.CheckMapper.update`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 仅调整桌台占用与服务状态，不移动批次归属、不改结清事实；清台不删除未结应收。

#### FOOD-API-034 · POST /biz/x/food/checks/{checkNo}:merge

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationResultVO mergeCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody MergeCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO mergeCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, MergeCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.CheckBatchMembershipMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.SettlementMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.selectActiveByBatchForUpdate`（LOCK）；`FoodMappers.CheckBatchMembershipMapper.detachMembershipIfActive`（WRITE）；`FoodMappers.CheckMapper.update`（WRITE）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.update`（WRITE）；`FoodMappers.CheckRelationMapper.insert`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 锁定批次/台账及资源仲裁者；先占后放；服务归属协作不改核心资金归属。具体动作只写对应变化行。

#### FOOD-API-035 · POST /biz/x/food/checks/{checkNo}:split

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationResultVO splitCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody SplitCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO splitCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, SplitCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.CheckBatchMembershipMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.SettlementMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.selectActiveByBatchForUpdate`（LOCK）；`FoodMappers.CheckBatchMembershipMapper.detachMembershipIfActive`（WRITE）；`FoodMappers.CheckMapper.update`（WRITE）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.update`（WRITE）；`FoodMappers.CheckRelationMapper.insert`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 锁定批次/台账及资源仲裁者；先占后放；服务归属协作不改核心资金归属。具体动作只写对应变化行。

#### FOOD-API-036 · POST /biz/x/food/checks/{checkNo}:clear

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationVO clearCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody ClearCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationVO clearCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, ClearCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SettlementMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableOccupancyMapper.endOccupancyIfActive`（WRITE）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.CheckMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`、`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 仅调整桌台占用与服务状态，不移动批次归属、不改结清事实；清台不删除未结应收。

#### FOOD-API-092 · GET /biz/x/food/stores/{storeNo}/table-areas

Controller：`ai.neargo.shop.food.api.biz.BizTableAreaController`

```java
PageData<TableAreaVO> listTableAreas(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListTableAreasQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query);
```

持久化：`FoodMappers.TableAreaMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-093 · POST /biz/x/food/stores/{storeNo}/table-areas

Controller：`ai.neargo.shop.food.api.biz.BizTableAreaController`

```java
TableAreaResultVO createTableArea(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateTableAreaReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo, CreateTableAreaReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-094 · PUT /biz/x/food/table-areas/{areaNo}

Controller：`ai.neargo.shop.food.api.biz.BizTableAreaController`

```java
TableAreaResultVO saveTableArea(@PathVariable("areaNo") String areaNo, @Valid @RequestBody SaveTableAreaReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SaveTableAreaReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableAreaMapper.selectOne`（READ）；`FoodMappers.TableAreaMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-095 · POST /biz/x/food/table-areas/{areaNo}:set-status

Controller：`ai.neargo.shop.food.api.biz.BizTableAreaController`

```java
TableAreaResultVO setStatusTableArea(@PathVariable("areaNo") String areaNo, @Valid @RequestBody SetStatusTableAreaReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo, SetStatusTableAreaReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableAreaMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableAreaMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-096 · GET /biz/x/food/stores/{storeNo}/tables

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
PageData<TableVO> listTables(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListTablesQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query);
```

持久化：`FoodMappers.TableMapper.selectPage`（READ）；`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-097 · GET /biz/x/food/tables/{tableNo}

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
TableResultVO getTable(@PathVariable("tableNo") String tableNo);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-098 · PUT /biz/x/food/tables/{tableNo}

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
TableResultVO saveTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody SaveTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SaveTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableMapper.updateCapacityIfVersion`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-099 · POST /biz/x/food/tables/{tableNo}:set-status

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
TableResultVO setStatusTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody SetStatusTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, SetStatusTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-100 · POST /biz/x/food/tables/{tableNo}:archive

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
OperationResultVO archiveTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody ArchiveTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, ArchiveTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-101 · POST /biz/x/food/tables/{tableNo}:revoke-token

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
OperationResultVO revokeTokenTable(@PathVariable("tableNo") String tableNo, @Valid @RequestBody RevokeTokenTableReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.TableService` / `ai.neargo.shop.food.service.impl.TableServiceImpl`

```java
OperationResultVO revokeTokenTable(String entityNo, String currentStoreNo, String tableNo, String actorNo, RevokeTokenTableReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableTokenMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableTokenMapper.update`（WRITE）；`FoodMappers.GuestSessionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-102 · GET /biz/x/food/checks/{checkNo}

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
GetCheckResultVO getCheck(@PathVariable("checkNo") String checkNo);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
GetCheckResultVO getCheck(String entityNo, String currentStoreNo, String checkNo);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SeatMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-103 · PUT /biz/x/food/checks/{checkNo}/seats

Controller：`ai.neargo.shop.food.api.biz.BizCheckSeatController`

```java
CheckResultVO saveSeat(@PathVariable("checkNo") String checkNo, @Valid @RequestBody SaveSeatReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
CheckResultVO saveSeat(String entityNo, String currentStoreNo, String checkNo, String actorNo, SaveSeatReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SeatMapper.selectList`（READ）；`FoodMappers.CheckMapper.updatePartySizeIfVersion`（WRITE）；`FoodMappers.TableOccupancyMapper.update`（WRITE）；`FoodMappers.SeatMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-104 · POST /biz/x/food/checks/{checkNo}:combine-tables

Controller：`ai.neargo.shop.food.api.biz.BizCheckController`

```java
OperationResultVO combineTablesCheck(@PathVariable("checkNo") String checkNo, @Valid @RequestBody CombineTablesCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO combineTablesCheck(String entityNo, String currentStoreNo, String checkNo, String actorNo, CombineTablesCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.TableOperationMapper.insertOperationIfAbsent`（WRITE）；`FoodMappers.TableOperationMapper.advanceOperationIfVersion`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-105 · POST /biz/x/food/tables/{tableNo}:share

Controller：`ai.neargo.shop.food.api.biz.BizTableController`

```java
CheckResultVO openSharedCheck(@PathVariable("tableNo") String tableNo, @Valid @RequestBody OpenSharedCheckReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
CheckResultVO openSharedCheck(String entityNo, String currentStoreNo, String tableNo, String actorNo, OpenSharedCheckReq req, String idempotencyKey);
```

持久化：`FoodMappers.TableMapper.selectOne`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.TableMapper.selectForUpdateByStoreAndTableNo`（LOCK）；`FoodMappers.TableOccupancyMapper.selectActiveByTableForUpdate`（LOCK）；`FoodMappers.CheckMapper.insert`（WRITE）；`FoodMappers.TableOccupancyMapper.insert`（WRITE）；`FoodMappers.SeatMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-106 · GET /biz/x/food/table-operations/{operationNo}

Controller：`ai.neargo.shop.food.api.biz.BizTableOperationController`

```java
OperationResultVO getTableOperation(@PathVariable("operationNo") String operationNo);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
OperationResultVO getTableOperation(String entityNo, String currentStoreNo, String operationNo);
```

持久化：`FoodMappers.TableOperationMapper.selectOne`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-107 · GET /biz/x/food/stores/{storeNo}/reservations

Controller：`ai.neargo.shop.food.api.biz.BizReservationController`

```java
PageData<ReservationVO> listReservations(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListReservationsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.ReservationService` / `ai.neargo.shop.food.service.impl.ReservationServiceImpl`

```java
PageData<ReservationVO> listReservations(String entityNo, String storeNo, ListReservationsQuery query);
```

持久化：`FoodMappers.ReservationMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-108 · GET /biz/x/food/checks/{checkNo}/actions

Controller：`ai.neargo.shop.food.api.biz.BizDiningActionLogController`

```java
PageData<ActionLogVO> listActions(@PathVariable("checkNo") String checkNo, @Valid @ModelAttribute ListActionsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DiningAuditService` / `ai.neargo.shop.food.service.impl.DiningAuditServiceImpl`

```java
PageData<ActionLogVO> listActions(String entityNo, String currentStoreNo, String checkNo, ListActionsQuery query);
```

持久化：`FoodMappers.ActionLogMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-138 · GET /mp/x/food/checks/{checkNo}

Controller：`ai.neargo.shop.food.api.mp.MpCheckController`

```java
CustomerGetCheckResultVO getCheck(@PathVariable("checkNo") String checkNo);
```

Service / Impl：`ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`

```java
CustomerGetCheckResultVO customerGetCheck(String entityNo, String currentStoreNo, String checkNo, String authenticatedSessionNo);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.selectList`（READ）。

跨域：`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-139 · POST /mp/x/food/sessions/{sessionNo}:end

Controller：`ai.neargo.shop.food.api.mp.MpDiningSessionController`

```java
OperationResultVO endSession(@PathVariable("sessionNo") String sessionNo, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningSessionService` / `ai.neargo.shop.food.service.impl.DiningSessionServiceImpl`

```java
OperationResultVO customerEndSession(String entityNo, String currentStoreNo, String sessionNo, String authenticatedSessionNo, String actorNo, String idempotencyKey);
```

持久化：`FoodMappers.GuestSessionMapper.selectOne`（READ）；`FoodMappers.GuestSessionMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 结束本次会话，不取消正式订单。

#### FOOD-API-157 · POST /biz/x/food/queue-tickets

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO issue(@Valid @RequestBody IssueReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO issue(String entityNo, String currentStoreNo, String actorNo, IssueReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-158 · GET /biz/x/food/queue-tickets

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
ListQueueResultVO list(@Valid @ModelAttribute QueueListQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
ListQueueResultVO list(String entityNo, String currentStoreNo, QueueListQuery query);
```

持久化：`FoodMappers.QueueTicketMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 原排队票与可信当前门店范围。仅查持久票据，不补发通知/开台。

#### FOOD-API-159 · GET /biz/x/food/queue-tickets/{ticketNo}

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO get(@PathVariable("ticketNo") String ticketNo);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO get(String entityNo, String currentStoreNo, String ticketNo);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 原排队票与可信当前门店范围。仅查持久票据，不补发通知/开台。

#### FOOD-API-160 · POST /biz/x/food/queue-tickets:call-next

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO callNext(@Valid @RequestBody CallNextReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO callNext(String entityNo, String currentStoreNo, String actorNo, CallNextReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-161 · POST /biz/x/food/queue-tickets/{ticketNo}:call

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO call(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody CallReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO change(String entityNo, String currentStoreNo, String ticketNo, String actorNo, CallReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-162 · POST /biz/x/food/queue-tickets/{ticketNo}:cancel

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO cancel(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody CancelReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO change(String entityNo, String currentStoreNo, String ticketNo, String actorNo, CancelReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-163 · POST /biz/x/food/queue-tickets/{ticketNo}:no-show

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO noShow(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody NoShowReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO change(String entityNo, String currentStoreNo, String ticketNo, String actorNo, NoShowReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 原排队票与可信当前门店范围。锁定原门店策略及排队票，CAS/持久幂等操作与票状态同事务；排队号不作取餐号，未送通知不宣称成功。

#### FOOD-API-164 · POST /biz/x/food/queue-tickets/{ticketNo}:seat

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO seat(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody QueueSeatReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO seat(String entityNo, String currentStoreNo, String ticketNo, String actorNo, QueueSeatReq req, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 新就座锁定原票后，在同一food primary事务内调用CheckService.openQueuedCheck，仅限内部Java调用，不能经HTTP/remote；原开台、SEATED、queue动作与outbox同提交。冲突整体回滚，原CALLED票可重选；不把永久SEATING作为新流程。

#### FOOD-API-165 · POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating

Controller：`ai.neargo.shop.food.api.biz.BizQueueController`

```java
QueueResultVO recover(@PathVariable("ticketNo") String ticketNo, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.QueueService` / `ai.neargo.shop.food.service.impl.QueueServiceImpl`

```java
QueueResultVO recoverSeating(String entityNo, String currentStoreNo, String ticketNo, String actorNo, String idempotencyKey);
```

持久化：`FoodMappers.QueueTicketMapper.selectOne`（READ）；`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.QueueTicketMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 旧SEATING仅兼容原冻结命令/OPEN_CHECK原键回执恢复；未知保持未知，不创建替代开台。新seat走同primary原子事务。

### MOD-02 菜品与餐牌管理

#### FOOD-API-004 · POST /biz/x/food/stores/{storeNo}/menus

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
CreateBundleResultVOVO createMenu(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateMenuReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
CreateBundleResultVOVO createMenu(String entityNo, String storeNo, String actorNo, CreateMenuReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuMapper.insert`（WRITE）；`FoodMappers.MenuStoreMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-005 · GET /biz/x/food/stores/{storeNo}/menus/{configNo}

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
MenuConfigResultVO getMenu(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
MenuConfigResultVO getMenu(String entityNo, String storeNo, String configNo);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-006 · POST /biz/x/food/stores/{storeNo}/menus/{configNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizConfigRevisionController`

```java
PublishConfigResultVO publishMenu(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`

```java
PublishConfigResultVO publishMenu(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 由所属资源Service校验，调用ConfigRevisionService发布；冻结子定义和依赖版本，短事务CAS头。

#### FOOD-API-007 · POST /biz/x/food/stores/{storeNo}/option-groups

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
CreateBundleResultVOVO createOptionGroup(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateOptionGroupReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
CreateBundleResultVOVO createOptionGroup(String entityNo, String storeNo, String actorNo, CreateOptionGroupReq req, String idempotencyKey);
```

持久化：`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-008 · GET /biz/x/food/stores/{storeNo}/option-groups/{configNo}

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
ConfigResultVO getOptionGroup(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
ConfigResultVO getOptionGroup(String entityNo, String storeNo, String configNo);
```

持久化：`FoodMappers.OptionGroupMapper.selectOne`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-009 · POST /biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
ConfigPublicationVO publishOptionGroup(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
ConfigPublicationVO publishOptionGroup(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.OptionGroupMapper.selectOne`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.OptionGroupMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 由所属资源Service校验，调用ConfigRevisionService发布；冻结子定义和依赖版本，短事务CAS头。

#### FOOD-API-010 · POST /biz/x/food/stores/{storeNo}/bundles

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
CreateBundleResultVOVO createBundle(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateBundleReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
CreateBundleResultVOVO createBundle(String entityNo, String storeNo, String actorNo, CreateBundleReq req, String idempotencyKey);
```

持久化：`FoodMappers.BundleMapper.selectList`（READ）；`FoodMappers.BundleMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-011 · GET /biz/x/food/stores/{storeNo}/bundles/{configNo}

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
GetBundleVOVO getBundle(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
GetBundleVOVO getBundle(String entityNo, String storeNo, String configNo);
```

持久化：`FoodMappers.BundleMapper.selectOne`（READ）；`FoodMappers.BundleGroupMapper.selectList`（READ）；`FoodMappers.BundleChoiceMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-012 · POST /biz/x/food/stores/{storeNo}/bundles/{configNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
ConfigPublicationVO publishBundle(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
ConfigPublicationVO publishBundle(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.BundleMapper.selectOne`（READ）；`FoodMappers.BundleGroupMapper.selectList`（READ）；`FoodMappers.BundleChoiceMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.BundleMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 由所属资源Service校验，调用ConfigRevisionService发布；冻结子定义和依赖版本，短事务CAS头。

#### FOOD-API-013 · POST /biz/x/food/stores/{storeNo}/stations

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
StationContracts.StationMutationVO createStation(@PathVariable("storeNo") String storeNo, @Valid @RequestBody StationContracts.CreateStationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
StationContracts.StationMutationVO createStation(String entityNo, String storeNo, String actorNo, StationContracts.CreateStationReq req, String idempotencyKey);
```

持久化：`FoodMappers.StationMapper.selectList`（READ）；`FoodMappers.StationMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-014 · GET /biz/x/food/stores/{storeNo}/stations/{configNo}

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
StationContracts.GetStationVO getStation(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
StationContracts.GetStationVO getStation(String entityNo, String storeNo, String configNo);
```

持久化：`FoodMappers.StationMapper.selectOne`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-015 · POST /biz/x/food/stores/{storeNo}/stations/{configNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizConfigRevisionController`

```java
PublishConfigResultVO publishStation(@PathVariable("storeNo") String storeNo, @PathVariable("configNo") String configNo, @Valid @RequestBody PublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`

```java
PublishConfigResultVO publishStation(String entityNo, String storeNo, String configNo, String actorNo, PublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.StationMapper.selectOne`（READ）；`FoodMappers.SkuStationMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.StationMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 由所属资源Service校验，调用ConfigRevisionService发布；冻结子定义和依赖版本，短事务CAS头。

#### FOOD-API-016 · PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}

Controller：`ai.neargo.shop.food.api.biz.BizDishController`

```java
DishResultVO saveDish(@PathVariable("storeNo") String storeNo, @PathVariable("goodsNo") String goodsNo, @Valid @RequestBody SaveDishReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DishService` / `ai.neargo.shop.food.service.impl.DishServiceImpl`

```java
DishResultVO saveDish(String entityNo, String storeNo, String goodsNo, String actorNo, SaveDishReq req, String idempotencyKey);
```

持久化：`FoodMappers.DishMapper.selectOne`（READ）；`FoodMappers.DishMapper.insert`（WRITE）；`FoodMappers.DishMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-017 · PUT /biz/x/food/menus/{menuNo}/definition

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
Object replaceDefinition(@PathVariable("menuNo") String menuNo, @Valid @RequestBody MenuDefinitionCommand req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
Object replaceDefinition(String entityNo, String currentStoreNo, String menuNo, String actorNo, MenuDefinitionCommand req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.MenuItemOptionGroupMapper.selectList`（READ）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.MenuStoreMapper.insert`（WRITE）；`FoodMappers.MenuStoreMapper.update`（WRITE）；`FoodMappers.MenuContextMapper.insert`（WRITE）；`FoodMappers.MenuContextMapper.update`（WRITE）；`FoodMappers.MenuScheduleMapper.insert`（WRITE）；`FoodMappers.MenuScheduleMapper.update`（WRITE）；`FoodMappers.MenuExceptionMapper.insert`（WRITE）；`FoodMappers.MenuExceptionMapper.update`（WRITE）；`FoodMappers.MenuCategoryMapper.insert`（WRITE）；`FoodMappers.MenuCategoryMapper.update`（WRITE）；`FoodMappers.MenuItemMapper.insert`（WRITE）；`FoodMappers.MenuItemMapper.update`（WRITE）；`FoodMappers.MenuItemSkuMapper.insert`（WRITE）；`FoodMappers.MenuItemSkuMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）；`FoodMappers.MenuItemOptionGroupMapper.update`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 ALL显式SCHEDULE/COMPOSITION/CONTEXT分支；原身份在事务外，CAS锁内重捕获；只替换本分段，不改发布历史

#### FOOD-API-018 · PUT /biz/x/food/bundles/{skuNo}/definition

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
BundleMutationVOVO saveBundleDefinition(@PathVariable("skuNo") String skuNo, @Valid @RequestBody SaveBundleDefinitionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
BundleMutationVOVO saveBundleDefinition(String entityNo, String currentStoreNo, String skuNo, String actorNo, SaveBundleDefinitionReq req, String idempotencyKey);
```

持久化：`FoodMappers.BundleMapper.selectOne`（READ）；`FoodMappers.BundleGroupMapper.selectList`（READ）；`FoodMappers.BundleChoiceMapper.selectList`（READ）；`FoodMappers.BundleMapper.update`（WRITE）；`FoodMappers.BundleGroupMapper.insert`（WRITE）；`FoodMappers.BundleGroupMapper.update`（WRITE）；`FoodMappers.BundleChoiceMapper.insert`（WRITE）；`FoodMappers.BundleChoiceMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-019 · PUT /biz/x/food/option-groups/{groupNo}/items

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
BundleMutationVOVO saveItem(@PathVariable("groupNo") String groupNo, @Valid @RequestBody SaveItemReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
BundleMutationVOVO saveItem(String entityNo, String currentStoreNo, String groupNo, String actorNo, SaveItemReq req, String idempotencyKey);
```

持久化：`FoodMappers.OptionGroupMapper.selectOne`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.update`（WRITE）；`FoodMappers.OptionGroupItemMapper.insert`（WRITE）；`FoodMappers.OptionGroupItemMapper.update`（WRITE）；`FoodMappers.DishOptionGroupMapper.insert`（WRITE）；`FoodMappers.DishOptionGroupMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-020 · PUT /biz/x/food/modifiers/{modifierNo}

Controller：`ai.neargo.shop.food.api.biz.BizModifierController`

```java
BundleMutationVOVO saveModifier(@PathVariable("modifierNo") String modifierNo, @Valid @RequestBody SaveModifierReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ModifierService` / `ai.neargo.shop.food.service.impl.ModifierServiceImpl`

```java
BundleMutationVOVO saveModifier(String entityNo, String currentStoreNo, String modifierNo, String actorNo, SaveModifierReq req, String idempotencyKey);
```

持久化：`FoodMappers.ModifierMapper.selectOne`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.ModifierMapper.update`（WRITE）；`FoodMappers.ModifierComponentMapper.insert`（WRITE）；`FoodMappers.ModifierComponentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-021 · PUT /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
OperationVO replaceRoutes(@PathVariable("storeNo") String storeNo, @PathVariable("skuNo") String skuNo, @Valid @RequestBody StationContracts.ReplaceStationRoutesReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
OperationVO replaceRoutes(String entityNo, String storeNo, String skuNo, String actorNo, StationContracts.ReplaceStationRoutesReq req, String idempotencyKey);
```

持久化：`FoodMappers.SkuStationMapper.selectOne`（READ）；`FoodMappers.StationMapper.selectList`（READ）；`FoodMappers.SkuStationMapper.insert`（WRITE）；`FoodMappers.SkuStationMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsCatalogQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-022 · POST /biz/x/food/stores/{storeNo}/sales-restrictions

Controller：`ai.neargo.shop.food.api.biz.BizSalesRestrictionController`

```java
SalesRestrictionVO manage(@PathVariable("storeNo") String storeNo, @Valid @RequestBody SalesRestrictionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`

```java
SalesRestrictionVO manage(String entityNo, String storeNo, String actorNo, SalesRestrictionReq req, String idempotencyKey);
```

持久化：`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.insert`（WRITE）；`FoodMappers.SalesRestrictionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 CLEAR只解除指定限制；并发覆盖规则串行化，不更新实物库存。

#### FOOD-API-023 · GET /mp/x/food/catalog

Controller：`ai.neargo.shop.food.api.mp.MpCatalogController`

```java
CatalogVO read(@Valid @ModelAttribute CustomerReadQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.CatalogService` / `ai.neargo.shop.food.service.impl.CatalogServiceImpl`

```java
CatalogVO publicCatalog(String authenticatedSessionNo, CustomerReadQuery query);
```

持久化：`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.DishMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.ModifierMapper.selectList`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectEffectiveByConfigAndTime`（READ）。

跨域：`StoreDirectoryPort`、`GoodsCatalogQueryPort`、`DishOfferQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 已发布冻结定义及服务器当前时点，读取当前原价格及PLATFORM物理可售；UNKNOWN/null不伪造值；复杂配置需报价且公开输出不含渠道账号/价格策略

#### FOOD-API-109 · GET /biz/x/food/stores/{storeNo}/menus

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
PageData<MenuVO> listMenus(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListMenusQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
PageData<MenuVO> listMenus(String entityNo, String storeNo, ListMenusQuery query);
```

持久化：`FoodMappers.MenuMapper.selectPage`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-110 · PUT /biz/x/food/menus/{menuNo}

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
MenuResultVO saveMenu(@PathVariable("menuNo") String menuNo, @Valid @RequestBody SaveMenuReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
MenuResultVO saveMenu(String entityNo, String currentStoreNo, String menuNo, String actorNo, SaveMenuReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-111 · POST /biz/x/food/menus/{menuNo}:disable

Controller：`ai.neargo.shop.food.api.biz.BizMenuController`

```java
MenuResultVO disableMenu(@PathVariable("menuNo") String menuNo, @Valid @RequestBody DisableMenuReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuService` / `ai.neargo.shop.food.service.impl.MenuServiceImpl`

```java
MenuResultVO disableMenu(String entityNo, String currentStoreNo, String menuNo, String actorNo, DisableMenuReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuMapper.selectOne`（READ）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-112 · GET /biz/x/food/stores/{storeNo}/dishes

Controller：`ai.neargo.shop.food.api.biz.BizDishController`

```java
PageData<DishCatalogItemVO> listDishes(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListDishesQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DishService` / `ai.neargo.shop.food.service.impl.DishServiceImpl`

```java
PageData<DishCatalogItemVO> listDishes(String entityNo, String storeNo, ListDishesQuery query);
```

持久化：`FoodMappers.DishMapper.selectPage`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）。

跨域：`GoodsCatalogQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-113 · GET /biz/x/food/stores/{storeNo}/dishes/{goodsNo}

Controller：`ai.neargo.shop.food.api.biz.BizDishController`

```java
GetDishResultVO getDish(@PathVariable("storeNo") String storeNo, @PathVariable("goodsNo") String goodsNo);
```

Service / Impl：`ai.neargo.shop.food.service.DishService` / `ai.neargo.shop.food.service.impl.DishServiceImpl`

```java
GetDishResultVO getDish(String entityNo, String storeNo, String goodsNo);
```

持久化：`FoodMappers.DishMapper.selectOne`（READ）；`FoodMappers.DishOptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）。

跨域：`GoodsCatalogQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-114 · GET /biz/x/food/stores/{storeNo}/catalog

Controller：`ai.neargo.shop.food.api.biz.BizCatalogController`

```java
CatalogVO read(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute CatalogQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.CatalogService` / `ai.neargo.shop.food.service.impl.CatalogServiceImpl`

```java
CatalogVO read(String entityNo, String storeNo, CatalogQuery query);
```

持久化：`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuStoreMapper.selectList`（READ）；`FoodMappers.MenuContextMapper.selectList`（READ）；`FoodMappers.MenuScheduleMapper.selectList`（READ）；`FoodMappers.MenuExceptionMapper.selectList`（READ）；`FoodMappers.MenuCategoryMapper.selectList`（READ）；`FoodMappers.MenuItemMapper.selectList`（READ）；`FoodMappers.MenuItemSkuMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.DishMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.OptionGroupItemMapper.selectList`（READ）；`FoodMappers.ModifierMapper.selectList`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectEffectiveByConfigAndTime`（READ）。

跨域：`StoreDirectoryPort`、`GoodsCatalogQueryPort`、`DishOfferQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 已发布冻结定义及服务器当前时点，读取当前原价格及PLATFORM物理可售；UNKNOWN/null不伪造值；复杂配置需报价且公开输出不含渠道账号/价格策略

#### FOOD-API-115 · GET /biz/x/food/config-revisions

Controller：`ai.neargo.shop.food.api.biz.BizConfigRevisionController`

```java
PageData<ConfigRevisionVO> listConfigRevisions(@Valid @ModelAttribute ListConfigRevisionsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`

```java
PageData<ConfigRevisionVO> listConfigRevisions(String entityNo, String currentStoreNo, ListConfigRevisionsQuery query);
```

持久化：`FoodMappers.ConfigRevisionMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-116 · POST /biz/x/food/config-revisions/{revisionNo}:republish

Controller：`ai.neargo.shop.food.api.biz.BizConfigRevisionController`

```java
PublishConfigResultVO republishRevision(@PathVariable("revisionNo") String revisionNo, @Valid @RequestBody RepublishConfigReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`

```java
PublishConfigResultVO republishRevision(String entityNo, String currentStoreNo, String revisionNo, String actorNo, RepublishConfigReq req, String idempotencyKey);
```

持久化：`FoodMappers.ConfigRevisionMapper.selectOne`（READ）；`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.BundleMapper.selectList`（READ）；`FoodMappers.OptionGroupMapper.selectList`（READ）；`FoodMappers.ModifierMapper.selectList`（READ）；`FoodMappers.StationMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）；`FoodMappers.MenuMapper.update`（WRITE）；`FoodMappers.BundleMapper.update`（WRITE）；`FoodMappers.OptionGroupMapper.update`（WRITE）；`FoodMappers.ModifierMapper.update`（WRITE）；`FoodMappers.StationMapper.update`（WRITE）。

跨域：`GoodsQueryPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 按被冻结configKind的固定白名单分派，仅更新对应一种头；Mapper禁止动态SQL表名。以旧内容生成新发布，不覆盖旧快照、不形成Service互相注入循环。

#### FOOD-API-117 · POST /biz/x/food/modifiers/{modifierNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizModifierController`

```java
ConfigPublicationVO publishModifier(@PathVariable("modifierNo") String modifierNo, @Valid @RequestBody PublishModifierReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ModifierService` / `ai.neargo.shop.food.service.impl.ModifierServiceImpl`

```java
ConfigPublicationVO publishModifier(String entityNo, String currentStoreNo, String modifierNo, String actorNo, PublishModifierReq req, String idempotencyKey);
```

持久化：`FoodMappers.ModifierMapper.selectOne`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.insertImmutableRevision`（WRITE）；`FoodMappers.ModifierMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-118 · GET /biz/x/food/stores/{storeNo}/option-groups

Controller：`ai.neargo.shop.food.api.biz.BizOptionGroupController`

```java
PageData<ConfigVO> listOptionGroups(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListOptionGroupsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.OptionGroupService` / `ai.neargo.shop.food.service.impl.OptionGroupServiceImpl`

```java
PageData<ConfigVO> listOptionGroups(String entityNo, String storeNo, ListOptionGroupsQuery query);
```

持久化：`FoodMappers.OptionGroupMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-119 · GET /biz/x/food/stores/{storeNo}/bundles

Controller：`ai.neargo.shop.food.api.biz.BizBundleController`

```java
PageData<BundleConfigVOVO> listBundles(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListBundlesQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.BundleService` / `ai.neargo.shop.food.service.impl.BundleServiceImpl`

```java
PageData<BundleConfigVOVO> listBundles(String entityNo, String storeNo, ListBundlesQuery query);
```

持久化：`FoodMappers.BundleMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-120 · GET /biz/x/food/stores/{storeNo}/modifiers

Controller：`ai.neargo.shop.food.api.biz.BizModifierController`

```java
PageData<ModifierConfigVOVO> listModifiers(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListModifiersQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.ModifierService` / `ai.neargo.shop.food.service.impl.ModifierServiceImpl`

```java
PageData<ModifierConfigVOVO> listModifiers(String entityNo, String storeNo, ListModifiersQuery query);
```

持久化：`FoodMappers.ModifierMapper.selectPage`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-121 · GET /biz/x/food/stores/{storeNo}/stations

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
PageData<StationContracts.StationVO> listStations(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListStationsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
PageData<StationContracts.StationVO> listStations(String entityNo, String storeNo, ListStationsQuery query);
```

持久化：`FoodMappers.StationMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-122 · GET /biz/x/food/stores/{storeNo}/sales-restrictions

Controller：`ai.neargo.shop.food.api.biz.BizSalesRestrictionController`

```java
PageData<SalesRestrictionVO> list(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute SalesRestrictionQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`

```java
PageData<SalesRestrictionVO> list(String entityNo, String storeNo, SalesRestrictionQuery query);
```

持久化：`FoodMappers.SalesRestrictionMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-141 · POST /biz/x/food/stores/{storeNo}/modifiers

Controller：`ai.neargo.shop.food.api.biz.BizModifierController`

```java
CreateBundleResultVOVO createModifier(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateModifierReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ModifierService` / `ai.neargo.shop.food.service.impl.ModifierServiceImpl`

```java
CreateBundleResultVOVO createModifier(String entityNo, String storeNo, String actorNo, CreateModifierReq req, String idempotencyKey);
```

持久化：`FoodMappers.ModifierMapper.selectList`（READ）；`FoodMappers.ModifierComponentMapper.selectList`（READ）；`FoodMappers.ModifierMapper.insert`（WRITE）；`FoodMappers.ModifierComponentMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`GoodsQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-142 · POST /biz/x/food/stores/{storeNo}/menu-channel-bindings

Controller：`ai.neargo.shop.food.api.biz.BizMenuChannelBindingController`

```java
MenuChannelBindingVO createBinding(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateMenuChannelBindingReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuChannelBindingService` / `ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl`

```java
MenuChannelBindingVO createBinding(String entityNo, String storeNo, String actorNo, CreateMenuChannelBindingReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuChannelBindingMapper.selectList`（READ）；`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuChannelBindingMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 已实现草稿和授权启停；Keeta仅规划，缺真实提供方ACTIVE拒绝；公开目录禁止泄漏账号引用

#### FOOD-API-143 · GET /biz/x/food/stores/{storeNo}/menu-channel-bindings

Controller：`ai.neargo.shop.food.api.biz.BizMenuChannelBindingController`

```java
PageData<MenuChannelBindingVO> listBindings(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListBindingsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.MenuChannelBindingService` / `ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl`

```java
PageData<MenuChannelBindingVO> listBindings(String entityNo, String storeNo, ListBindingsQuery query);
```

持久化：`FoodMappers.MenuChannelBindingMapper.selectPage`（READ）；`FoodMappers.MenuMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 已实现草稿和授权启停；Keeta仅规划，缺真实提供方ACTIVE拒绝；公开目录禁止泄漏账号引用

#### FOOD-API-144 · GET /biz/x/food/menu-channel-bindings/{bindingNo}

Controller：`ai.neargo.shop.food.api.biz.BizMenuChannelBindingController`

```java
MenuChannelBindingVO getBinding(@PathVariable("bindingNo") String bindingNo);
```

Service / Impl：`ai.neargo.shop.food.service.MenuChannelBindingService` / `ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl`

```java
MenuChannelBindingVO getBinding(String entityNo, String currentStoreNo, String bindingNo);
```

持久化：`FoodMappers.MenuChannelBindingMapper.selectOne`（READ）；`FoodMappers.MenuMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 已实现草稿和授权启停；Keeta仅规划，缺真实提供方ACTIVE拒绝；公开目录禁止泄漏账号引用

#### FOOD-API-145 · PUT /biz/x/food/menu-channel-bindings/{bindingNo}

Controller：`ai.neargo.shop.food.api.biz.BizMenuChannelBindingController`

```java
MenuChannelBindingVO updateBinding(@PathVariable("bindingNo") String bindingNo, @Valid @RequestBody UpdateMenuChannelBindingReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.MenuChannelBindingService` / `ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl`

```java
MenuChannelBindingVO updateBinding(String entityNo, String currentStoreNo, String bindingNo, String actorNo, UpdateMenuChannelBindingReq req, String idempotencyKey);
```

持久化：`FoodMappers.MenuChannelBindingMapper.selectOne`（READ）；`FoodMappers.MenuMapper.selectList`（READ）；`FoodMappers.MenuChannelBindingMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 已实现草稿和授权启停；Keeta仅规划，缺真实提供方ACTIVE拒绝；公开目录禁止泄漏账号引用

#### FOOD-API-148 · POST /biz/x/food/stores/{storeNo}/recipes

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
RecipeResultVO createRecipe(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateRecipeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
RecipeResultVO createRecipe(String entityNo, String storeNo, String actorNo, CreateRecipeReq req, String idempotencyKey);
```

持久化：`FoodMappers.RecipeMapper.selectList`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）；`FoodMappers.RecipeMapper.update`（WRITE）；`FoodMappers.RecipeLineMapper.update`（WRITE）；`FoodMappers.RecipeAdjustmentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-149 · GET /biz/x/food/stores/{storeNo}/recipes

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
PageData<RecipeVO> listRecipes(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListRecipesQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
PageData<RecipeVO> listRecipes(String entityNo, String storeNo, ListRecipesQuery query);
```

持久化：`FoodMappers.RecipeMapper.selectPage`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-150 · GET /biz/x/food/recipes/{recipeNo}

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
RecipeResultVO getRecipe(@PathVariable("recipeNo") String recipeNo);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
RecipeResultVO getRecipe(String entityNo, String currentStoreNo, String recipeNo);
```

持久化：`FoodMappers.RecipeMapper.selectOne`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-151 · PUT /biz/x/food/recipes/{recipeNo}/definition

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
RecipeResultVO saveRecipeDefinition(@PathVariable("recipeNo") String recipeNo, @Valid @RequestBody SaveRecipeDefinitionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
RecipeResultVO saveRecipeDefinition(String entityNo, String currentStoreNo, String recipeNo, String actorNo, SaveRecipeDefinitionReq req, String idempotencyKey);
```

持久化：`FoodMappers.RecipeMapper.selectOne`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）；`FoodMappers.RecipeMapper.update`（WRITE）；`FoodMappers.RecipeLineMapper.update`（WRITE）；`FoodMappers.RecipeAdjustmentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-152 · POST /biz/x/food/recipes/{recipeNo}:publish

Controller：`ai.neargo.shop.food.api.biz.BizRecipeController`

```java
ConfigPublicationVO publishRecipe(@PathVariable("recipeNo") String recipeNo, @Valid @RequestBody PublishRecipeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.RecipeService` / `ai.neargo.shop.food.service.impl.RecipeServiceImpl`

```java
ConfigPublicationVO publishRecipe(String entityNo, String currentStoreNo, String recipeNo, String actorNo, PublishRecipeReq req, String idempotencyKey);
```

持久化：`FoodMappers.RecipeMapper.selectOne`（READ）；`FoodMappers.RecipeLineMapper.selectList`（READ）；`FoodMappers.RecipeAdjustmentMapper.selectList`（READ）；`FoodMappers.RecipeMapper.update`（WRITE）；`FoodMappers.RecipeLineMapper.update`（WRITE）；`FoodMappers.RecipeAdjustmentMapper.update`（WRITE）；`FoodMappers.ConfigRevisionMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-155 · GET /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}

Controller：`ai.neargo.shop.food.api.biz.BizStationController`

```java
StationContracts.StationRoutesVO getRoutes(@PathVariable("storeNo") String storeNo, @PathVariable("skuNo") String skuNo);
```

Service / Impl：`ai.neargo.shop.food.service.StationService` / `ai.neargo.shop.food.service.impl.StationServiceImpl`

```java
StationContracts.StationRoutesVO getRoutes(String entityNo, String storeNo, String skuNo);
```

持久化：`FoodMappers.SkuStationMapper.selectOne`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 ALL-04实际只读当前关系；空路由保留整体关系计数，主体门店隔离

### MOD-03 点餐与订单管理

#### FOOD-API-037 · GET /biz/x/food/carts/{cartNo}

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartResultVO getCart(@PathVariable("cartNo") String cartNo,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartResultVO getCart(String entityNo, String storeNo, String cartNo);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-038 · POST /biz/x/food/stores/{storeNo}/carts

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartResultVO createCart(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCartReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartResultVO createCart(String entityNo, String storeNo, String actorNo, CreateCartReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-039 · PUT /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartVersionVO replaceLine(@PathVariable("cartNo") String cartNo, @PathVariable("sourceLineRef") String sourceLineRef, @Valid @RequestBody ReplaceCartLineReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartVersionVO replaceLine(String entityNo, String storeNo, String cartNo, String sourceLineRef, String actorNo, ReplaceCartLineReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.SeatMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.history`（LOCK）；`FoodMappers.CheckCartItemMapper.retain`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.CheckCartItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。事务外读取原已发布CatalogService；QR与原车来源目录交集校验。

#### FOOD-API-040 · POST /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartVersionVO archiveLine(@PathVariable("cartNo") String cartNo, @PathVariable("sourceLineRef") String sourceLineRef, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartVersionVO archiveLine(String entityNo, String storeNo, String cartNo, String sourceLineRef, String actorNo, CartVersionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.history`（LOCK）；`FoodMappers.CheckCartItemMapper.retain`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-041 · POST /biz/x/food/stores/{storeNo}/quotes

Controller：`ai.neargo.shop.food.api.biz.BizDiningQuoteController`

```java
DiningQuoteResultVO quote(@PathVariable("storeNo") String storeNo, @Valid @RequestBody QuoteReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
DiningQuoteResultVO quote(String entityNo, String storeNo, String actorNo, QuoteReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）。

跨域：`GoodsQueryPort`、`OrderPlacementPort`。

事务：**NO_LOCAL_WRITE**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 报价由核心返回，绑定车版本、源行与规则指纹；不能餐饮重算价格。

#### FOOD-API-042 · POST /biz/x/food/stores/{storeNo}/batches

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
OrderBatchResultVO place(@PathVariable("storeNo") String storeNo, @Valid @RequestBody PlaceReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
OrderBatchResultVO place(String entityNo, String storeNo, String actorNo, PlaceReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.lockCartIfVersion`（LOCK）；`FoodMappers.CheckCartItemMapper.selectSelectedLinesForUpdate`（LOCK）；`FoodMappers.CheckCartItemMapper.archiveSelectedSubmittedLines`（WRITE）；`FoodMappers.OrderBatchMapper.insertBatchIfAbsent`（WRITE）；`FoodMappers.OrderBatchMapper.bindCoreResultIfVersion`（WRITE）；`FoodMappers.OrderBatchMapper.advancePlacementIfStatus`（WRITE）；`FoodMappers.CoreLineAllocationMapper.insertAllocationIfAbsent`（WRITE）；`FoodMappers.SalesRestrictionMapper.reserveQuotaIfCapacity`（WRITE）；`FoodMappers.SalesRestrictionMapper.consumeQuotaIfReserved`（WRITE）；`FoodMappers.SalesRestrictionMapper.releaseQuotaIfReserved`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.insertClaimIfAbsent`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.advanceClaimIfStatus`（WRITE）；`FoodMappers.OrderLineMapper.insert`（WRITE）；`FoodMappers.OrderComponentMapper.insert`（WRITE）；`FoodMappers.OrderModifierMapper.insert`（WRITE）；`FoodMappers.CoreOrderBindingMapper.insert`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.insert`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`、`InventoryCommitmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 意图与冻结源行本地提交后调用核心；UNKNOWN查同键；结果绑定再清理此次草稿，后付合同未齐禁用。

#### FOOD-API-043 · GET /biz/x/food/batches/{batchNo}

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
BatchDetailResultVO getBatch(@PathVariable("batchNo") String batchNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
BatchDetailResultVO getBatch(String entityNo, String currentStoreNo, String batchNo);
```

持久化：`FoodMappers.OrderBatchMapper.selectOne`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.OrderComponentMapper.selectList`（READ）；`FoodMappers.OrderModifierMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）。

跨域：`OrderPlacementPort`、`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-123 · GET /biz/x/food/stores/{storeNo}/batches

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
PageData<BatchVO> listBatchs(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListBatchsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
PageData<BatchVO> listBatchs(String entityNo, String storeNo, ListBatchsQuery query);
```

持久化：`FoodMappers.OrderBatchMapper.selectPage`（READ）；`FoodMappers.CheckBatchMembershipMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-124 · POST /biz/x/food/batches/{batchNo}:cancel

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
CancelBatchOrderBatchResultVO cancelBatch(@PathVariable("batchNo") String batchNo, @Valid @RequestBody CancelBatchReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
CancelBatchOrderBatchResultVO cancelBatch(String entityNo, String currentStoreNo, String batchNo, String actorNo, CancelBatchReq req, String idempotencyKey);
```

持久化：`FoodMappers.OrderBatchMapper.selectOne`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.SalesQuotaClaimMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.advancePlacementIfStatus`（WRITE）；`FoodMappers.SalesRestrictionMapper.releaseQuotaIfReserved`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.advanceClaimIfStatus`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`、`InventoryCommitmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-125 · GET /biz/x/food/batches/{batchNo}/operations

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchOperationController`

```java
PageData<OperationVO> listOperations(@PathVariable("batchNo") String batchNo, @Valid @ModelAttribute ListOperationsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
PageData<OperationVO> listOperations(String entityNo, String currentStoreNo, String batchNo, ListOperationsQuery query);
```

持久化：`FoodMappers.OrderBatchMapper.selectPage`（READ）。

跨域：`OrderPlacementPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 查询原意图/操作状态；除显式恢复任务外GET不补写业务状态。

#### FOOD-API-126 · POST /biz/x/food/carts/{cartNo}:archive

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
OperationResultVO archiveCart(@PathVariable("cartNo") String cartNo, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
OperationResultVO archiveCart(String entityNo, String storeNo, String cartNo, String actorNo, CartVersionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-127 · GET /mp/x/food/carts/{cartNo}

Controller：`ai.neargo.shop.food.api.mp.MpCartController`

```java
CartResultVO customerGetCart(@PathVariable("cartNo") String cartNo, @RequestParam("sessionNo") String sessionNo, @RequestHeader("X-Food-Session") String token,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartResultVO customerGetCart(String cartNo, CartAccess access);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-128 · POST /mp/x/food/stores/{storeNo}/carts

Controller：`ai.neargo.shop.food.api.mp.MpCartController`

```java
CartResultVO customerCreateCart(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCustomerCartReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader("X-Food-Session") String token,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartResultVO customerCreateCart(String storeNo, CartAccess access, CreateCustomerCartReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.insert`（WRITE）；`FoodMappers.GuestSessionMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-129 · PUT /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}

Controller：`ai.neargo.shop.food.api.mp.MpCartController`

```java
CartVersionVO customerReplaceLine(@PathVariable("cartNo") String cartNo, @PathVariable("sourceLineRef") String sourceLineRef, @Valid @RequestBody ReplaceCartLineReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestParam("sessionNo") String sessionNo, @RequestHeader("X-Food-Session") String token,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartVersionVO customerReplaceLine(String cartNo, String sourceLineRef, CartAccess access, ReplaceCartLineReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.SeatMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.history`（LOCK）；`FoodMappers.CheckCartItemMapper.retain`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.CheckCartItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。事务外读取原已发布CatalogService；QR与原车来源目录交集校验。

#### FOOD-API-130 · POST /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive

Controller：`ai.neargo.shop.food.api.mp.MpCartController`

```java
CartVersionVO customerArchiveLine(@PathVariable("cartNo") String cartNo, @PathVariable("sourceLineRef") String sourceLineRef, @Valid @RequestBody CartVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestParam("sessionNo") String sessionNo, @RequestHeader("X-Food-Session") String token,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartVersionVO customerArchiveLine(String cartNo, String sourceLineRef, CartAccess access, CartVersionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectOne`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.history`（LOCK）；`FoodMappers.CheckCartItemMapper.retain`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 实现以owner行锁→会话→车锁串行化；同check唯一OPEN车，保留sourceLineRef墓碑。C凭证在幂等重放前重新验证，草稿不触碰正式订单。

#### FOOD-API-131 · POST /mp/x/food/stores/{storeNo}/quotes

Controller：`ai.neargo.shop.food.api.mp.MpDiningQuoteController`

```java
DiningQuoteResultVO quote(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CustomerQuoteReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
DiningQuoteResultVO customerQuote(String entityNo, String storeNo, String authenticatedSessionNo, String actorNo, CustomerQuoteReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）。

跨域：`GoodsQueryPort`、`OrderPlacementPort`。

事务：**NO_LOCAL_WRITE**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 本人/明确授权台账范围；禁止现金/审批/店长权限。报价由核心返回，绑定车版本、源行与规则指纹；不能餐饮重算价格。

#### FOOD-API-132 · POST /mp/x/food/stores/{storeNo}/batches

Controller：`ai.neargo.shop.food.api.mp.MpOrderBatchController`

```java
OrderBatchResultVO place(@PathVariable("storeNo") String storeNo, @Valid @RequestBody PlaceReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
OrderBatchResultVO customerPlace(String entityNo, String storeNo, String authenticatedSessionNo, String actorNo, PlaceReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckCartMapper.selectList`（READ）；`FoodMappers.CheckCartItemMapper.selectList`（READ）；`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.SalesRestrictionMapper.selectList`（READ）；`FoodMappers.CheckCartMapper.lockCartIfVersion`（LOCK）；`FoodMappers.CheckCartItemMapper.selectSelectedLinesForUpdate`（LOCK）；`FoodMappers.CheckCartItemMapper.archiveSelectedSubmittedLines`（WRITE）；`FoodMappers.OrderBatchMapper.insertBatchIfAbsent`（WRITE）；`FoodMappers.OrderBatchMapper.bindCoreResultIfVersion`（WRITE）；`FoodMappers.OrderBatchMapper.advancePlacementIfStatus`（WRITE）；`FoodMappers.CoreLineAllocationMapper.insertAllocationIfAbsent`（WRITE）；`FoodMappers.SalesRestrictionMapper.reserveQuotaIfCapacity`（WRITE）；`FoodMappers.SalesRestrictionMapper.consumeQuotaIfReserved`（WRITE）；`FoodMappers.SalesRestrictionMapper.releaseQuotaIfReserved`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.insertClaimIfAbsent`（WRITE）；`FoodMappers.SalesQuotaClaimMapper.advanceClaimIfStatus`（WRITE）；`FoodMappers.OrderLineMapper.insert`（WRITE）；`FoodMappers.OrderComponentMapper.insert`（WRITE）；`FoodMappers.OrderModifierMapper.insert`（WRITE）；`FoodMappers.CoreOrderBindingMapper.insert`（WRITE）；`FoodMappers.CheckBatchMembershipMapper.insert`（WRITE）；`FoodMappers.CheckCartMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`、`InventoryCommitmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 本人/明确授权台账范围；禁止现金/审批/店长权限。意图与冻结源行本地提交后调用核心；UNKNOWN查同键；结果绑定再清理此次草稿，后付合同未齐禁用。

#### FOOD-API-133 · GET /mp/x/food/batches/{batchNo}

Controller：`ai.neargo.shop.food.api.mp.MpOrderBatchController`

```java
BatchDetailResultVO getBatch(@PathVariable("batchNo") String batchNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
BatchDetailResultVO customerGetBatch(String entityNo, String currentStoreNo, String batchNo, String authenticatedSessionNo);
```

持久化：`FoodMappers.OrderBatchMapper.selectOne`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.OrderComponentMapper.selectList`（READ）；`FoodMappers.OrderModifierMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）。

跨域：`OrderPlacementPort`、`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 本人/明确授权台账范围；禁止现金/审批/店长权限。

#### FOOD-API-156 · POST /biz/x/food/checks/{checkNo}/cart-sessions

Controller：`ai.neargo.shop.food.api.biz.BizCartController`

```java
CartSessionGrantVO authorizeSession(@PathVariable("checkNo") String checkNo, @Valid @RequestBody AuthorizeCartSessionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey,  jakarta.servlet.http.HttpServletRequest request);
```

Service / Impl：`ai.neargo.shop.food.service.CheckCartService` / `ai.neargo.shop.food.service.impl.CheckCartServiceImpl`

```java
CartSessionGrantVO authorizeSession(String entityNo, String storeNo, String checkNo, String actorNo, AuthorizeCartSessionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.GuestSessionMapper.selectList`（READ）；`FoodMappers.TableMapper.selectList`（READ）；`FoodMappers.TableAreaMapper.selectList`（READ）；`FoodMappers.TableOccupancyMapper.selectList`（READ）；`FoodMappers.GuestSessionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 复用原哈希会话；店员授权不会泄露凭证，不赋予支付或退款权限。

### MOD-04 厨房与履约管理

#### FOOD-API-044 · GET /biz/x/food/stores/{storeNo}/kitchen/tasks

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
PageData<TaskVO> listTasks(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListTasksQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
PageData<TaskVO> listTasks(String entityNo, String storeNo, ListTasksQuery query);
```

持久化：`FoodMappers.KitchenTaskMapper.selectPage`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-045 · POST /biz/x/food/tasks/{taskNo}:start

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO startTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody StartTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO startTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, StartTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-046 · POST /biz/x/food/tasks/{taskNo}:ready

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO readyTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody ReadyTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO readyTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, ReadyTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-047 · POST /biz/x/food/tasks/{taskNo}:serve

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO serveTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody ServeTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO serveTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, ServeTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-048 · POST /biz/x/food/tasks/{taskNo}:hold

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO holdTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody HoldTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO holdTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, HoldTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.changePendingStateIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-049 · POST /biz/x/food/tasks/{taskNo}:fire

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO fireTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody FireTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO fireTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, FireTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.changePendingStateIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-050 · POST /biz/x/food/tasks/{taskNo}:urge

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO urgeTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody UrgeTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO urgeTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, UrgeTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 仅追加催菜事实/审计；不推进任务数量，不重复派工或计销量。

#### FOOD-API-051 · POST /biz/x/food/tasks/{taskNo}:void

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
KitchenTaskResultVO voidTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody VoidTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
KitchenTaskResultVO voidTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, VoidTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.ReturnMapper.selectList`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 start/ready/serve推进数量；hold/fire/urge不增加销量；void必须退餐授权。

#### FOOD-API-052 · POST /biz/x/food/tasks/{taskNo}:remake

Controller：`ai.neargo.shop.food.api.biz.BizKitchenTaskController`

```java
RemakeTaskKitchenTaskResultVO remakeTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody RemakeTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`

```java
RemakeTaskKitchenTaskResultVO remakeTask(String entityNo, String currentStoreNo, String taskNo, String actorNo, RemakeTaskReq req, String idempotencyKey);
```

持久化：`FoodMappers.KitchenTaskMapper.selectOne`（READ）；`FoodMappers.KitchenTicketMapper.selectList`（READ）；`FoodMappers.KitchenTicketMapper.insertDispatchIfAbsent`（WRITE）；`FoodMappers.ServiceEventMapper.appendEventIfAbsent`（WRITE）；`FoodMappers.KitchenTaskMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-053 · POST /biz/x/food/checks/{checkNo}/courses

Controller：`ai.neargo.shop.food.api.biz.BizCourseController`

```java
CourseResultVO createCourse(@PathVariable("checkNo") String checkNo, @Valid @RequestBody CreateCourseReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CourseService` / `ai.neargo.shop.food.service.impl.CourseServiceImpl`

```java
CourseResultVO createCourse(String entityNo, String currentStoreNo, String checkNo, String actorNo, CreateCourseReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.CourseMapper.selectList`（READ）；`FoodMappers.CourseMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-054 · POST /biz/x/food/courses/{courseNo}:fire

Controller：`ai.neargo.shop.food.api.biz.BizCourseController`

```java
OperationVO fireCourse(@PathVariable("courseNo") String courseNo, @Valid @RequestBody FireCourseReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CourseService` / `ai.neargo.shop.food.service.impl.CourseServiceImpl`

```java
OperationVO fireCourse(String entityNo, String currentStoreNo, String courseNo, String actorNo, FireCourseReq req, String idempotencyKey);
```

持久化：`FoodMappers.CourseMapper.selectOne`（READ）；`FoodMappers.KitchenTaskMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.advanceTaskQuantityIfVersion`（WRITE）；`FoodMappers.CourseMapper.update`（WRITE）；`FoodMappers.ServiceEventMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-055 · POST /biz/x/food/batches/{batchNo}:handover

Controller：`ai.neargo.shop.food.api.biz.BizOrderBatchController`

```java
HandoverBatchOrderBatchResultVO handoverBatch(@PathVariable("batchNo") String batchNo, @Valid @RequestBody HandoverBatchReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`

```java
HandoverBatchOrderBatchResultVO handoverBatch(String entityNo, String currentStoreNo, String batchNo, String actorNo, HandoverBatchReq req, String idempotencyKey);
```

持久化：`FoodMappers.OrderBatchMapper.selectOne`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.update`（WRITE）；`FoodMappers.ServiceEventMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`DiningFulfillmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 核心核销/配送交接为权威，部分交付与制作状态不能混写。

#### FOOD-API-153 · GET /biz/x/food/stores/{storeNo}/production-consumptions

Controller：`ai.neargo.shop.food.api.biz.BizProductionConsumptionController`

```java
PageData<ProductionConsumptionVO> list(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute ListQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.ProductionConsumptionService` / `ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl`

```java
PageData<ProductionConsumptionVO> list(String entityNo, String storeNo, ListQuery query);
```

持久化：`FoodMappers.ProductionConsumptionMapper.selectPage`（READ）；`FoodMappers.ProductionConsumptionLineMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 CODED_PENDING_VERIFICATION；可信主体/门店/actor；from/to营业日区间最多93日，SQL同过滤COUNT与分页；共享PageData<ConsumptionVO>真实回执递归，BLOCKED/UNKNOWN/未知成本保真；只读不补扣库存。

#### FOOD-API-154 · GET /biz/x/food/production-consumptions/{consumptionNo}

Controller：`ai.neargo.shop.food.api.biz.BizProductionConsumptionController`

```java
ProductionConsumptionResultVO getProductionConsumption(@PathVariable("consumptionNo") String consumptionNo);
```

Service / Impl：`ai.neargo.shop.food.service.ProductionConsumptionService` / `ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl`

```java
ProductionConsumptionResultVO getProductionConsumption(String entityNo, String currentStoreNo, String consumptionNo);
```

持久化：`FoodMappers.ProductionConsumptionMapper.selectOne`（READ）；`FoodMappers.ProductionConsumptionLineMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

### MOD-05 收银与结账管理

#### FOOD-API-056 · POST /biz/x/food/stores/{storeNo}/settlements

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
DiningCheckoutResultVO createSettlement(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
DiningCheckoutResultVO createSettlement(String entityNo, String storeNo, String actorNo, CreateSettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.SettlementMapper.insertFrozenScope`（WRITE）；`FoodMappers.SettlementItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 跨结账/跨根应收独占由核心持有；本地唯一键不等于防双收。

#### FOOD-API-057 · GET /biz/x/food/settlements/{settlementNo}

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
GetSettlementDiningCheckoutResultVO getSettlement(@PathVariable("settlementNo") String settlementNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
GetSettlementDiningCheckoutResultVO getSettlement(String entityNo, String currentStoreNo, String settlementNo);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）。

跨域：`CashierPort`、`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-058 · POST /biz/x/food/settlements/{settlementNo}:pay

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
PaySettlementDiningCheckoutResultVO paySettlement(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody PaySettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
PaySettlementDiningCheckoutResultVO paySettlement(String entityNo, String currentStoreNo, String settlementNo, String actorNo, PaySettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.CashShiftMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.insertIntentIfAbsent`（WRITE）；`FoodMappers.ChargeIntentMapper.confirmIntentFromCoreIfStatus`（WRITE）；`FoodMappers.CashPaymentLinkMapper.linkCorePaymentIfAbsent`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`、`PaymentQueryPort`、`MemberAssetPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-059 · POST /biz/x/food/settlements/{settlementNo}:cancel

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
OperationResultVO cancelSettlement(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody CancelSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
OperationResultVO cancelSettlement(String entityNo, String currentStoreNo, String settlementNo, String actorNo, CancelSettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.SettlementMapper.update`（WRITE）；`FoodMappers.ChargeIntentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`、`PaymentQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 仅核心确认无成功/未知不可撤销资金才能解冻。

#### FOOD-API-060 · POST /biz/x/food/settlements/{settlementNo}:finalize

Controller：`ai.neargo.shop.food.api.biz.BizDiningCheckoutController`

```java
DiningCheckoutResultVO finalizeSettlement(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody FinalizeSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
DiningCheckoutResultVO finalizeSettlement(String entityNo, String currentStoreNo, String settlementNo, String actorNo, FinalizeSettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.SettlementMapper.finalizeIfCoreConfirmed`（WRITE）；`FoodMappers.CheckMapper.closeIfSettlementConfirmed`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`、`PaymentQueryPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-061 · POST /biz/x/food/settlements/{settlementNo}/adjustments

Controller：`ai.neargo.shop.food.api.biz.BizDiningAdjustmentController`

```java
DiningAdjustmentResultVO createAdjustment(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody CreateDiningAdjustmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
DiningAdjustmentResultVO createAdjustment(String entityNo, String currentStoreNo, String settlementNo, String actorNo, CreateDiningAdjustmentReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderAdjustmentPort`、`ApprovalPort`、`AccountsReceivablePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-134 · GET /mp/x/food/settlements/{settlementNo}

Controller：`ai.neargo.shop.food.api.mp.MpDiningCheckoutController`

```java
GetSettlementDiningCheckoutResultVO getSettlement(@PathVariable("settlementNo") String settlementNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
GetSettlementDiningCheckoutResultVO customerGetSettlement(String entityNo, String currentStoreNo, String settlementNo, String authenticatedSessionNo);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）。

跨域：`CashierPort`、`PaymentQueryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 本人/明确授权台账范围；禁止现金/审批/店长权限。

#### FOOD-API-135 · POST /mp/x/food/settlements/{settlementNo}:pay

Controller：`ai.neargo.shop.food.api.mp.MpDiningCheckoutController`

```java
PaySettlementDiningCheckoutResultVO paySettlement(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody CustomerPaySettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
PaySettlementDiningCheckoutResultVO customerPaySettlement(String entityNo, String currentStoreNo, String settlementNo, String authenticatedSessionNo, String actorNo, CustomerPaySettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.insertIntentIfAbsent`（WRITE）；`FoodMappers.ChargeIntentMapper.confirmIntentFromCoreIfStatus`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`、`PaymentQueryPort`、`MemberAssetPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 本人/明确授权台账范围；禁止现金/审批/店长权限。

#### FOOD-API-140 · POST /mp/x/food/checks/{checkNo}/settlements

Controller：`ai.neargo.shop.food.api.mp.MpDiningCheckoutController`

```java
DiningCheckoutResultVO createSettlement(@PathVariable("checkNo") String checkNo, @Valid @RequestBody CustomerCreateSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`

```java
DiningCheckoutResultVO customerCreateSettlement(String entityNo, String currentStoreNo, String checkNo, String authenticatedSessionNo, String actorNo, CustomerCreateSettlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.CoreOrderBindingMapper.selectList`（READ）；`FoodMappers.SettlementItemMapper.selectList`（READ）；`FoodMappers.SettlementMapper.insertFrozenScope`（WRITE）；`FoodMappers.SettlementItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`CashierPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 顾客仅获准源行/应收范围；不接受任意核心单号。跨结账/跨根应收独占由核心持有；本地唯一键不等于防双收。

### MOD-06 退餐与售后管理

#### FOOD-API-062 · POST /biz/x/food/stores/{storeNo}/returns

Controller：`ai.neargo.shop.food.api.biz.BizDiningReturnController`

```java
DiningReturnResultVO createReturn(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateReturnReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
DiningReturnResultVO createReturn(String entityNo, String storeNo, String actorNo, CreateReturnReq req, String idempotencyKey);
```

持久化：`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）；`FoodMappers.ReturnMapper.selectList`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.ReturnItemMapper.selectActiveReturnQuantityByFoodLine`（READ）；`FoodMappers.ReturnMapper.insert`（WRITE）；`FoodMappers.ReturnItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 锁来源行后核对有效申请退量再插入；Mapper不按现菜单价算退款。

#### FOOD-API-063 · POST /biz/x/food/returns/{returnNo}:execute

Controller：`ai.neargo.shop.food.api.biz.BizDiningReturnController`

```java
ExecuteReturnDiningReturnResultVO executeReturn(@PathVariable("returnNo") String returnNo, @Valid @RequestBody ExecuteReturnReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
ExecuteReturnDiningReturnResultVO executeReturn(String entityNo, String currentStoreNo, String returnNo, String actorNo, ExecuteReturnReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReturnMapper.selectOne`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.KitchenTaskMapper.selectList`（READ）；`FoodMappers.OrderLineMapper.selectScopedByKeyForUpdate`（LOCK）；`FoodMappers.ReturnItemMapper.selectActiveReturnQuantityByFoodLine`（READ）；`FoodMappers.ReturnMapper.update`（WRITE）；`FoodMappers.KitchenTaskMapper.update`（WRITE）；`FoodMappers.ServiceEventMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`AfterSalePort`、`OrderAdjustmentPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 核心财务与厨房两条轨道，部分退款能力未齐禁用。

#### FOOD-API-064 · GET /biz/x/food/returns/{returnNo}

Controller：`ai.neargo.shop.food.api.biz.BizDiningReturnController`

```java
GetReturnDiningReturnResultVO getReturn(@PathVariable("returnNo") String returnNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
GetReturnDiningReturnResultVO getReturn(String entityNo, String currentStoreNo, String returnNo);
```

持久化：`FoodMappers.ReturnMapper.selectOne`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）。

跨域：`AfterSalePort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-136 · POST /mp/x/food/stores/{storeNo}/returns

Controller：`ai.neargo.shop.food.api.mp.MpDiningReturnController`

```java
DiningReturnResultVO createReturn(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateReturnReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
DiningReturnResultVO customerCreateReturn(String entityNo, String storeNo, String authenticatedSessionNo, String actorNo, CreateReturnReq req, String idempotencyKey);
```

持久化：`FoodMappers.OrderLineMapper.selectList`（READ）；`FoodMappers.CoreLineAllocationMapper.selectList`（READ）；`FoodMappers.ReturnMapper.selectList`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）；`FoodMappers.ReturnItemMapper.selectActiveReturnQuantityByFoodLine`（READ）；`FoodMappers.ReturnMapper.insert`（WRITE）；`FoodMappers.ReturnItemMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 本人/明确授权台账范围；禁止现金/审批/店长权限。锁来源行后核对有效申请退量再插入；Mapper不按现菜单价算退款。

#### FOOD-API-137 · GET /mp/x/food/returns/{returnNo}

Controller：`ai.neargo.shop.food.api.mp.MpDiningReturnController`

```java
GetReturnDiningReturnResultVO getReturn(@PathVariable("returnNo") String returnNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`

```java
GetReturnDiningReturnResultVO customerGetReturn(String entityNo, String currentStoreNo, String returnNo, String authenticatedSessionNo);
```

持久化：`FoodMappers.ReturnMapper.selectOne`（READ）；`FoodMappers.ReturnItemMapper.selectList`（READ）。

跨域：`AfterSalePort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 本人/明确授权台账范围；禁止现金/审批/店长权限。

### MOD-07 打印、通知与设备管理

#### FOOD-API-065 · POST /biz/print/jobs

Controller：`ai.neargo.shop.food.api.biz.BizPrintJobController`

```java
PrintJobResultVO createJob(@Valid @RequestBody CreateJobReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.printing.service.PrintService` / `ai.neargo.shop.printing.service.impl.PrintServiceImpl`

```java
PrintJobResultVO createJob(String entityNo, String currentStoreNo, String actorNo, CreateJobReq req, String idempotencyKey);
```

持久化：`PrintMappers.DeviceMapper.selectList`（READ）；`PrintMappers.RouteMapper.selectList`（READ）；`PrintMappers.TemplateMapper.selectList`（READ）；`PrintMappers.JobMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 共享打印持有队列，food只冻结票面；此段是共享模块目标方法，不在food-core实现其Mapper。 共享PrintService经受控票面提供者取得冻结内容，再本地写队列/outbox；不回调自己实现的PrintPort。 共享B端目标路径NOT_REGISTERED；以下目标方法/Req/VO为PLANNED_TRANSPORT_CONTRACT，不声称存在对应Java；实际FOOD打印Controller/DTO及delegate见actualImplementations。

#### FOOD-API-066 · GET /biz/print/jobs/{jobNo}

Controller：`ai.neargo.shop.food.api.biz.BizPrintJobController`

```java
GetJobPrintJobResultVO getJob(@PathVariable("jobNo") String jobNo);
```

Service / Impl：`ai.neargo.shop.printing.service.PrintService` / `ai.neargo.shop.printing.service.impl.PrintServiceImpl`

```java
GetJobPrintJobResultVO getJob(String entityNo, String currentStoreNo, String jobNo);
```

持久化：`PrintMappers.JobMapper.selectOne`（READ）；`PrintMappers.AttemptMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 共享B端目标路径NOT_REGISTERED；以下目标方法/Req/VO为PLANNED_TRANSPORT_CONTRACT，不声称存在对应Java；实际FOOD打印Controller/DTO及delegate见actualImplementations。

#### FOOD-API-067 · POST /biz/print/jobs/{jobNo}:reprint

Controller：`ai.neargo.shop.food.api.biz.BizPrintJobController`

```java
ReprintJobResultVO reprintJob(@PathVariable("jobNo") String jobNo, @Valid @RequestBody ReprintJobReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.printing.service.PrintService` / `ai.neargo.shop.printing.service.impl.PrintServiceImpl`

```java
ReprintJobResultVO reprintJob(String entityNo, String currentStoreNo, String jobNo, String actorNo, ReprintJobReq req, String idempotencyKey);
```

持久化：`PrintMappers.JobMapper.selectOne`（READ）；`PrintMappers.DeviceMapper.selectList`（READ）；`PrintMappers.JobMapper.update`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。 创建新补打任务并引用原任务，不再次派工。 共享PrintService经受控票面提供者取得冻结内容，再本地写队列/outbox；不回调自己实现的PrintPort。 共享B端目标路径NOT_REGISTERED；以下目标方法/Req/VO为PLANNED_TRANSPORT_CONTRACT，不声称存在对应Java；实际FOOD打印Controller/DTO及delegate见actualImplementations。

### MOD-08 班次与现金管理

#### FOOD-API-068 · POST /biz/x/food/stores/{storeNo}/cash-shifts

Controller：`ai.neargo.shop.food.api.biz.BizCashShiftController`

```java
CashShiftResultVO createCashShift(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCashShiftReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CashShiftService` / `ai.neargo.shop.food.service.impl.CashShiftServiceImpl`

```java
CashShiftResultVO createCashShift(String entityNo, String storeNo, String actorNo, CreateCashShiftReq req, String idempotencyKey);
```

持久化：`FoodMappers.CashShiftMapper.selectList`（READ）；`FoodMappers.CashShiftMapper.selectActiveDrawerForUpdate`（LOCK）；`FoodMappers.CashShiftMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ResourcePort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 钱箱无活动行时不能靠SELECT FOR UPDATE锁空结果；须共享资源/稳定钱箱仲裁者，否则禁用并发开班。

#### FOOD-API-069 · POST /biz/x/food/cash-shifts/{shiftNo}/entries

Controller：`ai.neargo.shop.food.api.biz.BizCashEntryController`

```java
CashEntryResultVO createEntry(@PathVariable("shiftNo") String shiftNo, @Valid @RequestBody CreateCashEntryReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CashShiftService` / `ai.neargo.shop.food.service.impl.CashShiftServiceImpl`

```java
CashEntryResultVO createEntry(String entityNo, String currentStoreNo, String shiftNo, String actorNo, CreateCashEntryReq req, String idempotencyKey);
```

持久化：`FoodMappers.CashShiftMapper.selectOne`（READ）；`FoodMappers.CashEntryMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-070 · POST /biz/x/food/cash-shifts/{shiftNo}:count

Controller：`ai.neargo.shop.food.api.biz.BizCashShiftController`

```java
CountCashShiftResultVO countCashShift(@PathVariable("shiftNo") String shiftNo, @Valid @RequestBody CountCashShiftReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CashShiftService` / `ai.neargo.shop.food.service.impl.CashShiftServiceImpl`

```java
CountCashShiftResultVO countCashShift(String entityNo, String currentStoreNo, String shiftNo, String actorNo, CountCashShiftReq req, String idempotencyKey);
```

持久化：`FoodMappers.CashShiftMapper.selectOne`（READ）；`FoodMappers.CashEntryMapper.selectList`（READ）；`FoodMappers.CashPaymentLinkMapper.selectList`（READ）；`FoodMappers.CashCountMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ReconciliationPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 先冻结权威收款水位核对后短事务记录实盘；盲盘前不泄露应有现金。

#### FOOD-API-071 · POST /biz/x/food/cash-shifts/{shiftNo}:handover

Controller：`ai.neargo.shop.food.api.biz.BizCashShiftController`

```java
HandoverCashShiftResultVO handoverCashShift(@PathVariable("shiftNo") String shiftNo, @Valid @RequestBody HandoverCashShiftReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CashShiftService` / `ai.neargo.shop.food.service.impl.CashShiftServiceImpl`

```java
HandoverCashShiftResultVO handoverCashShift(String entityNo, String currentStoreNo, String shiftNo, String actorNo, HandoverCashShiftReq req, String idempotencyKey);
```

持久化：`FoodMappers.CashShiftMapper.selectOne`（READ）；`FoodMappers.CashCountMapper.selectList`（READ）；`FoodMappers.CashShiftMapper.handoverIfVersion`（WRITE）；`FoodMappers.CashCountMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

### MOD-09 营业日、日结与对账管理

#### FOOD-API-072 · POST /biz/x/food/stores/{storeNo}/closes

Controller：`ai.neargo.shop.food.api.biz.BizBusinessCloseController`

```java
BusinessCloseResultVO createClose(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCloseReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
BusinessCloseResultVO createClose(String entityNo, String storeNo, String actorNo, CreateCloseReq req, String idempotencyKey);
```

持久化：`FoodMappers.BusinessDayMapper.selectList`（READ）；`FoodMappers.OrderBatchMapper.selectList`（READ）；`FoodMappers.ChargeIntentMapper.selectList`（READ）；`FoodMappers.ReturnMapper.selectList`（READ）；`FoodMappers.CashShiftMapper.selectList`（READ）；`FoodMappers.SyncBatchMapper.selectList`（READ）；`FoodMappers.BusinessDayMapper.insert`（WRITE）；`FoodMappers.CloseRunMapper.insert`（WRITE）；`FoodMappers.CloseBalanceMapper.insert`（WRITE）；`FoodMappers.CloseExceptionMapper.insert`（WRITE）。

跨域：`ReconciliationPort`、`DiningReportPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 事实冻结水位，未决资金/离线同步列明；不把报表结果当收款。

#### FOOD-API-073 · GET /biz/x/food/closes/{closeNo}

Controller：`ai.neargo.shop.food.api.biz.BizBusinessCloseController`

```java
BusinessCloseResultVO getClose(@PathVariable("closeNo") String closeNo);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
BusinessCloseResultVO getClose(String entityNo, String currentStoreNo, String closeNo);
```

持久化：`FoodMappers.CloseRunMapper.selectOne`（READ）；`FoodMappers.CloseBalanceMapper.selectList`（READ）；`FoodMappers.CloseExceptionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-074 · POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry

Controller：`ai.neargo.shop.food.api.biz.BizCloseExceptionController`

```java
OperationVO carryException(@PathVariable("closeNo") String closeNo, @PathVariable("exceptionNo") String exceptionNo, @Valid @RequestBody CarryExceptionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
OperationVO carryException(String entityNo, String currentStoreNo, String closeNo, String exceptionNo, String actorNo, CarryExceptionReq req, String idempotencyKey);
```

持久化：`FoodMappers.CloseRunMapper.selectOne`（READ）；`FoodMappers.CloseExceptionMapper.selectList`（READ）；`FoodMappers.BusinessDayMapper.selectList`（READ）；`FoodMappers.CloseExceptionMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-075 · POST /biz/x/food/closes/{closeNo}:sign

Controller：`ai.neargo.shop.food.api.biz.BizBusinessCloseController`

```java
BusinessCloseResultVO signClose(@PathVariable("closeNo") String closeNo, @Valid @RequestBody SignCloseReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
BusinessCloseResultVO signClose(String entityNo, String currentStoreNo, String closeNo, String actorNo, SignCloseReq req, String idempotencyKey);
```

持久化：`FoodMappers.BusinessDayMapper.selectOne`（READ）；`FoodMappers.CloseRunMapper.selectList`（READ）；`FoodMappers.CloseBalanceMapper.selectList`（READ）；`FoodMappers.CloseExceptionMapper.selectList`（READ）；`FoodMappers.CloseRunMapper.signIfVersionHashAndWatermark`（WRITE）；`FoodMappers.BusinessDayMapper.replaceCurrentCloseIfVersion`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ReconciliationPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-076 · POST /biz/x/food/closes/{closeNo}/adjustments

Controller：`ai.neargo.shop.food.api.biz.BizBusinessCloseController`

```java
CreateAdjustmentBusinessCloseResultVO createAdjustment(@PathVariable("closeNo") String closeNo, @Valid @RequestBody CreateCloseAdjustmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BusinessCloseService` / `ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl`

```java
CreateAdjustmentBusinessCloseResultVO createAdjustment(String entityNo, String currentStoreNo, String closeNo, String actorNo, CreateCloseAdjustmentReq req, String idempotencyKey);
```

持久化：`FoodMappers.CloseRunMapper.selectOne`（READ）；`FoodMappers.BusinessDayMapper.selectList`（READ）；`FoodMappers.CloseAdjustmentMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ReconciliationPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

### MOD-10 经营报表管理

#### FOOD-API-077 · GET /biz/x/food/reports/{reportCode}

Controller：`ai.neargo.shop.food.api.biz.BizDiningReportController`

```java
DiningReportResultVO getReport(@PathVariable("reportCode") String reportCode, @Valid @ModelAttribute GetReportQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReportService` / `ai.neargo.shop.food.service.impl.DiningReportServiceImpl`

```java
DiningReportResultVO getReport(String entityNo, String currentStoreNo, String reportCode, Set<String> authorizedStoreNos, GetReportQuery query);
```

持久化：`FoodMappers.MetricDefinitionMapper.selectOne`（READ）；`FoodMappers.ReportRunMapper.selectList`（READ）。

跨域：`DiningReportPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-078 · POST /biz/x/food/report-exports

Controller：`ai.neargo.shop.food.api.biz.BizDiningReportExportController`

```java
DiningReportExportResultVO createReportExport(@Valid @RequestBody CreateReportExportReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReportService` / `ai.neargo.shop.food.service.impl.DiningReportServiceImpl`

```java
DiningReportExportResultVO createReportExport(String entityNo, String currentStoreNo, Set<String> authorizedStoreNos, String actorNo, CreateReportExportReq req, String idempotencyKey);
```

持久化：`FoodMappers.MetricDefinitionMapper.selectList`（READ）。

跨域：`DiningReportPort`。

事务：**SHARED_JOB**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 导出任务由共享任务/报表服务创建，餐饮没有导出任务表，不得用fnb_report_run冒充导出任务。

#### FOOD-API-079 · GET /biz/x/food/report-exports/{exportNo}

Controller：`ai.neargo.shop.food.api.biz.BizDiningReportExportController`

```java
GetReportExportDiningReportExportResultVO getReportExport(@PathVariable("exportNo") String exportNo);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReportService` / `ai.neargo.shop.food.service.impl.DiningReportServiceImpl`

```java
GetReportExportDiningReportExportResultVO getReportExport(String entityNo, String currentStoreNo, String exportNo, Set<String> authorizedStoreNos, String actorNo);
```

持久化：无餐饮本地表写入/查询。

跨域：`DiningReportPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 查询共享导出任务，验证本人/授权范围。

#### FOOD-API-080 · POST /ops/x/food/report-rebuilds

Controller：`ai.neargo.shop.food.api.ops.OpsDiningReportRebuildController`

```java
DiningReportRebuildResultVO createReportRebuild(@Valid @RequestBody CreateReportRebuildReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningReportService` / `ai.neargo.shop.food.service.impl.DiningReportServiceImpl`

```java
DiningReportRebuildResultVO createReportRebuild(String entityNo, String currentStoreNo, Set<String> authorizedStoreNos, String actorNo, CreateReportRebuildReq req, String idempotencyKey);
```

持久化：`FoodMappers.MetricDefinitionMapper.selectList`（READ）；`FoodMappers.ReportRunMapper.selectList`（READ）；`FoodMappers.ReportRunMapper.insert`（WRITE）；`FoodMappers.ReportRunMapper.update`（WRITE）。

跨域：`DiningReportPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 报表投影写入在shop-report由DAO执行，food只跟踪运行引用；不重开营业日。

### MOD-11 专项业态管理

#### FOOD-API-085 · POST /biz/x/food/checks/{checkNo}/entitlements

Controller：`ai.neargo.shop.food.api.biz.BizDiningEntitlementController`

```java
DiningEntitlementResultVO createEntitlement(@PathVariable("checkNo") String checkNo, @Valid @RequestBody CreateEntitlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningEntitlementService` / `ai.neargo.shop.food.service.impl.DiningEntitlementServiceImpl`

```java
DiningEntitlementResultVO createEntitlement(String entityNo, String currentStoreNo, String checkNo, String actorNo, CreateEntitlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.CheckMapper.selectOne`（READ）；`FoodMappers.DiningEntitlementMapper.selectList`（READ）；`FoodMappers.DiningEntitlementMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-086 · POST /biz/x/food/entitlements/{entitlementNo}:extend

Controller：`ai.neargo.shop.food.api.biz.BizDiningEntitlementController`

```java
OperationVO extendEntitlement(@PathVariable("entitlementNo") String entitlementNo, @Valid @RequestBody ExtendEntitlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningEntitlementService` / `ai.neargo.shop.food.service.impl.DiningEntitlementServiceImpl`

```java
OperationVO extendEntitlement(String entityNo, String currentStoreNo, String entitlementNo, String actorNo, ExtendEntitlementReq req, String idempotencyKey);
```

持久化：`FoodMappers.DiningEntitlementMapper.selectOne`（READ）；`FoodMappers.DiningEntitlementMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderAdjustmentPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-087 · POST /biz/x/food/stores/{storeNo}/banquet-plans

Controller：`ai.neargo.shop.food.api.biz.BizBanquetPlanController`

```java
BanquetPlanResultVO createBanquetPlan(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateBanquetPlanReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BanquetPlanService` / `ai.neargo.shop.food.service.impl.BanquetPlanServiceImpl`

```java
BanquetPlanResultVO createBanquetPlan(String entityNo, String storeNo, String actorNo, CreateBanquetPlanReq req, String idempotencyKey);
```

持久化：`FoodMappers.ReservationMapper.selectList`（READ）；`FoodMappers.BanquetPlanMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-088 · POST /biz/x/food/banquet-plans/{planNo}:confirm-attendance

Controller：`ai.neargo.shop.food.api.biz.BizBanquetPlanController`

```java
OperationVO confirmAttendanceBanquetPlan(@PathVariable("planNo") String planNo, @Valid @RequestBody ConfirmAttendanceBanquetPlanReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BanquetPlanService` / `ai.neargo.shop.food.service.impl.BanquetPlanServiceImpl`

```java
OperationVO confirmAttendanceBanquetPlan(String entityNo, String currentStoreNo, String planNo, String actorNo, ConfirmAttendanceBanquetPlanReq req, String idempotencyKey);
```

持久化：`FoodMappers.BanquetPlanMapper.selectOne`（READ）；`FoodMappers.BanquetPlanMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`OrderPlacementPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-089 · POST /biz/x/food/stores/{storeNo}/custodies

Controller：`ai.neargo.shop.food.api.biz.BizBottleCustodyController`

```java
BottleCustodyResultVO createCustody(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreateCustodyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BottleCustodyService` / `ai.neargo.shop.food.service.impl.BottleCustodyServiceImpl`

```java
BottleCustodyResultVO createCustody(String entityNo, String storeNo, String actorNo, CreateCustodyReq req, String idempotencyKey);
```

持久化：`FoodMappers.BottleCustodyMapper.selectList`（READ）；`FoodMappers.BottleCustodyMapper.insert`（WRITE）；`FoodMappers.CustodyEntryMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-090 · POST /biz/x/food/custodies/{custodyNo}/entries

Controller：`ai.neargo.shop.food.api.biz.BizBottleCustodyController`

```java
OperationVO createEntry(@PathVariable("custodyNo") String custodyNo, @Valid @RequestBody CreateCustodyEntryReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.BottleCustodyService` / `ai.neargo.shop.food.service.impl.BottleCustodyServiceImpl`

```java
OperationVO createEntry(String entityNo, String currentStoreNo, String custodyNo, String actorNo, CreateCustodyEntryReq req, String idempotencyKey);
```

持久化：`FoodMappers.BottleCustodyMapper.selectOne`（READ）；`FoodMappers.BottleCustodyMapper.withdrawIfRemaining`（WRITE）；`FoodMappers.CustodyEntryMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-091 · POST /biz/x/food/settlements/{settlementNo}/room-charges

Controller：`ai.neargo.shop.food.api.biz.BizRoomChargeController`

```java
RoomChargeResultVO createRoomCharge(@PathVariable("settlementNo") String settlementNo, @Valid @RequestBody CreateRoomChargeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.RoomChargeService` / `ai.neargo.shop.food.service.impl.RoomChargeServiceImpl`

```java
RoomChargeResultVO createRoomCharge(String entityNo, String currentStoreNo, String settlementNo, String actorNo, CreateRoomChargeReq req, String idempotencyKey);
```

持久化：`FoodMappers.SettlementMapper.selectOne`（READ）；`FoodMappers.RoomChargeIntentMapper.selectList`（READ）；`FoodMappers.RoomChargeIntentMapper.update`（WRITE）；`FoodMappers.ChargeIntentMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`AccountsReceivablePort`、`RoomAccountPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

### MOD-12 第三方接入与离线营业

#### FOOD-API-082 · PUT /biz/x/food/integrations/{provider}/mappings

Controller：`ai.neargo.shop.food.api.biz.BizExternalMappingController`

```java
OperationVO saveMapping(@PathVariable("provider") String provider, @Valid @RequestBody SaveMappingReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.ExternalMappingService` / `ai.neargo.shop.food.service.impl.ExternalMappingServiceImpl`

```java
OperationVO saveMapping(String entityNo, String currentStoreNo, String provider, String actorNo, SaveMappingReq req, String idempotencyKey);
```

持久化：`FoodMappers.ExternalMappingMapper.selectOne`（READ）；`FoodMappers.ExternalMappingMapper.insert`（WRITE）；`FoodMappers.ExternalMappingMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-083 · GET /ops/x/food/integration-exceptions

Controller：`ai.neargo.shop.food.api.ops.OpsIntegrationExceptionController`

```java
PageData<Map<String, Object>> listIntegrationExceptions(@Valid @ModelAttribute ListIntegrationExceptionsQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.IntegrationExceptionService` / `ai.neargo.shop.food.service.impl.IntegrationExceptionServiceImpl`

```java
PageData<Map<String, Object>> listIntegrationExceptions(String entityNo, String currentStoreNo, ListIntegrationExceptionsQuery query);
```

持久化：`FoodMappers.IntegrationExceptionMapper.selectPage`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-084 · POST /internal/food/sync-batches

Controller：`ai.neargo.shop.food.api.internal.InternalSyncBatchController`

```java
SyncBatchResultVO createSyncBatch(@Valid @RequestBody CreateSyncBatchReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.SyncBatchService` / `ai.neargo.shop.food.service.impl.SyncBatchServiceImpl`

```java
SyncBatchResultVO createSyncBatch(String entityNo, String currentStoreNo, String authenticatedDeviceNo, String actorNo, CreateSyncBatchReq req, String idempotencyKey);
```

持久化：`FoodMappers.EdgeLeaseMapper.selectList`（READ）；`FoodMappers.SyncBatchMapper.selectList`（READ）；`FoodMappers.ExternalMappingMapper.selectList`（READ）；`FoodMappers.EdgeLeaseMapper.selectLeaseByStoreEpochForUpdate`（LOCK）；`FoodMappers.SyncBatchMapper.insertSyncBatchIfAbsent`（WRITE）；`FoodMappers.SyncBatchMapper.selectAcceptedThrough`（READ）；`FoodMappers.IntegrationExceptionMapper.insert`（WRITE）。

跨域：`ExternalRestaurantPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 同步入口仅验签/认领/排入恢复；消费合法事件须走业务Service，不能直接批写ord/prd/pay。

#### FOOD-API-146 · POST /biz/x/food/menu-channel-bindings/{bindingNo}/syncs

Controller：`ai.neargo.shop.food.api.biz.BizCatalogSyncController`

```java
CatalogSyncResultVO createCatalogSync(@PathVariable("bindingNo") String bindingNo, @Valid @RequestBody CreateCatalogSyncReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.CatalogSyncService` / `ai.neargo.shop.food.service.impl.CatalogSyncServiceImpl`

```java
CatalogSyncResultVO createCatalogSync(String entityNo, String currentStoreNo, String bindingNo, String actorNo, CreateCatalogSyncReq req, String idempotencyKey);
```

持久化：`FoodMappers.CatalogSyncJobMapper.selectOne`（READ）；`FoodMappers.CatalogSyncItemMapper.selectList`（READ）；`FoodMappers.MenuChannelBindingMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）；`FoodMappers.CatalogSyncJobMapper.update`（WRITE）；`FoodMappers.CatalogSyncItemMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_WRITE**。共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

#### FOOD-API-147 · GET /biz/x/food/catalog-syncs/{syncNo}

Controller：`ai.neargo.shop.food.api.biz.BizCatalogSyncController`

```java
CatalogSyncResultVO getCatalogSync(@PathVariable("syncNo") String syncNo);
```

Service / Impl：`ai.neargo.shop.food.service.CatalogSyncService` / `ai.neargo.shop.food.service.impl.CatalogSyncServiceImpl`

```java
CatalogSyncResultVO getCatalogSync(String entityNo, String currentStoreNo, String syncNo);
```

持久化：`FoodMappers.CatalogSyncJobMapper.selectOne`（READ）；`FoodMappers.CatalogSyncItemMapper.selectList`（READ）；`FoodMappers.MenuChannelBindingMapper.selectList`（READ）；`FoodMappers.ConfigRevisionMapper.selectList`（READ）。

跨域：无新增跨域调用。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。 PLANNED_NOT_IMPLEMENTED；权威单位/价格及渠道校验合同PENDING，不可据清单发布或交易

### MOD-13 营业配置与权限治理

#### FOOD-API-001 · GET /biz/x/food/stores/{storeNo}/policy

Controller：`ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController`

```java
StoreOperationPolicyResultVO getPolicy(@PathVariable("storeNo") String storeNo, @Valid @ModelAttribute GetPolicyQuery query);
```

Service / Impl：`ai.neargo.shop.food.service.StoreOperationService` / `ai.neargo.shop.food.service.impl.StoreOperationServiceImpl`

```java
StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query);
```

持久化：`FoodMappers.StorePolicyMapper.selectList`（READ）。

跨域：`StoreDirectoryPort`。

事务：**READ_ONLY**。只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。

#### FOOD-API-002 · POST /biz/x/food/stores/{storeNo}/policies

Controller：`ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController`

```java
OperationVO createPolicy(@PathVariable("storeNo") String storeNo, @Valid @RequestBody CreatePolicyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.StoreOperationService` / `ai.neargo.shop.food.service.impl.StoreOperationServiceImpl`

```java
OperationVO createPolicy(String entityNo, String storeNo, String actorNo, CreatePolicyReq req, String idempotencyKey);
```

持久化：`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：`StoreDirectoryPort`、`ApprovalPort`。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。

#### FOOD-API-003 · PUT /biz/x/food/stores/{storeNo}/calendar/{localDate}

Controller：`ai.neargo.shop.food.api.biz.BizServiceCalendarController`

```java
OperationVO saveCalendar(@PathVariable("storeNo") String storeNo, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @PathVariable("localDate") LocalDate localDate, @Valid @RequestBody SaveCalendarReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.StoreOperationService` / `ai.neargo.shop.food.service.impl.StoreOperationServiceImpl`

```java
OperationVO saveCalendar(String entityNo, String storeNo, LocalDate localDate, String actorNo, SaveCalendarReq req, String idempotencyKey);
```

持久化：`FoodMappers.StorePolicyMapper.selectOne`（READ）；`FoodMappers.ServiceCalendarMapper.selectList`（READ）；`FoodMappers.ServiceCalendarMapper.insert`（WRITE）；`FoodMappers.ServiceCalendarMapper.update`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**LOCAL_ATOMIC**。本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。

#### FOOD-API-081 · PUT /ops/x/food/stores/{storeNo}/capabilities

Controller：`ai.neargo.shop.food.api.ops.OpsDiningCapabilityController`

```java
OperationVO saveCapability(@PathVariable("storeNo") String storeNo, @Valid @RequestBody SaveCapabilityReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service / Impl：`ai.neargo.shop.food.service.DiningCapabilityService` / `ai.neargo.shop.food.service.impl.DiningCapabilityServiceImpl`

```java
OperationVO saveCapability(String entityNo, String storeNo, String actorNo, SaveCapabilityReq req, String idempotencyKey);
```

持久化：`FoodMappers.StorePolicyMapper.selectList`（READ）；`FoodMappers.StorePolicyMapper.insert`（WRITE）；`FoodMappers.ActionLogMapper.insert`（WRITE）。

跨域：无新增跨域调用。

事务：**ORCHESTRATED**。外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。 平台授权工单与CAS，能力变更发布新策略版本，不覆盖旧营业快照。

## 三、逐Mapper / DAO的方法签名

本节保留原目标方法；共享打印真实实现见前置源码清单，rpt_继续由原共享owner负责，不能在food-core创建副本。下列原子写方法返回int时必须处理0行冲突；不能把核心金额/库存/资金守卫伪装成餐饮表单条SQL。

### fnb_dish

`ai.neargo.shop.food.mapper.FoodMappers.DishMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbDish selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> queryWrapper);
// inherited BaseMapper
List<FnbDish> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbDish> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbDish> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> queryWrapper);
// inherited BaseMapper
int insert(FnbDish row);
// inherited BaseMapper
int update(FnbDish changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDish> updateWrapper);
```



### fnb_bundle

`ai.neargo.shop.food.mapper.FoodMappers.BundleMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBundle selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> queryWrapper);
// inherited BaseMapper
List<FnbBundle> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBundle> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBundle> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> queryWrapper);
// inherited BaseMapper
int insert(FnbBundle row);
// inherited BaseMapper
int update(FnbBundle changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundle> updateWrapper);
```



### fnb_bundle_group

`ai.neargo.shop.food.mapper.FoodMappers.BundleGroupMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBundleGroup selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> queryWrapper);
// inherited BaseMapper
List<FnbBundleGroup> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBundleGroup> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBundleGroup> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> queryWrapper);
// inherited BaseMapper
int insert(FnbBundleGroup row);
// inherited BaseMapper
int update(FnbBundleGroup changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleGroup> updateWrapper);
```



### fnb_bundle_choice

`ai.neargo.shop.food.mapper.FoodMappers.BundleChoiceMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBundleChoice selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> queryWrapper);
// inherited BaseMapper
List<FnbBundleChoice> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBundleChoice> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBundleChoice> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> queryWrapper);
// inherited BaseMapper
int insert(FnbBundleChoice row);
// inherited BaseMapper
int update(FnbBundleChoice changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBundleChoice> updateWrapper);
```



### fnb_modifier

`ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbModifier selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> queryWrapper);
// inherited BaseMapper
List<FnbModifier> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbModifier> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbModifier> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> queryWrapper);
// inherited BaseMapper
int insert(FnbModifier row);
// inherited BaseMapper
int update(FnbModifier changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifier> updateWrapper);
```



### fnb_modifier_component

`ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbModifierComponent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> queryWrapper);
// inherited BaseMapper
List<FnbModifierComponent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbModifierComponent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbModifierComponent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> queryWrapper);
// inherited BaseMapper
int insert(FnbModifierComponent row);
// inherited BaseMapper
int update(FnbModifierComponent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbModifierComponent> updateWrapper);
```



### fnb_option_group

`ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOptionGroup selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> queryWrapper);
// inherited BaseMapper
List<FnbOptionGroup> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOptionGroup> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOptionGroup> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> queryWrapper);
// inherited BaseMapper
int insert(FnbOptionGroup row);
// inherited BaseMapper
int update(FnbOptionGroup changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroup> updateWrapper);
```



### fnb_option_group_item

`ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOptionGroupItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> queryWrapper);
// inherited BaseMapper
List<FnbOptionGroupItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOptionGroupItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOptionGroupItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> queryWrapper);
// inherited BaseMapper
int insert(FnbOptionGroupItem row);
// inherited BaseMapper
int update(FnbOptionGroupItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOptionGroupItem> updateWrapper);
```



### fnb_dish_option_group

`ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbDishOptionGroup selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> queryWrapper);
// inherited BaseMapper
List<FnbDishOptionGroup> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbDishOptionGroup> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbDishOptionGroup> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> queryWrapper);
// inherited BaseMapper
int insert(FnbDishOptionGroup row);
// inherited BaseMapper
int update(FnbDishOptionGroup changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDishOptionGroup> updateWrapper);
```



### fnb_menu

`ai.neargo.shop.food.mapper.FoodMappers.MenuMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenu selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> queryWrapper);
// inherited BaseMapper
List<FnbMenu> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenu> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenu> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> queryWrapper);
// inherited BaseMapper
int insert(FnbMenu row);
// inherited BaseMapper
int update(FnbMenu changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenu> updateWrapper);
```



### fnb_menu_store

`ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuStore selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> queryWrapper);
// inherited BaseMapper
List<FnbMenuStore> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuStore> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuStore> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuStore row);
// inherited BaseMapper
int update(FnbMenuStore changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuStore> updateWrapper);
```



### fnb_menu_context

`ai.neargo.shop.food.mapper.FoodMappers.MenuContextMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuContext selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> queryWrapper);
// inherited BaseMapper
List<FnbMenuContext> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuContext> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuContext> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuContext row);
// inherited BaseMapper
int update(FnbMenuContext changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuContext> updateWrapper);
```



### fnb_menu_schedule

`ai.neargo.shop.food.mapper.FoodMappers.MenuScheduleMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuSchedule selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> queryWrapper);
// inherited BaseMapper
List<FnbMenuSchedule> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuSchedule> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuSchedule> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuSchedule row);
// inherited BaseMapper
int update(FnbMenuSchedule changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuSchedule> updateWrapper);
```



### fnb_menu_exception

`ai.neargo.shop.food.mapper.FoodMappers.MenuExceptionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuException selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> queryWrapper);
// inherited BaseMapper
List<FnbMenuException> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuException> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuException> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuException row);
// inherited BaseMapper
int update(FnbMenuException changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuException> updateWrapper);
```



### fnb_menu_category

`ai.neargo.shop.food.mapper.FoodMappers.MenuCategoryMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuCategory selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> queryWrapper);
// inherited BaseMapper
List<FnbMenuCategory> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuCategory> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuCategory> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuCategory row);
// inherited BaseMapper
int update(FnbMenuCategory changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuCategory> updateWrapper);
```



### fnb_menu_item

`ai.neargo.shop.food.mapper.FoodMappers.MenuItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> queryWrapper);
// inherited BaseMapper
List<FnbMenuItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuItem row);
// inherited BaseMapper
int update(FnbMenuItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItem> updateWrapper);
```



### fnb_menu_item_sku

`ai.neargo.shop.food.mapper.FoodMappers.MenuItemSkuMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuItemSku selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> queryWrapper);
// inherited BaseMapper
List<FnbMenuItemSku> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItemSku> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItemSku> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuItemSku row);
// inherited BaseMapper
int update(FnbMenuItemSku changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemSku> updateWrapper);
```



### fnb_station

`ai.neargo.shop.food.mapper.FoodMappers.StationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbStation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> queryWrapper);
// inherited BaseMapper
List<FnbStation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbStation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbStation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> queryWrapper);
// inherited BaseMapper
int insert(FnbStation row);
// inherited BaseMapper
int update(FnbStation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStation> updateWrapper);
```



### fnb_sku_station

`ai.neargo.shop.food.mapper.FoodMappers.SkuStationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSkuStation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> queryWrapper);
// inherited BaseMapper
List<FnbSkuStation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSkuStation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSkuStation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> queryWrapper);
// inherited BaseMapper
int insert(FnbSkuStation row);
// inherited BaseMapper
int update(FnbSkuStation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSkuStation> updateWrapper);
```



### fnb_store_policy

`ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbStorePolicy selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> queryWrapper);
// inherited BaseMapper
List<FnbStorePolicy> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbStorePolicy> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbStorePolicy> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> queryWrapper);
// inherited BaseMapper
int insert(FnbStorePolicy row);
// inherited BaseMapper
int update(FnbStorePolicy changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStorePolicy> updateWrapper);
```



### fnb_service_calendar

`ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbServiceCalendar selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> queryWrapper);
// inherited BaseMapper
List<FnbServiceCalendar> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceCalendar> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceCalendar> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceCalendar row);
// inherited BaseMapper
int update(FnbServiceCalendar changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceCalendar> updateWrapper);
```



### fnb_table

`ai.neargo.shop.food.mapper.FoodMappers.TableMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTable selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> queryWrapper);
// inherited BaseMapper
List<FnbTable> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTable> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTable> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> queryWrapper);
// inherited BaseMapper
int insert(FnbTable row);
// inherited BaseMapper
int update(FnbTable changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTable> updateWrapper);
FnbTable selectForUpdateByStoreAndTableNo(String entityNo, String storeNo, String tableNo);
int updateCapacityIfVersion(String entityNo, String storeNo, String tableNo, int capacity, long expectedVersion);
```

- `selectForUpdateByStoreAndTableNo`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `updateCapacityIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_table_token

`ai.neargo.shop.food.mapper.FoodMappers.TableTokenMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTableToken selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> queryWrapper);
// inherited BaseMapper
List<FnbTableToken> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTableToken> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTableToken> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> queryWrapper);
// inherited BaseMapper
int insert(FnbTableToken row);
// inherited BaseMapper
int update(FnbTableToken changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableToken> updateWrapper);
FnbTableToken selectValidByTokenHash(byte[] tokenHash, Instant now);
```

- `selectValidByTokenHash`：仅允许桌码会话启动：按唯一哈希精确查未吊销且未过期令牌，限制速率和失败重试；不返回明文令牌。查验成功后从记录解析主体/门店再访问其他表，不是通用无主体查询入口。

### fnb_reservation

`ai.neargo.shop.food.mapper.FoodMappers.ReservationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbReservation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> queryWrapper);
// inherited BaseMapper
List<FnbReservation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReservation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReservation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> queryWrapper);
// inherited BaseMapper
int insert(FnbReservation row);
// inherited BaseMapper
int update(FnbReservation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservation> updateWrapper);
```



### fnb_check

`ai.neargo.shop.food.mapper.FoodMappers.CheckMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheck selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> queryWrapper);
// inherited BaseMapper
List<FnbCheck> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheck> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheck> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> queryWrapper);
// inherited BaseMapper
int insert(FnbCheck row);
// inherited BaseMapper
int update(FnbCheck changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheck> updateWrapper);
int updatePartySizeIfVersion(String entityNo, String storeNo, String checkNo, int partySize, long expectedVersion);
int closeIfSettlementConfirmed(String entityNo, String storeNo, String checkNo, Instant closedAt, long expectedVersion);
```

- `updatePartySizeIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `closeIfSettlementConfirmed`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_table_occupancy

`ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTableOccupancy selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> queryWrapper);
// inherited BaseMapper
List<FnbTableOccupancy> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTableOccupancy> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTableOccupancy> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> queryWrapper);
// inherited BaseMapper
int insert(FnbTableOccupancy row);
// inherited BaseMapper
int update(FnbTableOccupancy changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOccupancy> updateWrapper);
List<FnbTableOccupancy> selectActiveByTableForUpdate(String entityNo, String tableNo);
int endOccupancyIfActive(String entityNo, String occupancyNo, Instant endedAt, long expectedVersion);
```

- `selectActiveByTableForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `endOccupancyIfActive`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_check_relation

`ai.neargo.shop.food.mapper.FoodMappers.CheckRelationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheckRelation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> queryWrapper);
// inherited BaseMapper
List<FnbCheckRelation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckRelation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckRelation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckRelation row);
// inherited BaseMapper
int update(FnbCheckRelation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckRelation> updateWrapper);
```



### fnb_guest_session

`ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbGuestSession selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> queryWrapper);
// inherited BaseMapper
List<FnbGuestSession> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbGuestSession> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbGuestSession> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> queryWrapper);
// inherited BaseMapper
int insert(FnbGuestSession row);
// inherited BaseMapper
int update(FnbGuestSession changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbGuestSession> updateWrapper);
```



### fnb_seat

`ai.neargo.shop.food.mapper.FoodMappers.SeatMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSeat selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> queryWrapper);
// inherited BaseMapper
List<FnbSeat> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSeat> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSeat> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> queryWrapper);
// inherited BaseMapper
int insert(FnbSeat row);
// inherited BaseMapper
int update(FnbSeat changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSeat> updateWrapper);
```



### fnb_check_cart

`ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheckCart selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> queryWrapper);
// inherited BaseMapper
List<FnbCheckCart> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckCart> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckCart> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckCart row);
// inherited BaseMapper
int update(FnbCheckCart changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCart> updateWrapper);
FnbCheckCart lockCartIfVersion(String entityNo, String storeNo, String cartNo, long expectedVersion);
int closeCartIfNoPlacingBatch(String entityNo, String storeNo, String cartNo, long expectedVersion);
```

- `lockCartIfVersion`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `closeCartIfNoPlacingBatch`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_check_cart_item

`ai.neargo.shop.food.mapper.FoodMappers.CheckCartItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheckCartItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> queryWrapper);
// inherited BaseMapper
List<FnbCheckCartItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckCartItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckCartItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckCartItem row);
// inherited BaseMapper
int update(FnbCheckCartItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckCartItem> updateWrapper);
List<FnbCheckCartItem> selectSelectedLinesForUpdate(String entityNo, String cartNo, List<String> sourceLineRefs);
int archiveSelectedSubmittedLines(String entityNo, String cartItemNo, long expectedVersion);
FnbCheckCartItem history(String entityNo, String cartNo, String sourceLineRef);
int retain(String entityNo, String cartNo, String sourceLineRef, BigDecimal qty, String seatNo, String courseNo, String selection, int deleted, String actorNo, LocalDateTime at);
```

- `selectSelectedLinesForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `archiveSelectedSubmittedLines`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `history`：原逻辑归档行也读取；显式主体/车/源行与FOR UPDATE，源行身份不可重绑商品或SKU。
- `retain`：保留原源行/addedBy，按已锁owner/cart CAS更新或复活墓碑；影响行数必须为1。

### fnb_order_batch

`ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOrderBatch selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> queryWrapper);
// inherited BaseMapper
List<FnbOrderBatch> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderBatch> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderBatch> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> queryWrapper);
// inherited BaseMapper
int insert(FnbOrderBatch row);
// inherited BaseMapper
int update(FnbOrderBatch changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderBatch> updateWrapper);
int insertBatchIfAbsent(FnbOrderBatch row);
int bindCoreResultIfVersion(String entityNo, String storeNo, String batchNo, long expectedVersion);
int advancePlacementIfStatus(String entityNo, String storeNo, String batchNo, String expectedStatus, String targetStatus, long expectedVersion);
FnbOrderBatch selectScopedByKeyForUpdate(String entityNo, String storeNo, String batchNo);
```

- `insertBatchIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `bindCoreResultIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `advancePlacementIfStatus`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `selectScopedByKeyForUpdate`：必须在当前餐饮本地事务内调用，锁到的是已存在父/实体行；空结果不能充当稳定仲裁者。

### fnb_core_order_binding

`ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCoreOrderBinding selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> queryWrapper);
// inherited BaseMapper
List<FnbCoreOrderBinding> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCoreOrderBinding> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCoreOrderBinding> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> queryWrapper);
// inherited BaseMapper
int insert(FnbCoreOrderBinding row);
// inherited BaseMapper
int update(FnbCoreOrderBinding changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreOrderBinding> updateWrapper);
```



### fnb_order_line

`ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOrderLine selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> queryWrapper);
// inherited BaseMapper
List<FnbOrderLine> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderLine> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderLine> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> queryWrapper);
// inherited BaseMapper
int insert(FnbOrderLine row);
// inherited BaseMapper
int update(FnbOrderLine changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderLine> updateWrapper);
FnbOrderLine selectScopedByKeyForUpdate(String entityNo, String foodLineNo);
```

- `selectScopedByKeyForUpdate`：必须在当前餐饮本地事务内调用，锁到的是已存在父/实体行；空结果不能充当稳定仲裁者。

### fnb_core_line_allocation

`ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCoreLineAllocation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> queryWrapper);
// inherited BaseMapper
List<FnbCoreLineAllocation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCoreLineAllocation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCoreLineAllocation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> queryWrapper);
// inherited BaseMapper
int insert(FnbCoreLineAllocation row);
// inherited BaseMapper
int update(FnbCoreLineAllocation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCoreLineAllocation> updateWrapper);
int insertAllocationIfAbsent(FnbCoreLineAllocation row);
List<FnbCoreLineAllocation> selectAllocationsByFoodLine(String entityNo, String foodLineNo);
```

- `insertAllocationIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `selectAllocationsByFoodLine`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_order_component

`ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOrderComponent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> queryWrapper);
// inherited BaseMapper
List<FnbOrderComponent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderComponent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderComponent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> queryWrapper);
// inherited BaseMapper
int insert(FnbOrderComponent row);
// inherited BaseMapper
int update(FnbOrderComponent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderComponent> updateWrapper);
```



### fnb_order_modifier

`ai.neargo.shop.food.mapper.FoodMappers.OrderModifierMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbOrderModifier selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> queryWrapper);
// inherited BaseMapper
List<FnbOrderModifier> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderModifier> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOrderModifier> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> queryWrapper);
// inherited BaseMapper
int insert(FnbOrderModifier row);
// inherited BaseMapper
int update(FnbOrderModifier changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOrderModifier> updateWrapper);
```



### fnb_course

`ai.neargo.shop.food.mapper.FoodMappers.CourseMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCourse selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> queryWrapper);
// inherited BaseMapper
List<FnbCourse> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCourse> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCourse> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> queryWrapper);
// inherited BaseMapper
int insert(FnbCourse row);
// inherited BaseMapper
int update(FnbCourse changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCourse> updateWrapper);
```



### fnb_kitchen_ticket

`ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbKitchenTicket selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> queryWrapper);
// inherited BaseMapper
List<FnbKitchenTicket> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbKitchenTicket> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbKitchenTicket> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> queryWrapper);
// inherited BaseMapper
int insert(FnbKitchenTicket row);
// inherited BaseMapper
int update(FnbKitchenTicket changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTicket> updateWrapper);
int insertDispatchIfAbsent(FnbKitchenTicket row);
```

- `insertDispatchIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。

### fnb_kitchen_task

`ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbKitchenTask selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> queryWrapper);
// inherited BaseMapper
List<FnbKitchenTask> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbKitchenTask> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbKitchenTask> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> queryWrapper);
// inherited BaseMapper
int insert(FnbKitchenTask row);
// inherited BaseMapper
int update(FnbKitchenTask changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKitchenTask> updateWrapper);
int advanceTaskQuantityIfVersion(String entityNo, String taskNo, String action, BigDecimal qty, long expectedVersion);
int changePendingStateIfVersion(String entityNo, String taskNo, String targetState, long expectedVersion);
```

- `advanceTaskQuantityIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `changePendingStateIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_service_event

`ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbServiceEvent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> queryWrapper);
// inherited BaseMapper
List<FnbServiceEvent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceEvent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceEvent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceEvent row);
// inherited BaseMapper
int update(FnbServiceEvent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceEvent> updateWrapper);
int appendEventIfAbsent(FnbServiceEvent row);
```

- `appendEventIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。

### fnb_settlement

`ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSettlement selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> queryWrapper);
// inherited BaseMapper
List<FnbSettlement> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSettlement> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSettlement> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> queryWrapper);
// inherited BaseMapper
int insert(FnbSettlement row);
// inherited BaseMapper
int update(FnbSettlement changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlement> updateWrapper);
int insertFrozenScope(FnbSettlement row);
int finalizeIfCoreConfirmed(String entityNo, String storeNo, String settlementNo, long expectedVersion);
```

- `insertFrozenScope`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `finalizeIfCoreConfirmed`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_settlement_item

`ai.neargo.shop.food.mapper.FoodMappers.SettlementItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSettlementItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> queryWrapper);
// inherited BaseMapper
List<FnbSettlementItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSettlementItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSettlementItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> queryWrapper);
// inherited BaseMapper
int insert(FnbSettlementItem row);
// inherited BaseMapper
int update(FnbSettlementItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSettlementItem> updateWrapper);
```



### fnb_charge_intent

`ai.neargo.shop.food.mapper.FoodMappers.ChargeIntentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbChargeIntent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> queryWrapper);
// inherited BaseMapper
List<FnbChargeIntent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbChargeIntent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbChargeIntent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> queryWrapper);
// inherited BaseMapper
int insert(FnbChargeIntent row);
// inherited BaseMapper
int update(FnbChargeIntent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbChargeIntent> updateWrapper);
int insertIntentIfAbsent(FnbChargeIntent row);
int confirmIntentFromCoreIfStatus(String entityNo, String intentNo, String expectedStatus, String coreStatus, String coreReference, long expectedVersion);
```

- `insertIntentIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `confirmIntentFromCoreIfStatus`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_return

`ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbReturn selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> queryWrapper);
// inherited BaseMapper
List<FnbReturn> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReturn> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReturn> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> queryWrapper);
// inherited BaseMapper
int insert(FnbReturn row);
// inherited BaseMapper
int update(FnbReturn changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturn> updateWrapper);
```



### fnb_return_item

`ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbReturnItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> queryWrapper);
// inherited BaseMapper
List<FnbReturnItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReturnItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReturnItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> queryWrapper);
// inherited BaseMapper
int insert(FnbReturnItem row);
// inherited BaseMapper
int update(FnbReturnItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnItem> updateWrapper);
BigDecimal selectActiveReturnQuantityByFoodLine(String entityNo, String foodLineNo);
```

- `selectActiveReturnQuantityByFoodLine`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_action_log

`ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbActionLog selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> queryWrapper);
// inherited BaseMapper
List<FnbActionLog> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbActionLog> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbActionLog> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> queryWrapper);
// inherited BaseMapper
int insert(FnbActionLog row);
// inherited BaseMapper
int update(FnbActionLog changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbActionLog> updateWrapper);
```



### fnb_business_day

`ai.neargo.shop.food.mapper.FoodMappers.BusinessDayMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBusinessDay selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> queryWrapper);
// inherited BaseMapper
List<FnbBusinessDay> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessDay> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessDay> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> queryWrapper);
// inherited BaseMapper
int insert(FnbBusinessDay row);
// inherited BaseMapper
int update(FnbBusinessDay changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessDay> updateWrapper);
int replaceCurrentCloseIfVersion(String entityNo, String storeNo, String dayNo, String currency, String closeNo, long expectedVersion);
```

- `replaceCurrentCloseIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_cash_shift

`ai.neargo.shop.food.mapper.FoodMappers.CashShiftMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCashShift selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> queryWrapper);
// inherited BaseMapper
List<FnbCashShift> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCashShift> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCashShift> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> queryWrapper);
// inherited BaseMapper
int insert(FnbCashShift row);
// inherited BaseMapper
int update(FnbCashShift changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashShift> updateWrapper);
FnbCashShift selectActiveDrawerForUpdate(String entityNo, String storeNo, String drawerNo, String currency);
int handoverIfVersion(String entityNo, String storeNo, String shiftNo, String countNo, String acceptedBy, long expectedVersion);
```

- `selectActiveDrawerForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `handoverIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_cash_entry

`ai.neargo.shop.food.mapper.FoodMappers.CashEntryMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCashEntry selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> queryWrapper);
// inherited BaseMapper
List<FnbCashEntry> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCashEntry> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCashEntry> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> queryWrapper);
// inherited BaseMapper
int insert(FnbCashEntry row);
// inherited BaseMapper
int update(FnbCashEntry changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashEntry> updateWrapper);
```



### fnb_cash_payment_link

`ai.neargo.shop.food.mapper.FoodMappers.CashPaymentLinkMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCashPaymentLink selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> queryWrapper);
// inherited BaseMapper
List<FnbCashPaymentLink> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCashPaymentLink> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCashPaymentLink> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> queryWrapper);
// inherited BaseMapper
int insert(FnbCashPaymentLink row);
// inherited BaseMapper
int update(FnbCashPaymentLink changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashPaymentLink> updateWrapper);
int linkCorePaymentIfAbsent(String entityNo, String linkNo, long expectedVersion);
```

- `linkCorePaymentIfAbsent`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_cash_count

`ai.neargo.shop.food.mapper.FoodMappers.CashCountMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCashCount selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> queryWrapper);
// inherited BaseMapper
List<FnbCashCount> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCashCount> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCashCount> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> queryWrapper);
// inherited BaseMapper
int insert(FnbCashCount row);
// inherited BaseMapper
int update(FnbCashCount changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCashCount> updateWrapper);
```



### fnb_close_run

`ai.neargo.shop.food.mapper.FoodMappers.CloseRunMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCloseRun selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> queryWrapper);
// inherited BaseMapper
List<FnbCloseRun> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseRun> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseRun> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> queryWrapper);
// inherited BaseMapper
int insert(FnbCloseRun row);
// inherited BaseMapper
int update(FnbCloseRun changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseRun> updateWrapper);
int signIfVersionHashAndWatermark(String entityNo, String closeNo, String snapshotHash, String watermark, String signedBy, Instant signedAt, long expectedVersion);
```

- `signIfVersionHashAndWatermark`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_close_balance

`ai.neargo.shop.food.mapper.FoodMappers.CloseBalanceMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCloseBalance selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> queryWrapper);
// inherited BaseMapper
List<FnbCloseBalance> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseBalance> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseBalance> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> queryWrapper);
// inherited BaseMapper
int insert(FnbCloseBalance row);
// inherited BaseMapper
int update(FnbCloseBalance changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseBalance> updateWrapper);
```



### fnb_close_exception

`ai.neargo.shop.food.mapper.FoodMappers.CloseExceptionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCloseException selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> queryWrapper);
// inherited BaseMapper
List<FnbCloseException> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseException> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseException> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> queryWrapper);
// inherited BaseMapper
int insert(FnbCloseException row);
// inherited BaseMapper
int update(FnbCloseException changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseException> updateWrapper);
```



### fnb_close_adjustment

`ai.neargo.shop.food.mapper.FoodMappers.CloseAdjustmentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCloseAdjustment selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> queryWrapper);
// inherited BaseMapper
List<FnbCloseAdjustment> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseAdjustment> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCloseAdjustment> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> queryWrapper);
// inherited BaseMapper
int insert(FnbCloseAdjustment row);
// inherited BaseMapper
int update(FnbCloseAdjustment changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCloseAdjustment> updateWrapper);
```



### fnb_metric_definition

`ai.neargo.shop.food.mapper.FoodMappers.MetricDefinitionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMetricDefinition selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> queryWrapper);
// inherited BaseMapper
List<FnbMetricDefinition> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMetricDefinition> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMetricDefinition> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> queryWrapper);
// inherited BaseMapper
int insert(FnbMetricDefinition row);
// inherited BaseMapper
int update(FnbMetricDefinition changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMetricDefinition> updateWrapper);
```



### fnb_report_run

`ai.neargo.shop.food.mapper.FoodMappers.ReportRunMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbReportRun selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> queryWrapper);
// inherited BaseMapper
List<FnbReportRun> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReportRun> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReportRun> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> queryWrapper);
// inherited BaseMapper
int insert(FnbReportRun row);
// inherited BaseMapper
int update(FnbReportRun changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReportRun> updateWrapper);
```



### fnb_external_mapping

`ai.neargo.shop.food.mapper.FoodMappers.ExternalMappingMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbExternalMapping selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> queryWrapper);
// inherited BaseMapper
List<FnbExternalMapping> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbExternalMapping> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbExternalMapping> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> queryWrapper);
// inherited BaseMapper
int insert(FnbExternalMapping row);
// inherited BaseMapper
int update(FnbExternalMapping changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbExternalMapping> updateWrapper);
```



### fnb_sync_batch

`ai.neargo.shop.food.mapper.FoodMappers.SyncBatchMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSyncBatch selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> queryWrapper);
// inherited BaseMapper
List<FnbSyncBatch> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSyncBatch> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSyncBatch> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> queryWrapper);
// inherited BaseMapper
int insert(FnbSyncBatch row);
// inherited BaseMapper
int update(FnbSyncBatch changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSyncBatch> updateWrapper);
int insertSyncBatchIfAbsent(FnbSyncBatch row);
Long selectAcceptedThrough(String entityNo, String storeNo, String edgeNo, long epoch);
```

- `insertSyncBatchIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `selectAcceptedThrough`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_edge_lease

`ai.neargo.shop.food.mapper.FoodMappers.EdgeLeaseMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbEdgeLease selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> queryWrapper);
// inherited BaseMapper
List<FnbEdgeLease> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbEdgeLease> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbEdgeLease> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> queryWrapper);
// inherited BaseMapper
int insert(FnbEdgeLease row);
// inherited BaseMapper
int update(FnbEdgeLease changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbEdgeLease> updateWrapper);
int renewLeaseIfEpoch(String entityNo, String storeNo, String leaseNo, long epoch, Instant expiresAt, long expectedVersion);
FnbEdgeLease selectLeaseByStoreEpochForUpdate(String entityNo, String storeNo, String edgeNo, long epoch);
```

- `renewLeaseIfEpoch`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `selectLeaseByStoreEpochForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_integration_exception

`ai.neargo.shop.food.mapper.FoodMappers.IntegrationExceptionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbIntegrationException selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> queryWrapper);
// inherited BaseMapper
List<FnbIntegrationException> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbIntegrationException> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbIntegrationException> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> queryWrapper);
// inherited BaseMapper
int insert(FnbIntegrationException row);
// inherited BaseMapper
int update(FnbIntegrationException changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIntegrationException> updateWrapper);
```



### fnb_dining_entitlement

`ai.neargo.shop.food.mapper.FoodMappers.DiningEntitlementMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbDiningEntitlement selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> queryWrapper);
// inherited BaseMapper
List<FnbDiningEntitlement> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbDiningEntitlement> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbDiningEntitlement> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> queryWrapper);
// inherited BaseMapper
int insert(FnbDiningEntitlement row);
// inherited BaseMapper
int update(FnbDiningEntitlement changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningEntitlement> updateWrapper);
```



### fnb_banquet_plan

`ai.neargo.shop.food.mapper.FoodMappers.BanquetPlanMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBanquetPlan selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> queryWrapper);
// inherited BaseMapper
List<FnbBanquetPlan> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBanquetPlan> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBanquetPlan> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> queryWrapper);
// inherited BaseMapper
int insert(FnbBanquetPlan row);
// inherited BaseMapper
int update(FnbBanquetPlan changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBanquetPlan> updateWrapper);
```



### fnb_bottle_custody

`ai.neargo.shop.food.mapper.FoodMappers.BottleCustodyMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbBottleCustody selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> queryWrapper);
// inherited BaseMapper
List<FnbBottleCustody> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBottleCustody> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBottleCustody> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> queryWrapper);
// inherited BaseMapper
int insert(FnbBottleCustody row);
// inherited BaseMapper
int update(FnbBottleCustody changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBottleCustody> updateWrapper);
int withdrawIfRemaining(String entityNo, String storeNo, String custodyNo, BigDecimal qty, long expectedVersion);
```

- `withdrawIfRemaining`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_custody_entry

`ai.neargo.shop.food.mapper.FoodMappers.CustodyEntryMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCustodyEntry selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> queryWrapper);
// inherited BaseMapper
List<FnbCustodyEntry> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCustodyEntry> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCustodyEntry> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> queryWrapper);
// inherited BaseMapper
int insert(FnbCustodyEntry row);
// inherited BaseMapper
int update(FnbCustodyEntry changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCustodyEntry> updateWrapper);
```



### fnb_room_charge_intent

`ai.neargo.shop.food.mapper.FoodMappers.RoomChargeIntentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbRoomChargeIntent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> queryWrapper);
// inherited BaseMapper
List<FnbRoomChargeIntent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbRoomChargeIntent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbRoomChargeIntent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> queryWrapper);
// inherited BaseMapper
int insert(FnbRoomChargeIntent row);
// inherited BaseMapper
int update(FnbRoomChargeIntent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRoomChargeIntent> updateWrapper);
```



### prn_device

`ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnDevice selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> queryWrapper);
// inherited BaseMapper
List<PrnDevice> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnDevice> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnDevice> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> queryWrapper);
// inherited BaseMapper
int insert(PrnDevice row);
// inherited BaseMapper
int update(PrnDevice changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnDevice> updateWrapper);
```



### prn_template

`ai.neargo.shop.printing.mapper.PrintMappers.TemplateMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnTemplate selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> queryWrapper);
// inherited BaseMapper
List<PrnTemplate> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnTemplate> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnTemplate> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> queryWrapper);
// inherited BaseMapper
int insert(PrnTemplate row);
// inherited BaseMapper
int update(PrnTemplate changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnTemplate> updateWrapper);
```



### prn_route

`ai.neargo.shop.printing.mapper.PrintMappers.RouteMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnRoute selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> queryWrapper);
// inherited BaseMapper
List<PrnRoute> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnRoute> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnRoute> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> queryWrapper);
// inherited BaseMapper
int insert(PrnRoute row);
// inherited BaseMapper
int update(PrnRoute changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnRoute> updateWrapper);
```



### prn_job

`ai.neargo.shop.printing.mapper.PrintMappers.JobMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnJob selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> queryWrapper);
// inherited BaseMapper
List<PrnJob> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnJob> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnJob> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> queryWrapper);
// inherited BaseMapper
int insert(PrnJob row);
// inherited BaseMapper
int update(PrnJob changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnJob> updateWrapper);
```



### prn_attempt

`ai.neargo.shop.printing.mapper.PrintMappers.AttemptMapper`；文件：`backend/shop-core/src/main/java/ai/neargo/shop/printing/mapper/PrintMappers.java`。

```java
// inherited BaseMapper
PrnAttempt selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> queryWrapper);
// inherited BaseMapper
List<PrnAttempt> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<PrnAttempt> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<PrnAttempt> page, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> queryWrapper);
// inherited BaseMapper
int insert(PrnAttempt row);
// inherited BaseMapper
int update(PrnAttempt changes, com.baomidou.mybatisplus.core.conditions.Wrapper<PrnAttempt> updateWrapper);
```



### fnb_table_area

`ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTableArea selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> queryWrapper);
// inherited BaseMapper
List<FnbTableArea> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTableArea> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTableArea> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> queryWrapper);
// inherited BaseMapper
int insert(FnbTableArea row);
// inherited BaseMapper
int update(FnbTableArea changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableArea> updateWrapper);
FnbTableArea selectScopedByKeyForUpdate(String entityNo, String storeNo, String areaNo);
```

- `selectScopedByKeyForUpdate`：必须在当前餐饮本地事务内调用，锁到的是已存在父/实体行；空结果不能充当稳定仲裁者。

### fnb_table_operation

`ai.neargo.shop.food.mapper.FoodMappers.TableOperationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbTableOperation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> queryWrapper);
// inherited BaseMapper
List<FnbTableOperation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbTableOperation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbTableOperation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> queryWrapper);
// inherited BaseMapper
int insert(FnbTableOperation row);
// inherited BaseMapper
int update(FnbTableOperation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbTableOperation> updateWrapper);
int insertOperationIfAbsent(FnbTableOperation row);
int advanceOperationIfVersion(String entityNo, String storeNo, String operationNo, String targetStatus, long expectedVersion);
```

- `insertOperationIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `advanceOperationIfVersion`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_config_revision

`ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbConfigRevision selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> queryWrapper);
// inherited BaseMapper
List<FnbConfigRevision> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbConfigRevision> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbConfigRevision> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> queryWrapper);
// inherited BaseMapper
int insert(FnbConfigRevision row);
// inherited BaseMapper
int update(FnbConfigRevision changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbConfigRevision> updateWrapper);
int insertImmutableRevision(FnbConfigRevision row);
FnbConfigRevision selectEffectiveByConfigAndTime(String entityNo, String configKind, String configNo, Instant at);
```

- `insertImmutableRevision`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `selectEffectiveByConfigAndTime`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。

### fnb_sales_restriction

`ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSalesRestriction selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> queryWrapper);
// inherited BaseMapper
List<FnbSalesRestriction> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSalesRestriction> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSalesRestriction> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> queryWrapper);
// inherited BaseMapper
int insert(FnbSalesRestriction row);
// inherited BaseMapper
int update(FnbSalesRestriction changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesRestriction> updateWrapper);
int reserveQuotaIfCapacity(String entityNo, String storeNo, String restrictionNo, BigDecimal qty, long expectedVersion);
int consumeQuotaIfReserved(String entityNo, String storeNo, String restrictionNo, BigDecimal qty, long expectedVersion);
int releaseQuotaIfReserved(String entityNo, String storeNo, String restrictionNo, BigDecimal qty, long expectedVersion);
```

- `reserveQuotaIfCapacity`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `consumeQuotaIfReserved`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `releaseQuotaIfReserved`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_check_batch_membership

`ai.neargo.shop.food.mapper.FoodMappers.CheckBatchMembershipMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCheckBatchMembership selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> queryWrapper);
// inherited BaseMapper
List<FnbCheckBatchMembership> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckBatchMembership> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckBatchMembership> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckBatchMembership row);
// inherited BaseMapper
int update(FnbCheckBatchMembership changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckBatchMembership> updateWrapper);
FnbCheckBatchMembership selectActiveByBatchForUpdate(String entityNo, String batchNo);
int detachMembershipIfActive(String entityNo, String membershipNo, Instant detachedAt, long expectedVersion);
```

- `selectActiveByBatchForUpdate`：显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。
- `detachMembershipIfActive`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_sales_quota_claim

`ai.neargo.shop.food.mapper.FoodMappers.SalesQuotaClaimMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbSalesQuotaClaim selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> queryWrapper);
// inherited BaseMapper
List<FnbSalesQuotaClaim> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbSalesQuotaClaim> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbSalesQuotaClaim> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> queryWrapper);
// inherited BaseMapper
int insert(FnbSalesQuotaClaim row);
// inherited BaseMapper
int update(FnbSalesQuotaClaim changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbSalesQuotaClaim> updateWrapper);
int insertClaimIfAbsent(FnbSalesQuotaClaim row);
int advanceClaimIfStatus(String entityNo, String claimNo, String expectedStatus, String targetStatus, long expectedVersion);
```

- `insertClaimIfAbsent`：业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。
- `advanceClaimIfStatus`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。

### fnb_menu_channel_binding

`ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuChannelBinding selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> queryWrapper);
// inherited BaseMapper
List<FnbMenuChannelBinding> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuChannelBinding> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuChannelBinding> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuChannelBinding row);
// inherited BaseMapper
int update(FnbMenuChannelBinding changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuChannelBinding> updateWrapper);
```



### fnb_menu_item_option_group

`ai.neargo.shop.food.mapper.FoodMappers.MenuItemOptionGroupMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbMenuItemOptionGroup selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> queryWrapper);
// inherited BaseMapper
List<FnbMenuItemOptionGroup> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItemOptionGroup> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbMenuItemOptionGroup> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> queryWrapper);
// inherited BaseMapper
int insert(FnbMenuItemOptionGroup row);
// inherited BaseMapper
int update(FnbMenuItemOptionGroup changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbMenuItemOptionGroup> updateWrapper);
```



### fnb_recipe

`ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbRecipe selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> queryWrapper);
// inherited BaseMapper
List<FnbRecipe> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipe> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipe> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> queryWrapper);
// inherited BaseMapper
int insert(FnbRecipe row);
// inherited BaseMapper
int update(FnbRecipe changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipe> updateWrapper);
```



### fnb_recipe_line

`ai.neargo.shop.food.mapper.FoodMappers.RecipeLineMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbRecipeLine selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> queryWrapper);
// inherited BaseMapper
List<FnbRecipeLine> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipeLine> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipeLine> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> queryWrapper);
// inherited BaseMapper
int insert(FnbRecipeLine row);
// inherited BaseMapper
int update(FnbRecipeLine changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeLine> updateWrapper);
```



### fnb_recipe_adjustment

`ai.neargo.shop.food.mapper.FoodMappers.RecipeAdjustmentMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbRecipeAdjustment selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> queryWrapper);
// inherited BaseMapper
List<FnbRecipeAdjustment> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipeAdjustment> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbRecipeAdjustment> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> queryWrapper);
// inherited BaseMapper
int insert(FnbRecipeAdjustment row);
// inherited BaseMapper
int update(FnbRecipeAdjustment changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbRecipeAdjustment> updateWrapper);
```



### fnb_production_consumption

`ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbProductionConsumption selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> queryWrapper);
// inherited BaseMapper
List<FnbProductionConsumption> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbProductionConsumption> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbProductionConsumption> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> queryWrapper);
// inherited BaseMapper
int insert(FnbProductionConsumption row);
// inherited BaseMapper
int update(FnbProductionConsumption changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumption> updateWrapper);
```



### fnb_production_consumption_line

`ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionLineMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbProductionConsumptionLine selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> queryWrapper);
// inherited BaseMapper
List<FnbProductionConsumptionLine> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbProductionConsumptionLine> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbProductionConsumptionLine> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> queryWrapper);
// inherited BaseMapper
int insert(FnbProductionConsumptionLine row);
// inherited BaseMapper
int update(FnbProductionConsumptionLine changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbProductionConsumptionLine> updateWrapper);
```



### fnb_queue_ticket

`ai.neargo.shop.food.mapper.FoodMappers.QueueTicketMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbQueueTicket selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> queryWrapper);
// inherited BaseMapper
List<FnbQueueTicket> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbQueueTicket> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbQueueTicket> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> queryWrapper);
// inherited BaseMapper
int insert(FnbQueueTicket row);
// inherited BaseMapper
int update(FnbQueueTicket changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueTicket> updateWrapper);
```



### fnb_reservation_table

`ai.neargo.shop.food.mapper.ReservationTableMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/ReservationTableMapper.java`。

```java
// inherited BaseMapper
FnbReservationTable selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> queryWrapper);
// inherited BaseMapper
List<FnbReservationTable> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReservationTable> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReservationTable> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> queryWrapper);
// inherited BaseMapper
int insert(FnbReservationTable row);
// inherited BaseMapper
int update(FnbReservationTable changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationTable> updateWrapper);
```



### fnb_business_share_plan

`ai.neargo.shop.food.mapper.BusinessSharePlanMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/BusinessSharePlanMapper.java`。

```java
// inherited BaseMapper
FnbBusinessSharePlan selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> queryWrapper);
// inherited BaseMapper
List<FnbBusinessSharePlan> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessSharePlan> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessSharePlan> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> queryWrapper);
// inherited BaseMapper
int insert(FnbBusinessSharePlan row);
// inherited BaseMapper
int update(FnbBusinessSharePlan changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessSharePlan> updateWrapper);
```



### fnb_dining_device

`ai.neargo.shop.food.mapper.DiningIdentityMappers.DeviceMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/DiningIdentityMappers/DeviceMapper.java`。

```java
// inherited BaseMapper
FnbDiningDevice selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningDevice> queryWrapper);
// inherited BaseMapper
List<FnbDiningDevice> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningDevice> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbDiningDevice> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbDiningDevice> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningDevice> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningDevice> queryWrapper);
// inherited BaseMapper
int insert(FnbDiningDevice row);
// inherited BaseMapper
int update(FnbDiningDevice changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbDiningDevice> updateWrapper);
```



### fnb_identity_authorization

`ai.neargo.shop.food.mapper.DiningIdentityMappers.AuthorizationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/DiningIdentityMappers/AuthorizationMapper.java`。

```java
// inherited BaseMapper
FnbIdentityAuthorization selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityAuthorization> queryWrapper);
// inherited BaseMapper
List<FnbIdentityAuthorization> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityAuthorization> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbIdentityAuthorization> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbIdentityAuthorization> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityAuthorization> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityAuthorization> queryWrapper);
// inherited BaseMapper
int insert(FnbIdentityAuthorization row);
// inherited BaseMapper
int update(FnbIdentityAuthorization changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityAuthorization> updateWrapper);
```



### fnb_identity_operation

`ai.neargo.shop.food.mapper.DiningIdentityMappers.OperationMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/DiningIdentityMappers/OperationMapper.java`。

```java
// inherited BaseMapper
FnbIdentityOperation selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityOperation> queryWrapper);
// inherited BaseMapper
List<FnbIdentityOperation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityOperation> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbIdentityOperation> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbIdentityOperation> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityOperation> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityOperation> queryWrapper);
// inherited BaseMapper
int insert(FnbIdentityOperation row);
// inherited BaseMapper
int update(FnbIdentityOperation changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbIdentityOperation> updateWrapper);
```



### fnb_check_freeze

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbCheckFreeze selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckFreeze> queryWrapper);
// inherited BaseMapper
List<FnbCheckFreeze> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckFreeze> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckFreeze> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckFreeze> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckFreeze> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckFreeze> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckFreeze row);
// inherited BaseMapper
int update(FnbCheckFreeze changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckFreeze> updateWrapper);
```



### fnb_check_line_membership

`ai.neargo.shop.food.mapper.BusinessSharePlanMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/BusinessSharePlanMapper.java`。

```java
// inherited BaseMapper
FnbCheckLineMembership selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckLineMembership> queryWrapper);
// inherited BaseMapper
List<FnbCheckLineMembership> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckLineMembership> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckLineMembership> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCheckLineMembership> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckLineMembership> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckLineMembership> queryWrapper);
// inherited BaseMapper
int insert(FnbCheckLineMembership row);
// inherited BaseMapper
int update(FnbCheckLineMembership changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCheckLineMembership> updateWrapper);
```



### fnb_reservation_preorder

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbReservationPreorder selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationPreorder> queryWrapper);
// inherited BaseMapper
List<FnbReservationPreorder> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationPreorder> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReservationPreorder> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReservationPreorder> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationPreorder> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationPreorder> queryWrapper);
// inherited BaseMapper
int insert(FnbReservationPreorder row);
// inherited BaseMapper
int update(FnbReservationPreorder changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReservationPreorder> updateWrapper);
```



### fnb_service_slot

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbServiceSlot selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceSlot> queryWrapper);
// inherited BaseMapper
List<FnbServiceSlot> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceSlot> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceSlot> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceSlot> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceSlot> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceSlot> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceSlot row);
// inherited BaseMapper
int update(FnbServiceSlot changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceSlot> updateWrapper);
```



### fnb_capacity_claim

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbCapacityClaim selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCapacityClaim> queryWrapper);
// inherited BaseMapper
List<FnbCapacityClaim> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCapacityClaim> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCapacityClaim> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCapacityClaim> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCapacityClaim> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCapacityClaim> queryWrapper);
// inherited BaseMapper
int insert(FnbCapacityClaim row);
// inherited BaseMapper
int update(FnbCapacityClaim changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCapacityClaim> updateWrapper);
```



### fnb_service_handover

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbServiceHandover selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandover> queryWrapper);
// inherited BaseMapper
List<FnbServiceHandover> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandover> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceHandover> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceHandover> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandover> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandover> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceHandover row);
// inherited BaseMapper
int update(FnbServiceHandover changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandover> updateWrapper);
```



### fnb_service_handover_item

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbServiceHandoverItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandoverItem> queryWrapper);
// inherited BaseMapper
List<FnbServiceHandoverItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandoverItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceHandoverItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceHandoverItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandoverItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandoverItem> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceHandoverItem row);
// inherited BaseMapper
int update(FnbServiceHandoverItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceHandoverItem> updateWrapper);
```



### fnb_service_responsibility

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbServiceResponsibility selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceResponsibility> queryWrapper);
// inherited BaseMapper
List<FnbServiceResponsibility> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceResponsibility> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceResponsibility> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceResponsibility> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceResponsibility> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceResponsibility> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceResponsibility row);
// inherited BaseMapper
int update(FnbServiceResponsibility changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceResponsibility> updateWrapper);
```



### fnb_operation_case

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbOperationCase selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOperationCase> queryWrapper);
// inherited BaseMapper
List<FnbOperationCase> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOperationCase> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbOperationCase> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbOperationCase> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOperationCase> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOperationCase> queryWrapper);
// inherited BaseMapper
int insert(FnbOperationCase row);
// inherited BaseMapper
int update(FnbOperationCase changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbOperationCase> updateWrapper);
```



### fnb_business_command

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbBusinessCommand selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessCommand> queryWrapper);
// inherited BaseMapper
List<FnbBusinessCommand> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessCommand> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessCommand> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessCommand> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessCommand> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessCommand> queryWrapper);
// inherited BaseMapper
int insert(FnbBusinessCommand row);
// inherited BaseMapper
int update(FnbBusinessCommand changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessCommand> updateWrapper);
```



### fnb_scheduled_dispatch

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbScheduledDispatch selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbScheduledDispatch> queryWrapper);
// inherited BaseMapper
List<FnbScheduledDispatch> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbScheduledDispatch> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbScheduledDispatch> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbScheduledDispatch> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbScheduledDispatch> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbScheduledDispatch> queryWrapper);
// inherited BaseMapper
int insert(FnbScheduledDispatch row);
// inherited BaseMapper
int update(FnbScheduledDispatch changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbScheduledDispatch> updateWrapper);
```



### fnb_queue_notice

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbQueueNotice selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueNotice> queryWrapper);
// inherited BaseMapper
List<FnbQueueNotice> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueNotice> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbQueueNotice> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbQueueNotice> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueNotice> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueNotice> queryWrapper);
// inherited BaseMapper
int insert(FnbQueueNotice row);
// inherited BaseMapper
int update(FnbQueueNotice changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbQueueNotice> updateWrapper);
```



### fnb_kds_cursor

`ai.neargo.shop.food.mapper.FoodKdsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodKdsMapper.java`。

```java
// inherited BaseMapper
FnbKdsCursor selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsCursor> queryWrapper);
// inherited BaseMapper
List<FnbKdsCursor> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsCursor> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbKdsCursor> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbKdsCursor> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsCursor> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsCursor> queryWrapper);
// inherited BaseMapper
int insert(FnbKdsCursor row);
// inherited BaseMapper
int update(FnbKdsCursor changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsCursor> updateWrapper);
```



### fnb_kds_subscription

`ai.neargo.shop.food.mapper.FoodKdsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodKdsMapper.java`。

```java
// inherited BaseMapper
FnbKdsSubscription selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsSubscription> queryWrapper);
// inherited BaseMapper
List<FnbKdsSubscription> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsSubscription> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbKdsSubscription> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbKdsSubscription> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsSubscription> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsSubscription> queryWrapper);
// inherited BaseMapper
int insert(FnbKdsSubscription row);
// inherited BaseMapper
int update(FnbKdsSubscription changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsSubscription> updateWrapper);
```



### fnb_kds_event

`ai.neargo.shop.food.mapper.FoodKdsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodKdsMapper.java`。

```java
// inherited BaseMapper
FnbKdsEvent selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsEvent> queryWrapper);
// inherited BaseMapper
List<FnbKdsEvent> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsEvent> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbKdsEvent> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbKdsEvent> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsEvent> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsEvent> queryWrapper);
// inherited BaseMapper
int insert(FnbKdsEvent row);
// inherited BaseMapper
int update(FnbKdsEvent changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsEvent> updateWrapper);
```



### fnb_station_runtime

`ai.neargo.shop.food.mapper.FoodStationRuntimeMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodStationRuntimeMapper.java`。

```java
// inherited BaseMapper
FnbStationRuntime selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStationRuntime> queryWrapper);
// inherited BaseMapper
List<FnbStationRuntime> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStationRuntime> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbStationRuntime> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbStationRuntime> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStationRuntime> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStationRuntime> queryWrapper);
// inherited BaseMapper
int insert(FnbStationRuntime row);
// inherited BaseMapper
int update(FnbStationRuntime changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbStationRuntime> updateWrapper);
```



### fnb_kds_voice_command

`ai.neargo.shop.food.mapper.FoodStationRuntimeMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodStationRuntimeMapper.java`。

```java
// inherited BaseMapper
FnbKdsVoiceCommand selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsVoiceCommand> queryWrapper);
// inherited BaseMapper
List<FnbKdsVoiceCommand> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsVoiceCommand> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbKdsVoiceCommand> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbKdsVoiceCommand> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsVoiceCommand> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsVoiceCommand> queryWrapper);
// inherited BaseMapper
int insert(FnbKdsVoiceCommand row);
// inherited BaseMapper
int update(FnbKdsVoiceCommand changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbKdsVoiceCommand> updateWrapper);
```



### fnb_return_amendment

`ai.neargo.shop.food.mapper.BusinessSharePlanMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/BusinessSharePlanMapper.java`。

```java
// inherited BaseMapper
FnbReturnAmendment selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnAmendment> queryWrapper);
// inherited BaseMapper
List<FnbReturnAmendment> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnAmendment> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbReturnAmendment> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbReturnAmendment> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnAmendment> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnAmendment> queryWrapper);
// inherited BaseMapper
int insert(FnbReturnAmendment row);
// inherited BaseMapper
int update(FnbReturnAmendment changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbReturnAmendment> updateWrapper);
```



### fnb_fee_source_binding

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbFeeSourceBinding selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeSourceBinding> queryWrapper);
// inherited BaseMapper
List<FnbFeeSourceBinding> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeSourceBinding> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbFeeSourceBinding> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbFeeSourceBinding> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeSourceBinding> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeSourceBinding> queryWrapper);
// inherited BaseMapper
int insert(FnbFeeSourceBinding row);
// inherited BaseMapper
int update(FnbFeeSourceBinding changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeSourceBinding> updateWrapper);
```



### fnb_cart_delivery

`ai.neargo.shop.food.mapper.DiningDeliveryMapper.DiningDeliveryMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/DiningDeliveryMapper/DiningDeliveryMapper.java`。

```java
// inherited BaseMapper
FnbCartDelivery selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCartDelivery> queryWrapper);
// inherited BaseMapper
List<FnbCartDelivery> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCartDelivery> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCartDelivery> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCartDelivery> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCartDelivery> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCartDelivery> queryWrapper);
// inherited BaseMapper
int insert(FnbCartDelivery row);
// inherited BaseMapper
int update(FnbCartDelivery changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCartDelivery> updateWrapper);
```



### fnb_business_organization

`ai.neargo.shop.food.mapper.ServiceFormMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/ServiceFormMapper.java`。

```java
// inherited BaseMapper
FnbBusinessOrganization selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessOrganization> queryWrapper);
// inherited BaseMapper
List<FnbBusinessOrganization> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessOrganization> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessOrganization> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbBusinessOrganization> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessOrganization> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessOrganization> queryWrapper);
// inherited BaseMapper
int insert(FnbBusinessOrganization row);
// inherited BaseMapper
int update(FnbBusinessOrganization changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbBusinessOrganization> updateWrapper);
```



### fnb_service_form

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbServiceForm selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceForm> queryWrapper);
// inherited BaseMapper
List<FnbServiceForm> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceForm> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceForm> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceForm> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceForm> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceForm> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceForm row);
// inherited BaseMapper
int update(FnbServiceForm changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceForm> updateWrapper);
```



### fnb_service_attendee

`ai.neargo.shop.food.mapper.ServiceFormMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/ServiceFormMapper.java`。

```java
// inherited BaseMapper
FnbServiceAttendee selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceAttendee> queryWrapper);
// inherited BaseMapper
List<FnbServiceAttendee> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceAttendee> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceAttendee> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceAttendee> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceAttendee> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceAttendee> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceAttendee row);
// inherited BaseMapper
int update(FnbServiceAttendee changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceAttendee> updateWrapper);
```



### fnb_service_form_batch

`ai.neargo.shop.food.mapper.ServiceFormMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/ServiceFormMapper.java`。

```java
// inherited BaseMapper
FnbServiceFormBatch selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceFormBatch> queryWrapper);
// inherited BaseMapper
List<FnbServiceFormBatch> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceFormBatch> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceFormBatch> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbServiceFormBatch> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceFormBatch> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceFormBatch> queryWrapper);
// inherited BaseMapper
int insert(FnbServiceFormBatch row);
// inherited BaseMapper
int update(FnbServiceFormBatch changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbServiceFormBatch> updateWrapper);
```



### fnb_fee_contract_claim

`ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodBusinessOperationsMapper.java`。

```java
// inherited BaseMapper
FnbFeeContractClaim selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeContractClaim> queryWrapper);
// inherited BaseMapper
List<FnbFeeContractClaim> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeContractClaim> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbFeeContractClaim> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbFeeContractClaim> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeContractClaim> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeContractClaim> queryWrapper);
// inherited BaseMapper
int insert(FnbFeeContractClaim row);
// inherited BaseMapper
int update(FnbFeeContractClaim changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbFeeContractClaim> updateWrapper);
```



### fnb_catalog_sync_job

`ai.neargo.shop.food.mapper.FoodMappers.CatalogSyncJobMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCatalogSyncJob selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> queryWrapper);
// inherited BaseMapper
List<FnbCatalogSyncJob> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCatalogSyncJob> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCatalogSyncJob> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> queryWrapper);
// inherited BaseMapper
int insert(FnbCatalogSyncJob row);
// inherited BaseMapper
int update(FnbCatalogSyncJob changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncJob> updateWrapper);
```



### fnb_catalog_sync_item

`ai.neargo.shop.food.mapper.FoodMappers.CatalogSyncItemMapper`；文件：`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java`。

```java
// inherited BaseMapper
FnbCatalogSyncItem selectOne(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> queryWrapper);
// inherited BaseMapper
List<FnbCatalogSyncItem> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> queryWrapper);
// inherited BaseMapper
com.baomidou.mybatisplus.core.metadata.IPage<FnbCatalogSyncItem> selectPage(com.baomidou.mybatisplus.core.metadata.IPage<FnbCatalogSyncItem> page, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> queryWrapper);
// inherited BaseMapper
Long selectCount(com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> queryWrapper);
// inherited BaseMapper
int insert(FnbCatalogSyncItem row);
// inherited BaseMapper
int update(FnbCatalogSyncItem changes, com.baomidou.mybatisplus.core.conditions.Wrapper<FnbCatalogSyncItem> updateWrapper);
```



### rpt_food_metric_daily

`ai.neargo.shop.report.dao.ReportFoodMetricDailyDao`；文件：`backend/shop-report/src/main/java/ai/neargo/shop/report/dao/ReportFoodMetricDailyDao.java`。

```java
List<ai.neargo.shop.report.dto.DailyFoodMetricRow> queryByMetricVersionAndWatermark(String entityNo, String storeNo, LocalDate from, LocalDate to, int metricVersion, String watermark);
int upsertProjectionIfSourceVersion(ai.neargo.shop.report.dto.DailyFoodMetricRow row);
```

- `queryByMetricVersionAndWatermark`：更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。
- `upsertProjectionIfSourceVersion`：只由shop-report使用，按投影唯一键和水位幂等更新，不改营业日签核事实。

## 四、Service内部门面与恢复方法

这些方法由同域Service/事件/任务调用，不额外开放HTTP接口；ServiceImpl实现同一签名。内部参数来自已验证对象，不能让Controller直接接收Entity或任意snapshot。

- `ai.neargo.shop.food.service.CheckService` / `ai.neargo.shop.food.service.impl.CheckServiceImpl`：

```java
OperationVO recoverTableOperation(String entityNo, String storeNo, String operationNo);
```

查询原Resource操作，短事务应用占用；失败保留恢复状态。

- `ai.neargo.shop.food.service.ConfigRevisionService` / `ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl`：

```java
ConfigRevisionVO publishRevision(String entityNo, String configKind, String configNo, long expectedVersion, java.util.Map<String,Object> validatedDefinition, java.time.Instant effectiveAt, String idempotencyKey);
```

输入是所属资源Service完成校验的定义，冻结不可变内容及依赖版本。

- `ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`：

```java
java.util.List<String> reserveBatchQuota(String entityNo, String storeNo, String batchNo, java.util.Map<String,java.math.BigDecimal> restrictionQuantities);
```

按批次/限制唯一键预占；额度数量来自已验证源行。

- `ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`：

```java
void consumeBatchQuota(String entityNo, String storeNo, String batchNo);
```

仅已受理批次从RESERVED一次转CONSUMED。

- `ai.neargo.shop.food.service.SalesRestrictionService` / `ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl`：

```java
void releaseBatchQuota(String entityNo, String storeNo, String batchNo);
```

核心确认失败/取消才释放，UNKNOWN保留。

- `ai.neargo.shop.food.service.DiningOrderService` / `ai.neargo.shop.food.service.impl.DiningOrderServiceImpl`：

```java
BatchDetailVO recoverPlacement(String entityNo, String storeNo, String batchNo);
```

从冻结请求查原键结果，不能另造请求。

- `ai.neargo.shop.food.service.KitchenTaskService` / `ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl`：

```java
java.util.List<String> dispatchAcceptedBatch(String entityNo, String storeNo, String batchNo, String acceptedEventNo);
```

受理合法后按冻结路由派工；无组件任务也可去重。

- `ai.neargo.shop.food.service.DiningCheckoutService` / `ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl`：

```java
OperationVO recoverChargeIntent(String entityNo, String storeNo, String intentNo);
```

查询核心资金状态再条件回写，不换键重复收费。

- `ai.neargo.shop.food.service.DiningReturnService` / `ai.neargo.shop.food.service.impl.DiningReturnServiceImpl`：

```java
OperationVO recoverReturn(String entityNo, String storeNo, String returnNo);
```

财务与厨房独立恢复。

- `ai.neargo.shop.food.service.DiningAuditService` / `ai.neargo.shop.food.service.impl.DiningAuditServiceImpl`：

```java
void appendAction(String entityNo, String storeNo, String actorNo, String objectType, String objectNo, String action, String reason);
```

真实操作者与敏感原因，接共享审计，不包含口令令牌。

- `ai.neargo.shop.food.service.RoomChargeService` / `ai.neargo.shop.food.service.impl.RoomChargeServiceImpl`：

```java
OperationVO recoverRoomCharge(String entityNo, String storeNo, String intentNo);
```

查原PMS/应收凭据，UNKNOWN不标结清。

## 五、辅助类的方法

### ai.neargo.shop.food.policy.TableOccupancyPolicy

容量、先占后放、跨台协作守卫。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateCapacity(FnbTable table, java.util.List<FnbTableOccupancy> activeOccupancies, int requestedSeats);
```

### ai.neargo.shop.food.statemachine.FoodCheckStateMachine

服务台账状态；不接管核心资金状态。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void assertTransition(String from, String to);
```

### ai.neargo.shop.food.job.TableOperationRecoveryJob

按原操作号恢复资源与占用。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.assembler.TableViewAssembler

桌态、占用和资金摘要分别投影。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
TableVO toTableVO(FnbTable table, java.util.List<FnbTableOccupancy> authorizedOccupancies);
```

### ai.neargo.shop.food.policy.MenuAvailabilityPolicy

门店/时段/商品可售求交。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
boolean isAvailable(FnbMenu menu, java.time.Instant at, String orderSource, String fulfillment);
```

### ai.neargo.shop.food.policy.SelectionRulePolicy

必选互斥、组合循环及单位校验。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validate(SelectionRules rules, java.util.List<SelectionReq> selections);
```

### ai.neargo.shop.food.policy.ConfigPublicationPolicy

不可变发布及回滚新修订。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateSnapshot(String configKind, java.util.Map<String,Object> validatedDefinition);
```

### ai.neargo.shop.food.policy.SalesQuotaPolicy

额度预占、消费、释放幂等。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateRemaining(FnbSalesRestriction restriction, java.math.BigDecimal requestedQty);
```

### ai.neargo.shop.food.job.FoodSalesRestrictionExpiryJob

限制到期，不回填实存。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.policy.OrderPlacementPolicy

报价指纹、源行与车版本。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateSubmission(CartVO cart, QuoteVO quote, java.util.List<OrderLineReq> selectedLines);
```

### ai.neargo.shop.food.statemachine.OrderBatchStateMachine

建单/UNKNOWN/受理/取消状态。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void assertTransition(String from, String to);
```

### ai.neargo.shop.food.policy.OrderLineAllocationPolicy

源行和核心成交行数量金额守恒。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateConservation(java.util.List<FnbOrderLine> sourceLines, java.util.List<FnbCoreLineAllocation> verifiedAllocations);
```

### ai.neargo.shop.food.job.FoodPlacementRecoveryJob

同键查询原核心结果。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.event.CoreSubOrderPaidConsumer

可信支付事实只受理一次。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void onSubOrderPaid(String eventNo, String storeOrderNo, java.time.Instant occurredAt);
```

### ai.neargo.shop.food.policy.KitchenTaskPolicy

制作上菜撤菜数量守卫。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateAction(FnbKitchenTask task, String action, java.math.BigDecimal qty);
```

### ai.neargo.shop.food.event.KitchenDispatchConsumer

只由正式受理派工，事件去重。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void onBatchAccepted(String eventNo, String batchNo, long batchVersion);
```

### ai.neargo.shop.food.policy.SettlementPolicy

范围冻结、防重复收款、未知资金守卫。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateFinalization(FnbSettlement settlement, java.util.List<FnbChargeIntent> intents, boolean coreConfirmedSettled);
```

### ai.neargo.shop.food.job.FoodChargeRecoveryJob

查原资金意图恢复。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.policy.FoodReturnPolicy

可退数量、审批、财务/厨房分轨。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateQuantity(java.math.BigDecimal originalQty, java.math.BigDecimal activeReturnQty, java.math.BigDecimal requestedQty);
```

### ai.neargo.shop.food.job.FoodReturnRecoveryJob

原退款请求查询恢复。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.assembler.FoodPrintDocumentAssembler

food-core冻结内容交共享打印，不另造打印队列。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
java.util.Map<String,Object> freezeDocument(String scene, java.util.Map<String,Object> verifiedSnapshot, long contentVersion);
```

### ai.neargo.shop.food.policy.CashDrawerPolicy

活跃钱箱班次、实收找零与盘点差异。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateCount(FnbCashShift shift, long countedMinor, long expectedMinor);
```

### ai.neargo.shop.food.policy.BusinessClosePolicy

资金证据、水位指纹与正式版本。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateSignature(FnbCloseRun close, String expectedHash, String expectedWatermark);
```

### ai.neargo.shop.food.job.FoodBusinessDayRolloverJob

营业日滚动不等于签核。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.job.FoodClosePrecheckJob

预结巡检和异常清单。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.job.FoodReportWatermarkJob

投影水位、幂等重算。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.policy.DiningEntitlementPolicy

限时入场、频控与最后点单。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateOrdering(FnbDiningEntitlement entitlement, java.time.Instant at);
```

### ai.neargo.shop.food.policy.CustodyQuantityPolicy

寄存领用/报损上限。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateWithdrawal(FnbBottleCustody custody, java.math.BigDecimal qty);
```

### ai.neargo.shop.food.job.FoodRoomChargeRecoveryJob

原PMS/应收意图查询恢复。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.policy.EdgeWriterLeasePolicy

租约代次与单写者隔离；正式协议仍待专项。纯规则/组装不直接改核心表；事件处理先验证事实及幂等。

```java
void validateWriter(FnbEdgeLease lease, String edgeNo, long epoch, java.time.Instant now);
```

### ai.neargo.shop.food.job.FoodSyncRecoveryJob

连续同步水位和冲突认领。严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。

```java
String name();
ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation);
```

### ai.neargo.shop.food.config.FoodAutoConfiguration

启用/禁用与自动装配。配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。

无新增业务方法；仅声明装配/属性职责。

### ai.neargo.shop.food.config.FoodProperties

沿主库的餐饮配置。配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。

无新增业务方法；仅声明装配/属性职责。

### ai.neargo.shop.food.config.FoodPersistenceConfiguration

主DataSource与独立fnb迁移记录，唯一执行者。配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。

无新增业务方法；仅声明装配/属性职责。

### ai.neargo.shop.food.config.FoodPortConfiguration

本地/远程适配互斥。配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。

无新增业务方法；仅声明装配/属性职责。

Entity/DTO方法：实体沿Lombok及BaseEntity的getXxx/setXxx；record沿字段名访问器，不在数据对象上增加收退款/计价/授权方法。字段与完整类名见模块机器清单dtoTypes/persistence。

## 六、实现核对

1. 按模块选择API用例后，再实现相应Controller、Service/Impl与Mapper；先桌台，再菜品、订单。
2. 校验同名Service方法参数/返回不冲突、同一Mapper方法定义唯一、跨域只走Port；每个Service方法的请求字段和返回结构以API册为准。
3. 核心能力缺口继续门控：源行、后付库存、应收拆合、部分退款、离线租约和真实设备均需合同测试。
4. Entity沿Lombok/BaseEntity访问器，DTO record沿字段访问器；不额外生成一套领域实体/Repository。辅助Policy/Job及内部门面方法已在方法机器清单逐项列出，不改变现有HTTP目标。
5. 已应用的V1冻结；方法设计不更新数据库。文档守卫通过不等于Java编译、SQL事务或业务测试通过。
