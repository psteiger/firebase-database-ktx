package com.freelapp.firebase.database

import com.google.firebase.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

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

class ValueListenerScopeImpl : ValueListenerScope {
    var onDataChange: (DataSnapshot) -> Unit = {}
    var onCancelled: (DatabaseError) -> Unit = {}

    override fun onDataChange(block: (DataSnapshot) -> Unit) {
        onDataChange = block
    }

    override fun onCancelled(block: (DatabaseError) -> Unit) {
        onCancelled = block
    }
}

class ChildListenerScopeImpl : ChildListenerScope {
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

fun DatabaseReference.addValueListener(block: ValueListenerScope.() -> Unit): ValueEventListener =
    addValueEventListener(valueListener(block))

fun DatabaseReference.addChildrenListener(block: ChildListenerScope.() -> Unit): ChildEventListener =
    addChildEventListener(childrenListener(block))

@ExperimentalCoroutinesApi
fun DatabaseReference.valueFlow(): Flow<DataSnapshot> =
    callbackFlow {
        val listener = addValueListener {
            onDataChange { trySendBlocking(it) }
            onCancelled { cancel("Flow cancelled with exception", it.toException()) }
        }
        awaitClose { removeEventListener(listener) }
    }.flowOn(Dispatchers.IO)

@ExperimentalCoroutinesApi
fun DatabaseReference.childrenFlow(): Flow<List<DataSnapshot>> =
    callbackFlow {
        val snapshots = mutableListOf<DataSnapshot>()
        fun nextIndex(s: String?) =
            if (s == null) 0 else snapshots.indexOfFirst { it.key == s } + 1
        val listener = addChildrenListener {
            onChildAdded { dataSnapshot, s ->
                with(snapshots) {
                    add(nextIndex(s), dataSnapshot)
                    trySendBlocking(toList())
                }
            }
            onChildChanged { dataSnapshot, s ->
                val index = nextIndex(s)
                with (snapshots) {
                    removeAt(index)
                    add(index, dataSnapshot)
                    trySendBlocking(toList())
                }
            }
            onChildRemoved { dataSnapshot ->
                with (snapshots) {
                    val index = indexOfFirst { it.key == dataSnapshot.key }
                    removeAt(index)
                    trySendBlocking(toList())
                }
            }
            onChildMoved { dataSnapshot, s ->
                with (snapshots) {
                    val oldIndex = indexOfFirst { it.key == dataSnapshot.key }
                    val newIndex = nextIndex(s)
                    removeAt(oldIndex)
                    add(newIndex, dataSnapshot)
                    trySendBlocking(toList())
                }
            }
            onCancelled { cancel("Flow cancelled with exception", it.toException()) }
        }
        awaitClose { removeEventListener(listener) }
    }.flowOn(Dispatchers.IO)