package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.ModuleKind
import dev.strata.core.model.Stability
import dev.strata.core.model.Strategy
import dev.strata.core.model.XpathEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each strategy (§5.6) has a positive case and a fall-through case where it is not unique. */
class XpathGeneratorTest {

    private fun generate(body: String, target: String): XpathEntry? {
        val doc = Jsoup.parse("<html><body><form id='f'>$body</form></body></html>")
        val module = doc.getElementById("f")!!
        val verifier = JsoupXpathVerifier()
        val generator = DefaultXpathGenerator(verifier) { if (it === module) "//form[@id='f']" else null }
        return generator.generate(doc.selectFirst(target)!!, module)
    }

    private fun assertStrategy(expected: Strategy, value: String, entry: XpathEntry?) {
        assertNotNull(entry)
        assertEquals(expected, entry.strategy)
        assertEquals(value, entry.value)
        assertEquals(1, entry.matches)
    }

    @Test
    fun testId() {
        assertStrategy(Strategy.TESTID, ".//*[@data-testid='save']",
            generate("<button data-testid='save' id='s'>Save</button>", "button"))
        assertStrategy(Strategy.TESTID, ".//*[@data-qa='go']", generate("<button data-qa='go'>Go</button>", "button"))
    }

    @Test
    fun testIdFallsThroughWhenDuplicated() {
        assertStrategy(Strategy.ID, ".//button[@id='a']",
            generate("<button data-testid='x' id='a'>A</button><button data-testid='x' id='b'>B</button>", "#a"))
    }

    @Test
    fun id() {
        assertStrategy(Strategy.ID, ".//input[@id='username']", generate("<input id='username' name='u'>", "input"))
    }

    @Test
    fun dynamicIdIsNeverUsed() {
        assertStrategy(Strategy.NAME, ".//input[@name='u']", generate("<input id='mat-input-7' name='u'>", "input"))
    }

    @Test
    fun name() {
        assertStrategy(Strategy.NAME, ".//select[@name='account']", generate("<select name='account'></select>", "select"))
    }

    @Test
    fun nameFallsThroughWhenDuplicated() {
        assertStrategy(Strategy.ARIA_LABEL, ".//input[@aria-label='First']",
            generate("<input name='q' aria-label='First'><input name='q' aria-label='Second'>", "input"))
    }

    @Test
    fun ariaLabel() {
        assertStrategy(Strategy.ARIA_LABEL, ".//button[@aria-label='Export']",
            generate("<button aria-label='Export'><svg></svg></button>", "button"))
    }

    @Test
    fun ariaLabelFallsThroughWhenDuplicated() {
        assertStrategy(Strategy.TEXT, ".//button[normalize-space()='One']",
            generate("<button aria-label='Go'>One</button><button aria-label='Go'>Two</button>", "button"))
    }

    @Test
    fun labelForWhenIdIsNotUnique() {
        val entry = generate(
            "<label for='qty'>Quantity</label><input id='qty'><span><input id='qty'></span>",
            "input",
        )
        assertStrategy(Strategy.LABEL_FOR, ".//label[normalize-space()='Quantity']/following::input[1]", entry)
    }

    @Test
    fun labelForWithDynamicIdUsesTheForAttributeWithoutTheId() {
        val entry = generate("<label for='mat-input-7'>Trade date</label><input id='mat-input-7' type='date'>", "input")
        assertStrategy(Strategy.LABEL_FOR, ".//input[@id=//label[normalize-space()='Trade date']/@for]", entry)
        assertEquals(Stability.MEDIUM, entry!!.stability)
        assertTrue(DynamicIds.idLiterals(entry.value).isEmpty())
    }

    @Test
    fun followingLabelIsLowStability() {
        val entry = generate("<label for='qty'>Quantity</label><input id='qty'><span><input id='qty'></span>", "input")
        assertEquals(Stability.LOW, entry!!.stability)
    }

    @Test
    fun labelWrappingInput() {
        assertStrategy(Strategy.LABEL_FOR, ".//label[normalize-space()='Remember me']//input",
            generate("<label><input type='checkbox'> Remember me</label>", "input"))
    }

    @Test
    fun labelFallsThroughWhenLabelTextRepeats() {
        assertStrategy(Strategy.PLACEHOLDER, ".//input[@placeholder='Min']",
            generate(
                "<label>Range</label><input id='mat-input-1' placeholder='Min'>" +
                    "<label>Range</label><input id='mat-input-2' placeholder='Max'>",
                "input",
            ),
        )
    }

    @Test
    fun text() {
        assertStrategy(Strategy.TEXT, ".//button[normalize-space()='Sign in']",
            generate("<button type='submit'>  Sign   in </button>", "button"))
        assertStrategy(Strategy.TEXT, ".//a[normalize-space()='Reports']", generate("<a href='/r'>Reports</a>", "a"))
    }

    @Test
    fun textIsNotUsedForRedactedOrTruncatedText() {
        assertStrategy(Strategy.POSITIONAL, "(.//button)[1]", generate("<button>···</button><button>···</button>", "button"))
        val long = "x".repeat(60)
        assertStrategy(Strategy.POSITIONAL, "(.//button)[1]", generate("<button>$long</button>", "button"))
    }

    @Test
    fun textWithApostropheUsesConcat() {
        assertStrategy(Strategy.TEXT, ".//button[normalize-space()=concat('Don', \"'\", 't save')]",
            generate("<button>Don't save</button>", "button"))
    }

    @Test
    fun placeholder() {
        assertStrategy(Strategy.PLACEHOLDER, ".//input[@placeholder='Search']", generate("<input placeholder='Search'>", "input"))
    }

    @Test
    fun positionalIsTheLastResort() {
        val entry = generate("<button>View</button><button>View</button>", "button:nth-of-type(2)")
        assertStrategy(Strategy.POSITIONAL, "(.//button)[2]", entry)
        assertEquals("(//form[@id='f']//button)[2]", entry!!.absolute)
    }

    @Test
    fun absoluteIsModulePlusTail() {
        val entry = generate("<select name='account'></select>", "select")!!
        assertEquals("//form[@id='f']//select[@name='account']", entry.absolute)
    }

    @Test
    fun typesAndNames() {
        val cases = listOf(
            "<button aria-label='Export'></button>" to ("exportButton" to ElementType.BUTTON),
            "<select name='account'></select>" to ("accountDropdown" to ElementType.DROPDOWN),
            "<label for='mat-input-7'>Trade date</label><input id='mat-input-7'>" to ("tradeDateInput" to ElementType.TEXT_INPUT),
            "<input type='checkbox' name='agree'>" to ("agreeCheckbox" to ElementType.CHECKBOX),
            "<input type='radio' name='side'>" to ("sideRadio" to ElementType.RADIO),
            "<textarea name='notes'></textarea>" to ("notesTextarea" to ElementType.TEXTAREA),
            "<div role='tab' tabindex='0'>Open</div>" to ("openTab" to ElementType.TAB),
            "<a href='/x'>Reports</a>" to ("reportsLink" to ElementType.LINK),
            "<a>Help</a>" to ("helpLink" to ElementType.LINK),
            "<input type='password' id='pw'>" to ("pwInput" to ElementType.PASSWORD),
            "<button aria-haspopup='listbox'>Currency</button>" to ("currencyDropdown" to ElementType.DROPDOWN),
            "<input type='submit' name='go'>" to ("goButton" to ElementType.BUTTON),
            "<mat-select formcontrolname='ccy' aria-label='Currency'></mat-select>" to ("currencyDropdown" to ElementType.DROPDOWN),
        )
        for ((html, expected) in cases) {
            val entry = generate(html, "form > :not(label)")
            assertNotNull(entry, html)
            assertEquals(expected, entry.name to entry.type, html)
        }
    }

    @Test
    fun noStrategyMeansNull() {
        val doc = Jsoup.parse("<body><form id='f'><button>A</button></form></body>")
        val module = doc.getElementById("f")!!
        // A module the generator cannot address cannot host entries.
        val generator = DefaultXpathGenerator(JsoupXpathVerifier()) { null }
        assertNull(generator.generate(doc.selectFirst("button")!!, module))
    }
}

class VerifierTest {
    private val doc: Document = Jsoup.parse(
        "<body><form id='a'><button>Go</button></form><form id='b'><button>Go</button></form></body>",
    )

    @Test
    fun countsMatches() {
        val verifier = JsoupXpathVerifier()
        assertEquals(2, verifier.count(doc, "//button[normalize-space()='Go']"))
        assertEquals(1, verifier.count(doc, "//form[@id='a']//button"))
        assertEquals(0, verifier.count(doc, "//nope"))
        assertEquals(0, verifier.count(doc, "//button[")) // malformed
    }

    @Test
    fun generatorRejectsMultiMatch() {
        val verifier = JsoupXpathVerifier()
        val module: Element = doc.body()
        val generator = DefaultXpathGenerator(verifier) { "//body" }
        val entry = generator.generate(doc.selectFirst("button")!!, module)!!
        // TEXT matches 2 within <body>, so it must fall through to POSITIONAL.
        assertEquals(Strategy.POSITIONAL, entry.strategy)
        assertEquals(1, verifier.count(doc, entry.absolute))
    }

    @Test
    fun relativeCountsAreScopedToTheModule() {
        val verifier = JsoupXpathVerifier()
        assertEquals(1, verifier.countWithin(doc.getElementById("a")!!, ".//button"))
    }
}

class ModuleDetectorTest {
    @Test
    fun kindsAndXpathPriority() {
        val doc = Jsoup.parse(
            """
            <body>
              <nav aria-label="Main"><a href="/">Home</a></nav>
              <form id="mat-form-3"><fieldset><legend>Filters</legend><input name="q"></fieldset></form>
              <div data-testid="grid"><button>Go</button></div>
              <section><h2>Allocations</h2><button>Allocate</button></section>
              <div role="dialog" id="confirm"><button>OK</button></div>
              <section><button>Lost</button></section>
            </body>
            """.trimIndent(),
        )
        val verifier = JsoupXpathVerifier()
        val xpaths = ModuleXpaths(verifier)
        val resolved = DefaultModuleDetector.detect(doc).map { (el, kind) -> kind to xpaths.resolve(doc, el, kind) }
        assertEquals(
            listOf(
                ModuleKind.PAGE_ROOT to "//body",
                ModuleKind.NAV to "//nav[@aria-label='Main']",
                // The legend belongs to the fieldset, so it can't name the form; the form merges into its parent.
                ModuleKind.FORM to null,
                ModuleKind.GROUP to "//fieldset[.//legend[normalize-space()='Filters']]",
                ModuleKind.ANCHORED to "//div[@data-testid='grid']",
                ModuleKind.SECTION to "//section[.//h2[normalize-space()='Allocations']]",
                ModuleKind.DIALOG to "//div[@id='confirm']",
                ModuleKind.SECTION to null,
            ),
            resolved.map { (kind, m) -> kind to m?.xpath },
        )
        assertEquals(listOf("Page", "Main", "Filters", "Grid", "Allocations", "ConfirmDialog"),
            resolved.mapNotNull { it.second?.name })
    }

    @Test
    fun landmarksMenusAndGroups() {
        val doc = Jsoup.parse(
            "<body><header><a href='/'>Home</a></header><main><div role='tablist'><button role='tab'>A</button></div>" +
                "<ul role='menu' aria-label='Row actions'><li role='menuitem'>Edit</li></ul></main>" +
                "<div class='cdk-overlay-pane'><div role='listbox'><mat-option role='option'>x</mat-option></div></div></body>",
        )
        val xpaths = ModuleXpaths(JsoupXpathVerifier())
        val resolved = DefaultModuleDetector.detect(doc).mapNotNull { (el, kind) -> xpaths.resolve(doc, el, kind) }
        assertEquals(
            listOf(
                "Page" to "//body",
                "Header" to "//header",
                "Main" to "//main",
                "Tablist" to "//div[@role='tablist']",
                "RowActionsMenu" to "//ul[@aria-label='Row actions']",
                "OverlayMenu" to "//div[contains(concat(' ', normalize-space(@class), ' '), ' cdk-overlay-pane ')]",
                "ListboxMenu" to "//div[@role='listbox']",
            ),
            resolved.map { it.name to it.xpath },
        )
    }

    @Test
    fun unresolvedModuleMergesIntoParent() {
        val doc = Jsoup.parse("<body><section><button>Lost</button></section></body>")
        val modules = StrataGenerator.analyze(doc)
        assertEquals(listOf("//body"), modules.map { it.first.xpath })
        val entry = modules.single().second.single()
        assertEquals(Strategy.TEXT, entry.strategy)
        assertEquals("//body//button[normalize-space()='Lost']", entry.absolute)
    }

    @Test
    fun hiddenElementsAreSkipped() {
        val doc = Jsoup.parse("<body><div aria-hidden='true'><button>A</button></div><button hidden>B</button><button>C</button></body>")
        assertEquals(listOf("C"), Interactables.find(doc.body()).map { it.text() })
    }
}
