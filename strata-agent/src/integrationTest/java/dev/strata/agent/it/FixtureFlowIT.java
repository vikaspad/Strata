package dev.strata.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.strata.core.DefaultCoverageXmlWriter;
import dev.strata.core.DynamicIds;
import dev.strata.core.JsoupXpathVerifier;
import dev.strata.core.ManifestReader;
import dev.strata.core.StrataGenerator;
import dev.strata.core.model.LocatorCoverageRun;
import dev.strata.core.model.ModuleModel;
import dev.strata.core.model.PageModel;
import dev.strata.core.model.SnapshotRecord;
import dev.strata.core.model.XpathEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Runs in a JVM started with {@code -javaagent:strata-agent.jar}. The test only drives the fixture site; whatever
 * the agent does must not change its outcome. When {@code strata.it.expectCapture=true} it also checks what the
 * agent wrote and runs the core generator over it.
 */
class FixtureFlowIT {
    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startServer() throws IOException {
        final Path root = Path.of(System.getProperty("strata.fixtures"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            Path file = root.resolve(path.substring(1)).normalize();
            if (!Files.isRegularFile(file)) {
                file = root.resolve(path.substring(1) + ".html").normalize();
            }
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            final byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type",
                    file.toString().endsWith(".js") ? "text/javascript" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    /** AG Grid adapter: a real AG Grid Community grid, driven the way a typical test drives it. */
    @Test
    void gridFlow() throws IOException {
        final ChromeOptions options = new ChromeOptions()
                .addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--window-size=1280,900");
        final ChromeDriver driver = new ChromeDriver(options);
        final String sessionId = driver.getSessionId().toString();
        try {
            final WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            driver.get(base + "/grid");
            wait.until(ExpectedConditions.numberOfElementsToBe(By.cssSelector("[role=row][row-index]"), 4));
            driver.findElement(By.cssSelector(".ag-header-cell[col-id='orderId']")).click();          // sort
            driver.findElement(By.cssSelector("[row-id='O-48214'] [col-id='actions'] button")).click(); // cancel row 2
            wait.until(ExpectedConditions.textToBe(By.cssSelector("[row-id='O-48214'] [col-id='status']"), "Cancelled"));
            driver.findElement(By.cssSelector("[row-index='0'] [col-id='notional']")).getText();
            driver.findElements(By.cssSelector("[role=row][row-index]"));
        } finally {
            driver.quit();
        }
        if (!Boolean.getBoolean("strata.it.expectCapture")) {
            return;
        }
        final Path out = Path.of(System.getProperty("strata.out"));
        verifyGrid(out, records(out, sessionId));
    }

    static void verifyGrid(final Path out, final List<SnapshotRecord> records) throws IOException {
        assertEquals(List.of("GET"), records.stream().map(r -> r.getTrigger().name()).collect(Collectors.toList()));
        final String html = Files.readString(out.resolve(records.get(0).getDomFile()), StandardCharsets.UTF_8);
        for (final String secret : List.of("row-id=", "O-48213", "Meridian", "1250000", "1,250,000")) {
            assertFalse(html.contains(secret), "'" + secret + "' survived redaction in the grid snapshot");
        }
        assertTrue(html.contains("Notional"), "column header text is kept");

        final LocatorCoverageRun run = StrataGenerator.INSTANCE.generate(out);
        final PageModel page = page(run, "/grid");
        final ModuleModel grid = page.getModules().stream().filter(m -> m.getKind().name().equals("GRID")).findFirst()
                .orElseThrow(() -> new AssertionError("no GRID module"));
        assertEquals("//div[@data-testid='orders-grid']", grid.getXpath());
        final java.util.Map<String, XpathEntry> byName = grid.getElements().stream()
                .collect(Collectors.toMap(XpathEntry::getName, x -> x));
        assertEquals("//div[@data-testid='orders-grid']//div[@role='columnheader'][@col-id='notional']",
                byName.get("notionalHeader").getAbsolute());
        assertEquals(".//div[@role='row'][@row-index=%s-1]", byName.get("ordersRow").getValue());
        assertEquals(4, byName.get("notionalCell").getInstances());
        assertTrue(byName.get("notionalCell").getUsed(), "notional cell used");
        assertTrue(byName.get("orderHeader").getUsed(), "sorted header used");
        assertTrue(byName.get("cancelButton").getUsed(), "cancel button used");
        assertEquals("ROW_TEMPLATE", byName.get("cancelButton").getStrategy().name());
        assertTrue(byName.containsKey("selectionCheckbox"), "select-all checkbox: " + byName.keySet());
        for (final XpathEntry x : grid.getElements()) {
            assertFalse(x.getStability().name().equals("LOW"), "LOW entry in grid: " + x);
            assertFalse(x.getAbsolute().matches(".*ag-\\d+-.*"), "AG Grid generated id in " + x.getAbsolute());
        }
        assertTrue(page.getOmitted().isEmpty(), "AG Grid internals must not count as omitted: " + page.getOmitted());
        assertTrue(run.getUnmatched().stream().noneMatch(u -> u.getRouteKey().equals("/grid")), run.getUnmatched().toString());
        verifyAllXpaths(out, run, ManifestReader.INSTANCE.read(out.resolve("manifest.jsonl"), m -> kotlin.Unit.INSTANCE));
    }

    /** M2 criterion 0: every one of the 21 required components comes out with a verified entry. */
    @Test
    void componentsFlow() throws IOException {
        final ChromeOptions options = new ChromeOptions()
                .addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--window-size=1280,900");
        final ChromeDriver driver = new ChromeDriver(options);
        final String sessionId = driver.getSessionId().toString();
        try {
            driver.get(base + "/components");
            driver.findElement(By.id("full-name")).sendKeys("Jane");
            driver.findElement(By.name("password")).sendKeys("secret");
            driver.findElement(By.id("bio")).sendKeys("notes");
            new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("country"))).selectByVisibleText("India");
            driver.findElement(By.name("newsletter")).click();
            driver.findElement(By.xpath("//label[normalize-space()='Call me']/input")).click();

            driver.findElement(By.id("currency-toggle")).click();                  // opens the overlay
            driver.findElement(By.xpath("//mat-option[normalize-space()='EUR']")).click();
            driver.findElement(By.id("actions-toggle")).click();                   // opens the menu
            driver.findElement(By.xpath("//li[@role='menuitem'][normalize-space()='Export']"));

            assertEquals(3, driver.findElements(By.cssSelector("#positions tbody tr")).size());
            assertEquals("410.55", driver.findElement(By.xpath("//table[@id='positions']/tbody/tr[2]/td[3]")).getText());
            driver.findElement(By.id("disclaimer")).getText();
            driver.findElement(By.xpath("//span[normalize-space()='Meridian Capital']"));  // unanchored but used (R2)
            driver.findElement(By.cssSelector("[aria-label='Download'] i"));

            driver.switchTo().frame("help-frame");
            driver.findElement(By.id("question")).sendKeys("How do I export?");
            driver.switchTo().defaultContent();
            driver.findElement(By.id("ext-frame"));
        } finally {
            driver.quit();
        }
        if (!Boolean.getBoolean("strata.it.expectCapture")) {
            return;
        }

        final Path out = Path.of(System.getProperty("strata.out"));
        final List<SnapshotRecord> records = records(out, sessionId);
        assertEquals(List.of("GET", "CONTAINER_CHANGE", "CONTAINER_CHANGE"),
                records.stream().map(r -> r.getTrigger().name()).collect(Collectors.toList()), "triggers: " + records);
        assertEquals(1, records.get(0).getFrames().size(), "same-origin help frame");
        assertTrue(records.get(0).getSkipped().contains("iframe#ext-frame"), "cross-origin frame skipped: " + records.get(0));
        for (final SnapshotRecord r : records) {
            final String html = Files.readString(out.resolve(r.getDomFile()), StandardCharsets.UTF_8);
            for (final String secret : List.of("Halvorsen", "Meridian", "190.12", "hunter2", "Jane Doe",
                    "Private notes", "Netherlands", "value=")) {
                assertFalse(html.contains(secret), "'" + secret + "' survived redaction in " + r.getDomFile());
            }
        }

        final LocatorCoverageRun run = StrataGenerator.INSTANCE.generate(out);
        DefaultCoverageXmlWriter.INSTANCE.write(run, out.resolve("locator-coverage.xml"));
        final PageModel page = page(run, "/components");
        final Set<String> types = page.getModules().stream().flatMap(m -> m.getElements().stream())
                .map(x -> x.getType().name()).collect(Collectors.toSet());
        for (final String required : List.of("TEXT_INPUT", "TEXTAREA", "PASSWORD", "BUTTON", "LINK", "ICON", "DROPDOWN",
                "OPTION", "CHECKBOX", "RADIO", "TABLE", "TABLE_ROW", "TABLE_HEADER", "TABLE_CELL", "HEADING", "LABEL",
                "PARAGRAPH", "SPAN", "DIV", "LIST", "LIST_ITEM", "IFRAME")) {
            assertTrue(types.contains(required), "no " + required + " entry; got " + types);
        }
        assertEquals(1, page.getFrames().size(), "help frame model");
        assertTrue(page.getFrames().get(0).getModules().stream().flatMap(m -> m.getElements().stream())
                .anyMatch(x -> x.getUsed() && x.getName().equals("questionInput")), "frame input not used");
        assertFalse(page.getOmitted().isEmpty(), "omitted counts");
        assertTrue(used(page, "(//section[@aria-label='Toolbar']//i)[1]")
                || page.getModules().stream().flatMap(m -> m.getElements().stream())
                .anyMatch(x -> x.getType().name().equals("ICON") && x.getUsed()), "icon not used");
        verifyAllXpaths(out, run, ManifestReader.INSTANCE.read(out.resolve("manifest.jsonl"), m -> kotlin.Unit.INSTANCE));
        verifyOverheadBudget(out);
    }

    @Test
    void fixtureFlow() throws IOException {
        final long started = System.nanoTime();
        final ChromeOptions options = new ChromeOptions()
                .addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--window-size=1280,900");
        final ChromeDriver driver = new ChromeDriver(options);
        final String sessionId = driver.getSessionId().toString();
        try {
            final WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            driver.get(base + "/login");
            driver.findElement(By.id("username")).click();                       // no page change
            driver.findElement(By.id("username")).sendKeys("jdoe");
            driver.findElement(By.xpath("//button[normalize-space()='Sign in']")).click();
            wait.until(ExpectedConditions.titleIs("Trade Blotter"));

            driver.findElement(By.cssSelector("[aria-label='Apply filters']")).click(); // no page change
            driver.findElement(By.cssSelector("[aria-label='Export']")).click();
            assertTrue(driver.findElement(By.id("export-dialog")).isDisplayed());

            driver.get(base + "/blotter/99001");
            driver.get(base + "/blotter/99001");                                   // duplicate: no snapshot
            assertEquals("Trade Blotter", driver.getTitle());
        } finally {
            driver.quit();
        }
        System.out.printf("[strata-it] flow wall clock: %d ms%n", (System.nanoTime() - started) / 1_000_000);

        if (Boolean.getBoolean("strata.it.expectCapture")) {
            verifyCapture(Path.of(System.getProperty("strata.out")), sessionId);
        }
    }

    static List<SnapshotRecord> records(final Path out, final String sessionId) {
        final List<String> problems = new java.util.ArrayList<>();
        final List<SnapshotRecord> records = ManifestReader.INSTANCE.read(out.resolve("manifest.jsonl"), msg -> {
            problems.add(msg);
            return kotlin.Unit.INSTANCE;
        });
        assertTrue(problems.isEmpty(), problems.toString());
        return records.stream().filter(r -> r.getSessionId().equals(sessionId)).collect(Collectors.toList());
    }

    private static void verifyCapture(final Path out, final String sessionId) throws IOException {
        final List<SnapshotRecord> records = records(out, sessionId);

        assertEquals(List.of("GET", "CLICK", "CONTAINER_CHANGE", "GET"),
                records.stream().map(r -> r.getTrigger().name()).collect(Collectors.toList()),
                "triggers: " + records);
        assertEquals(List.of("/login", "/blotter/{id}", "/blotter/{id}", "/blotter/{id}"),
                records.stream().map(SnapshotRecord::getRouteKey).collect(Collectors.toList()));

        final Set<String> keys = new HashSet<>();
        for (final SnapshotRecord r : records) {
            assertTrue(keys.add(r.getSessionId() + r.getRouteKey() + r.getContainerHash()), "duplicate snapshot " + r);
            assertTrue(r.getSettleMs() >= 0 && r.getSettleMs() <= 5000, "settleMs " + r.getSettleMs());
            assertFalse(r.getUrl().contains("?"), "query string kept: " + r.getUrl());

            final String html = Files.readString(out.resolve(r.getDomFile()), StandardCharsets.UTF_8);
            assertFalse(html.contains("value="), "value attribute in " + r.getDomFile());
            for (final String secret : List.of("T-1001", "1,250,000", "1,560,000", "hunter2", "jdoe@client",
                    "late settlement", "terms. Contact", "<script", "<style", "<!--", "<path")) {
                assertFalse(html.contains(secret), "'" + secret + "' survived redaction in " + r.getDomFile());
            }
            final Document doc = StrataGenerator.INSTANCE.load(out, r, msg -> kotlin.Unit.INSTANCE);
            for (final Element tbody : doc.select("tbody")) {
                for (final Element cell : tbody.select("*")) {
                    final String own = cell.ownText().trim();
                    // §4.4 repeated-label exception: an action label shared by >= 2 rows is UI, not data.
                    final boolean repeatedAction = cell.is("button, a, [role=button], [role=link]")
                            && tbody.select("tr").stream().filter(row -> row.select("button, a").stream()
                            .anyMatch(a -> a.text().equals(cell.text()))).count() >= 2;
                    assertTrue(own.isEmpty() || own.equals("···") || repeatedAction,
                            "tbody text '" + own + "' in " + r.getDomFile());
                }
            }
        }
        assertEquals(List.of("iframe#chart:blank"), records.get(1).getSkipped());

        final LocatorCoverageRun run = StrataGenerator.INSTANCE.generate(out);
        DefaultCoverageXmlWriter.INSTANCE.write(run, out.resolve("locator-coverage.xml"));
        final PageModel blotter = page(run, "/blotter/{id}");
        assertEquals("/blotter/{id}", blotter.getRouteKey());
        assertEquals(3, blotter.getVisits());
        assertTrue(blotter.getModules().stream().anyMatch(m -> m.getXpath().equals("//div[@id='export-dialog']")),
                "dialog module missing");
        assertTrue(blotter.getModules().stream().anyMatch(m -> m.getXpath().equals("//section[@id='allocations']")),
                "99001-only module missing");
        assertTrue(used(blotter, "//section[@data-testid='trade-grid']//button[@aria-label='Export']"), "Export not used");
        assertTrue(used(page(run, "/login"), "//form[@id='login-form']//input[@id='username']"), "username not used");
        verifyAllXpaths(out, run, ManifestReader.INSTANCE.read(out.resolve("manifest.jsonl"), m -> kotlin.Unit.INSTANCE));
        verifyOverheadBudget(out);
    }

    /**
     * CLAUDE.md §10 overhead budget, from {@code agent.log} timings (medians, so one slow CI command doesn't fail it):
     * a captured page costs at most quietMs + 200 ms, a command that changes nothing at most 50 ms, and recording a
     * newly used element at most 50 ms. The limits leave room for a loaded CI machine; typical values are far lower.
     */
    static void verifyOverheadBudget(final Path out) throws IOException {
        final java.util.regex.Pattern line = java.util.regex.Pattern.compile(" -> (.*) \\((\\d+) ms\\)$");
        final List<Integer> captured = new java.util.ArrayList<>();
        final List<Integer> unchanged = new java.util.ArrayList<>();
        final List<Integer> usage = new java.util.ArrayList<>();
        for (final String l : Files.readAllLines(out.resolve("agent.log"), StandardCharsets.UTF_8)) {
            final java.util.regex.Matcher m = line.matcher(l);
            if (!m.find()) {
                continue;
            }
            final String what = m.group(1);
            final int ms = Integer.parseInt(m.group(2));
            if (what.contains("captured seq")) {
                captured.add(ms);
            } else if (what.contains("no wait")) {
                unchanged.add(ms);
            } else if (what.startsWith("used ")) {
                usage.add(ms);
            }
        }
        System.out.printf("[strata-it] overhead medians: captured %d ms (max %d), unchanged %d ms, usage %d ms%n",
                median(captured), captured.stream().max(Integer::compare).orElse(0), median(unchanged), median(usage));
        final int quietMs = Integer.getInteger("strata.quietMs", 500);
        assertTrue(median(captured) <= quietMs + 200, "captured page median " + median(captured) + " ms");
        assertTrue(median(unchanged) <= 50, "unchanged command median " + median(unchanged) + " ms");
        assertTrue(median(usage) <= 50, "usage median " + median(usage) + " ms");
    }

    private static int median(final List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        final List<Integer> sorted = values.stream().sorted().collect(Collectors.toList());
        return sorted.get(sorted.size() / 2);
    }

    static PageModel page(final LocatorCoverageRun run, final String route) {
        return run.getPages().stream().filter(p -> p.getRouteKey().equals(route)).findFirst()
                .orElseThrow(() -> new AssertionError("no page " + route));
    }

    static boolean used(final PageModel page, final String absolute) {
        return page.getModules().stream().flatMap(m -> m.getElements().stream())
                .anyMatch(x -> x.getAbsolute().equals(absolute) && x.getUsed());
    }

    /** §10.4: every plain XPath re-verifies to exactly one match against a snapshot of its route. */
    static void verifyAllXpaths(final Path out, final LocatorCoverageRun run, final List<SnapshotRecord> records) {
        final JsoupXpathVerifier verifier = new JsoupXpathVerifier();
        for (final PageModel page : run.getPages()) {
            final List<Document> docs = records.stream()
                    .filter(r -> r.getRouteKey().equals(page.getRouteKey()))
                    .map(r -> StrataGenerator.INSTANCE.load(out, r, msg -> kotlin.Unit.INSTANCE))
                    .collect(Collectors.toList());
            for (final ModuleModel module : page.getModules()) {
                for (final XpathEntry x : module.getElements()) {
                    assertEquals(1, x.getMatches());
                    if (x.isTemplate()) {
                        continue; // templates are verified per instance by the generator and in core tests
                    }
                    assertTrue(docs.stream().anyMatch(d -> verifier.count(d, x.getAbsolute()) == 1),
                            "does not re-verify: " + x.getAbsolute());
                    for (final String id : DynamicIds.INSTANCE.idLiterals(x.getAbsolute())) {
                        assertFalse(DynamicIds.INSTANCE.isDynamic(id), "dynamic id in " + x.getAbsolute());
                    }
                }
            }
        }
    }
}
