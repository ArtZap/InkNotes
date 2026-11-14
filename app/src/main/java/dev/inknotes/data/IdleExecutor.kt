package dev.inknotes.data

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One background thread, in order, like [java.util.concurrent.Executors.newSingleThreadExecutor],
 * but the thread ends after [idleSeconds] without work and comes back with the next task: a
 * notebook tab in the background keeps no threads.
 */
fun idleExecutor(name: String, idleSeconds: Long = 20): ExecutorService =
    ThreadPoolExecutor(1, 1, idleSeconds, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, name) }
        .apply { allowCoreThreadTimeOut(true) }
