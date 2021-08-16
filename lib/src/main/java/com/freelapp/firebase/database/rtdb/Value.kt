package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@ExperimentalCoroutinesApi
fun DatabaseReference.valueFlow(): Flow<DataSnapshot> =
    callbackFlow {
        val listener = addValueListener {
            onDataChange { trySendBlocking(it) }
            onCancelled { cancel("Flow cancelled with exception", it.toException()) }
        }
        awaitClose { removeEventListener(listener) }
    }.applyOperators()

inline fun DatabaseReference.addValueListener(
    crossinline block: ValueListenerScope.() -> Unit
): ValueEventListener =
    addValueEventListener(valueListener(block))

inline fun valueListener(
    crossinline block: ValueListenerScope.() -> Unit
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
