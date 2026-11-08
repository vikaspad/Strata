package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.ModuleKind
import dev.strata.core.model.Stability
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Repeated cards (CLAUDE.md §5.13) and the form module fallbacks (§5.3). */
class CardsTest {

    private fun card(id: Int, price: String) =
        "<div class='col-sm-4'><div class='product-wrapper'>" +
            "<div class='info'><img src='/p/$id' alt='product photo'><h2>$price</h2><p>···</p>" +
            "<a data-product-id='$id' class='btn add-to-cart'><i class='fa fa-shopping-cart'></i>Add to cart</a></div>" +
            "<div class='overlay'><h2>$price</h2><a data-product-id='$id' class='btn add-to-cart'><i class='fa fa-shopping-cart'></i>Add to cart</a></div>" +
            "<a href='/product/$id'>View Product</a></div></div>"

    private val products = "<div class='features'><h2 class='title'>All Products</h2>" +
        card(1, "Rs. 500") + card(2, "Rs. 400") + card(3, "Rs. 500") + "</div>"

    private fun analyze(html: String, used: (org.jsoup.nodes.Document) -> Set<org.jsoup.nodes.Element> = { emptySet() }): SnapshotAnalysis {
        val doc = Jsoup.parse("<html><body><main>$html</main></body></html>")
        return SnapshotAnalyzer().analyze(doc, used(doc))
    }

    @Test
    fun repeatedCardsBecomeOneTemplatePerControl() {
        val entries = analyze(products).modules.flatMap { it.second }
        val cards = ".//div[${hasClass("features")}]/div[${hasClass("col-sm-4")}][{n}]"
        val byValue = entries.associateBy { it.value }
        assertEquals("productCard", byValue.getValue(cards).name)
        assertEquals(3, byValue.getValue(cards).instances)
        assertEquals("addToCartLink", byValue.getValue("$cards//div[${hasClass("info")}]//a[normalize-space()='Add to cart']").name)
        assertEquals(ElementType.LINK, byValue.getValue("$cards//div[${hasClass("overlay")}]//a[normalize-space()='Add to cart']").type)
        assertEquals("viewProductLink", byValue.getValue("$cards//a[normalize-space()='View Product']").name)
        assertEquals(ElementType.ICON, byValue.getValue("$cards//img[@alt='product photo']").type)
        // Prices differ between cards, so they are data: headings are found by structure, never by their text.
        assertTrue(entries.none { it.value.contains("Rs. 500") }, entries.map { it.value }.toString())
        assertTrue(byValue.containsKey("$cards//div[${hasClass("info")}]//h2"))
        assertTrue(entries.filter { it.value.startsWith(cards) }.all { it.stability == Stability.MEDIUM && it.instances == 3 })
        assertTrue(entries.none { it.strategy == dev.strata.core.model.Strategy.POSITIONAL }, entries.toString())
    }

    @Test
    fun usageInAnyCardMarksTheTemplate() {
        val a = analyze(products) { doc -> setOf(doc.select("a.add-to-cart")[2]) }
        val used = a.modules.flatMap { it.second }.filter { it.used }
        assertEquals(listOf("addToCartLink"), used.map { it.name })
        assertTrue(used.single().value.contains("{n}"))
    }

    @Test
    fun siblingsWithoutASharedLabelKeepTheirOwnEntries() {
        val panels = "<div class='panels'>" + listOf("Women", "Men", "Kids").joinToString("") {
            "<div class='panel'><h4><a href='#$it'><i class='fa fa-plus'></i>$it</a></h4></div>"
        } + "</div>"
        val entries = analyze(panels).modules.flatMap { it.second }
        assertTrue(entries.none { it.isTemplate }, entries.map { it.value }.toString())
        assertTrue(entries.any { it.value == ".//a[normalize-space()='Women']" })
    }

    @Test
    fun cardsOnSeveralParentsShareOneTemplate() {
        val slide = { a: Int, b: Int -> "<div class='item'>${card(a, "x")}${card(b, "y")}</div>" }
        val carousel = "<div class='carousel'><div class='item active'>${card(1, "x")}${card(2, "y")}</div>${slide(3, 4)}</div>"
        val entries = analyze(carousel).modules.flatMap { it.second }
        val viewProduct = entries.single { it.name == "viewProductLink" }
        assertEquals("(.//div[${hasClass("item")}]/div[${hasClass("col-sm-4")}])[{n}]//a[normalize-space()='View Product']", viewProduct.value)
        assertEquals(4, viewProduct.instances)
    }

    @Test
    fun formsWithoutIdsUseNameActionOrClass() {
        val html = "<form action='/login' method='POST'><input name='email'><button>Login</button></form>" +
            "<form action='/signup'><input name='name'><button>Signup</button></form>" +
            "<form class='searchform'><input type='email' id='subscribe_email'><button id='subscribe'>Go</button></form>" +
            "<form action='/orders/48213/edit' class='edit'><input name='qty'></form>" +
            "<form name='filters'><input name='q'></form>"
        val forms = analyze(html).modules.map { it.first }.filter { it.kind == ModuleKind.FORM }
        assertEquals(
            listOf(
                "//form[@action='/login']" to "LoginForm",
                "//form[@action='/signup']" to "SignupForm",
                "//form[${hasClass("searchform")}]" to "SearchForm",
                // An action with a record id is data, so the class wins.
                "//form[${hasClass("edit")}]" to "EditForm",
                "//form[@name='filters']" to "FiltersForm",
            ),
            forms.map { it.xpath to it.name },
        )
    }
}
