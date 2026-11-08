# M2 Report: Coverage and Locator Quality

**Status: M2 Must items complete; `./gradlew check` green in all three modules.**

| Done | Not done |
|---|---|
| 21 components, usage + coverage %, stability, M1 quality fixes, dropdown overlays and options, same-origin iframes, overhead rule A+B+C | AG Grid adapter and Gradle-delegated runs (both *Should*, see below) |
| | Manual right-click checks in a real IDE (M1 #1, M2 needs the same) |

## Acceptance criteria (CLAUDE.md v0.2.0 §10)

| # | Criterion | Result | Evidence |
|---|---|---|---|
| 0 | All 21 components have verified entries; no dynamic ids or redacted text; unanchored content only in `<omitted>` | **Pass** | `GoldenXmlTest.allRequiredComponentsAreCovered`; `FixtureFlowIT.componentsFlow` (live Chrome) |
| 1 | Used elements are `used="true"` or `<unmatched>` | **Pass** | Golden run: 24 used, **0 unmatched**. `StrataDemo`: 27 used, 0 unmatched |
| 2 | Coverage numbers are exact | **Pass** | `GoldenXmlTest.coverageNumbersAreExact` |
| 3 | Overhead budget, medians | **Pass** | Captured page 532–536 ms (limit 700); unchanged command 5–9 ms (limit 50); usage 6 ms (limit 50) |
| 4 | Table actions are ROW_TEMPLATE; header-keyed cells are MEDIUM | **Pass** | `closeButton`, `viewButton`; `priceCell` MEDIUM |
| 5 | No LABEL_FOR with `following::` on the fixtures | **Pass** | 0 in the golden XML |
| 6 | Overlay listbox and menu → `CONTAINER_CHANGE` + `OPTION` entries | **Pass** | Components triggers: GET, CONTAINER_CHANGE, CONTAINER_CHANGE |
| 7 | Same-origin frame → `<frame>` with usage; cross-origin → `IFRAME` entry, content `skipped` | **Pass** | `Help` frame with `questionInput` used; `iframe#ext-frame` skipped |
| 8 | M1 criteria 3–7 still hold | **Pass** | Same tests as M1, updated for the repeated-label exception |
| 9 | `./gradlew check` green | **Pass** | core 92 tests; agent 20 unit tests + 2 integration flows × 3 runs (JDK 17, JDK 21, unwritable); plugin 9 |

## Overhead

**Fixture flow, no agent vs agent at the default quietMs = 500, three calm runs:**

| | run 1 | run 2 | run 3 | mean |
|---|---|---|---|---|
| no agent | 2840 ms | 2108 ms | 2130 ms | 2359 ms |
| agent (M2) | 4665 ms | 4631 ms | 4493 ms | 4596 ms (**+95 %**; M1 was +120 %) |

**Per command, medians from `agent.log`:**

| Command | Cost |
|---|---|
| a page that was captured | ~535 ms, almost all of it the quiet window |
| a command that changed nothing | 5–9 ms (M1: up to 519 ms for a repeated `get`) |
| recording newly used elements | 6 ms |

**Settle window, interleaved runs.** IntelliJ was indexing at the time, so absolute numbers are noisy; medians:

| quietMs | flow wall clock | vs no agent (3459 ms in this series) |
|---|---|---|
| 500 (default) | 5230 ms | +51 % |
| 200 | 4414 ms | +28 % |
| 100 | 3850 ms | +11 % |

Capture stayed correct at 100 and 200 ms on the fixtures, and all assertions passed. The fixtures are static pages, though. Real SPAs that render after network calls need a longer window. **Recommendation:** keep the default at 500 until it's measured on a real suite, then lower it. The knob is `-Dstrata.quietMs`, and the integration tests take `-PquietMs`.

**A bug found while doing this.** The cheap probe was skipping the wait after a click that submits a form, because the navigation hadn't started yet. The blotter page was then only captured on the next click, and lookups in between were attributed to the login page. The fix: the agent flags a document that is leaving (`submit`, same-window link click, `beforeunload`, `pagehide`; the flag expires after 2 s), and never skips the wait while that flag is set. Three repeat runs confirmed the fix.

## Dropped elements

**0 dropped, 0 modules merged** on the golden run (`generator.log` is empty). Unanchored content, counted under `<omitted>` and not lost:

| Page | DIV | PARAGRAPH | SPAN | ICON |
|---|---|---|---|---|
| components | 4 | 1 | 1 | 1 |
| blotter | — | 1 | — | — |

## Stability mix (golden run, 119 entries)

**30 HIGH, 43 MEDIUM, 46 LOW.**

Of the LOW entries, 39 are TEXT; the rest are 3 ROW_TEMPLATE, 2 POSITIONAL and 2 STRUCTURE.

## Biggest locator-quality issues still open

1. **Visible text is the most common locator.** 39 of the 46 LOW entries are TEXT: nav links, headings, labels and buttons located by their wording. These break on copy changes and in other languages. The fixtures have few test ids. On a real app, this number is the main thing to bring down, and the stability rating now makes it visible. Option for M3: flag TEXT-only entries in the tool window.
2. **The same row action appears twice after merging.** On blotter 48213, *View* repeats across two rows, so its text is kept: `tr[{row}]//button[normalize-space()='View']`. Blotter 99001 has a single row, so the text stays redacted, and that visit produces `tr[{row}]/td[4]/descendant::button[1]` (LOW). Merging dedupes by XPath, so both entries survive. Fix: when merging, collapse templates that point at the same column action, and keep the stronger one.
3. **`<main>` collects a lot.** With `main` as a LANDMARK module, everything not inside a smaller container lands in one "Main" module: 13 mixed entries on the components page, including the table, iframes, lists and dropdown toggles. That's better than `//body`, but still coarse. Grouping by nearest heading (`h2` sections without a `<section>` tag) would help.

## What was not built, and why

- **AG Grid adapter (Should).** It needs a vendored AG Grid build in the fixtures and a spike to confirm the DOM attributes (`col-id`, `row-index`). The generic rules partly cover a grid today: `[role=grid]` → TABLE, column headers keep their text. But no `@col-id` templates exist yet. This is the next item.
- **Gradle-delegated runs (Should).** These need the IntelliJ Gradle plugin's run-configuration extension and an init script. They can't be verified without driving a real IDE. The menu item still only appears on IntelliJ-run (non-delegated) configurations.
- **Manual IDE checks.** Neither the right-click flow nor the balloon has been seen in a running IDE. The plugin tests cover the patcher, runner and notification text.

## Contract changes

All are recorded in `CLAUDE.md` v0.2.0 and were approved on 2026-11-07:

- schema v2;
- `usage.jsonl`;
- frames in the manifest;
- the `CaptureSink` signature;
- 15 new element types;
- 5 new strategies;
- 3 new module kinds.

## Interpretations worth reviewing

- **Interactive elements always get an XPath** (POSITIONAL as a last resort), as in M1. Rules R1/R2 only govern `p`/`span`/`div`/icons.
- **A `div`/`span`/`p` with a stable id is a module only if it contains something interactive.** Otherwise it's a content element. Before this rule, an anchored `<span data-testid>` silently disappeared.
- **Menu items keep their text and get individual entries.** They are UI commands. Option text is redacted, per D3.
- **`about:blank` frames are skipped** (`iframe#x:blank`), not modelled.
- **Text with four or more consecutive digits is treated as data** and never used as a locator. Examples: "Blotter 48213", amounts.
