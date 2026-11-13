package dev.inknotes.data

import java.io.File
import java.io.IOException

/** File moves that never leave the user with neither the old nor the new version. Plain JVM. */
object SafeFiles {
    /** Where a folder being replaced waits until its replacement is in place. */
    fun asideOf(dest: File) = File(dest.parentFile, ".replacing-" + dest.name)

    /**
     * [staging] becomes [dest]. An old [dest] is moved aside first and removed only once the
     * new one is in place; if that fails, the old one is put back and this throws.
     */
    fun replaceDir(staging: File, dest: File, rename: (File, File) -> Boolean = File::renameTo) {
        val aside = asideOf(dest)
        if (aside.exists()) aside.deleteRecursively()
        val hadOld = dest.exists()
        if (hadOld && !rename(dest, aside)) throw IOException("could not move ${dest.name} aside")
        if (!rename(staging, dest)) {
            if (hadOld && !rename(aside, dest)) throw IOException("could not put ${dest.name} back; it is in ${aside.name}")
            throw IOException("could not move the import into place")
        }
        if (hadOld) aside.deleteRecursively()
    }

    /**
     * After a crash in the middle of [replaceDir]: a folder left aside goes back where it was
     * when nothing took its place, else it is the old version and goes.
     */
    fun recoverAside(root: File) {
        for (aside in root.listFiles().orEmpty()) {
            if (!aside.name.startsWith(".replacing-")) continue
            val dest = File(root, aside.name.removePrefix(".replacing-"))
            if (dest.exists()) aside.deleteRecursively() else aside.renameTo(dest)
        }
    }

    /**
     * The library folder an imported notebook goes to: its own folder from the file (made
     * under the chosen one), else the folder the user chose, else where the notebook already is.
     */
    fun importFolder(fromFile: String?, chosen: String?, existing: String?): String? = fromFile ?: chosen ?: existing
}
