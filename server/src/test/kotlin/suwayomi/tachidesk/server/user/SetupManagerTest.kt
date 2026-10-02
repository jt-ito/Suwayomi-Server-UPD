package suwayomi.tachidesk.server.user

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import suwayomi.tachidesk.server.user.model.UserTable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Own in-memory database instead of ApplicationTest: that base class shares one app and database between all test
// classes, and the default database is put back afterwards so those keep working.
class SetupManagerTest {
    companion object {
        private val previous = TransactionManager.defaultDatabase

        @BeforeAll
        @JvmStatic
        fun connect() {
            Database.connect("jdbc:h2:mem:setupmanager;DB_CLOSE_DELAY=-1;", "org.h2.Driver")
        }

        @AfterAll
        @JvmStatic
        fun restore() {
            TransactionManager.defaultDatabase = previous
        }
    }

    @Test
    fun `first run account cannot log in, can be claimed once, then works`() {
        transaction {
            SchemaUtils.create(UserTable)
            UserTable.deleteAll()
            UserTable.insert {
                it[id] = 1
                it[username] = "admin"
                it[passwordHash] = ""
                it[salt] = ""
            }
        }
        assertTrue(SetupManager.isUnclaimed())
        assertNull(UserManager.authenticate("admin", "admin"))

        assertThrows<IllegalArgumentException> { SetupManager.claim("owner", "short") }
        assertTrue(SetupManager.isUnclaimed())

        SetupManager.claim("owner", "correct horse")
        assertFalse(SetupManager.isUnclaimed())
        assertEquals("ADMIN", UserManager.authenticate("owner", "correct horse")?.role)
        assertNull(UserManager.authenticate("owner", "wrong password"))

        assertThrows<IllegalArgumentException> { SetupManager.claim("intruder", "another password") }
        assertNotNull(UserManager.authenticate("owner", "correct horse"))
    }

    @Test
    fun `setup code is only waived for this machine's own same-origin browser`() {
        fun local(
            peer: String? = "127.0.0.1",
            host: String? = "localhost:4567",
            proxied: Boolean = false,
            fetchSite: String? = "same-origin",
            origin: String? = null,
        ) = SetupManager.isTrustedLocal(peer, host, proxied, fetchSite, origin)

        assertTrue(local())
        assertTrue(local(peer = "0:0:0:0:0:0:0:1", host = "[::1]:4567", fetchSite = "none"))
        assertTrue(local(host = "127.0.0.1:4567", fetchSite = null, origin = "http://127.0.0.1:4567"))

        assertFalse(local(fetchSite = "cross-site", origin = "http://evil.example"))
        assertFalse(local(fetchSite = null, origin = "http://evil.example"))
        assertFalse(local(fetchSite = null, origin = "null"))
        assertFalse(local(host = "evil.example:4567")) // DNS rebinding
        assertFalse(local(host = "manga.example.com"))
        assertFalse(local(peer = "192.168.1.20"))
        assertFalse(local(proxied = true))
        assertFalse(local(peer = null))
    }
}
