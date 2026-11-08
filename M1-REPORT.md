# M1 Report: Run with Locator Coverage

**Status: implementation complete, M1 not yet signed off.** `./gradlew check` is green in all three modules, but
§10.2 (overhead ≤ 15 %) fails on the fixture suite, and §10.1 (the right-click flow in a real IDE) still needs a
manual run. Because of that, `v0.1.0-m1` has not been tagged.

## Acceptance criteria (§10)

| # | Criterion | Result |
|---|---|---|
| 1 | *Run with Locator Coverage* on `Blotter.feature`, test runs normally | **Not verified by hand yet.** Executor, runner and patcher are registered, and `BasePlatformTestCase` tests cover them. The `StrataDemo` project has `Blotter.feature` ready for the manual check. |
| 2 | Overhead ≤ 15 % | **Fails.** See below. |
| 3 | XML within 10 s of process end, schema v1 | Generation on the fixture run takes well under 1 s. Golden test `GoldenXmlTest` pins the schema. |
| 4 | Every xpath re-verifies to `matches="1"` | Pass: `GoldenXmlTest.everyXpathReverifiesAndHasNoDynamicId`, plus the agent integration test end to end. |
| 5 | No `tbody` text / `value=` in snapshots | Pass: `RedactionContractTest` and the integration test. |
| 6 | No dynamic id in any XPath | Pass: same tests. |
| 7 | Tests fail only for their own reasons | Pass: `agentIntegrationTestUnwritable`. Sink exceptions are swallowed (`AgentUnitTest`). |
| 8 | `./gradlew check` green | Pass: core 78 tests, agent 15 unit tests plus 3 integration runs (JDK 17, JDK 21, unwritable), plugin 8 tests. |

## Overhead (§10.2)

Fixture flow (`FixtureFlowIT`, headless Chrome, JDK 21, wall clock including browser start). Three alternating runs
each:

| | run 1 | run 2 | run 3 | mean |
|---|---|---|---|---|
| no agent (`fixtureFlowBaselineJdk21`) | 2821 ms | 2234 ms | 2290 ms | **2448 ms** |
| with agent (`agentIntegrationTestJdk21`) | 5583 ms | 4943 ms | 5653 ms | **5393 ms** (+120 %) |

Per-command timing from `agent.log`:

```
get          -> captured GET /login            settle 524 ms  (798 ms total)
clickElement -> unchanged /login               settle   0 ms  (22 ms)
clickElement -> captured CLICK /blotter/{id}   settle 511 ms  (533 ms)
clickElement -> unchanged /blotter/{id}        settle   0 ms  (7 ms)
clickElement -> captured CONTAINER_CHANGE      settle 483 ms  (513 ms)
get          -> captured GET /blotter/{id}     settle 511 ms  (533 ms)
get          -> unchanged /blotter/{id}        settle 519 ms  (527 ms)
```

Redaction plus writing a snapshot costs 10–20 ms. The overhead is almost all the §4.2 quiet window: after each
navigation, a new document has to be quiet for 500 ms before the agent can capture it. Five navigations in a 2.4 s
flow add about 2.6 s. With a 500 ms window, ≤ 15 % only holds for suites that average ≥ ~3.5 s per navigation.
**Decision needed for M2 scoping:** (a) lower or make configurable the quiet window, (b) judge overhead on a
realistic suite instead of the fixture flow, or (c) both. The 500 ms constant was left as specified.

## Dropped elements

**0 dropped, 0 modules merged** on the fixture run (`generator.log` is empty). Every interactable got a verified
XPath. Some of them only got one through POSITIONAL (see issue 1).

## Three biggest XPath-quality issues on the fixtures

1. **Row actions in tables become POSITIONAL and unnamed.** The *View* buttons in `tbody` have no attributes, and
   their text is redacted to `···`, so the only XPath left is `(//table[@id='trades']//button)[1]`, named `button`
   and `button2`. These XPaths depend on row order and break when the data changes. M2 needs a row-scoped strategy,
   for example column header plus row index, or should flag them.
2. **LABEL_FOR falls back to `following::`.** Labels pointing at dynamic ids (`mat-input-7`) produce
   `//label[normalize-space()='Trade date']/following::input[1]`. That XPath verifies, but it depends on DOM order
   and `following::` can escape the module. The `for` → id variant of §5.6 step 5 never fires on its own, because a
   stable id is already taken by the ID strategy one step earlier.
3. **Elements outside any container land in `//body`.** The tablist buttons become
   `//body//button[@id='tab-open']`, because `role=tablist`, `header` and `main` are not module kinds. With a
   PAGE_ROOT module, the absolute path adds no scoping, and on a real app this module turns into a catch-all.

## Deviations from CLAUDE.md, and interpretations

- **Interception point (§4.1).** The advice sits on `RemoteWebDriver#execute(CommandPayload)`, not
  `execute(String, Map)`. In Selenium 4.39, `get()` and element clicks call the payload overload directly, and
  `execute(String, Map)` only delegates to it. Advice on the `(String, Map)` overload alone would never see
  `get`/`clickElement`. Same class, same ByteBuddy Advice, and every command passes through exactly once.
  §4.1 should be reworded.
- **Redaction of "other" text (§4.4).** Text that is neither in a blank zone nor inside a keep element (`<p>`,
  `<span>`, `<title>`, …) is also replaced with `···`. The spec does not say what happens to it, so I chose the
  conservative reading.
- **`<option>` text is kept, as §4.4 says, and it can carry client data.** The fixture's account dropdown keeps
  "ACC-1 Meridian Capital". Worth revisiting.
- **TEXT and label strategies skip text of 60 chars or more.** Kept text is truncated to 60 chars in the snapshot,
  so an XPath built from it would verify against the snapshot and fail on the live page.
- **`[onclick]` in §5.2 never matches.** Redaction strips `on*` attributes before the generator sees the DOM.
- **`containerHash` (§4.3) includes dynamic container ids.** On apps that generate container ids per render, this
  will produce duplicate snapshots. The hash was implemented exactly as specified.
- **Unknown JRE version.** If the run config's JDK version cannot be determined, `AgentPatcher` lets the launch
  through instead of refusing it.
- **Gradle-delegated test runs** (IntelliJ's default for Gradle projects) are not `JavaRunConfigurationBase`, so
  the menu item doesn't appear for them. Maven projects, and Gradle projects set to run tests with IntelliJ, work.
- **Plugin build.** Kotlin `apiVersion` is 1.9 for core and plugin, because IntelliJ 2024.2 bundles kotlin-stdlib
  1.9 and the plugin does not ship its own copy.
