# CLAUDE.md — Strata: Run with Locator Coverage

**Version:** v0.1.2 · **Milestone:** M1 · **Status:** active

> Assumptions, to be confirmed before implementation starts:
> - A1. The target framework is on **Selenium 4.x** (`RemoteWebDriver.execute(String, Map)` exists).
> - A2. `-javaagent` is **allowed** on test JVMs. The listener fallback is a defined seam (§6) and is **not built in M1**.
> - A4. Test JVMs run on **Java 17+**. The agent is compiled with `--release 17` so the same jar works on 17, 21 and later. On an older JVM it fails `premain` with `UnsupportedClassVersionError` and the test JVM will not start.
> - A3. Cucumber-JVM 7 and JUnit 5 run configs. TestNG works for free via the same patcher but is not a test target.
>
> If A1 or A2 is false, STOP and ask. Do not improvise an alternative architecture.

---

## 1. What M1 delivers (and nothing else)

Right-click a test, choose **Run with Locator Coverage**, and the test runs normally with an agent attached. On each page change the agent writes a **redacted** DOM snapshot. When the process ends, the IDE generates `.strata/locator-coverage.xml` in **page / module / xpath** form and shows a notification with an "Open XML" action.

### In scope
- Custom executor + program runner + `JavaProgramPatcher` (attaches the agent)
- Java agent: intercepts `RemoteWebDriver.execute`, detects page change, waits for DOM settle, redacts in-browser, writes the snapshot + manifest line
- Core generator: snapshots → page/module/xpath model → XML, every XPath **verified** against its own snapshot
- Post-run hook in the IDE: runs the generator on a background task, writes the XML, notifies

### Out of scope (do NOT build; reject if asked mid-milestone)
- `used` flag / coverage %, tool window, gutter badges, run diff, Insert `@FindBy`
- `WebDriverListener` fallback, Playwright, Selenium 3
- iframes, shadow DOM (log them as `skipped` in the manifest, nothing more)
- Element naming beyond the deterministic rule in §5.4
- Any AI/LLM call

---

## 2. Repository layout

```
strata/
├── CLAUDE.md
├── settings.gradle.kts
├── strata-core/        # pure Kotlin, `jvmTarget = 17` (toolchain 21), Jsoup. No IntelliJ, no Selenium.
├── strata-agent/       # **Java 17 bytecode** (`--release 17`), ByteBuddy (shaded+relocated). No Kotlin.
├── strata-plugin/      # IntelliJ Platform plugin, Kotlin, depends on strata-core
└── fixtures/
    ├── site/           # static HTML test site (§8)
    └── snapshots/      # golden .dom.html + expected .xml for core tests
```

**Why the agent is Java:** a Kotlin agent drags `kotlin-stdlib` onto the test classpath and collides with the framework's own Kotlin version. The agent jar has **zero unrelocated dependencies**.

---

## 3. Pinned type contracts

### 3.1 Agent → disk (the only interface between agent and IDE)

Output dir is the `-Dstrata.out=<abs path>` system property, e.g. `<project>/.strata/runs/<runId>/`.

```
<out>/
├── manifest.jsonl          # one SnapshotRecord per line, append-only
└── dom/<seq>-<routeKey>.dom.html
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
    val containerHash: String,    // sha1 of sorted top-level container ids, see §4.3
    val domFile: String,          // relative to <out>
    val capturedAt: String,       // ISO-8601 UTC
    val settleMs: Int,            // time spent waiting for quiet DOM
    val skipped: List<String> = emptyList()   // e.g. ["iframe#chart", "shadow:ag-grid"]
)
enum class Trigger { GET, CLICK, BACK, FORWARD, REFRESH, CONTAINER_CHANGE }
```

The agent writes JSON by hand (no JSON lib). Field names and order are exactly as above.

### 3.2 Core model

```kotlin
data class LocatorCoverageRun(
    val runId: String,
    val generatedAt: String,
    val pages: List<PageModel>
)
data class PageModel(
    val name: String,             // PascalCase, §5.4
    val routeKey: String,
    val visits: Int,              // snapshots merged into this page
    val modules: List<ModuleModel>
)
data class ModuleModel(
    val name: String,
    val kind: ModuleKind,
    val xpath: String,            // absolute, verified 1 match
    val elements: List<XpathEntry>
)
enum class ModuleKind { FORM, NAV, TABLE, DIALOG, SECTION, ANCHORED, PAGE_ROOT }

data class XpathEntry(
    val name: String,
    val type: ElementType,
    val value: String,            // relative to module: starts with ".//"
    val absolute: String,         // module.xpath + value-tail, verified
    val strategy: Strategy,
    val matches: Int              // within the snapshot; must be 1 to be emitted as-is
)
enum class ElementType { BUTTON, LINK, TEXT_INPUT, DROPDOWN, CHECKBOX, RADIO, TEXTAREA, TAB, OTHER_INTERACTIVE }
enum class Strategy { TESTID, ID, NAME, ARIA_LABEL, LABEL_FOR, TEXT, PLACEHOLDER, POSITIONAL }
```

### 3.3 Core services

```kotlin
interface RouteNormalizer   { fun normalize(url: String): String }
interface ModuleDetector    { fun detect(doc: Document): List<Pair<Element, ModuleKind>> }
interface XpathGenerator    { fun generate(el: Element, module: Element): XpathEntry? }   // null = could not make a unique XPath
interface XpathVerifier     { fun count(doc: Document, xpath: String): Int }              // Jsoup selectXpath
interface CoverageXmlWriter { fun write(run: LocatorCoverageRun, out: Path) }

object StrataGenerator {
    fun generate(runDir: Path): LocatorCoverageRun   // reads manifest + dom/, merges by routeKey
}
```

---

## 4. Agent (strata-agent)

### 4.1 Interception
- ByteBuddy `AgentBuilder` with `Advice` on `org.openqa.selenium.remote.RemoteWebDriver#execute(String, Map)`, on **method exit**, and only when no exception was thrown.
- Act on command names: `get`, `clickElement`, `goBack`, `goForward`, `refresh`. Ignore all others in M1.
- **Re-entrancy guard:** a `ThreadLocal<Boolean>`. The agent's own `executeScript` calls go through `execute` too and must be ignored.
- Every agent failure is caught and logged to `<out>/agent.log`. **The agent must never fail or slow a test beyond the settle cap.**

### 4.2 Settle wait
Use `executeAsyncScript`. The page script resolves once a `MutationObserver` has been quiet for **500 ms**, with a hard cap of **5000 ms**. Record the measured `settleMs`.

### 4.3 Page change detection
A snapshot is taken when **either** condition holds:
- `routeKey` differs from the last snapshot for this `sessionId`, or
- `containerHash` differs. This is sha1 of the sorted `id`/`data-testid` values of elements matching `form, nav, table, dialog, [role=dialog], section, main > *` that have such an attribute.

If the same (`routeKey`, `containerHash`) was already captured in this session, skip it. No duplicate snapshots.

### 4.4 In-browser redaction (data never reaches disk unredacted)
Before returning HTML, a JS function walks a **clone** of `document.documentElement`:
- Remove `<script>`, `<style>`, `<svg>` children (keep the `<svg>` tag itself), comments, and `<noscript>`.
- **Blank text** inside `tbody`, `[role=grid]`, `[role=row]`, `[role=gridcell]`, `td`, and `output`, and replace it with `···`.
- Remove the `value` attribute on every `input` and `textarea`, and clear `textarea` text.
- **Keep** text in `button`, `a`, `label`, `th`, `h1`–`h6`, `legend`, `option`, `[role=tab]`, and `[role=menuitem]`, truncated to 60 chars.
- Keep all attributes except `value`, `style`, and `on*`.

Return `clone.outerHTML`. Write it as UTF-8.

### 4.5 Packaging
- Shadow jar: relocate `net.bytebuddy` to `dev.strata.shaded.bytebuddy`.
- `MANIFEST.MF`: `Premain-Class`, `Can-Retransform-Classes: true`.
- Selenium is `compileOnly`, so the agent uses whatever Selenium the test brings.

---

## 5. Core generation rules (strata-core)

### 5.1 Route normalization
Strip the scheme, host, query, and fragment. Then replace any path segment that matches one of these with `{id}`:
`^\d+$`, `^[0-9a-f]{8}-[0-9a-f]{4}-`, `^[A-Z0-9]{8,}$`, `^[0-9a-f]{16,}$`

The routeKey for `/blotter/48213?x=1` is `/blotter/{id}`, and the file-safe form is `blotter_id`.

### 5.2 Interactable elements (the only things that get XPaths)
`button, a[href], input:not([type=hidden]), select, textarea, [role=button], [role=link], [role=tab], [role=checkbox], [role=radio], [role=combobox], [role=menuitem], [onclick], [tabindex]:not([tabindex="-1"]), mat-select, ng-select`

Skip elements inside `[aria-hidden=true]` or `[hidden]`.

### 5.3 Modules
- A container is the nearest ancestor that is `form`, `nav`, `table`, `dialog`/`[role=dialog]`, or `section`, or that carries a stable `id`/`data-testid` (§5.5). Elements with no container go to the `PAGE_ROOT` module (`//body`).
- Module XPath priority: `@data-testid` → stable `@id` → `@aria-label` → tag + heading text (`//section[.//h2[normalize-space()='Filters']]`). It must verify to exactly 1 match, otherwise fall to the next option. If every option fails, merge the module into its parent.
- Do not nest modules more than **1 level** in the XML for M1. The XML is page → module → xpath, flat.

### 5.4 Naming (deterministic, no AI)
- Page name: PascalCase of the `<title>` if one exists and is unique within the run, otherwise of the routeKey (`BlotterId`).
- Module name: PascalCase of `data-testid`, `id`, `aria-label`, or heading text, then a `Form`/`Dialog` suffix where the kind adds information.
- Element name: camelCase of the label source + type suffix (`exportButton`, `accountDropdown`, `tradeDateInput`). On collisions within a module, append `2`, `3`, and so on.

### 5.5 Stable vs dynamic ids
An `id` is **dynamic** if it matches any of:
`\d{4,}`, `[0-9a-f]{8}-`, `^ember\d+`, `^mat-[a-z-]+-\d+`, `^react-select-\d+`, `^cdk-`, `^:r[0-9a-z]+:$`, `^ext-gen\d+`, `^ui-id-\d+`

Dynamic ids are never used in any XPath.

### 5.6 Strategy order (first one with exactly 1 match within the module wins)
1. `TESTID`: `.//*[@data-testid='…']` (also `data-test`, `data-qa`, `data-cy`)
2. `ID`: stable `@id`
3. `NAME`: `@name`
4. `ARIA_LABEL`: `@aria-label`
5. `LABEL_FOR`: `//label[normalize-space()='X']/@for` → the target id (stable ids only); otherwise `label` → `following::<tag>[1]`
6. `TEXT`: `normalize-space()='…'` for buttons, links, and tabs
7. `PLACEHOLDER`: `@placeholder`
8. `POSITIONAL`: tag + 1-based index within the module. Always emitted with `strategy="POSITIONAL"` so M2 can flag it.

Use XPath 1.0 only, and use `'` quoting. For text that contains `'`, use `concat()`.

### 5.7 Verification (hard rule)
Every emitted `value` and `absolute` is checked with `XpathVerifier.count` against **the snapshot it came from**. `matches != 1` means the next strategy is tried. If nothing works, the element is **dropped** and a line goes to `generator.log`. Never emit an unverified XPath.

### 5.8 Merging visits
Snapshots that share a routeKey merge into one `PageModel`. Elements are deduplicated by `absolute`. A module seen only in some snapshots, such as a dialog, is still included. `visits` counts the snapshots.

---

## 6. Listener fallback seam (defined, NOT built)

```java
// strata-agent: dev.strata.agent.CaptureSink — the agent calls only this
public interface CaptureSink {
    void onCommandCompleted(Object driver, String command);
}
```

All capture logic lives behind `CaptureSink`, so a future `WebDriverListener` can call the same sink. Do not build the listener in M1.

---

## 7. Plugin (strata-plugin)

- Target: IntelliJ IDEA **2024.2+** (`sinceBuild = 242`), using IntelliJ Platform Gradle Plugin 2.x, Kotlin, and JVM 21 (2024.2+ runs on JBR 21, so no conflict).
- Depends on `com.intellij.java`. Cucumber support is optional (`<depends optional="true">`), because Cucumber-Java configs extend `ApplicationConfiguration` and the patcher covers them regardless.

```xml
<extensions defaultExtensionNs="com.intellij">
  <executor implementation="dev.strata.plugin.LocatorCoverageExecutor"/>
  <programRunner implementation="dev.strata.plugin.LocatorCoverageRunner"/>
  <java.programPatcher implementation="dev.strata.plugin.AgentPatcher"/>
  <notificationGroup id="Strata" displayType="BALLOON"/>
</extensions>
```

| Class | Responsibility |
|---|---|
| `LocatorCoverageExecutor` | `id = "StrataLocatorCoverage"`, text "Run with Locator Coverage", own icon |
| `LocatorCoverageRunner` | extends `DefaultJavaProgramRunner`; `canRun` = executor matches && profile is a `JavaRunConfigurationBase` |
| `AgentPatcher` | only for this executor: **first checks the run config's JRE is ≥ 17**; if not, abort the launch with an error balloon "Locator Coverage requires Java 17+ (configured: N)" instead of attaching the agent. Otherwise adds `-javaagent:<bundled jar>` and `-Dstrata.out=<project>/.strata/runs/<runId>` |
| `PostRunHandler` | `ProcessListener.processTerminated` → `Task.Backgroundable` → `StrataGenerator` → write `<runDir>/locator-coverage.xml` and copy it to `.strata/locator-coverage.xml` → VFS refresh → notification "N pages, M elements · Open XML" |

- `runId` = `yyyyMMdd-HHmmss`.
- The agent jar is bundled in the plugin's `lib/`, and its path is resolved via `PluginManagerCore`.
- The generator never runs on the EDT. File writes go through `WriteAction` + VFS refresh.

---

## 8. XML output (schema v1, exact)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<locator-coverage schema="1" run="20261106-223012" generated="2026-11-06T22:33:40Z">
  <page name="TradeBlotter" route="/blotter/{id}" visits="3">
    <module name="FilterForm" kind="FORM" xpath="//form[@id='blotter-filter']">
      <xpath name="accountDropdown" type="DROPDOWN" strategy="NAME" matches="1"
             value=".//select[@name='account']"
             absolute="//form[@id='blotter-filter']//select[@name='account']"/>
    </module>
  </page>
</locator-coverage>
```

- Pages are sorted by `route`, modules by document order of the first visit, and xpaths by document order.
- Output is deterministic: the same snapshots always produce byte-identical XML apart from the `generated` attribute.

---

## 9. Fixtures and tests

### `fixtures/site/` (static HTML, served by the integration test)
| Page | Purpose |
|---|---|
| `login.html` | form, label-for inputs, text button |
| `blotter/48213.html` | data-testid grid, dynamic ids (`mat-input-7`, `ps-7731`), tbody with fake client text, `aria-label` buttons |
| `blotter/99001.html` | same route as 48213; must merge |
| `blotter/48213.html#export` | same URL with a JS-opened `role=dialog`; must be captured via `CONTAINER_CHANGE` on click |

### Required tests
- **core (unit):** `RouteNormalizer` table tests; dynamic-id regex table; every strategy has positive and fallthrough cases; the verifier rejects multi-match XPaths; golden-file XML comparison; merging two snapshots of the same route.
- **core (redaction contract):** given `fixtures/snapshots/*.dom.html`, assert that no `tbody` text other than `···` survives and no `value=` attribute survives.
- **agent (integration):** run on a **JDK 17 + JDK 21 matrix**. Headless Chrome + local HTTP server over `fixtures/site`. Run a JUnit test with `-javaagent`. Assert 4 manifest lines, correct triggers, no duplicates, `settleMs <= 5000`, and that the test still passes when the agent's output dir is unwritable.
- **plugin:** `BasePlatformTestCase`. The patcher adds args only for the Strata executor, it refuses a JRE below 17, and `canRun` is false for non-Java configs.

---

## 10. Acceptance criteria (M1 is done when ALL pass)

1. Right-clicking `Blotter.feature` shows **Run with Locator Coverage**, and the test runs and reports normally.
2. Test wall-clock overhead is **≤ 15 %** on the fixture suite.
3. `.strata/locator-coverage.xml` appears within 10 s of the process ending, matching schema v1.
4. Every `xpath` in the XML has `matches="1"` when re-verified against its snapshot (the test asserts this).
5. No snapshot contains `tbody` text or `value=` attributes from the fixture.
6. No dynamic id from §5.5 appears in any XPath.
7. A test fails **only** for its own reasons. Agent errors land in `agent.log`, never in test results.
8. `./gradlew check` is green in all three modules.

## 11. Hard stop

When §10 passes: tag `v0.1.0-m1` and **stop**. Do not start the `used` flag, the tool window, or naming improvements. Write `M1-REPORT.md` with the overhead numbers, the counts of dropped elements and why each was dropped, and the 3 biggest XPath-quality issues observed on the fixtures. That report is the input to M2 scoping.

---

## 12. Conventions
- Kotlin: explicit return types on public API, no `!!` outside tests, and `Result`/null over exceptions in core.
- Java (agent): `--release 17`, built with a JDK 21 toolchain. Allowed: records, `instanceof` pattern matching, switch expressions, text blocks (handy for the redaction JS). **Not allowed:** pattern matching in `switch`, record patterns, sequenced collections, or any other 21-only API. CI runs the agent integration test on **both JDK 17 and 21**. **No virtual threads in the agent**: capture runs on the test's own thread. No lambdas capturing Selenium types into static state; everything `final`.
- Commits: one concern per commit, prefixed `core:`, `agent:`, `plugin:`, `fixtures:`, or `docs:`.
- Changing any contract in §3 or the schema in §8 requires bumping this file's version and a changelog line. Ask first.

## Changelog
- **v0.1.2**: Java 17+ support. The agent and core emit Java 17 bytecode; the plugin stays on JVM 21 (required by IntelliJ 2024.2). The patcher threshold is now 17, and CI runs a 17/21 matrix.
- **v0.1.1**: all modules target Java 21; added assumption A4; `AgentPatcher` refuses JREs below 21.
- **v0.1.0**: initial M1 scope.
