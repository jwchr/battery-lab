package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder backups keep the newest two dated copies and never touch other files. */
class DriveBackupFolderTest {
    @Test fun keepsTheNewestTwo() {
        val names = listOf("battery-lab-20261115-120000.csv.gz", "battery-lab-20261117-000000.csv.gz",
            "battery-lab-20261116-120000.csv.gz", "battery-lab-20261114-120000.csv.gz")
        assertEquals(listOf("battery-lab-20261115-120000.csv.gz", "battery-lab-20261114-120000.csv.gz"),
            DriveBackup.toPrune(names))
    }

    @Test fun neverPrunesFilesItDidNotName() {
        val names = listOf("battery-lab-history.csv.gz", "notes.txt", "battery-lab-20261117-000000.csv.gz",
            "battery-lab-20261116-000000 (1).csv.gz", "battery-lab-20261115-000000.csv.gz")
        assertEquals(listOf("battery-lab-20261115-000000.csv.gz"), DriveBackup.toPrune(names, keep = 1))
    }

    @Test fun fewerThanKeepPrunesNothing() {
        assertTrue(DriveBackup.toPrune(listOf("battery-lab-20261117-000000.csv.gz")).isEmpty())
    }

    @Test fun fileNamesSortInTimeOrderAndMatchThePruneRule() {
        val a = DriveBackup.fileName(1_700_000_000_000L); val b = DriveBackup.fileName(1_700_000_060_000L)
        assertTrue(a < b)
        assertEquals(listOf(a), DriveBackup.toPrune(listOf(a, b), keep = 1))
    }
}
