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

    @Test
    void fixtureFlow() throws IOException {
        final long started = System.nanoTime();
        final ChromeOptions options = new ChromeOptions()
                .addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--window-size=1280,900");
        final WebDriver driver = new ChromeDriver(options);
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
            verifyCapture(Path.of(System.getProperty("strata.out")));
        }
    }

    private static void verifyCapture(final Path out) throws IOException {
        final List<String> problems = new java.util.ArrayList<>();
        final List<SnapshotRecord> records = ManifestReader.INSTANCE.read(out.resolve("manifest.jsonl"), msg -> {
            problems.add(msg);
            return kotlin.Unit.INSTANCE;
        });
        assertTrue(problems.isEmpty(), problems.toString());

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
                    assertTrue(own.isEmpty() || own.equals("···"), "tbody text '" + own + "' in " + r.getDomFile());
                }
            }
        }
        assertEquals(List.of("iframe#chart"), records.get(1).getSkipped());

        final LocatorCoverageRun run = StrataGenerator.INSTANCE.generate(out);
        DefaultCoverageXmlWriter.INSTANCE.write(run, out.resolve("locator-coverage.xml"));
        assertEquals(2, run.getPages().size());
        final PageModel blotter = run.getPages().get(0);
        assertEquals("/blotter/{id}", blotter.getRouteKey());
        assertEquals(3, blotter.getVisits());
        assertTrue(blotter.getModules().stream().anyMatch(m -> m.getXpath().equals("//div[@id='export-dialog']")),
                "dialog module missing");
        assertTrue(blotter.getModules().stream().anyMatch(m -> m.getXpath().equals("//section[@id='allocations']")),
                "99001-only module missing");

        // §10.4: every XPath re-verifies to exactly one match against a snapshot of its route.
        final JsoupXpathVerifier verifier = new JsoupXpathVerifier();
        for (final PageModel page : run.getPages()) {
            final List<Document> docs = records.stream()
                    .filter(r -> r.getRouteKey().equals(page.getRouteKey()))
                    .map(r -> StrataGenerator.INSTANCE.load(out, r, msg -> kotlin.Unit.INSTANCE))
                    .collect(Collectors.toList());
            for (final ModuleModel module : page.getModules()) {
                for (final XpathEntry x : module.getElements()) {
                    assertEquals(1, x.getMatches());
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
