package com.freelapp.firebase.database

import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.*

@DslMarker
annotation class FirebaseDatabaseDsl

@FirebaseDatabaseDsl
interface ValueListenerScope {
    fun onDataChange(block: (DataSnapshot) -> Unit)
    fun onCancelled(block: (DatabaseError) -> Unit)
}

@FirebaseDatabaseDsl
interface ChildListenerScope {
    fun onChildAdded(block: (DataSnapshot, String?) -> Unit)
    fun onChildChanged(block: (DataSnapshot, String?) -> Unit)
    fun onChildRemoved(block: (DataSnapshot) -> Unit)
    fun onChildMoved(block: (DataSnapshot, String?) -> Unit)
    fun onCancelled(block: (DatabaseError) -> Unit)
}

@PublishedApi
internal class ValueListenerScopeImpl : ValueListenerScope {
    var onDataChange: (DataSnapshot) -> Unit = {}
    var onCancelled: (DatabaseError) -> Unit = {}

    override fun onDataChange(block: (DataSnapshot) -> Unit) {
        onDataChange = block
    }

    override fun onCancelled(block: (DatabaseError) -> Unit) {
        onCancelled = block
    }
}

@PublishedApi
internal class ChildListenerScopeImpl : ChildListenerScope {
    var onChildAdded: (DataSnapshot, String?) -> Unit = { _, _ -> }
    var onChildChanged: (DataSnapshot, String?) -> Unit = { _, _ -> }
    var onChildRemoved: (DataSnapshot) -> Unit = {}
    var onChildMoved: (DataSnapshot, String?) -> Unit = { _, _ -> }
    var onCancelled: (DatabaseError) -> Unit = {}

    override fun onChildAdded(block: (DataSnapshot, String?) -> Unit) {
        onChildAdded = block
    }

    override fun onChildChanged(block: (DataSnapshot, String?) -> Unit) {
        onChildChanged = block
    }

    override fun onChildMoved(block: (DataSnapshot, String?) -> Unit) {
        onChildMoved = block
    }

    override fun onChildRemoved(block: (DataSnapshot) -> Unit) {
        onChildRemoved = block
    }

    override fun onCancelled(block: (DatabaseError) -> Unit) {
        onCancelled = block
    }
}

inline fun valueListener(crossinline block: ValueListenerScope.() -> Unit): ValueEventListener =
    object : ValueEventListener {
        val scope = ValueListenerScopeImpl().apply(block)

        override fun onDataChange(snapshot: DataSnapshot) {
            scope.onDataChange(snapshot)
        }

        override fun onCancelled(error: DatabaseError) {
            scope.onCancelled(error)
        }
    }

inline fun childrenListener(crossinline block: ChildListenerScope.() -> Unit): ChildEventListener =
    object : ChildEventListener {
        val scope = ChildListenerScopeImpl().apply(block)

        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            scope.onChildAdded(snapshot, previousChildName)
        }

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
            scope.onChildChanged(snapshot, previousChildName)
        }

        override fun onChildRemoved(snapshot: DataSnapshot) {
            scope.onChildRemoved(snapshot)
        }

        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
            scope.onChildMoved(snapshot, previousChildName)
        }

        override fun onCancelled(error: DatabaseError) {
            scope.onCancelled(error)
        }
    }

inline fun DatabaseReference.addValueListener(
    crossinline block: ValueListenerScope.() -> Unit
): ValueEventListener =
    addValueEventListener(valueListener(block))

inline fun DatabaseReference.addChildrenListener(
    crossinline block: ChildListenerScope.() -> Unit
): ChildEventListener =
    addChildEventListener(childrenListener(block))

@ExperimentalCoroutinesApi
fun DatabaseReference.valueFlow(): Flow<DataSnapshot> =
    callbackFlow {
        val listener = addValueListener {
            onDataChange { trySendBlocking(it) }
            onCancelled { cancel("Flow cancelled with exception", it.toException()) }
        }
        awaitClose { removeEventListener(listener) }
    }.applyOperators()

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
fun DatabaseReference.childrenFlow(): Flow<List<DataSnapshot>> =
    callbackFlow {
        // Actor is used to run operations in a worker thread.
        // Firebase's Realtime Database callbacks are received in the main thread.
        val channel = actor<MutableList<DataSnapshot>.() -> Unit>(capacity = UNLIMITED) {
            val snapshots = mutableListOf<DataSnapshot>()
            for (msg in channel) {
                msg(snapshots)
                trySend(snapshots.toList())
            }
        }
        fun MutableList<DataSnapshot>.nextIndex(s: String?) =
            if (s == null) 0 else indexOfFirst { it.key == s } + 1
        val listener = addChildrenListener {
            onChildAdded { dataSnapshot, s ->
                channel.trySend {
                    add(nextIndex(s), dataSnapshot)
                }
            }
            onChildChanged { dataSnapshot, s ->
                channel.trySend {
                    val index = nextIndex(s)
                    removeAt(index)
                    add(index, dataSnapshot)
                }
            }
            onChildRemoved { dataSnapshot ->
                channel.trySend {
                    val index = indexOfFirst { it.key == dataSnapshot.key }
                    removeAt(index)
                }
            }
            onChildMoved { dataSnapshot, s ->
                channel.trySend {
                    val currIndex = indexOfFirst { it.key == dataSnapshot.key }
                    val newIndex = nextIndex(s)
                    removeAt(currIndex)
                    add(newIndex, dataSnapshot)
                }
            }
            onCancelled { cancel("Flow cancelled with exception", it.toException()) }
        }
        awaitClose { removeEventListener(listener) }
    }.applyOperators()

private fun <T> Flow<T>.applyOperators(): Flow<T> =
    conflate().flowOn(Dispatchers.IO)
