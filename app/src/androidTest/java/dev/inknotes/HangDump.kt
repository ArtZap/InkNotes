package dev.inknotes

import android.os.Looper
import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Fails a test that runs longer than [seconds] instead of letting the whole run hang, and
 * says where it is stuck: the stacks of the main thread and of the test's own thread go into
 * the failure message (and the log, tag "HangDump"). Needs no root, unlike a thread dump.
 */
class HangDump(private val seconds: Long) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var error: Throwable? = null
            val worker = Thread({
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    error = t
                }
            }, "test-${description.methodName}")
            worker.start()
            worker.join(seconds * 1000)
            if (worker.isAlive) {
                val text = buildString {
                    appendLine("${description.methodName} still running after $seconds s")
                    appendLine("main thread:")
                    for (f in Looper.getMainLooper().thread.stackTrace) appendLine("    at $f")
                    appendLine("test thread:")
                    for (f in worker.stackTrace) appendLine("    at $f")
                }
                Log.e("HangDump", text)
                worker.interrupt()
                throw AssertionError(text)
            }
            error?.let { throw it }
        }
    }
}
