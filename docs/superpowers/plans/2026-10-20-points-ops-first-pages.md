# Points Operations First Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Launch the first configuration group of Points Operations as independent, list-first pages, wired to existing APIs and visible under a dedicated top-level menu.

**Architecture:** Add a top-level `/points` navigation section with the overview, type/rule, and store agreement/access entries. Implement `/points/types` and `/points/store-access` as focused pages using the existing API contracts, shared table/drawer/forms, and the current permission codes. Map the approved old `overview` and `configuration` links to the new routes; retain the original mixed configuration view only at an explicit transitional `configuration-legacy` URL so existing order inspection/refund and other not-yet-migrated operations remain reachable until their own pages ship.

**Tech Stack:** Next.js App Router, React Query, TypeScript, existing ops-web `DataTable`/`Drawer`/form components, `sys_function_point` Flyway migrations, existing deployment script.

**Spec:** [docs/superpowers/specs/2026-10-20-points-ops-information-architecture-design.md](../specs/2026-10-20-points-ops-information-architecture-design.md)

## Scope Decision

“前几个菜单”按已确认信息架构中的第一个完整业务组处理：总览入口、积分类型与规则、门店协议与接入。此轮不提前实现后续资金、开关、退款、订单核验、账单或查询菜单。旧配置页中的剩余能力继续可从旧 `?tab=` 地址访问，待相应独立菜单实现时再迁移；新菜单不会把未完成页面显示成已可用。

## Global Constraints

- Keep existing points API contracts and service-side permission semantics unchanged.
- Reuse the existing ops-web component library, language selector/settings, and visual tokens.
- Lists remain the primary view; create/edit/detail/changes use existing drawers or confirmation dialogs.
- Use existing selectable data sources for store and points-type identifiers instead of free-form identifiers where available.
- Keep old `/points?tab=...` links functional during migration.
- Keep `configuration-legacy` as a temporary compatibility route only; do not expose it as a menu item.
- Run targeted checks and the production ops-web build; do not run unrelated full-repository or concurrency suites.
- Deploy only the reviewed commit using `scripts/deploy-frontend.sh ops-web`, then verify the live `VERSION` SHA and loaded assets.

## Review Focus

- Old configuration deep links must land on the new type/rule route without losing access to legacy refund and order inspection flows; cover with route compatibility tests.
- Store search results must be scoped/paginated using `StoreApi.listStores`; verify empty search, failed search, and selecting a store.
- Agreement results require both a type and store; verify no request fires with either selection missing and pagination resets when either changes.
- Store integration mutation must retain expected version and invalidate both policy and obligations queries; cover the mutation refresh and error path.
- A page read permission must not accidentally enable state-changing controls; verify read-only rendering against `finance:settle:execute` permission.

---

## File Map

- `ops-web/lib/nav.ts`: add the dedicated Points Operations section with first-group routes followed by links to existing legacy points features; remove points leaves from Finance to prevent duplicates.
- `ops-web/lib/nav.test.ts`: assert section ownership, active route mapping, first-group children, and retained legacy compatibility.
- `ops-web/lib/nav-visibility.baseline.json`: update the intentional navigation ownership baseline.
- `backend/shop-app/src/main/resources/db/migration/V529__points_ops_first_pages_menu.sql`: register the new menu routes in `sys_function_point`, preserve existing grants by copying grants from the current points overview point, and update migrated hrefs/names idempotently.
- `ops-web/app/points/page.tsx`: preserve the existing overview and legacy tabs; map legacy `overview` and `configuration` links to dedicated routes, and retain old configuration content under `configuration-legacy` during migration.
- `ops-web/app/points/legacy-configuration-tab.tsx`: retain the current mixed configuration implementation intact for old workflows until all contained capabilities have dedicated pages.
- `ops-web/app/points/types/page.tsx`: focused type/rule route.
- `ops-web/app/points/store-access/page.tsx`: focused agreement/access route.
- `ops-web/app/points/types-tab.tsx`: extract the existing type list, create drawer, detail drawer, fallback rule, and terms editing into a single type/rule responsibility.
- `ops-web/app/points/store-access-tab.tsx`: new store/type selectors, agreement list, integration policy and obligation inspection, detail drawer, and versioned mutation.
- `ops-web/lib/points-ops-routes.test.ts`: route/menu migration and compatibility tests.
- `ops-web/lib/points-ops-routes.ts`: pure legacy-tab mapping used by the `/points` compatibility view.
- `ops-web/lib/points-configuration-ui.test.ts`: extend existing page-contract tests for the split responsibilities, selector behavior, and read/write permission separation.

## Task 1: Menu and route compatibility

**Files:**
- Modify: `ops-web/lib/nav.ts`
- Modify: `ops-web/lib/nav.test.ts`
- Modify: `ops-web/lib/nav-visibility.baseline.json`
- Create: `backend/shop-app/src/main/resources/db/migration/V529__points_ops_first_pages_menu.sql`
- Test: `ops-web/lib/points-ops-routes.test.ts`

**Interfaces:** The top-level section key is `points`, href `/points`, module `finance`; its first children are `/points` (“积分总览”), `/points/types` (“积分类型与规则”), and `/points/store-access` (“门店协议与接入”). Existing switches/treasury/settlements/queries stay reachable under the same Points Operations section as legacy routes until their independent pages ship. All read leaves use `finance:settle:read`; write controls retain existing action permissions.

- [x] **Step 1: Add failing route/menu assertions** for the top-level `points` section, first-group and retained legacy leaf hrefs, Finance no longer owning `/points`, and legacy route redirects.
- [x] **Step 2: Run the targeted test and confirm it fails** with the missing points section/route assertions.
- [x] **Step 3: Add the new nav section and update path ownership.** Link the points overview leaf to `/points`; map `/points?tab=overview` to `/points` and `/points?tab=configuration` to `/points/types`. Leave old mixed configuration UI reachable at `/points?tab=configuration-legacy` for operations not yet split.
- [x] **Step 4: Add V529 menu migration.** Insert new route points under `OPS_FINANCE` because backend function-point grouping and permission namespace remain finance; grant only the same roles/entities currently granted the points overview; update the existing type/configuration point to `/points/types`; use `INSERT ... WHERE NOT EXISTS` and keyed updates so repeated migration-safe execution does not create duplicates.
- [x] **Step 5: Update nav/baseline tests** and run `cd ops-web && npx vitest run lib/points-ops-routes.test.ts lib/nav.test.ts`.

## Task 2: Split the type and rule page

**Files:**
- Create: `ops-web/app/points/types/page.tsx`
- Create: `ops-web/app/points/legacy-configuration-tab.tsx`
- Modify: `ops-web/app/points/types-tab.tsx`
- Modify: `ops-web/app/points/page.tsx`
- Test: `ops-web/lib/points-configuration-ui.test.ts`

**Interfaces:** The page consumes `FinanceApi.pointsTypes`, `pointsFallback`, `pointsTerms`, `createPointsType`, `transitionPointsType`, `savePointsFallback`, and `publishPointsTerms`; it exports a focused `PointsTypesPage` composition. Store agreements, store integration/obligations, order inspection, and manual refunds are not rendered on this route.

- [x] **Step 1: Add failing page-contract checks** asserting the route renders the type list and type/rule controls but not agreements, integration, order inspection, or manual refund sections.
- [x] **Step 2: Run the targeted test** and confirm current mixed page violates the focused-page contract.
- [x] **Step 3: Extract the type/rule page** while preserving list row drawer behavior, create drawer, fallback revision, published terms, request idempotency, read/write permissions, and query invalidation.
- [x] **Step 4: Add the dedicated route** with the existing page shell, breadcrumbs/title, language support, and read permission guard.
- [x] **Step 5: Keep the original mixed component intact** at `/points?tab=configuration-legacy`; add a small compatibility link to that route from `/points/types`, and links from the legacy view to the new type and store-access routes. This route is temporary and is not linked from the new menu.
- [x] **Step 6: Verify** with the focused page-contract test and TypeScript/lint check for changed files.

## Task 3: Build the store agreement and access page

**Files:**
- Create: `ops-web/app/points/store-access/page.tsx`
- Create: `ops-web/app/points/store-access-tab.tsx`
- Modify: `ops-web/app/points/page.tsx`
- Test: `ops-web/lib/points-configuration-ui.test.ts`

**Interfaces:** Use `FinanceApi.pointsTypes()`, `pointsAgreements({typeCode, storeNo, after, limit})`, `orderPointsIntegration({storeNo, scene})`, `orderPointsObligations(storeNo)`, and `changeOrderPointsIntegration({storeNo, scene, mode, earnEnabled, redeemEnabled, expectedVersion, reason, requestId})`. Use `StoreApi.listStores({keyword, page, size})` through the existing query contract for store selection.

- [x] **Step 1: Add failing interaction checks** for type/store selection prerequisites, agreement pagination reset, policy/obligation query keys, and versioned mutation invalidation.
- [x] **Step 2: Implement searchable selectors** using existing `Select`/searchable-list patterns; do not submit agreement/policy requests until required selections exist.
- [x] **Step 3: Render the agreement/access list** as the main content; clicking a row opens a detail drawer showing agreement facts, current integration policy by scene, and stored obligations.
- [x] **Step 4: Keep integration changes in the detail drawer** with mode and earning/redemption controls, reason input, expected version, idempotent request ID, and explicit confirmation for detaching/draining transitions; do not allow full detach when obligations are unknown or uncleared.
- [x] **Step 5: Add route and compatibility mapping** and keep existing order-inspection/refund behavior reachable through the transitional legacy route without rendering it on the new focused route.
- [x] **Step 6: Run focused tests** and verify request failures keep current form values and surface an actionable error.

## Task 4: Remove the ops-web electronic-components surface

**Files:**
- Delete: `ops-web/app/elec/`
- Delete: `ops-web/lib/api/contracts/elec.ts`, `ops-web/lib/api/https/elec.ts`, `ops-web/lib/api/mocks/elec.ts`, `ops-web/lib/types/elec.ts`
- Modify: `ops-web/lib/nav.ts`, `ops-web/lib/nav.test.ts`, `ops-web/lib/nav-visibility.baseline.json`, `ops-web/lib/point-codes.ts`, `ops-web/lib/api/contract.ts`, `ops-web/lib/api/http.ts`, `ops-web/lib/api/mock.ts`, `ops-web/lib/api/http-client.ts`, `ops-web/lib/types/index.ts`, `ops-web/lib/permissions.ts`, `ops-web/lib/auth.ts`, `ops-web/lib/i18n/nav-labels.ts`, `ops-web/lib/i18n/messages/zh.ts`, `ops-web/lib/i18n/messages/en.ts`
- Modify: `ops-web/lib/permissions.test.ts`, `ops-web/lib/nav.test.ts`, `ops-web/lib/api/contract.test.ts`
- Create: `backend/shop-app/src/main/resources/db/migration/V528__retire_ops_electronic_components_menu.sql`
- Test: `ops-web/lib/ops-elec-removal.test.ts`

**Interfaces:** Remove only the ops-web `ElecApi` slice and `elec:*` UI permission routes. Keep the independent `elec-svc`, its data, buyer/supplier mini-program surfaces, and the legacy `ELEC_ADMIN` identity available for existing staff records; the retired role retains only its existing dashboard-read permission. The migration soft-retires the `OPS_ELEC` function/menu points and revokes their active role-point grants without deleting historical records or user assignments.

- [x] **Step 1: Add failing removal assertions** for no `elec` nav section/route, no Elec API slice in the mock/http compositions, and no `elec:*` UI permission mappings.
- [x] **Step 2: Run the targeted removal test** and confirm it fails against the current ops-web surface.
- [x] **Step 3: Remove the frontend Elec route and API surface.** Delete the `/elec` pages and their dedicated types/contract/http/mock files; remove their composition, base-url, point-code, navigation, icon/comment, and permission-map references.
- [x] **Step 4: Preserve unrelated role behavior.** Remove Elec permissions from product ops, merchant BD, support, risk, and the legacy Elec role; keep the role identifier in auth and translations so existing users continue to receive a valid role label and dashboard access.
- [x] **Step 5: Add V528 retirement migration.** Set the `OPS_ELEC` `sys_function.enabled=0`; soft-delete its active function points and role-point grants; leave `sys_function_point`, `sys_role_point`, role membership, and all Elec service data physically intact.
- [x] **Step 6: Update nav/matrix/role/API tests** to assert the Elec entry is absent and run the focused tests.

## Task 5: Verification and deployment

**Files:** No additional product files unless verification finds a defect.

- [x] **Step 1: Run targeted tests**: `cd ops-web && npx vitest run lib/points-ops-routes.test.ts lib/points-configuration-ui.test.ts lib/nav.test.ts`.
- [x] **Step 2: Run Elec removal tests**: `cd ops-web && npx vitest run lib/ops-elec-removal.test.ts lib/nav.test.ts lib/permissions.test.ts lib/api/contract.test.ts`.
- [x] **Step 3: Run the ops-web production build** with the repository's production build command and resolve only errors caused by this change.
- [x] **Step 4: Review the final diff** for unintended changes; preserve all unrelated dirty files and do not include them in any commit.
- [ ] **Step 5: Commit only this plan's exact files** after targeted checks/build pass; leave all pre-existing staged/unstaged/untracked changes untouched.
- [ ] **Step 6: Deploy the reviewed commit** with `scripts/deploy-frontend.sh ops-web`; the script builds a clean commit worktree, backs up the current site, uploads, verifies `VERSION`, and checks JS/CSS assets.
- [ ] **Step 7: Verify the live site** by opening `/ops-web/`, checking the `VERSION` SHA matches the deployed commit, confirming Points Operations exposes overview/types/store access and both new routes load, and confirming the Elec menu is absent.

## Acceptance

- Points Operations appears as its own top-level menu, with overview and the first two operational pages first; old points-operation links remain in this section until their own pages ship.
- The type/rule page only manages point types, fallback rules, and terms.
- The store agreement/access page uses searched store/type selections, presents a single list, and puts details and mutations in the existing drawer/dialog patterns.
- Existing agreements, order inspection, and refund capabilities remain reachable through the transitional legacy route until their own dedicated pages ship; old `configuration` URLs resolve to the new type/rule page as approved in the spec.
- Existing API contracts and backend business behavior remain unchanged; menu permission grants are preserved for the same operators.
- The Ops UI no longer imports, exposes, mocks, or routes to the Elec ops service; V528 removes its live Ops menu/grants while preserving Elec backend and historical records.
- Targeted tests and production build pass, deployment script confirms the live SHA and assets, and unrelated workspace edits remain untouched.
