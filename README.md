# Strata: Run with Locator Coverage

Strata is an IntelliJ IDEA plugin for Selenium test suites. Right-click a test, choose **Run with Locator Coverage**,
and Strata watches the run, captures each page the test visits, and writes an XML catalogue of verified XPath
locators for every element on those pages, organised by page and component. Each locator says how stable it is
and whether your tests actually used that element.

```
Right-click a test → Run with Locator Coverage
  → the test runs as usual, with a Java agent attached
  → each new page state is captured (redacted, in the browser)
  → .strata/locator-coverage.xml + "6 pages, 394 elements, 25 used (6%)"
```

Use it to:

- find the elements your suite never touches, page by page;
- replace fragile locators with verified ones, rated `HIGH`, `MEDIUM` or `LOW`;
- get ready-made templates for tables, lists, grids, cards and dropdown options.

## Requirements

| | |
|---|---|
| IDE | IntelliJ IDEA 2024.2 or later (Community or Ultimate) |
| Tests | Selenium 4.x, run as JUnit 5 or Cucumber-JVM 7 run configurations (TestNG works but isn't tested) |
| Test JVM | Java 17 or later; launches on older JREs are refused with a message |
| Browser | Anything Selenium drives; it's tested on Chrome |
| Build (from source) | JDK 21; Gradle comes with the wrapper |

The test JVM must allow `-javaagent`.

## Install

Build the plugin and install it from disk:

```
./gradlew buildPlugin
```

Then, in IntelliJ, go to **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick
`strata-plugin/build/distributions/strata-plugin-0.1.0.zip`, and restart.

To try it without installing, run `./gradlew :strata-plugin:runIde`, which opens a sandbox IDE with the plugin
loaded.

## Use

1. Right-click a test class, method or `.feature` file, or pick a run configuration, and choose
   **Run with Locator Coverage**.
2. The test runs normally. When it ends, a notification shows the totals; click **Open XML**.
3. The result is `.strata/locator-coverage.xml` in your project. Each run also keeps its own folder in
   `.strata/runs/<yyyyMMdd-HHmmss>/`.

Add `.strata/` to your `.gitignore`.

> The menu item only appears when IntelliJ runs the tests itself. If test runs are delegated to Gradle
> (**Settings → Build Tools → Gradle → Run tests using**) or Maven, switch them to *IntelliJ IDEA*.

A sample project with a local test site and tests is in [`../StrataDemo`](../StrataDemo/README.md).

## The output

An excerpt from the golden run in `fixtures/` (the `unmatched` line is illustrative):

```xml
<locator-coverage schema="4" run="20261107-120000" generated="2026-11-07T12:01:00Z" used="29" total="141">
  <page name="TradeBlotter" route="/blotter/{id}" visits="3" used="3" total="45">
    <module name="Trades" kind="TABLE" xpath="//table[@id='trades']" used="0" total="12">
      <xpath name="tradesRow" type="TABLE_ROW" strategy="ROW_TEMPLATE" stability="MEDIUM" matches="1" instances="2" used="false"
             value="./tbody/tr[%s]"
             absolute="//table[@id='trades']/tbody/tr[%s]"/>
      <xpath name="viewButton" type="BUTTON" strategy="ROW_TEMPLATE" stability="MEDIUM" matches="1" instances="2" used="false"
             value="./tbody/tr[%s]//button[normalize-space()='View']"
             absolute="//table[@id='trades']/tbody/tr[%s]//button[normalize-space()='View']"/>
    </module>
    <omitted type="PARAGRAPH" count="1"/>
  </page>
  <unmatched route="/login" locator="css selector: .toast" command="findElement" reason="STATE_NOT_CAPTURED"/>
</locator-coverage>
```

| Element | Meaning |
|---|---|
| `page` | One route. URLs that differ only by an id share a route (`/blotter/48213` and `/blotter/99001` both become `/blotter/{id}`), and their visits are merged. |
| `module` | A component of the page: a form, table, dialog, nav, menu, grid, section, landmark or anchored container. Its `xpath` is absolute. |
| `frame` | A same-origin iframe's content, with its own modules. |
| `xpath` | One element. `value` is relative to the module and `absolute` is from the page root. Both were verified to match exactly one element in the captured page. |
| `omitted` | Plain `div`/`span`/`p` content with nothing stable to locate it by, counted rather than listed. |
| `unmatched` | Elements your tests used that Strata couldn't place, with a reason. |
| `used` / `total` | How many entries your tests used, per module, page and run. |

### Stability

| Rating | Locates by |
|---|---|
| `HIGH` | test id (`data-testid`, `data-test`, `data-qa`, `data-cy`), stable `id`, or `name` |
| `MEDIUM` | `aria-label`, label, attributes, classes, structure, and templates keyed by header text, `col-id` or shared labels |
| `LOW` | visible text, position, `following::`, or a column number |

Generated ids such as `mat-input-7`, `react-select-3` or `ag-22-input` are recognised as dynamic and never
used.

### Templates

Repeated things get one template instead of one locator per copy. The dynamic part is a single `%s`:

| What | `%s` stands for | Example |
|---|---|---|
| Table rows, cells and row buttons | row number, from 1 | `//table[@id='trades']/tbody/tr[%s]//button[normalize-space()='View']` |
| List items | item number, from 1 | `//main//ul[@id='actions-menu']/li[%s]` |
| Cards (product tiles and similar) | card number, from 1 | `.//div[contains(concat(' ', normalize-space(@class), ' '), ' features_items ')]/div[contains(…, ' col-sm-4 ')][%s]//a[normalize-space()='Add to cart']` |
| AG Grid rows and cells | row number, from 1 | `//div[@data-testid='orders-grid']//div[@role='row'][@row-index=%s-1]//div[@role='gridcell'][@col-id='notional']` |
| Dropdown options | the option's text | `//form[@id='blotter-filter']//select[@name='account']//option[normalize-space()='%s']` |

Fill them with `String.format`:

```java
By viewButton = By.xpath(String.format(
        "//table[@id='trades']/tbody/tr[%s]//button[normalize-space()='View']", 2));
```

A literal `%` in a template is written `%%`, so `String.format` always gives the right XPath. For option text that
contains `'`, pass an XPath `concat(...)` expression instead of plain text.

`instances` on a template says how many copies it was verified against.

## Privacy

Page content is redacted **in the browser, before anything is written to disk**:

- scripts, styles, comments, `value` attributes, inline handlers and textarea contents are removed;
- table cells, grid cells, list items, paragraphs, spans and option text are replaced with `···`;
- AG Grid row keys (`row-id`) and cell tooltips are removed.

UI text is kept so it can be used as a locator: buttons, links, labels, headings, column headers and tabs. Kept
text is cut to 60 characters, and text with four or more digits in a row is never used as a locator.

**Headings are kept.** If your pages put customer data in headings, such as a product name or a price in an
`<h2>`, it will be in the snapshots. Check `.strata/runs/*/dom/` before sharing a run.

## Configuration

Set these as VM options on the run configuration.

| Property | Default | What it does |
|---|---|---|
| `-Dstrata.quietMs` | `500` | How long the page must stop changing before a capture. Lower is faster; single-page apps that render after network calls need more. |
| `-Dstrata.capMs` | `5000` | The longest Strata waits for a page to settle. |

Commands that don't change the page cost about 5 ms each. A new page state costs roughly `quietMs` plus 30 ms.

## Limitations

- Selenium 4 only: no Playwright, Selenium 3 or `WebDriverListener`-only setups.
- No Gradle- or Maven-delegated runs, and no command-line or CI mode yet.
- Shadow DOM content, cross-origin iframes and iframes nested inside iframes are skipped (logged as `skipped`).
- AG Grid is the only grid library with an adapter. Other `div`-based grids get position-based locators.
- There are no gutter marks or code insertion yet. Results are in the **Locator Coverage** tool window and the XML file.

## How it works

```
test JVM                                              IDE
┌─────────────────────────────────────────┐          ┌────────────────────────────────┐
│ your test → RemoteWebDriver.execute()   │          │ strata-plugin                  │
│               │                         │          │  run finishes                  │
│        strata-agent (Java agent)        │          │   → strata-core reads the run  │
│  waits for the page to settle,          │  files   │   → modules, xpaths, templates │
│  redacts the DOM in the browser,        │ ───────► │   → verifies every xpath       │
│  records which elements were used       │          │   → locator-coverage.xml       │
└─────────────────────────────────────────┘          └────────────────────────────────┘
```

| Module | Language | Role |
|---|---|---|
| `strata-agent` | Java 17, ByteBuddy (shaded) | Hooks `RemoteWebDriver.execute`, captures redacted snapshots and usage to `.strata/runs/<id>/`. It has no dependencies on the test classpath and never fails a test. |
| `strata-core` | Kotlin, Jsoup | Turns snapshots into pages, modules and verified XPaths, and writes the XML. |
| `strata-plugin` | Kotlin, IntelliJ Platform | The run action, attaching the agent, and generating the XML in the background when the run ends. |
| `fixtures` | HTML | A static test site and a golden run used by the tests. |

Each run folder holds `manifest.jsonl` (one line per captured page state), `usage.jsonl` (one line per element
used), `dom/` (redacted snapshots), `agent.log` (what the agent did, with timings), `generator.log` (anything
dropped while generating) and `locator-coverage.xml`.

## Development

```
./gradlew check                       # all unit tests, plus Chrome integration tests on JDK 17 and 21
./gradlew :strata-core:test           # generator rules only
./gradlew :strata-core:test -PupdateGolden   # rewrite fixtures/snapshots/expected-locator-coverage.xml
./gradlew :strata-agent:agentIntegrationTestJdk21 -PquietMs=200   # try another settle window
./gradlew buildPlugin                 # build the plugin zip
./gradlew :strata-plugin:runIde       # sandbox IDE with the plugin
```

The integration tests need Chrome installed; Selenium Manager downloads the driver.

The rules and contracts are in [`CLAUDE.md`](CLAUDE.md). Milestone reports, with overhead measurements and the
open issues, are in [`M1-REPORT.md`](M1-REPORT.md) and [`M2-REPORT.md`](M2-REPORT.md).

## Status

Milestone 2 is done. The right-click flow has not yet been checked by hand in a running IDE, and `v0.1.0-m1`
isn't tagged. Next is the rest of milestone 3: gutter marks, a diff between runs, and inserting `@FindBy`
fields.
