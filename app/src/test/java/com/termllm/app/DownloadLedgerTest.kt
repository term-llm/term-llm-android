package com.termllm.app

import com.termllm.app.listen.DownloadLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/** The daily byte ledger behind the "MB today" figure. */
class DownloadLedgerTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var ledger: DownloadLedger

    @Before
    fun setUp() {
        dir = createTempDir(prefix = "listen-ledger")
        file = File(dir, "listen-network.json")
        ledger = DownloadLedger(file)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun bytesAccumulateForTheDay() {
        ledger.record(1024)
        ledger.record(2048, transfers = 2)
        assertEquals(3072L, ledger.bytesToday())
        assertEquals(3L, ledger.transfersOnDay(day()))
    }

    @Test
    fun theLedgerSurvivesRestart() {
        ledger.record(4096)
        val restarted = DownloadLedger(file)
        assertEquals(4096L, restarted.bytesToday())
        assertEquals(4096L, restarted.record(0))
        assertEquals(4096L, restarted.bytesToday())
    }

    @Test
    fun daysAreKeptSeparately() {
        ledger.record(500, at = 0L)
        ledger.record(700, at = System.currentTimeMillis())
        assertEquals(700L, ledger.bytesToday())
        assertEquals(500L, ledger.bytesOnDay("1970-01-01"))
    }

    @Test
    fun aCorruptLedgerIsTreatedAsEmptyRatherThanCrashing() {
        file.writeText("not json at all")
        assertEquals(0L, ledger.bytesToday())
        ledger.record(128)
        assertEquals(128L, ledger.bytesToday())
    }

    @Test
    fun negativeInputsAreIgnored() {
        ledger.record(-1024, transfers = -1)
        assertEquals(0L, ledger.bytesToday())
        ledger.record(64)
        assertEquals(64L, ledger.bytesToday())
    }

    private fun day(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date())
}
