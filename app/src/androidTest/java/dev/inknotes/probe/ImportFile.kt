package dev.inknotes.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.NotebookRepository
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Not a test: imports `<external files>/<-e file NAME>` (.inknote) into the library as a copy. */
@RunWith(AndroidJUnit4::class)
class ImportFile {
    @Test
    fun import() {
        val name = InstrumentationRegistry.getArguments().getString("file")
        assumeTrue(name != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.getExternalFilesDir(null), name!!)
        val repo = NotebookRepository(context.filesDir)
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            repo.readArchiveAsync({ file.inputStream() }) { result ->
                if (result is NotebookRepository.ArchiveImport.Ready) {
                    repo.commitImportAsync(result, replace = false) { done.countDown() }
                } else {
                    done.countDown()
                }
            }
        }
        done.await(30, TimeUnit.SECONDS)
    }
}
