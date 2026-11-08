# CLAUDE.md — Strata: Run with Locator Coverage

**Version:** v0.3.0 · **Milestone:** M2 · **Status:** active (M1 baseline committed; `v0.1.0-m1` not tagged, see `M1-REPORT.md`)

> Assumptions:
> - A1. The target framework is on **Selenium 4.x**. Every command funnels through `RemoteWebDriver.execute(CommandPayload)`.
> - A2. `-javaagent` is **allowed** on test JVMs. The listener fallback is a defined seam (§6) and is **not built**.
> - A3. Cucumber-JVM 7 and JUnit 5 run configs. TestNG works for free via the same patcher but is not a test target.
> - A4. Test JVMs run on **Java 17+**. The agent is compiled with `--release 17` so the same jar works on 17, 21 and later. On an older JVM it fails `premain` with `UnsupportedClassVersionError` and the test JVM will not start.
>
> If A1 or A2 is false, STOP and ask. Do not improvise an alternative architecture.

---

## 1. What M2 delivers

Everything M1 delivers (right-click a test → **Run with Locator Coverage** → redacted snapshots → `.strata/locator-coverage.xml` + notification), plus:

- **Component coverage.** A verified entry for every one of the 21 required component types (§5.2): text boxes, text areas, password fields, buttons, hyperlinks, icons, dropdown menus, dropdown options, checkboxes, radio buttons, tables, table rows, table headers, table data cells, headings, labels, paragraphs, spans, divisions, list elements and inline frames.
- **Usage.** Every element the test looked up or interacted with is marked `used="true"`. Coverage counts (`used`/`total`) appear per module, page, frame and run. Usage that can't be matched is listed as `<unmatched>` with a reason.
- **Stability.** Every xpath is rated `HIGH`, `MEDIUM` or `LOW` (§5.8).
- **Quality fixes from M1.** Table actions are row templates, labels with dynamic ids use `@for`, and there are more module kinds.
- **Same-origin iframes.** Frame content is captured with its page and modelled as `<frame>` scopes.
- **Overhead.** A cheap probe skips the settle wait when nothing changed, the settle window is configurable, and the overhead budget is per command (§10).
- **AG Grid.** An adapter models AG Grid by column (§5.12): a GRID module, `col-id` headers, `row-index` row templates, and per-column cell and control templates.
- **Cards.** Repeated sibling containers such as product tiles get one `{n}` template per control (§5.13) instead of a positional entry per copy.

### Decisions (2026-11-07)
- D2: text of paragraphs, spans, divs, table cells and list items **stays redacted**. They are located by attributes and structure only.
- D3: dropdown option text is **redacted**. Options are emitted as a `{text}` template.
- D4: plain `div`/`span`/`p` are emitted only when **anchored or used** (rules R1, R2). The rest are reported as `<omitted>` counts.
- D5: one milestone, with one stop and one report.

### Out of scope (do NOT build; reject if asked mid-milestone)
- Tool window, gutter badges, run diff, Insert `@FindBy` (M3)
- `WebDriverListener` fallback, Playwright, Selenium 3
- Shadow DOM and cross-origin iframe content (logged as `skipped`), nested frames (`iframe#x:nested`)
- AI/LLM naming or any other AI call
- The *Should* item not built yet: Gradle-delegated runs (see `M2-SCOPE.md`)
- Other grid libraries, and generic ARIA grids without `tbody` (only AG Grid has an adapter)

---

## 2. Repository layout

```
strata/
├── CLAUDE.md, M1-REPORT.md, M2-SCOPE.md, M2-REPORT.md
├── settings.gradle.kts
├── strata-core/        # pure Kotlin, `jvmTarget = 17` (toolchain 21), apiVersion 1.9, Jsoup. No IntelliJ, no Selenium.
├── strata-agent/       # **Java 17 bytecode** (`--release 17`), ByteBuddy (shaded+relocated). No Kotlin.
├── strata-plugin/      # IntelliJ Platform plugin, Kotlin, depends on strata-core
└── fixtures/
    ├── site/           # static HTML test site (§9)
    └── snapshots/      # golden run (dom/, manifest.jsonl, usage.jsonl) + expected-locator-coverage.xml
```

**Why the agent is Java:** a Kotlin agent drags `kotlin-stdlib` onto the test classpath and collides with the framework's own Kotlin version. The agent jar has **zero unrelocated dependencies**.

---

## 3. Pinned type contracts

### 3.1 Agent → disk (the only interface between agent and IDE)

Output dir is the `-Dstrata.out=<abs path>` system property, e.g. `<project>/.strata/runs/<runId>/`.

```
<out>/
├── manifest.jsonl          # one SnapshotRecord per line, append-only
├── usage.jsonl             # one UsageRecord per line, append-only
├── agent.log
└── dom/<seq>-<routeKey>.dom.html, dom/<seq>-<routeKey>.frame<index>.dom.html
```

```kotlin
// strata-core: dev.strata.core.model
data class SnapshotRecord(
    val seq: Int,                 // global, monotonic per run
    val sessionId: String,        // WebDriver session id (parallel-safe key)
    val url: String,              // raw URL; query string stripped by the agent
    val routeKey: String,         // normalized, see §5.1
    val title: String,
    val trigger: Trigger,
    val containerHash: String,    // sha1 of sorted container ids + overlay markers, see §4.3
    val domFile: String,          // relative to <out>
    val capturedAt: String,       // ISO-8601 UTC
    val settleMs: Int,            // time spent waiting for quiet DOM
    val skipped: List<String> = emptyList(),   // e.g. ["iframe#ext", "iframe#chart:blank", "shadow:ag-grid"]
    val frames: List<FrameRef> = emptyList()   // same-origin frames captured with this snapshot
)
data class FrameRef(val index: Int, val url: String, val domFile: String)   // index among `iframe, frame` in document order
enum class Trigger { GET, CLICK, BACK, FORWARD, REFRESH, CONTAINER_CHANGE }

data class UsageRecord(
    val sessionId: String,
    val snapshotSeq: Int,         // latest snapshot of this session when the element was first used; 0 = none
    val routeKey: String,
    val command: String,          // the first lookup or interaction, e.g. "findElement", "clickElement"
    val locatorUsing: String?,    // from the lookup, e.g. "css selector"; null if first seen in an interaction
    val locatorValue: String?,
    val framePath: List<Int>,     // frame indexes from the top document; [] = top document
    val elementPath: List<Int>,   // element-child indexes from <html>, skipping script/style/noscript; SVG descendants map to their <svg>
    val tag: String,
    val usedAt: String
)
```

The agent writes JSON by hand (no JSON lib). Field names and order are exactly as above.

### 3.2 Core model

```kotlin
data class LocatorCoverageRun(val runId: String, val generatedAt: String, val pages: List<PageModel>,
                              val unmatched: List<UnmatchedUsage> = emptyList())
data class PageModel(val name: String, val routeKey: String, val visits: Int, val modules: List<ModuleModel>,
                     val frames: List<FrameModel> = emptyList(), val omitted: List<OmittedCount> = emptyList())
data class FrameModel(val name: String, val xpath: String /* the <iframe> in its page */, val routeKey: String,
                      val modules: List<ModuleModel>)
data class ModuleModel(val name: String, val kind: ModuleKind, val xpath: String, val elements: List<XpathEntry>)
enum class ModuleKind { FORM, NAV, TABLE, GRID, DIALOG, SECTION, LANDMARK, GROUP, MENU, ANCHORED, PAGE_ROOT }

data class XpathEntry(
    val name: String,
    val type: ElementType,
    val value: String,            // relative to module: starts with "./" or "(.//"
    val absolute: String,         // module.xpath + value-tail, verified
    val strategy: Strategy,
    val matches: Int,             // always 1; for a template, 1 per instance
    val stability: Stability,     // §5.8
    val used: Boolean = false,
    val instances: Int = 1        // instances a template verified against
)   // a ROW_TEMPLATE or TEXT_TEMPLATE entry is a template: value and absolute hold one %s (§5.9)
enum class ElementType {
    BUTTON, LINK, TEXT_INPUT, PASSWORD, DROPDOWN, OPTION, CHECKBOX, RADIO, TEXTAREA, TAB, ICON,
    TABLE, TABLE_ROW, TABLE_HEADER, TABLE_CELL, HEADING, LABEL, PARAGRAPH, SPAN, DIV, LIST, LIST_ITEM, IFRAME,
    OTHER_INTERACTIVE
}
enum class Strategy { TESTID, ID, NAME, ARIA_LABEL, LABEL_FOR, TEXT, PLACEHOLDER, ATTRIBUTE, CLASS,
                      ROW_TEMPLATE, TEXT_TEMPLATE, STRUCTURE, POSITIONAL }
enum class Stability { HIGH, MEDIUM, LOW }
data class OmittedCount(val type: ElementType, val count: Int)
data class UnmatchedUsage(val routeKey: String, val locator: String, val command: String, val reason: UnmatchedReason)
enum class UnmatchedReason { NO_SNAPSHOT, STATE_NOT_CAPTURED, NOT_ADDRESSABLE }
```

### 3.3 Core services

```kotlin
interface RouteNormalizer   { fun normalize(url: String): String }
interface ModuleDetector    { fun detect(doc: Document): List<Pair<Element, ModuleKind>> }
interface XpathGenerator    { fun generate(el: Element, module: Element): XpathEntry? }   // null = could not make a unique XPath
interface XpathVerifier     { fun count(doc: Document, xpath: String): Int }              // Jsoup W3C conversion, cached per document
interface CoverageXmlWriter { fun write(run: LocatorCoverageRun, out: Path) }

object StrataGenerator {
    fun generate(runDir: Path): LocatorCoverageRun   // reads manifest, usage and dom/; merges by routeKey
}
```

`SnapshotAnalyzer` builds one snapshot's model, and `TemplateBuilder` builds and verifies templates.

---

## 4. Agent (strata-agent)

### 4.1 Interception
- ByteBuddy `AgentBuilder` with `Advice` on `org.openqa.selenium.remote.RemoteWebDriver#execute(CommandPayload)`, on **method exit**, and only when no exception was thrown. Every command passes through this overload: `execute(String, Map)` only wraps its arguments into a payload, and `get`/element commands call the payload overload directly. The advice passes the command name, its parameters and `Response#getValue()`.
- Commands:
  - navigation `get`, `clickElement`, `goBack`, `goForward`, `refresh` (capture, §4.2–4.4);
  - lookups `findElement(s)`, `findChildElement(s)` (usage, §4.5);
  - element interactions `clickElement`, `sendKeysToElement`, `clearElement`, `submitElement`, `getElementText`, `getElementAttribute`, `getElementDomAttribute`, `getElementDomProperty`, `isElementSelected`, `isElementEnabled`, `getElementTagName`, `getElementRect`, `getElementValueOfCssProperty`, `getElementAriaRole`, `getElementAccessibleName` (usage).
  - All other commands are ignored.
- **Re-entrancy guard:** a `ThreadLocal<Boolean>`. The agent's own script calls go through `execute` too and must be ignored.
- Every agent failure is caught and logged to `<out>/agent.log`. **The agent must never fail a test, or slow it beyond the settle cap.** If the output dir can't be created, capture is disabled entirely.
- All scripts work on `window.top.document` when it's reachable, so a test that has switched into a same-origin frame still captures the whole page. From inside a cross-origin frame, nothing is captured.

### 4.2 Settle wait
1. **Cheap probe** (sync `executeScript`, no waiting). It installs a `MutationObserver` on a new document. If the (`routeKey`, `containerHash`) state was already captured, the document is `complete`, it's quiet (or it was just loaded) and it isn't **leaving**, the wait is skipped.
   - Leaving means a form `submit`, a same-window link click, `beforeunload` or `pagehide` happened in the last 2 s. This catches clicks that return before their navigation commits.
2. Otherwise `executeAsyncScript` waits until the observer has been quiet for **quietMs** and the document isn't leaving, with a hard cap of **capMs**.
   - Defaults: quietMs **500**, capMs **5000**, set with `-Dstrata.quietMs` and `-Dstrata.capMs`.
   - If the document unloads mid-wait, the wait runs once more on the new document.
   - The measured `settleMs` is recorded.

### 4.3 Page change detection
A snapshot is taken when the (`routeKey`, `containerHash`) pair hasn't been captured yet in this session.
- `containerHash` is the sha1 of the sorted `id`/`data-testid` values of `form, nav, table, dialog, [role=dialog], section, main > *`, **excluding §5.5 dynamic ids**, plus one overlay marker `overlay:<tag>:<role>:<aria-label>` per `[role=listbox], [role=menu], [role=dialog], dialog[open], .cdk-overlay-pane, .dropdown-menu.show`.
- A CLICK that changes the hash on the same document is recorded as `CONTAINER_CHANGE`.

### 4.4 In-browser redaction (data never reaches disk unredacted)
A JS function walks a **clone** of each document (top, and each same-origin frame).
- Remove `<script>`, `<style>` and `<noscript>`, comments, and the children of `<svg>` (keep the `<svg>` tag itself).
- For each text node, the first rule that applies decides:
  1. inside `option, [role=option], mat-option` → `···` (D3);
  2. inside `[role=columnheader]` → kept;
  3. inside `tbody, [role=grid], [role=row], [role=gridcell], td, output` → `···`, **except** the text of an action (`button, a, [role=button], [role=link], [role=menuitem]`) whose text repeats across ≥ 2 rows of the same `tbody`/grid (**repeated-label exception**: repetition means it's UI, not data);
  4. inside `button, a, label, th, h1–h6, legend, caption, [role=tab], [role=menuitem], mat-icon, .material-icons, .material-symbols-*` → kept;
  5. anything else → `···`.
- Kept text is truncated to 60 chars.
- Remove the `value` attribute everywhere, the `style` attribute, and every `on*` attribute. Clear `textarea` text.
- Remove `row-id` and `row-business-key` (AG Grid `getRowId` keys are the application's own data), and `title` inside `td, [role=gridcell]`.
- Frames:
  - same-origin frames are returned as separate documents;
  - `about:blank` frames are skipped as `iframe#x:blank`;
  - cross-origin frames are skipped as `iframe#x`;
  - frames nested inside a frame are skipped as `iframe#x:nested`.
- Open shadow roots are logged as `shadow:<tag>`.

### 4.5 Usage tracking
- **Lookup:** the returned element ids (`RemoteWebElement#getId` or W3C references), up to 200 per lookup, are reported with the lookup's `using`/`value`.
- **Interaction:** the element id in `parameters["id"]` is reported if it hasn't been seen yet. This runs **before** any capture triggered by the same command, because a click may navigate away.
- **One `PATHS` script call per command** that brings new element ids. The elements are passed as W3C references. Each element id is recorded once per session.
- `snapshotSeq` is the session's latest snapshot. Core resolves `elementPath` in that snapshot.

### 4.6 Packaging
- Shadow jar: relocate `net.bytebuddy` to `dev.strata.shaded.bytebuddy`.
- `MANIFEST.MF`: `Premain-Class`, `Can-Retransform-Classes: true`.
- Selenium is `compileOnly`, so the agent uses whatever Selenium the test brings.

---

## 5. Core generation rules (strata-core)

### 5.1 Route normalization
Strip the scheme, host, query, and fragment. Then replace any path segment that matches one of these with `{id}`:
`^\d+$`, `^[0-9a-f]{8}-[0-9a-f]{4}-`, `^[A-Z0-9]{8,}$`, `^[0-9a-f]{16,}$`

The routeKey for `/blotter/48213?x=1` is `/blotter/{id}`, and the file-safe form is `blotter_id`.

### 5.2 Element classes (what gets an entry, and as what type)
- **Interactive:** always emitted, with POSITIONAL as a last resort.
  - Matched by: `button, a[href], input:not([type=hidden]), select, textarea, [role=button|link|tab|checkbox|radio|combobox|menuitem|option], [onclick], [tabindex]:not([tabindex="-1"]), mat-select, ng-select, mat-option, option`, plus `<a>` without `href` that has text or `aria-label`.
  - Type mapping:
    - `input[type=password]` → `PASSWORD`;
    - `aria-haspopup` → `DROPDOWN`;
    - `option`/`[role=option]`/`mat-option`/`[role=menuitem]` → `OPTION`.
- **Structural:**
  - `table`/`[role=grid|treegrid]` → `TABLE`;
  - `tr`, `th`/`[role=columnheader]`, `td` → `TABLE_ROW`, `TABLE_HEADER`, `TABLE_CELL`;
  - `ul`/`ol`/`[role=list]` → `LIST`, `li` → `LIST_ITEM`;
  - `iframe`/`frame` → `IFRAME`.
  - Rows, headers, cells, list items, options and everything inside `tbody` are emitted through templates (§5.9).
- **Content:**
  - `h1`–`h6` → `HEADING` and `label` → `LABEL`: always emitted;
  - `p`, `span`, `div` → `PARAGRAPH`, `SPAN`, `DIV`; icons (`svg`, `img`, `mat-icon`, `i`, `span` with an icon class) → `ICON`. These follow the rules below:
    - **R1:** emitted only if anchored. Anchors: a test id, a stable id, `aria-label`, a non-generic `role`, `title`. Icons can also be anchored by `alt`/`data-icon`/an icon class/ligature text, or through the button or link they sit in.
    - **R2:** an unanchored element is still emitted (POSITIONAL, LOW) if the test used it.
    - **R3:** never located by redacted text.
    - Unanchored, unused content elements are counted in `<omitted>`.
- Elements inside `[aria-hidden=true]` or `[hidden]` are skipped. A `div`/`span`/`p` that is itself a module is not emitted again as an element.

### 5.3 Modules
- **Container:** the nearest ancestor that is one of:
  - an AG Grid host (§5.12) → GRID;
  - `form` → FORM; `nav`/`[role=navigation]` → NAV; `table` → TABLE; `dialog`/`[role=dialog|alertdialog]` → DIALOG; `section` → SECTION;
  - `[role=menu|listbox|menubar]`/`.dropdown-menu`/`.cdk-overlay-pane` → MENU;
  - `header`/`main`/`aside`/`footer` → LANDMARK;
  - `fieldset`/`[role=tablist|toolbar]`/a labelled `[role=group|region]` → GROUP;
  - an element with a stable `id`/`data-testid` **that contains something interactive** → ANCHORED.
- Elements with no container go to the `PAGE_ROOT` module (`//body`).
- **Module XPath priority:** `@data-testid` → stable `@id` → `@aria-label` → tag + its **own** heading text (a heading inside a nested module doesn't count) → tag + `@role` → for a form: `@name`, a route-like `@action` (no query, no id segment, no dynamic id), then each stable class → overlay class → the bare tag for `header/main/aside/footer/nav` → for a GRID, `//div[contains(concat(' ', normalize-space(@class), ' '), ' ag-root-wrapper ')]/ancestor::div[not(contains(@class, 'ag-'))][1]`. The XPath must verify to exactly 1 match, otherwise the next option is tried. If every option fails, the module merges into its parent.
- Do not nest modules in the XML. The XML is page → module → xpath, flat. Frames hold their own modules.

### 5.4 Naming (deterministic, no AI)
- **Page name:** PascalCase of the `<title>` if one exists and is unique within the run, otherwise of the routeKey (`BlotterId`).
- **Module name:** PascalCase of the source of the winning XPath (`data-testid`, `id`, `aria-label`, heading text, role), plus a `Form`/`Dialog`/`Menu` suffix where the kind adds information.
- **Element name:** camelCase of the label source plus a type suffix (`exportButton`, `accountDropdown`, `tradeDateInput`, `priceCell`, `downloadIcon`, `helpFrame`). An icon inside a button or link takes that control's name. On collisions within a module, append `2`, `3`, and so on.

### 5.5 Stable vs dynamic ids
An `id` is **dynamic** if it matches any of:
`\d{4,}`, `[0-9a-f]{8}-`, `^ember\d+`, `^mat-[a-z-]+-\d+`, `^react-select-\d+`, `^cdk-`, `^:r[0-9a-z]+:$`, `^ext-gen\d+`, `^ui-id-\d+`, `^ag-\d+-`

Dynamic ids are never written into any XPath; the same check applies to test ids, `name` and icon classes. Text containing four or more digits in a row is treated as data and is never used as a TEXT locator.

### 5.6 Strategy order (first one with exactly 1 match within the module wins)
1. `TESTID`: `.//*[@data-testid='…']` (also `data-test`, `data-qa`, `data-cy`)
2. `ID`: stable `@id`
3. `NAME`: `@name`
4. `ARIA_LABEL`: `@aria-label`
5. `LABEL_FOR` (form controls), tried in this order:
   - wrapped: `.//label[normalize-space()='X']//input`;
   - for a label pointing at the element's id: `.//input[@id=//label[normalize-space()='X']/@for]`. This works for dynamic ids because the id never appears in the XPath;
   - last resort: `label → following::<tag>[1]` (LOW).
6. `ATTRIBUTE`, by type:
   - icons: `title`/`alt`/`data-icon`/`svgicon`;
   - labels: `@for`;
   - iframes: `title`;
   - `p`/`span`/`div`: `role`/`title`;
   - lists: `role`;
   - buttons and links: `title`.
7. `STRUCTURE`: table `caption`.
8. `CLASS`: icons only. Uses the most specific icon class: `fa-download` from `fa fa-download fa-lg`.
9. `TEXT`: `normalize-space()='…'` for elements whose text is kept by redaction.
10. `PLACEHOLDER`: `@placeholder`
11. `STRUCTURE`: an icon through its button or link: `<control value>//<tag>`.
12. `POSITIONAL`: tag + 1-based index within the module. Used for interactive and structural elements, and for content only under R2.

- Use XPath 1.0 only, and use `'` quoting. For text that contains `'`, use `concat()`.
- `<svg>` is addressed as `*[local-name()='svg']`.

### 5.7 Verification (hard rule)
- Every emitted `value` and `absolute` is checked against **the snapshot it came from**: the relative value with the module as context, the absolute from the document root.
- `matches != 1` means the next strategy is tried. If nothing works, the element is **dropped** and a line goes to `generator.log`.
- Never emit an unverified XPath. Templates are verified per instance (§5.9).

### 5.8 Stability
- `HIGH`: TESTID, ID, NAME
- `LOW`: TEXT, POSITIONAL, anything with `following::`, and templates or structure with an index step (e.g. `td[4]`)
- `MEDIUM`: everything else

### 5.9 Templates
**Output form:** the rules below write the dynamic part as `{row}` (1-based row), `{n}` (1-based item or card) and `{text}` (option text) to say what it stands for. Emitted entries carry `%s` in its place, and any literal `%` is escaped as `%%`, e.g. `./tbody/tr[%s]` or `.//option[normalize-space()='%s']`. Each template has exactly one `%s`.

Templates are verified for every instance in the snapshot. For each `i`, the substituted XPath must select exactly instance `i`, both within the module and from the root. `instances` records how many instances were verified.

- **Table**, relative to the table module (`.`) or the table's own entry:
  - `thead/tr` (header row);
  - `thead/tr/th[normalize-space()='H']`, or `th[k]` when the header has no text;
  - `tbody/tr[{row}]`;
  - cell: `tbody/tr[{row}]/td[count(ancestor::table[1]/thead/tr/th[normalize-space()='H']/preceding-sibling::th)+1]`, or `td[k]` when the header has no text;
  - row actions: `tbody/tr[{row}]//button[normalize-space()='Close']`. Fallbacks: `@aria-label`, `@title`, `td[<column>]/descendant::<tag>[j]`.
- **List:** `<list value>/li[{n}]`
- **Options:**
  - native: `<select value>//option[normalize-space()='{text}']`;
  - custom: `.//mat-option[...]` or `.//*[@role='option'][normalize-space()='{text}']`, inside the MENU module.
  - The structural part (with the `{text}` predicate removed) must select exactly all options.

### 5.10 Usage mapping
For each `UsageRecord`, the path is resolved in snapshot `snapshotSeq`, or in its frame document. The tag must match.
- **Entry found:** its entry, or the template covering it, gets `used=true`.
- **Element found, no entry yet:** R2 applies (a POSITIONAL entry).
- **Unmatched** otherwise:
  - `NO_SNAPSHOT`: no snapshot existed yet;
  - `STATE_NOT_CAPTURED`: the path doesn't resolve, or the frame wasn't captured;
  - `NOT_ADDRESSABLE`: the element is found but can't be given an entry, e.g. `<body>`.

### 5.11 Merging visits
- Snapshots that share a routeKey merge into one `PageModel`. `visits` counts the snapshots.
- Modules merge by xpath and entries merge by `absolute`; `used` is ORed, `instances` takes the max.
- Frames merge by frame xpath.
- `omitted` takes the maximum per type over the visits.
- A module seen only in some snapshots, such as a dialog, is still included.

### 5.12 AG Grid adapter
Verified against AG Grid Community 36.2.0 (vendored in `fixtures/site/vendor/`, MIT).

- **Host:** the nearest ancestor of `.ag-root-wrapper` without an `ag-` class, i.e. the element the app passed to `createGrid`. It becomes a **GRID** module and is also emitted as a `TABLE` entry in its parent module.
- **Keys:**
  - `col-id` is the column's field or colId, so it's stable and holds no data;
  - `row-index` is the display position;
  - `row-id` is never used, because it's data and redaction strips it.
- **Entries**, relative to the GRID module:
  - header: `.//div[@role='columnheader'][@col-id='notional']` (ATTRIBUTE), plus header controls (e.g. select-all) inside it;
  - row: `.//div[@role='row'][@row-index={row}-1]` (ROW_TEMPLATE). `{row}` stays 1-based. It's omitted when pinned columns split a row into several row elements;
  - cell: `<row>//div[@role='gridcell'][@col-id='X']`;
  - cell controls: `<cell>//button[normalize-space()='Cancel']`, `<cell>//input[@type='checkbox']`, or `<cell>/descendant::<tag>[j]`.
- **Names:** from the header text (kept by redaction), else from `col-id`. Built-in columns are named `selection` (`ag-Grid-SelectionColumn`) and `group` (`ag-Grid-AutoColumn`).
- **Everything else inside the grid** (viewports, tab guards, measurement nodes, rows' own markup) is library chrome. It's neither emitted nor counted in `<omitted>`. App controls outside rows and headers, such as pagination buttons, follow the generic rules.
- **Virtualised rows:** templates cover them; only rendered rows are verified, and `instances` counts those.

### 5.13 Cards
- **Card group:** two or more siblings with the same tag and the same stable classes (state classes such as `active` or `show` ignored) that each contain something interactive and share at least one **label in every card**: text, `aria-label`, `title`, `alt`, `name`, `placeholder` or a test id. Same-signature groups under same-signature parents in one module are combined (a carousel's slides). Only the innermost groups count. Nothing inside tables, selects or AG Grid.
- **Labels that differ between cards** (names, prices) are data and are never used, like the repeated-label exception (§4.4).
- **Card locator:** `<parent>/<tag>[class][{n}]`, the parent by test id, stable id or class, or `(<cards>)[{n}]` for several parents. It's emitted as a `<class>Card` entry.
- **Controls and content in a card**, each step tried in order, the first matching exactly one element in its card wins: `//tag[shared label]` → `//<ancestor class>//tag[shared label]` → `//<ancestor class>//tag` → `//tag` → `/descendant::tag[j]` (interactive or used only). Each step becomes one template, `<card locator><step>`, verified per card. Unanchored, unused content is not templated (R1).

---

## 6. Listener fallback seam (defined, NOT built)

```java
// strata-agent: dev.strata.agent.CaptureSink — the agent calls only this
public interface CaptureSink {
    void onCommandCompleted(Object driver, String command, Map<String, ?> parameters, Object result);
}
```

All capture and usage logic lives behind `CaptureSink`, so a future `WebDriverListener` can call the same sink. Do not build the listener.

---

## 7. Plugin (strata-plugin)

- Target: IntelliJ IDEA **2024.2+** (`sinceBuild = 242`), using IntelliJ Platform Gradle Plugin 2.x, Kotlin, and JVM 21. Kotlin `apiVersion` is 1.9, matching the IDE's bundled stdlib; the plugin bundles no Kotlin of its own.
- Depends on `com.intellij.java`. Cucumber support is optional (`<depends optional="true">`), because Cucumber-Java configs extend `ApplicationConfiguration` and the patcher covers them regardless.

```xml
<extensions defaultExtensionNs="com.intellij">
  <executor implementation="dev.strata.plugin.LocatorCoverageExecutor"/>
  <programRunner implementation="dev.strata.plugin.LocatorCoverageRunner"/>
  <java.programPatcher implementation="dev.strata.plugin.AgentPatcher"/>
  <notificationGroup id="Strata" displayType="BALLOON"/>
</extensions>
<projectListeners>
  <listener class="dev.strata.plugin.StrataExecutionListener" topic="com.intellij.execution.ExecutionListener"/>
</projectListeners>
```

| Class | Responsibility |
|---|---|
| `LocatorCoverageExecutor` | `id = "StrataLocatorCoverage"`, text "Run with Locator Coverage", own icon |
| `LocatorCoverageRunner` | extends `DefaultJavaProgramRunner`; `canRun` = executor matches && profile is a `JavaRunConfigurationBase` |
| `AgentPatcher` | Only for this executor. **First checks the run config's JRE is ≥ 17**; if not, aborts the launch (`CantRunException`) with an error balloon "Locator Coverage requires Java 17+ (configured: N)" instead of attaching the agent. Otherwise adds `-javaagent:<bundled jar>` and `-Dstrata.out=<project>/.strata/runs/<runId>`, and stores the run dir on the run config |
| `StrataExecutionListener` | `processStarting` for this executor attaches a `PostRunHandler` before the process can terminate |
| `PostRunHandler` | `ProcessListener.processTerminated` → `Task.Backgroundable` → `StrataGenerator` → write `<runDir>/locator-coverage.xml` and copy it to `.strata/locator-coverage.xml` → VFS refresh → notification "N pages, M elements, U used (P%) · Open XML" |

- `runId` = `yyyyMMdd-HHmmss`, suffixed `-2`, `-3`… on a collision.
- The agent jar is bundled in the plugin's `lib/`, and its path is resolved via `PluginManagerCore`.
- The generator never runs on the EDT. File writes go through `WriteAction` + VFS refresh.

---

## 8. XML output (schema v3, exact)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<locator-coverage schema="3" run="20261107-120000" generated="2026-11-07T12:01:00Z" used="24" total="119">
  <page name="Components" route="/components" visits="3" used="19" total="63">
    <module name="ProfileForm" kind="FORM" xpath="//form[@id='profile-form']" used="6" total="15">
      <xpath name="countryOption" type="OPTION" strategy="TEXT_TEMPLATE" stability="MEDIUM" matches="1" instances="3" used="true"
             value=".//select[@id='country']//option[normalize-space()='%s']"
             absolute="//form[@id='profile-form']//select[@id='country']//option[normalize-space()='%s']"/>
    </module>
    <frame name="Help" xpath="//main//iframe[@id='help-frame']" route="/frame.html" used="1" total="4">
      <module …>…</module>
    </frame>
    <omitted type="DIV" count="4"/>
  </page>
  <unmatched route="/login" locator="css selector: .toast" command="findElement" reason="STATE_NOT_CAPTURED"/>
</locator-coverage>
```

- `instances` is written only for templates. `used`/`total` count entries (a template counts once) at run, page, frame and module level.
- Pages are sorted by `route`; modules, frames and xpaths follow document order of the first visit; `<omitted>` is sorted by type; `<unmatched>` is sorted by route, locator, command and reason.
- Output is deterministic: the same inputs always produce byte-identical XML apart from the `generated` attribute. Line endings are `\n`.

---

## 9. Fixtures and tests

### `fixtures/site/` (static HTML, served by the integration test; extension-less URLs map to `.html`)
| Page | Purpose |
|---|---|
| `login.html` | form, label-for inputs, text button |
| `blotter/48213.html` | data-testid grid, dynamic ids (`mat-input-7`, `ps-7731`), tbody with fake client text and repeated *View* actions, `aria-label` buttons, an `about:blank` iframe |
| `blotter/99001.html` | same route as 48213; must merge; one row only, so its *View* stays redacted |
| `blotter.js` | opens a `role=dialog` on *Export* (CONTAINER_CHANGE) |
| `components.html` + `components.js` | **one of each of the 21 required components**: a `cdk-overlay-pane` listbox and a `role=menu` that open on click, a same-origin (`frame.html`) and a cross-origin iframe, unanchored content for `<omitted>` |
| `frame.html` | the same-origin frame's content |
| `grid.html` + `vendor/ag-grid-community-36.2.0.min.js` | an AG Grid with a selection column, sortable headers, a numeric column and a *Cancel* button per row; `getRowId` returns order numbers, to prove they don't reach disk |

### Required tests
- **core (unit):**
  - `RouteNormalizer` and dynamic-id tables;
  - every strategy has positive and fall-through cases;
  - the verifier rejects multi-match XPaths;
  - module kinds and priorities;
  - component rules (templates, icons, R1/R2, data-like text);
  - AG Grid: column-keyed templates, pinned columns, unanchored host, two grids, generated ids;
  - golden-file XML; merging; manifest and usage parsing.
- **core (golden run):**
  - all 21 components are covered;
  - coverage numbers are exact;
  - every xpath re-verifies (templates per instance);
  - no dynamic id appears in any XPath.
- **core (redaction contract):** in `fixtures/snapshots/**/*.dom.html`:
  - no `tbody` text except `···` and repeated action labels;
  - no `value=` attribute;
  - no option, `p`, plain `li` or `td` text.
- **agent (unit):**
  - route keys;
  - manifest and usage JSON field order;
  - container hash (dynamic ids and overlays);
  - element ids from lookup results;
  - dispatcher failure isolation and re-entrancy.
- **agent (integration):** on a **JDK 17 + JDK 21 matrix**, headless Chrome + a local HTTP server.
  - Fixture flow: 4 manifest lines with the correct triggers, no duplicates, `settleMs <= 5000`, usage marked.
  - Components flow: every required type, the frame model, frame usage, omitted counts.
  - Grid flow: GRID module, `col-id` templates, usage on header/cell/button, no `row-id` or cell data on disk, no LOW entries, no omitted chrome.
  - The overhead budget (§10) holds.
  - The test still passes when the output dir is unwritable.
- **plugin:** `BasePlatformTestCase`.
  - The patcher adds args only for the Strata executor and refuses a JRE below 17.
  - `canRun` is false for non-Java configs.
  - Notification summary.

---

## 10. Acceptance criteria (M2 is done when ALL pass)

0. On the components fixture, every one of the 21 required components has a verified entry of the expected type, and every XPath is free of dynamic ids and redacted text. Unanchored, unused content appears only in `<omitted>` counts.
1. Every element the tests use is `used="true"` or listed in `<unmatched>` with a reason.
2. The coverage numbers are exact on the golden run.
3. **Overhead budget**, medians from `agent.log`:
   - a captured page ≤ quietMs + 200 ms;
   - a command that changes nothing ≤ 50 ms;
   - recording newly used elements ≤ 50 ms per command.
4. Table actions are ROW_TEMPLATE (not POSITIONAL). Cells keyed by header text are MEDIUM.
5. No LABEL_FOR xpath on the fixtures uses `following::`.
6. Opening the overlay listbox and the menu produces `CONTAINER_CHANGE` snapshots with `OPTION` entries.
7. A same-origin iframe's content appears as a `<frame>` with usage; a cross-origin one appears as an `IFRAME` entry with its content `skipped`.
8. M1 criteria 3–7 still hold.
9. `./gradlew check` is green in all three modules.
10. The AG Grid fixture yields a GRID module with header, row, cell and cell-control templates keyed by `col-id`, all MEDIUM or better, with usage marked and no row keys or cell data on disk.

## 11. Hard stop

When §10 passes: **stop**. Write `M2-REPORT.md` with the overhead numbers (including the quietMs comparison), what was not built and why, and the biggest locator-quality issues still open. Do not start M3 (tool window, gutter badges, run diff, Insert `@FindBy`).

---

## 12. Conventions
- Kotlin: explicit return types on public API, no `!!` outside tests, and `Result`/null over exceptions in core.
- Java (agent): `--release 17`, built with a JDK 21 toolchain.
  - Allowed: records, `instanceof` pattern matching, switch expressions, text blocks (handy for the scripts).
  - **Not allowed:** pattern matching in `switch`, record patterns, sequenced collections, or any other 21-only API.
  - CI runs the agent integration test on **both JDK 17 and 21**.
  - **No virtual threads in the agent**: capture runs on the test's own thread.
  - No lambdas capturing Selenium types into static state; everything `final`.
- Java copies of core rules (`RouteKeys`, `DynamicIds`) must stay in sync with core.
- Commits: one concern per commit, prefixed `core:`, `agent:`, `plugin:`, `fixtures:`, or `docs:`.
- Changing any contract in §3 or the schema in §8 requires bumping this file's version and a changelog line. Ask first.

## Changelog
- **v0.3.0**: Templates are emitted with `%s` for the dynamic part instead of `{row}`, `{n}` and `{text}`, so they fill with `String.format(xpath, value)`; a literal `%` is written `%%`. `XpathEntry.isTemplate` is decided by strategy, and `absoluteFor(arg)` fills one in. XML schema 3. The named placeholders in §5.9–5.13 describe what `%s` stands for.
- **v0.2.2**: Cards (§5.13): `{n}` templates for controls in repeated sibling containers. Forms without an id, test id, label or heading get a module XPath from `@name`, `@action` or a class (§5.3).
- **v0.2.1**: AG Grid adapter (§5.12): `ModuleKind.GRID`, the grid-host module fallback XPath, `^ag-\d+-` added to the dynamic ids, and `row-id`/`row-business-key`/cell `title` stripped by redaction.
- **v0.2.0**: M2, approved 2026-11-07.
  - Model: 21 component types, element classes R1–R3, templates (`{row}`, `{n}`, `{text}`), stability, `used` + coverage counts, `<unmatched>`, `<omitted>`.
  - Frames: same-origin frames (`FrameRef`, `FrameModel`).
  - Agent: `usage.jsonl`; interception on `execute(CommandPayload)` with parameters and results (`CaptureSink` gains both); cheap probe + leaving detection + configurable quietMs/capMs; dynamic ids out of `containerHash`, overlay markers in; repeated-label exception, options redacted, column headers and icon ligatures kept.
  - Core: new module kinds (LANDMARK, GROUP, MENU), own-heading rule, `@for` label XPath for dynamic ids, data-like text rule.
  - XML: schema v2.
- **v0.1.2**: Java 17+ support. The agent and core emit Java 17 bytecode; the plugin stays on JVM 21 (required by IntelliJ 2024.2). The patcher threshold is now 17, and CI runs a 17/21 matrix.
- **v0.1.1**: all modules target Java 21; added assumption A4; `AgentPatcher` refuses JREs below 21.
- **v0.1.0**: initial M1 scope.
