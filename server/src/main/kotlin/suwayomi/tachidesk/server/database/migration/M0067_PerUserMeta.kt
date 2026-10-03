package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/**
 * The settings (global meta), the per manga/chapter meta and the source meta belong to one account each.
 * Everything that exists so far keeps working: the default value assigns it to user 1, the first account.
 */
@Suppress("ClassName", "unused")
class M0067_PerUserMeta : SQLMigration() {
    private fun migrateTable(
        table: String,
        refColumn: String? = null,
    ): String {
        val columns = listOfNotNull("USER_ID", refColumn, "META_KEY").joinToString(", ")

        return """
            ALTER TABLE $table ADD COLUMN IF NOT EXISTS USER_ID INT NOT NULL DEFAULT 1;
            ALTER TABLE $table DROP CONSTRAINT IF EXISTS UC_$table;
            ALTER TABLE $table ADD CONSTRAINT UC_${table}_USER UNIQUE ($columns);
            """.trimIndent()
    }

    // M0049 made (ref, key) unique, now every account may have its own value for the same key. Categories made by a
    // restore or a sync had no owner and showed up for every account, they belong to the first account.
    override val sql: String by lazy {
        "UPDATE CATEGORY SET USER_ID = 1 WHERE USER_ID IS NULL AND ID <> 0;" +
            migrateTable("GLOBALMETA") +
            migrateTable("MANGAMETA", "MANGA_REF") +
            migrateTable("CHAPTERMETA", "CHAPTER_REF") +
            migrateTable("SOURCEMETA", "SOURCE_REF")
    }
}
