package io.reactors.common.concurrent



import java.util.concurrent.ConcurrentHashMap
import org.scalameter.api._
import org.scalameter.japi.JBench



class CacheTrieBenches extends JBench.OfflineReport {
  override def historian =
    org.scalameter.reporting.RegressionReporter.Historian.Complete()

  override def defaultConfig = Context(
    exec.minWarmupRuns -> 60,
    exec.maxWarmupRuns -> 120,
    exec.independentSamples -> 3,
    verbose -> true
  )

  case class Wrapper(value: Int)

  val elems = (0 until 1000000).map(i => Wrapper(i)).toArray

  val sizes = Gen.range("size")(100000, 1000000, 250000)

  val chms = for (size <- sizes) yield {
    val chm = new ConcurrentHashMap[Wrapper, Wrapper]
    for (i <- 0 until size) chm.put(elems(i), elems(i))
    (size, chm)
  }

  @gen("chms")
  @benchmark("cache-trie.apply")
  @curve("CHM")
  def chmLookup(sc: (Int, ConcurrentHashMap[Wrapper, Wrapper])): Int = {
    val (size, chm) = sc
    var i = 0
    var sum = 0
    while (i < size) {
      sum += chm.get(elems(i)).value
      i += 1
    }
    sum
  }
}
