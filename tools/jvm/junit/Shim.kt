package org.junit
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Test
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Before
class AssumptionViolatedException(m: String?) : RuntimeException(m)
object Assume { @JvmStatic fun assumeTrue(m: String?, b: Boolean) { if (!b) throw AssumptionViolatedException(m) }
  @JvmStatic fun assumeTrue(b: Boolean) = assumeTrue(null, b) }
object Assert {
  @JvmStatic fun fail(m: String?): Nothing = throw AssertionError(m)
  @JvmStatic fun assertTrue(m: String?, b: Boolean) { if (!b) throw AssertionError(m ?: "expected true") }
  @JvmStatic fun assertTrue(b: Boolean) = assertTrue(null, b)
  @JvmStatic fun assertFalse(m: String?, b: Boolean) { if (b) throw AssertionError(m ?: "expected false") }
  @JvmStatic fun assertFalse(b: Boolean) = assertFalse(null, b)
  @JvmStatic fun assertNull(m: String?, o: Any?) { if (o != null) throw AssertionError((m ?: "") + " expected null, got <$o>") }
  @JvmStatic fun assertNull(o: Any?) = assertNull(null, o)
  @JvmStatic fun assertNotNull(m: String?, o: Any?) { if (o == null) throw AssertionError(m ?: "expected not null") }
  @JvmStatic fun assertNotNull(o: Any?) = assertNotNull(null, o)
  @JvmStatic fun assertEquals(m: String?, a: Any?, b: Any?) { if (a != b) throw AssertionError((m ?: "") + " expected:<$a> but was:<$b>") }
  @JvmStatic fun assertEquals(a: Any?, b: Any?) = assertEquals(null, a, b)
  @JvmStatic fun assertEquals(m: String?, a: Double, b: Double, d: Double) { if (Math.abs(a - b) > d) throw AssertionError((m ?: "") + " expected:<$a> but was:<$b>") }
  @JvmStatic fun assertEquals(a: Double, b: Double, d: Double) = assertEquals(null, a, b, d)
  @JvmStatic fun assertEquals(a: Float, b: Float, d: Float) { if (Math.abs(a - b) > d) throw AssertionError(" expected:<$a> but was:<$b>") }
  @JvmStatic fun assertEquals(m: String?, a: Long, b: Long) { if (a != b) throw AssertionError((m ?: "") + " expected:<$a> but was:<$b>") }
  @JvmStatic fun assertEquals(a: Long, b: Long) = assertEquals(null, a, b)
}
object Runner {
  @JvmStatic fun main(args: Array<String>) {
    var pass = 0; var failN = 0; var skip = 0
    for (cn in args) {
      val c = Class.forName(cn)
      for (m in c.methods.filter { it.isAnnotationPresent(Test::class.java) }.sortedBy { it.name }) {
        val o = c.getDeclaredConstructor().newInstance()
        try { c.methods.filter { it.isAnnotationPresent(Before::class.java) }.forEach { it.invoke(o) }; m.invoke(o); pass++ }
        catch (e: java.lang.reflect.InvocationTargetException) {
          val t = e.targetException
          if (t is AssumptionViolatedException) skip++ else { failN++; println("FAIL ${c.simpleName}.${m.name}: ${t.toString().take(400)}") }
        }
      }
    }
    println("passed $pass, failed $failN, skipped $skip")
  }
}
