package org.junit
object Toggle { @JvmStatic fun main(a: Array<String>) { for (kv in a[0].split(',')) { val (k, v) = kv.split('='); dev.inknotes.math.Tune.set(k, v.toFloat()) }; Runner.main(a.drop(1).toTypedArray()) } }
