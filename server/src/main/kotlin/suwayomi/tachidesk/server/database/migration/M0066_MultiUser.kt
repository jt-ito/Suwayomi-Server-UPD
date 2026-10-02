package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.serverConfig

@Suppress("ClassName", "unused")
class M0066_MultiUser : SQLMigration() {
    override val sql by lazy {
        when (serverConfig.databaseType.value) {
            DatabaseType.H2 -> h2Query()
            DatabaseType.POSTGRESQL -> postgresQuery()
        }
    }

    private fun h2Query(): String {
        val now = System.currentTimeMillis()

        return """
            CREATE TABLE IF NOT EXISTS user_account (
                id INT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(64) NOT NULL UNIQUE,
                password_hash VARCHAR(256) NOT NULL,
                salt VARCHAR(64) NOT NULL,
                user_role VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
                created_at BIGINT NOT NULL DEFAULT 0,
                last_login_at BIGINT NOT NULL DEFAULT 0
            );

            ALTER TABLE user_account ADD COLUMN IF NOT EXISTS user_role VARCHAR(32) NOT NULL DEFAULT 'ADMIN';

            CREATE TABLE IF NOT EXISTS user_manga (
                id INT AUTO_INCREMENT PRIMARY KEY,
                user_id INT NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
                manga_id INT NOT NULL REFERENCES manga(id) ON DELETE CASCADE,
                in_library BOOLEAN NOT NULL DEFAULT FALSE,
                in_library_at BIGINT NOT NULL DEFAULT 0,
                viewer INT NOT NULL DEFAULT 0,
                viewer_flags INT NULL,
                chapter_flags INT NOT NULL DEFAULT 0,
                CONSTRAINT uq_user_manga UNIQUE (user_id, manga_id)
            );

            CREATE TABLE IF NOT EXISTS user_chapter (
                id INT AUTO_INCREMENT PRIMARY KEY,
                user_id INT NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
                chapter_id INT NOT NULL REFERENCES chapter(id) ON DELETE CASCADE,
                read BOOLEAN NOT NULL DEFAULT FALSE,
                bookmark BOOLEAN NOT NULL DEFAULT FALSE,
                last_page_read INT NOT NULL DEFAULT 0,
                last_read_at BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT uq_user_chapter UNIQUE (user_id, chapter_id)
            );

            ALTER TABLE category ADD COLUMN IF NOT EXISTS user_id INT NULL REFERENCES user_account(id) ON DELETE CASCADE;
            ALTER TABLE trackrecord ADD COLUMN IF NOT EXISTS user_id INT NULL REFERENCES user_account(id) ON DELETE CASCADE;

            -- Seed User #1 (Admin) if no user exists. The empty password hash means "not claimed yet": nobody can log in
            -- until SetupManager sets the credentials (configured at startup or entered on /setup).
            INSERT INTO user_account (id, username, password_hash, salt, user_role, created_at, last_login_at)
            SELECT 1, 'admin', '', '', 'ADMIN', $now, 0
            WHERE NOT EXISTS (SELECT 1 FROM user_account WHERE id = 1);

            -- Migrate existing manga in_library to user #1
            INSERT INTO user_manga (user_id, manga_id, in_library, in_library_at, viewer, viewer_flags, chapter_flags)
            SELECT 1, m.id, m.in_library, m.in_library_at, m.viewer, m.viewer_flags, m.chapter_flags
            FROM manga m
            WHERE m.in_library = TRUE
              AND NOT EXISTS (SELECT 1 FROM user_manga um WHERE um.user_id = 1 AND um.manga_id = m.id);

            -- Migrate existing chapter read/bookmark/page progress to user #1
            INSERT INTO user_chapter (user_id, chapter_id, read, bookmark, last_page_read, last_read_at)
            SELECT 1, c.id, c.read, c.bookmark, c.last_page_read, c.last_read_at
            FROM chapter c
            WHERE (c.read = TRUE OR c.bookmark = TRUE OR c.last_page_read > 0 OR c.last_read_at > 0)
              AND NOT EXISTS (SELECT 1 FROM user_chapter uc WHERE uc.user_id = 1 AND uc.chapter_id = c.id);

            UPDATE category SET user_id = 1 WHERE user_id IS NULL;
            UPDATE trackrecord SET user_id = 1 WHERE user_id IS NULL;
        """.trimIndent()
    }

    private fun postgresQuery(): String {
        val now = System.currentTimeMillis()

        return """
            CREATE TABLE IF NOT EXISTS user_account (
                id SERIAL PRIMARY KEY,
                username VARCHAR(64) NOT NULL UNIQUE,
                password_hash VARCHAR(256) NOT NULL,
                salt VARCHAR(64) NOT NULL,
                user_role VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
                created_at BIGINT NOT NULL DEFAULT 0,
                last_login_at BIGINT NOT NULL DEFAULT 0
            );

            ALTER TABLE user_account ADD COLUMN IF NOT EXISTS user_role VARCHAR(32) NOT NULL DEFAULT 'ADMIN';

            CREATE TABLE IF NOT EXISTS user_manga (
                id SERIAL PRIMARY KEY,
                user_id INT NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
                manga_id INT NOT NULL REFERENCES manga(id) ON DELETE CASCADE,
                in_library BOOLEAN NOT NULL DEFAULT FALSE,
                in_library_at BIGINT NOT NULL DEFAULT 0,
                viewer INT NOT NULL DEFAULT 0,
                viewer_flags INT NULL,
                chapter_flags INT NOT NULL DEFAULT 0,
                CONSTRAINT uq_user_manga UNIQUE (user_id, manga_id)
            );

            CREATE TABLE IF NOT EXISTS user_chapter (
                id SERIAL PRIMARY KEY,
                user_id INT NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
                chapter_id INT NOT NULL REFERENCES chapter(id) ON DELETE CASCADE,
                read BOOLEAN NOT NULL DEFAULT FALSE,
                bookmark BOOLEAN NOT NULL DEFAULT FALSE,
                last_page_read INT NOT NULL DEFAULT 0,
                last_read_at BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT uq_user_chapter UNIQUE (user_id, chapter_id)
            );

            ALTER TABLE category ADD COLUMN IF NOT EXISTS user_id INT NULL REFERENCES user_account(id) ON DELETE CASCADE;
            ALTER TABLE trackrecord ADD COLUMN IF NOT EXISTS user_id INT NULL REFERENCES user_account(id) ON DELETE CASCADE;

            -- Seed User #1 (Admin) if no user exists. The empty password hash means "not claimed yet": nobody can log in
            -- until SetupManager sets the credentials (configured at startup or entered on /setup).
            INSERT INTO user_account (id, username, password_hash, salt, user_role, created_at, last_login_at)
            SELECT 1, 'admin', '', '', 'ADMIN', $now, 0
            WHERE NOT EXISTS (SELECT 1 FROM user_account WHERE id = 1);

            -- Reset sequence for user_account. Wrapped in a DO block (not a bare SELECT) because the migration
            -- runner executes each statement expecting no result set, which a top-level SELECT always returns.
            DO $$ BEGIN
                PERFORM setval(pg_get_serial_sequence('user_account', 'id'), COALESCE((SELECT MAX(id) FROM user_account), 1));
            END $$;

            -- Migrate existing manga in_library to user #1
            INSERT INTO user_manga (user_id, manga_id, in_library, in_library_at, viewer, viewer_flags, chapter_flags)
            SELECT 1, m.id, m.in_library, m.in_library_at, m.viewer, m.viewer_flags, m.chapter_flags
            FROM manga m
            WHERE m.in_library = TRUE
              AND NOT EXISTS (SELECT 1 FROM user_manga um WHERE um.user_id = 1 AND um.manga_id = m.id);

            -- Migrate existing chapter read/bookmark/page progress to user #1
            INSERT INTO user_chapter (user_id, chapter_id, read, bookmark, last_page_read, last_read_at)
            SELECT 1, c.id, c.read, c.bookmark, c.last_page_read, c.last_read_at
            FROM chapter c
            WHERE (c.read = TRUE OR c.bookmark = TRUE OR c.last_page_read > 0 OR c.last_read_at > 0)
              AND NOT EXISTS (SELECT 1 FROM user_chapter uc WHERE uc.user_id = 1 AND uc.chapter_id = c.id);

            UPDATE category SET user_id = 1 WHERE user_id IS NULL;
            UPDATE trackrecord SET user_id = 1 WHERE user_id IS NULL;
        """.trimIndent()
    }
}
