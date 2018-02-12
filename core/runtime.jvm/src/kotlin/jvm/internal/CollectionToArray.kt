/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

@file:JvmName("CollectionToArray")

package kotlin.jvm.internal

import java.util.Arrays


@JvmName("toArray")
fun collectionToArray(collection: Collection<*>): Array<Any?> =
    toArrayImpl(
        collection,
        empty = { emptyArray() },
        alloc = { size -> arrayOfNulls<Any?>(size) },
        trim = { result, size -> Arrays.copyOf(result, size) }
    )

// Note: Array<Any?> here can have any reference array JVM type at run time
@JvmName("toArray")
fun collectionToArray(collection: Collection<*>, a: Array<Any?>?): Array<Any?> {
    // Collection.toArray contract requires that NullPointerException is thrown when array is null
    if (a == null) throw java.lang.NullPointerException()
    return toArrayImpl(
        collection,
        empty = {
            if (a.size > 0) a[0] = null
            a
        },
        alloc = { size ->
            if (size <= a.size) a else java.lang.reflect.Array.newInstance(a.javaClass.componentType, size) as Array<Any?>
        },
        trim = { result, size ->
            if (result === a) {
                a[size] = null
                a
            } else
                Arrays.copyOf(result, size)
        }
    )
}

private inline fun toArrayImpl(
    collection: Collection<*>,
    empty: () -> Array<Any?>,
    alloc: (Int) -> Array<Any?>,
    trim: (Array<Any?>, Int) -> Array<Any?>
): Array<Any?> {
    val size = collection.size
    if (size == 0) return empty() // quick path on zero size
    val iter = collection.iterator() // allocate iterator for non-empty collection
    if (!iter.hasNext()) return empty() // size was > 0, but no actual elements
    var result = alloc(size) // use size as a guess to allocate result array
    var i = 0
    // invariant: iter.hasNext is true && i < result.size
    while (true) {
        result[i++] = iter.next()
        if (i >= result.size) {
            if (!iter.hasNext()) return result // perfect match of array size
            // array size was too small -- grow array
            var newSize = i * 2
            if (newSize < 0) {
                if (i == Int.MAX_VALUE) throw OutOfMemoryError()
                newSize = Int.MAX_VALUE
            }
            result = Arrays.copyOf(result, newSize)

        } else {
            if (!iter.hasNext()) return trim(result, i) // ended too early (allocated array too big)
        }
    }
}
