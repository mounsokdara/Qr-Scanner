package com.localscanner.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTest {
    private fun record(text: String, title: String = "T") =
        ScanRecord("id1", text, "QR_CODE", 1000L, title, false)

    @Test fun csvNeutralisesFormulaInjection() {
        val csv = Transfer.toCsv(listOf(record("=HYPERLINK(\"http://evil\")"), record("@SUM(A1)", "+cmd")))
        assertTrue(csv.contains("'=HYPERLINK"))
        assertTrue(csv.contains("'@SUM(A1)"))
        assertTrue(csv.contains("'+cmd"))
        assertFalse(csv.lines().drop(1).any { it.split(',').last().startsWith("=") })
    }

    @Test fun csvRoundTripKeepsOriginalText() {
        val original = listOf(
            record("=1+1"),
            record("plain, with comma"),
            record("multi\nline", "-dash title"),
            record("'quoted")
        )
        val parsed = Transfer.parseCsv(Transfer.toCsv(original))
        assertEquals(original.map { it.text }, parsed.map { it.text })
        assertEquals(original.map { it.title }, parsed.map { it.title })
    }
}
