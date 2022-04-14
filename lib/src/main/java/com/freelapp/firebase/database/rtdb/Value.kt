package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend inline fun <reified T> Query.tryValue(): Result<T> = runCatching { value() }
suspend inline fun <reified T> Query.value(): T = snapshot().value()

inline fun <reified T> DataSnapshot.tryValue(): Result<T> = runCatching { value() }
inline fun <reified T> DataSnapshot.value(): T = getValue(T::class.java)!!

suspend fun Query.trySnapshot(): Result<DataSnapshot> = runCatching { snapshot() }
suspend fun Query.snapshot(): DataSnapshot =
    suspendCancellableCoroutine { cont ->
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                cont.resume(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        }
        addValueEventListener(listener)
        cont.invokeOnCancellation { removeEventListener(listener) }
    }

fun Query.snapshotFlow(): Flow<DataSnapshot> =
    callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                cancel("API Error", error.toException())
            }
        }
        addValueEventListener(listener)
        awaitClose { removeEventListener(listener) }
    }.buffer(UNLIMITED)

inline fun <reified T> Query.valueFlow(): Flow<T> =
    valueFlow { it.value() }

inline fun <reified T> Query.tryValueFlow(): Flow<Result<T>> =
    tryValueFlow { it.value() }

inline fun <reified T> Query.valueFlow(noinline transform: (DataSnapshot) -> T): Flow<T> =
    snapshotFlow().map { transform(it) }

inline fun <reified T> Query.tryValueFlow(noinline transform: (DataSnapshot) -> T): Flow<Result<T>> =
    snapshotFlow().map { runCatching { transform(it) } }
