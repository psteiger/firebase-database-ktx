package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

inline fun <reified T> DataSnapshot.value(): T =
    getValue(T::class.java)!!

suspend inline fun <reified T> Query.value(): T =
    snapshot().value()

suspend inline fun <reified T> Query.tryValue(): Result<T> =
    runCatching { value() }

suspend fun Query.snapshot(): DataSnapshot =
    suspendCoroutine { cont ->
        addSingleValueListener {
            onDataChange { cont.resume(it) }
            onCancelled { cont.resumeWithException(it.toException()) }
        }
    }

@ExperimentalCoroutinesApi
fun Query.snapshotFlow(): Flow<DataSnapshot> =
    callbackFlow {
        val listener = addValueListener {
            onDataChange { trySendBlocking(it) }
            onCancelled { cancel("Flow cancelled with exception", it.toException()) }
        }
        awaitClose { removeEventListener(listener) }
    }.applyOperators()

@ExperimentalCoroutinesApi
inline fun <reified T> Query.valueFlow(): Flow<T> =
    valueFlow { it.getValue(T::class.java)!! }

@ExperimentalCoroutinesApi
inline fun <reified T> Query.tryValueFlow(): Flow<Result<T>> =
    tryValueFlow { it.getValue(T::class.java)!! }

@ExperimentalCoroutinesApi
inline fun <reified T> Query.valueFlow(noinline transform: (DataSnapshot) -> T): Flow<T> =
    snapshotFlow().map { transform(it) }

@ExperimentalCoroutinesApi
inline fun <reified T> Query.tryValueFlow(noinline transform: (DataSnapshot) -> T): Flow<Result<T>> =
    snapshotFlow().map { runCatching { transform(it) } }

fun Query.addSingleValueListener(
    block: ValueListenerScope.() -> Unit
) {
    addListenerForSingleValueEvent(valueListener(block))
}

fun Query.addValueListener(
    block: ValueListenerScope.() -> Unit
): ValueEventListener =
    addValueEventListener(valueListener(block))

fun valueListener(
    block: ValueListenerScope.() -> Unit
): ValueEventListener =
    ValueListenerScopeImpl().apply(block).build()

@FirebaseRealtimeDatabaseDsl
interface ValueListenerScope {
    fun onDataChange(block: (DataSnapshot) -> Unit)
    fun onCancelled(block: (DatabaseError) -> Unit)
}

@PublishedApi
internal class ValueListenerScopeImpl : ValueListenerScope {
    internal var onDataChange: (DataSnapshot) -> Unit = {}
    internal var onCancelled: (DatabaseError) -> Unit = {}

    override fun onDataChange(block: (DataSnapshot) -> Unit) {
        onDataChange = block
    }

    override fun onCancelled(block: (DatabaseError) -> Unit) {
        onCancelled = block
    }
}

@PublishedApi
internal fun ValueListenerScopeImpl.build(): ValueEventListener =
    object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            this@build.onDataChange(snapshot)
        }

        override fun onCancelled(error: DatabaseError) {
            this@build.onCancelled(error)
        }
    }
