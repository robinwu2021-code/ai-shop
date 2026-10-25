# Print Compact V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Preserve the user-authorized sequence: all code and test sources, unified compilation, centralized acceptance.

**Goal:** Implement the approved concise receipt layout across 20 scenes and both roll widths.
**Architecture:** Opt-in V2 template compiler and layout branch; immutable V1 layouts and their copy slots remain supported. Compact copy marks replace protected slots without moving business drawings.
**Tech Stack:** Java 21, existing Maven print modules, PDFBox, JUnit.
**Spec:** docs/superpowers/specs/2026-10-23-print-compact-design.md

## Global Constraints

- Preserve unrelated dirty/staged files. No automatic commit, publish or production migration.
- Keep original mandatory business facts, currency correctness and unknown/partial-output safeguards.
- V2 updates builtin fallback definitions only; merchant-published versions stay immutable.

## Review Focus

- Long product titles or huge amounts must wrap without overlap or truncation.
- Zero refund/paid values remain visible; only zero freight is hidden.
- Reprints preserve original body coordinates and show original ticket plus warnings.
- All pages fit 270mm including expanded copy marks; legacy copies still use V1 slots.
- Financial scenes retain currency; operational notes and mandatory unpaid warnings remain visible.

### Task 1: Compiler and builtin V2 definitions

Files: DefaultPrintDocumentCompiler.java; new CompactReceiptRows.java; paired runtime/design print-template-definitions.json.
- [x] Add V2 row builder with semantic left/right cells, readable item fields, safe optional visibility and frozen full source data.
- [x] Update all 40 builtin templates to opt in, remove duplicate title/trace metadata, preserve required business binding validation.

### Task 2: Layout, protected copy slots and PDF

Files: DefaultPrintLayoutEngine.java; new CompactPrintLayout.java; PrintSystemSlotPolicy.java; PdfReceiptRenderer.java; job renderer-version assignments.
- [x] Add measured wrapping/alignment, thin rules and compact line spacing with dynamic ticket height.
- [x] Preserve V1 code path; V2 copies keep body positions and extend only controlled footer within profile bounds.
- [x] Use Chinese output marks and version V2 jobs; preserve legacy PDF footer for old layouts.

### Task 3: Centralized acceptance and artifacts

Files: ReceiptSoftwareAcceptanceTest.java; new CompactReceiptAcceptanceTest.java; output/pdf/print-compact-v2/; acceptance docs.
- [x] Add assertions for 58/80 layouts, protected zero values, currency, trace removal, title count, frozen copies, long items/pages, V1 compatibility.
- [x] Compile module/application and test sources with tests skipped, then run printing scoped and order integration tests.
- [x] Generate 40 PDFs through actual renderer, inspect contact sheets and payment/reprint samples, record result and limitations.

## 执行结果

63 项打印相关测试、B 端 271 项测试及类型检查/H5 构建通过；42 份 PDF 已渲染目检。飞鹅 V2 坐标布局明确门控，待适配与实机验收；历史 V1 任务不改写。详见 `docs/technical/acceptance/print-center/compact-v2.md`。
