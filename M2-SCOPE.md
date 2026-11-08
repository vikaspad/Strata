# M2 Scope: Coverage and Locator Quality (proposal)

**Status:** approved 2026-11-07 and built; see `CLAUDE.md` v0.2.0 and `M2-REPORT.md`. Kept as the scoping record.
**Original status:** draft for sign-off. Once it is approved, it becomes `CLAUDE.md` v0.2.0, with a
changelog line, because it changes the §3 contracts and the §8 schema.
**Inputs:** `M1-REPORT.md` (overhead numbers, the three XPath-quality issues, deviations) and the component review
(iframes, dropdowns, AG Grid).

---

## 0. Entry gate: close M1 first

M2 starts only after these four are done:

| # | Item | Owner | Notes |
|---|---|---|---|
| G1 | Decide the overhead rule (§10.2 vs §4.2 conflict) | **You** | Options in §3.1 below. M1 is then re-measured against the chosen rule. |
| G2 | Manual check: right-click `Blotter.feature` in `StrataDemo` → *Run with Locator Coverage* | You | §10.1. |
| G3 | Reword `CLAUDE.md` §4.1: intercept `execute(CommandPayload)` | Claude | Documentation only. The code already does this. |
| G4 | Tag `v0.1.0-m1` | Claude, on your go | Needs a git repo; the folder isn't one yet. |

---

## 1. What M2 delivers

> **After a run, the XML has a verified locator for every component type in the required list (§4.0), says which
> locators the tests actually used, and rates how stable each one is, within an agreed overhead budget.**

**Component coverage (§4.0) is a requirement from the product owner.** M1 only gives XPaths to interactive
elements. M2 extends that to every component in the required list. The `used` flag and coverage % come next. They
also decide which plain `div`/`span`/`p` elements are worth emitting (§4.0, rule R2).

### Priority bands

| Band | Item | Size | Section |
|---|---|---|---|
| **Must** | Coverage for all 21 required components | XL | §4.0 |
| **Must** | Usage tracking → `used` flag + coverage % per module / page / run | L | §2 |
| **Must** | Overhead fix under the rule chosen in G1 | M | §3.1 |
| **Must** | Stability rating on every xpath (POSITIONAL is flagged) | S | §3.2 |
| **Must** | Fixes for the three M1 quality issues (row templates, bounded labels, more module kinds) | M | §3.3 |
| **Must** | Dropdown overlays and options (part of the required list) | M | §4.1 |
| **Must** | Same-origin iframes (part of the required list) | L | §4.3 |
| **Should** | AG Grid adapter (preceded by a fixture spike) | L | §4.2 |
| **Should** | Gradle-delegated test runs in the IDE | M | §5 |
| **Could** | `containerHash` ignores dynamic ids | S | §3.4 |

M2 has grown from about L to XL. It stays one milestone (decision D5), built in the order in §8.

If time runs short, cut from the bottom.

### Out of scope for M2 (proposed for M3, the "IDE experience" milestone)
Tool window, gutter badges, run diff, *Insert `@FindBy`*, `WebDriverListener` fallback, Playwright, Selenium 3,
shadow DOM, cross-origin iframes, AI/LLM naming.

---

## 2. Usage tracking (Must)

### 2.1 What the agent records
- Extend the command set with element lookups and interactions:
  - lookups: `findElement`, `findElements`, `findChildElement`, `findChildElements`
  - interactions: `clickElement`, `sendKeysToElement`, `clearElement`, `getElementText`, `getElementAttribute`,
    `isElementSelected`, `isElementDisplayed`, `isElementEnabled`, `getElementTagName`, `elementGetProperty`
- On lookup exit, read the returned element ids from the `Response`, using `@Advice.Return`. This is free: no
  browser round trip.
- On the **first interaction with each element id**, make one `executeScript` call with the element as an argument.
  The script returns its **snapshot path**: the child-element indexes from `<html>` down, skipping
  `script`/`style`/`noscript` (the elements redaction removes), with SVG descendants mapped to their `<svg>`. That
  path lines up exactly with the redacted snapshot. Cost: one round trip per distinct element, about 5–15 ms. Later
  interactions with the same id cost nothing.
- New file `usage.jsonl`, append-only:

```kotlin
data class UsageRecord(
    val sessionId: String,
    val snapshotSeq: Int,        // latest snapshot of this session when the element was used; 0 = none yet
    val routeKey: String,
    val command: String,         // first interaction command, e.g. "clickElement"
    val locatorUsing: String?,   // from the lookup that returned this element, e.g. "css selector"
    val locatorValue: String?,   // e.g. "[aria-label='Export']"
    val elementPath: List<Int>,  // see above
    val tag: String,             // sanity check when matching against the snapshot
    val usedAt: String
)
```

**Alternative I considered and do not recommend:** re-evaluating the test's own locator against the snapshots in
core. It needs no browser call, but Jsoup's CSS engine differs from the browser's, relative lookups
(`findChildElement`) lose their parent, and the snapshot may show a different DOM state from the one the test used.

### 2.2 How core matches usage to elements
For each `UsageRecord`:
1. Resolve `elementPath` in snapshot `snapshotSeq`, then in later snapshots of the same route and session.
2. Check that the tag matches, then mark the `XpathEntry` whose element sits at that path as `used`.
3. If nothing matches, emit an `<unmatched>` entry with a reason: `no-snapshot`, `state-not-captured`, or
   `not-interactable`.

A used element that has no xpath is itself a finding: the test touches something the model can't address.

### 2.3 Coverage numbers
`coverage = used / total` over emitted xpaths, at module, page and run level. Unmatched usages are reported but not
counted in either side.

---

## 3. Quality and overhead (Must)

### 3.1 Overhead: you choose the rule (G1)

The M1 measurements: 500 ms quiet window × 5 navigations ≈ +2.6 s on a 2.4 s flow. Capture itself is 10–20 ms.

| Option | Change | Effect on fixture flow (estimate) | Risk |
|---|---|---|---|
| **A. Cheap probe first** | Before waiting, probe once with no wait. If `(routeKey, containerHash)` is already captured and `readyState=complete`, skip the settle wait. | Removes the wait on unchanged commands (≈ −0.5 s here) | Can miss content rendered asynchronously after the command. A later command still catches it. |
| **B. Configurable quiet window** | `-Dstrata.quietMs`, default lowered after a spike that measures capture correctness at 100 / 200 / 300 / 500 ms | 200 ms ≈ −1.5 s | Captures may come too early on slow SPAs. |
| **C. Budget-based criterion** | Replace "≤ 15 % of wall clock" with: ≤ quietMs + 50 ms per captured page, ≤ 20 ms per uncaptured command, ≤ 15 ms per newly used element | Measurable on any suite | Doesn't promise a % to users. |

**Recommendation: A + B + C.** Spike B first, then make the default whatever the spike shows is still correct on
the fixtures and `StrataDemo`.

### 3.2 Stability rating
Add `stability="HIGH|MEDIUM|LOW"` to every xpath:

- `HIGH`: TESTID, ID, NAME
- `MEDIUM`: ARIA_LABEL, LABEL_FOR, PLACEHOLDER, ROW_TEMPLATE
- `LOW`: TEXT, POSITIONAL, and any LABEL_FOR that uses `following::`

TEXT is LOW because it breaks with copy changes and i18n. Report a stability mix per page.

### 3.3 Fixes for the three M1 issues

| M1 issue | Fix | Contract change |
|---|---|---|
| 1. Row actions are POSITIONAL and unnamed | **Repeated-label exception in redaction:** inside blank zones, keep the text of `button`/`a`/`[role=button]` when the same text appears in ≥ 2 rows. Repetition means it's UI, not data. Then emit one **row template** per column action, e.g. `.//tbody/tr[{row}]//button[normalize-space()='View']`, verified for every row present. | New `Strategy.ROW_TEMPLATE`; §4.4 redaction rule |
| 2. LABEL_FOR falls back to `following::` | Use `.//input[@id=//label[normalize-space()='Trade date']/@for]`. It works with dynamic ids because the id never appears in the XPath. Keep `following::` only as a last resort, bounded to the module. | §5.6 step 5 wording ("stable ids only" is dropped) |
| 3. `//body` becomes a catch-all | New module kinds: `header`, `main`, `aside`, `footer`, `fieldset`, `[role=tablist]`, `[role=toolbar]`, `[role=menu]`, `[role=region][aria-label]` | `ModuleKind` gains `LANDMARK`, `GROUP` |

### 3.4 `containerHash` ignores dynamic ids
Filter the §5.5 dynamic-id patterns out of the hash in the agent, using a Java copy of the rules as with
`RouteKeys`. This prevents duplicate snapshots on apps that generate container ids per render.

---

## 4. Components

### 4.0 Required component coverage

M1 has one rule: only interactive elements get XPaths (§5.2). M2 replaces it with three element classes:

- **Interactive:** what M1 covers, plus new types.
- **Structural:** tables, rows, headers, cells, lists, iframes. Repeated parts are emitted as **templates**
  (`{row}`, `{n}`) and verified for every instance present in the snapshot.
- **Content:** headings, labels, paragraphs, spans, divs, icons. These follow rules R1–R3 below.

| # | Component | M1 today | M2 plan: new type, how it is located | Limits |
|---|---|---|---|---|
| 1 | Text boxes | ✅ `TEXT_INPUT` | Unchanged. Adds an `inputType` attribute (email, number, date, search…) | |
| 2 | Text areas | ✅ `TEXTAREA` | Unchanged | |
| 3 | Password fields | ⚠️ emitted as `TEXT_INPUT` | `PASSWORD` for `input[type=password]` | |
| 4 | Buttons | ✅ `BUTTON` | Unchanged. Also covers `input[type=submit/button/reset/image]`, `[role=button]` | |
| 5 | Hyperlinks | ✅ `LINK` for `a[href]` | Adds `a` without `href` that has text, `aria-label` or a role (SPA links), and `[role=link]` | |
| 6 | Icons | ❌ only inside a button or link | `ICON` for `svg`, `img`, `mat-icon`, and `i`/`span` with icon classes (`fa-*`, `bi-*`, `material-icons`, `icon-*`). Located via `aria-label`, `title`, `alt`, `data-icon`, the **new `CLASS` strategy**, or the `mat-icon` ligature text (newly kept by redaction) | Hashed classes (`css-1x2y3z`, `sc-…`, `_a1b2c`) and state classes (`active`, `disabled`) are never used |
| 7 | Dropdown menus | ⚠️ `select`, `mat-select`, `ng-select`, `[role=combobox]`; menus that open on click are not captured | Adds `[role=menu]`, `.dropdown-menu` and overlay panels as `MENU` modules, captured via `CONTAINER_CHANGE` when opened (§4.1) | Captured only if the test opens it |
| 8 | Dropdown options | ❌ | `OPTION` for native `<option>`, `[role=option]`, `mat-option`, `[role=menuitem]`. Text template: `.//select[@name='account']/option[normalize-space()='{text}']` | Option text policy, decision D3 |
| 9 | Checkboxes | ✅ `CHECKBOX` | Unchanged | |
| 10 | Radio buttons | ✅ `RADIO` | Adds a `group` attribute from `@name` | |
| 11 | Tables | ✅ as `TABLE` module, if the table has a stable XPath | Also emitted as an element. Adds `caption`, `aria-label`, preceding-heading anchors, and `[role=grid/treegrid]` | |
| 12 | Table rows | ❌ | `TABLE_ROW` template: `.//tbody/tr[{row}]`; header row `.//thead/tr` | Row content is redacted, so rows can't be found by their data |
| 13 | Table headers | ❌ (their text is kept, but not emitted) | `TABLE_HEADER`: `.//thead//th[normalize-space()='Notional']`, `[role=columnheader]` | |
| 14 | Table data cells | ❌ | `TABLE_CELL` template keyed by header text, not position: `.//tbody/tr[{row}]/td[count(ancestor::table[1]/thead//th[normalize-space()='Notional']/preceding-sibling::th)+1]` | Cell text is redacted. The test supplies values at run time |
| 15 | Headers (headings `h1`–`h6`, and `<header>`) | ❌ headings only name modules | `HEADING` by text (kept by redaction). `<header>` becomes a `LANDMARK` module | |
| 16 | Labels | ❌ only used by `LABEL_FOR` | `LABEL` by text or `@for` | |
| 17 | Paragraphs | ❌ | `PARAGRAPH`, under rules R1–R3 | No text strategy: `p` text is redacted |
| 18 | Spans | ❌ | `SPAN`, under rules R1–R3 | Same |
| 19 | Divisions | ❌ | `DIV`, under rules R1–R3 | Same |
| 20 | List elements | ❌ | `LIST` (`ul`/`ol`/`[role=list]`) and `LIST_ITEM` template: `.//ul[@id='watchlist']/li[{n}]` | `li` text is redacted unless it's inside a link or button |
| 21 | Inline frames | ❌ logged as `skipped` | `IFRAME` element by `id`/`name`/`title`. Content of same-origin frames is captured as a frame scope (§4.3) | Cross-origin content: the iframe element is emitted, its content stays `skipped` |

**Rules for content elements** (`p`, `span`, `div`, and icons without a label):

- **R1. Anchored only.** An element is emitted only if it has a test id, a stable id, an `aria-label`, a `role`,
  `title`, or kept text (headings, labels). Without this, every `div` on a real page would get a positional XPath:
  thousands of LOW-stability entries that bury the useful ones.
- **R2. Or used.** An unanchored element is still emitted, flagged LOW, if a test looked it up or interacted with it
  (§2). This is where component coverage and usage tracking meet.
- **R3. Never by redacted text.** Paragraph, span, div, cell and list-item text is data, and redaction blanks it
  before it reaches disk. These elements are located by attributes or structure only. Changing that needs decision
  D2.

Unanchored, unused content elements are counted in a per-page summary (`<omitted kind="DIV" count="412"/>`), so the
size of the gap is visible.

**Contract impact:** `ElementType` gains `PASSWORD`, `ICON`, `OPTION`, `TABLE`, `TABLE_ROW`, `TABLE_HEADER`,
`TABLE_CELL`, `HEADING`, `LABEL`, `PARAGRAPH`, `SPAN`, `DIV`, `LIST`, `LIST_ITEM`, `IFRAME`. `Strategy` gains
`CLASS`, `ROW_TEMPLATE`, `STRUCTURE`. XPath values may contain `{row}`, `{n}` or `{text}` placeholders, and
`matches` then means "1 per instance, verified for every instance present".

**Fixtures:** each of the 21 components gets a fixture element, with a test asserting its type, strategy and
verification. That's M2 acceptance criterion 0 (§7).

### 4.1 Custom dropdowns (Must, part of #7 and #8)
- Add `[role=listbox], [role=menu], .cdk-overlay-pane` to the container-change selector. For these the hash value is
  `role + aria-labelledby` text, because their ids are dynamic. Opening a `mat-select` then triggers a
  `CONTAINER_CHANGE` snapshot.
- `[role=option]` and `mat-option` become interactable, with new `ElementType.OPTION`. Each option is emitted
  relative to its overlay module, named from its text.
- **Decision D3 (privacy):** option text is often client data (the fixture keeps "ACC-1 Meridian Capital").
  Options:
  - (a) keep it, as M1 does;
  - (b) keep it only inside overlays/selects that have ≤ N options;
  - (c) redact options and emit a template `.//*[@role='option'][normalize-space()='{text}']`.
  - **Decided: (c).** The test supplies the text anyway.

### 4.2 AG Grid (Should, spike first)
1. **Spike (S):** vendor a pinned `ag-grid-community` UMD build into `fixtures/site/grid.html` (MIT licensed,
   offline). Record what M1 produces today. This replaces my predictions with data.
2. **Adapter (L), expected from AG Grid's DOM, to be confirmed by the spike:**
   - The grid root with a stable `aria-label`/testid becomes a new `ModuleKind.GRID`.
   - Keep `[role=columnheader]` text. Headers are not data.
   - XPaths use AG Grid's own stable attributes:
     - header: `.//div[@role='columnheader'][@col-id='price']`
     - cell template: `.//div[@row-index='{row}']//div[@col-id='price']`
     - row actions: ROW_TEMPLATE scoped by `col-id`
   - `@col-id` comes from the column field name, not row data, so it can be used.
   - Virtualised rows: emit templates only, never per-row XPaths.

### 4.3 Same-origin iframes (Must, #21)
- **Today (by reading the code, not yet tested):** if a test switches into an iframe, the agent's scripts run in the
  frame's context. The frame then gets captured as if it were its own page, with the frame's URL as the route. M2
  should handle this on purpose either way.
- **Plan:** the redaction script reads `iframe.contentDocument` for same-origin frames, with no `switchTo`, so the
  test's frame context is untouched. Each frame is written as its own dom file, linked from the parent snapshot.
  Track `switchToFrame`/`switchToParentFrame` so captures taken inside a frame are attributed to the parent page.
- **Schema:** `<frame xpath="//iframe[@id='chart']">` inside `<page>`, holding modules. Selenium needs
  `switchTo().frame(...)` first, so the frame xpath is emitted separately from the inner absolutes.
- Cross-origin frames stay `skipped`.

---

## 5. Gradle-delegated runs (Should)
IntelliJ's default for Gradle projects is to delegate test runs to Gradle. Those run configs aren't
`JavaRunConfigurationBase`, so the M1 menu item never appears there, even though most Selenium suites are on Gradle.

**Plan:** use the Gradle plugin's run-configuration extension to inject an init script that adds `-javaagent` and
`-Dstrata.out` to `Test` tasks. **Spike first (S):** confirm the extension point on 2024.2 and that the post-run hook
still fires.

---

## 6. Contract changes (needs `CLAUDE.md` v0.2.0)

| Contract | Change |
|---|---|
| §3.1 agent → disk | + `usage.jsonl` (`UsageRecord`); frame dom files |
| §3.2 model | `XpathEntry` + `used: Boolean`, `stability: Stability`; `Strategy` + `CLASS`, `ROW_TEMPLATE`, `STRUCTURE`; `ElementType` + the 15 types in §4.0; `ModuleKind` + `LANDMARK`, `GROUP`, `GRID`; `PageModel` + `frames`; run + `unmatched` |
| §4.1 | intercepts `execute(CommandPayload)`; lookup and interaction commands added |
| §4.3 | dynamic ids excluded from `containerHash`; overlay selectors |
| §4.4 | repeated-label exception; `columnheader` and `mat-icon` text kept; option policy; same-origin frame content |
| §5.2 | replaced by the three element classes and rules R1–R3 (§4.0) |
| §5.6 | label `@for` expression for dynamic ids; bounded `following::` |
| §8 schema | **v2**, see below. v1 consumers must ignore the new attributes. |

```xml
<locator-coverage schema="2" run="20261201-101500" generated="…" used="14" total="26">
  <page name="TradeBlotter" route="/blotter/{id}" visits="3" used="9" total="20">
    <module name="Trades" kind="TABLE" xpath="//table[@id='trades']" used="1" total="1">
      <xpath name="viewButton" type="BUTTON" strategy="ROW_TEMPLATE" stability="MEDIUM" matches="1" used="true"
             value=".//tbody/tr[{row}]//button[normalize-space()='View']"
             absolute="//table[@id='trades']//tbody/tr[{row}]//button[normalize-space()='View']"/>
    </module>
  </page>
  <unmatched route="/blotter/{id}" locator="css selector: .toast-close" reason="state-not-captured"/>
</locator-coverage>
```

---

## 7. M2 acceptance criteria (draft)

0. A fixture page has every one of the 21 required components (§4.0). For each one, the XML has an entry of the
   expected type, verified (templates for every instance), with no dynamic id and no redacted text in any XPath.
   Unanchored, unused content elements appear only in `<omitted>` counts.
1. On `StrataDemo`, every element the tests interact with is either `used="true"` in the XML or listed in
   `<unmatched>` with a reason. Nothing is silently lost.
2. Coverage numbers are exact on a fixture whose used/total counts are known in advance (golden test).
3. Overhead meets the rule chosen in G1 on both the fixture flow and `StrataDemo`.
4. The fixture's table *View* actions come out as ROW_TEMPLATE with stability MEDIUM, not POSITIONAL.
5. No LABEL_FOR xpath uses an unbounded `following::`.
6. Opening a `mat-select`-style overlay produces a `CONTAINER_CHANGE` snapshot and `OPTION` entries.
7. *(Should)* The AG Grid fixture yields a `GRID` module with header and cell templates keyed by `@col-id`.
8. *(Should)* *Run with Locator Coverage* appears on a Gradle-delegated JUnit config and produces the XML.
9. A same-origin iframe's content appears as a frame scope; a cross-origin one appears as an `IFRAME` element with
   its content `skipped`.
10. All M1 criteria 3–8 still hold.
11. `./gradlew check` is green.

---

## 8. Suggested order

1. Close M1 (§0).
2. Spikes, each small and each producing numbers for this doc:
   - quiet-window correctness at 100–500 ms;
   - AG Grid fixture;
   - Gradle-delegation extension point.
3. Contracts: update `CLAUDE.md` to v0.2.0 and bump the schema to v2.
4. **Components and quality:**
   - the 21-component fixture;
   - element classes and rules R1/R3;
   - new types and strategies;
   - templates for tables and lists;
   - dropdowns and options;
   - same-origin iframes;
   - stability rating;
   - the three M1 quality fixes.
5. **Usage and speed:**
   - usage tracking and coverage %;
   - rule R2 (used elements are emitted);
   - overhead fix.
6. **Should items:** AG Grid, Gradle runs.
7. Write `M2-REPORT.md` and stop. M2 is one milestone with a single stop (D5).

## 9. Decisions

### Decided (2026-11-07)
| # | Decision | Choice |
|---|---|---|
| D2 | Content text (p, span, div, td, li) | **Keep redacting.** Located by attributes and structure only (rule R3). |
| D3 | Dropdown option text | **(c)** Redact, and emit a `{text}` template. |
| D4 | Plain div/span/p volume | **Anchored or used** (rules R1 + R2). The rest are reported as `<omitted>` counts. |
| D5 | Split M2? | **No.** One M2 with a single stop and report. |

### Still open
1. **D1 (= G1):** overhead rule. Recommendation: A + B + C (§3.1).
2. **D6: Gradle-delegated runs:** in M2, or later?
3. **D7: approve** the schema v2 / `CLAUDE.md` v0.2.0 contract changes (§6).
4. **M1 gate:** manual right-click check (G2), and a go-ahead to set up git and tag `v0.1.0-m1` (G4).
