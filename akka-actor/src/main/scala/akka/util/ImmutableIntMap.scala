/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util
import java.util.Arrays
import akka.annotation.InternalApi
import scala.annotation.tailrec

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ImmutableIntMap {
  final val empty: ImmutableIntMap = new ImmutableIntMap(Array.emptyIntArray, 0)
}

/**
 * INTERNAL API
 * Specialized Map for primitive `Int` keys and values to avoid allocations (boxing).
 * Keys and values are encoded consecutively in a single Int array and does copy-on-write with no
 * structural sharing, it's intended for rather small maps (<1000 elements).
 */
@InternalApi private[akka] final class ImmutableIntMap private (private final val kvs: Array[Int], final val size: Int) {

  private[this] final def indexForKey(key: Int): Int = {
    // Custom implementation of binary search since we encode key + value in consecutive indicies.
    // We do the binary search on half the size of the array then project to the full size.
    // >>> 1 for division by 2: https://research.googleblog.com/2006/06/extra-extra-read-all-about-it-nearly.html
    @tailrec def find(lo: Int, hi: Int): Int =
      if (lo <= hi) {
        val lohi = lo + hi // Since we search in half the array we don't need to div by 2 to find the real index of key
        val idx = lohi & ~1 // Since keys are in even slots, we get the key idx from lo+hi by removing the lowest bit if set (odd)
        val k = kvs(idx)
        if (k < key) find((lohi >>> 1) + 1, hi)
        else if (k > key) find(lo, (lohi >>> 1) - 1)
        else idx
      } else ~(lo << 1) // same as -((lo*2)+1): Item should be placed, negated to indicate no match

    find(0, size - 1)
  }

  /**
   * Worst case `O(log n)`, allocation free.
   * Will return Int.MinValue if not found, so beware of storing Int.MinValues
   */
  final def get(key: Int): Int = {
    val i = indexForKey(key)
    if (i >= 0) kvs(i + 1) else Int.MinValue
  }

  /**
   * Worst case `O(log n)`, allocation free.
   */
  final def contains(key: Int): Boolean = indexForKey(key) >= 0

  /**
   * Worst case `O(n)`, creates new `ImmutableIntMap`
   * with the given key and value if that key is not yet present in the map.
   */
  final def updateIfAbsent(key: Int, value: ⇒ Int): ImmutableIntMap =
    if (size > 0) {
      val i = indexForKey(key)
      if (i >= 0) this
      else insert(key, value, i)
    } else new ImmutableIntMap(Array(key, value), 1)

  /**
   * Worst case `O(n)`, creates new `ImmutableIntMap`
   * with the given key with the given value.
   */
  final def updated(key: Int, value: Int): ImmutableIntMap =
    if (size > 0) {
      val i = indexForKey(key)
      if (i >= 0) {
        val valueIndex = i + 1
        if (kvs(valueIndex) != value)
          update(value, valueIndex)
        else
          this // If no change no need to copy anything
      } else insert(key, value, i)
    } else new ImmutableIntMap(Array(key, value), 1)

  private[this] final def update(value: Int, valueIndex: Int): ImmutableIntMap = {
    val newKvs = kvs.clone()
    newKvs(valueIndex) = value
    new ImmutableIntMap(newKvs, size)
  }

  private[this] final def insert(key: Int, value: Int, index: Int): ImmutableIntMap = {
    val at = ~index // ~n == -(n + 1): insert the entry at the right position—keep the array sorted
    val newKvs = new Array[Int](kvs.length + 2)
    System.arraycopy(kvs, 0, newKvs, 0, at)
    newKvs(at) = key
    newKvs(at + 1) = value
    System.arraycopy(kvs, at, newKvs, at + 2, kvs.length - at)
    new ImmutableIntMap(newKvs, size + 1)
  }

  /**
   * Worst case `O(n)`, creates new `ImmutableIntMap`
   * without the given key.
   */
  final def remove(key: Int): ImmutableIntMap = {
    val i = indexForKey(key)
    if (i >= 0) {
      if (size > 1) {
        val newKvs = new Array[Int](kvs.length - 2)
        System.arraycopy(kvs, 0, newKvs, 0, i)
        System.arraycopy(kvs, i + 2, newKvs, i, kvs.length - i - 2)
        new ImmutableIntMap(newKvs, size - 1)
      } else ImmutableIntMap.empty
    } else this
  }

  /**
   * All keys
   */
  final def keysIterator: Iterator[Int] =
    if (size < 1) Iterator.empty
    else Iterator.range(0, kvs.length - 1, 2).map(kvs.apply)

  override final def toString: String =
    if (size < 1) "ImmutableIntMap()"
    else Iterator.range(0, kvs.length - 1, 2).map(i ⇒ s"${kvs(i)} -> ${kvs(i + 1)}").mkString("ImmutableIntMap(", ", ", ")")

  override final def hashCode: Int = Arrays.hashCode(kvs)

  override final def equals(obj: Any): Boolean = obj match {
    case other: ImmutableIntMap ⇒ Arrays.equals(kvs, other.kvs)
    case _                      ⇒ false
  }
}
