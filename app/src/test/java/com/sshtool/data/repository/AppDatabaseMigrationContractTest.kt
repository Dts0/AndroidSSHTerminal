package com.sshtool.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM contract coverage for the exact SQL executed by MIGRATION_1_2. */
class AppDatabaseMigrationContractTest {

    @Test
    fun migration1To2_createsRuleTableAndHostIndexMatchingExportedSchema() {
        val statements = mutableListOf<String>()

        migrateFrom1To2 { sql -> statements += sql }

        assertEquals(2, statements.size)
        val tableSql = statements[0].normalizedSql()
        val indexSql = statements[1].normalizedSql()

        assertEquals(
            "CREATE TABLE IF NOT EXISTS `port_forward_rules` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`hostId` INTEGER NOT NULL, " +
                "`direction` TEXT NOT NULL, " +
                "`bindAddress` TEXT NOT NULL, " +
                "`listenPort` INTEGER NOT NULL, " +
                "`targetHost` TEXT NOT NULL, " +
                "`targetPort` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
            tableSql
        )
        assertEquals(
            "CREATE INDEX IF NOT EXISTS `index_port_forward_rules_hostId` " +
                "ON `port_forward_rules` (`hostId`)",
            indexSql
        )
    }

    private fun String.normalizedSql(): String = trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\(\\s+"), "(")
        .replace(Regex("\\s+\\)"), ")")
}
