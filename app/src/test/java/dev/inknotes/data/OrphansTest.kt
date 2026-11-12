package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class OrphansTest {
    private fun page(vararg images: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(0x494E4B21)
            d.writeFloat(1.5f)
            for (i in images) d.writeUTF(i)
        }
        return out.toByteArray()
    }

    @Test
    fun aDeletedPagesPictureIsNotReferenced() {
        val a = "img-00000000-0000-0000-0000-00000000000a.jpg"
        val b = "img-00000000-0000-0000-0000-00000000000b.png"
        val c = "img-00000000-0000-0000-0000-00000000000c.jpg"
        // Page 2 (with b) was deleted: only pages 1 and 3 are left.
        assertEquals(setOf(a, c), Orphans.referenced(setOf(a, b, c), sequenceOf(page(a), page(), page(c, a))))
    }
}
