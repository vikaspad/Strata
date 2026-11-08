package dev.strata.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteNormalizerTest {
    @ParameterizedTest
    @CsvSource(
        "/blotter/48213?x=1, /blotter/{id}",
        "http://localhost:8080/blotter/48213?x=1, /blotter/{id}",
        "https://h/blotter/99001#export, /blotter/{id}",
        "http://h/login, /login",
        "http://h/, /",
        "http://h, /",
        "'', /",
        "http://h/orders/550e8400-e29b-41d4-a716-446655440000/edit, /orders/{id}/edit",
        "http://h/trade/AB12CD34EF, /trade/{id}",
        "http://h/trade/AB12CD3, /trade/AB12CD3",
        "http://h/trade/ab12cd34ef, /trade/ab12cd34ef",
        "http://h/blob/0123456789abcdef01, /blob/{id}",
        "http://h/Settings/Profile/, /Settings/Profile",
        "http://h/v2/users/7/orders/12, /v2/users/{id}/orders/{id}",
    )
    fun normalizes(url: String, expected: String) {
        assertEquals(expected, DefaultRouteNormalizer.normalize(url))
    }

    @Test
    fun fileSafe() {
        assertEquals("blotter_id", DefaultRouteNormalizer.fileSafe("/blotter/{id}"))
        assertEquals("root", DefaultRouteNormalizer.fileSafe("/"))
        assertEquals("orders_id_edit", DefaultRouteNormalizer.fileSafe("/orders/{id}/edit"))
    }
}

class DynamicIdsTest {
    @ParameterizedTest
    @CsvSource(
        "ps-7731, true",
        "row-48213-1, true",
        "550e8400-e29b, true",
        "ember123, true",
        "mat-input-7, true",
        "mat-select-value-3, true",
        "react-select-2, true",
        "cdk-overlay-0, true",
        ":r1a:, true",
        "ext-gen42, true",
        "ui-id-3, true",
        "username, false",
        "blotter-filter, false",
        "tab-open, false",
        "row-123, false",
        "mat-input, false",
        "embers, false",
        "deadbeef, false",
    )
    fun classifies(id: String, dynamic: Boolean) {
        assertEquals(dynamic, DynamicIds.isDynamic(id))
    }

    @Test
    fun blankIsNotStable() {
        assertFalse(DynamicIds.isStable(""))
        assertFalse(DynamicIds.isStable(null))
        assertTrue(DynamicIds.isStable("account"))
    }
}

class NamesTest {
    @Test
    fun cases() {
        assertEquals("TradeBlotter", Names.pascal("Trade Blotter"))
        assertEquals("BlotterId", Names.pascal("/blotter/{id}"))
        assertEquals("BlotterFilter", Names.pascal("blotter-filter"))
        assertEquals("tradeDate", Names.camel("Trade date"))
        assertEquals("tradeDate", Names.camel("tradeDate"))
        assertEquals("xmlExport", Names.camel("XMLExport"))
        assertEquals("N2faCode", Names.pascal("2fa code"))
        assertEquals("exportButton", Names.withSuffix("exportButton", "Button"))
        assertEquals("exportButton", Names.withSuffix("export", "Button"))
        assertEquals("button", Names.withSuffix("button", "Button"))
        assertEquals("BlotterFilterForm", Names.withSuffix("BlotterFilter", "Form"))
    }

    @Test
    fun dedupe() {
        assertEquals(listOf("a", "a2", "b", "a3"), Names.dedupe(listOf("a", "a", "b", "a")))
        assertEquals(listOf("a", "a2", "a3"), Names.dedupe(listOf("a", "a2", "a")))
    }
}

class XpathLiteralTest {
    @Test
    fun quoting() {
        assertEquals("'Export'", xpathLiteral("Export"))
        assertEquals("concat('Trader', \"'\", 's desk')", xpathLiteral("Trader's desk"))
        assertEquals("concat(\"'\", 'quoted', \"'\")", xpathLiteral("'quoted'"))
    }

    @Test
    fun concatEvaluates() {
        val doc = org.jsoup.Jsoup.parse("<body><button>Trader's desk</button><button>'quoted'</button></body>")
        val verifier = JsoupXpathVerifier()
        assertEquals(1, verifier.count(doc, "//button[normalize-space()=${xpathLiteral("Trader's desk")}]"))
        assertEquals(1, verifier.count(doc, "//button[normalize-space()=${xpathLiteral("'quoted'")}]"))
    }
}
