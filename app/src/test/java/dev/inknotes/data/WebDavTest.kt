package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WebDAV without a server: file names that carry the notebook, and a server's listing. */
class WebDavTest {
    @Test
    fun namesCarryTheNotebook() {
        val id = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
        val n = WebDavNames.name("Матан: лекции [2]", id, 1731600000000)
        assertEquals("Матан_ лекции _2_ [$id~1731600000000].inknote", n)
        assertEquals(id to 1731600000000L, WebDavNames.parse(n))
        assertEquals("Матан_ лекции _2_", WebDavNames.title(n))
        assertNull("not the app's", WebDavNames.parse("photo.jpg"))
        assertNull(WebDavNames.parse("Тетрадь.inknote"))
    }

    @Test
    fun readsAListing() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/InkNotes/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/InkNotes/%D0%9C%D0%B0%D1%82%D0%B0%D0%BD%20%5Bab12cd34-0000~5%5D.inknote</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
              <D:response xmlns:D="DAV:"><D:href>https://cloud.example.com/InkNotes/other.txt</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
            </d:multistatus>"""
        val entries = WebDavListing.parse(xml)
        assertEquals(3, entries.size)
        assertEquals(listOf(true, false, false), entries.map { it.folder })
        val name = WebDavListing.nameOf(entries[1].href)
        assertEquals("Матан [ab12cd34-0000~5].inknote", name)
        assertEquals("ab12cd34-0000" to 5L, WebDavNames.parse(name))
        assertEquals("https://webdav.yandex.ru/InkNotes/a.inknote", WebDavListing.absolute("https://webdav.yandex.ru/InkNotes/", "/InkNotes/a.inknote"))
        assertEquals("%D0%9C%D0%B0%D1%82%20%5B1%7E2%5D.inknote", WebDavListing.encode("Мат [1~2].inknote"))
    }
}
