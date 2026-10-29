package com.google.android.accessibility.utils.output

internal class PreparedPcmStore<K>(private val maximumBytes: Int, private val maximumEntries: Int) {
  private val entries = LinkedHashMap<K, FloatArray>(8, 0.75f, true)
  private var bytes = 0

  @Synchronized fun get(key: K): FloatArray? = entries[key]
  @Synchronized fun contains(key: K): Boolean = entries.containsKey(key)
  @Synchronized fun byteCount(): Int = bytes

  @Synchronized
  fun put(key: K, samples: FloatArray): Boolean {
    val size = samples.size.toLong() * 4
    if (size > maximumBytes || maximumEntries <= 0) return false
    entries.remove(key)?.let { bytes -= it.size * 4 }
    while (entries.isNotEmpty() && (bytes + size > maximumBytes || entries.size >= maximumEntries)) {
      val iterator = entries.entries.iterator()
      bytes -= iterator.next().value.size * 4
      iterator.remove()
    }
    entries[key] = samples
    bytes += size.toInt()
    return true
  }

  @Synchronized
  fun clear() {
    entries.clear()
    bytes = 0
  }
}
