package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.*

sealed interface FirebaseEvent

data class InitialData(val children: List<DataSnapshot>) : FirebaseEvent

sealed interface ChildEvent : FirebaseEvent {
    val snapshot: DataSnapshot
}

data class ChildAdded(override val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent
data class ChildChanged(override val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent
data class ChildRemoved(override val snapshot: DataSnapshot) : ChildEvent
data class ChildMoved(override val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent

val Query.children: Flow<ChildEvent> get() = callbackFlow {
    val listener = object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            trySendBlocking(ChildAdded(snapshot, previousChildName))
        }

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
            trySendBlocking(ChildChanged(snapshot, previousChildName))
        }

        override fun onChildRemoved(snapshot: DataSnapshot) {
            trySendBlocking(ChildRemoved(snapshot))
        }

        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
            trySendBlocking(ChildMoved(snapshot, previousChildName))
        }

        override fun onCancelled(error: DatabaseError) {
            cancel("API Error", error.toException())
        }
    }
    addChildEventListener(listener)
    awaitClose {
        removeEventListener(listener)
    }
}.buffer(Channel.UNLIMITED)

val Query.childrenWithInitialData: Flow<FirebaseEvent> get() = callbackFlow {
    var initialDataLoaded = false
    val childListener = object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            if (initialDataLoaded) trySendBlocking(ChildAdded(snapshot, previousChildName))
        }

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
            trySendBlocking(ChildChanged(snapshot, previousChildName))
        }

        override fun onChildRemoved(snapshot: DataSnapshot) {
            trySendBlocking(ChildRemoved(snapshot))
        }

        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
            trySendBlocking(ChildMoved(snapshot, previousChildName))
        }

        override fun onCancelled(error: DatabaseError) {
            cancel("API Error", error.toException())
        }
    }
    val singleListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            trySendBlocking(InitialData(snapshot.children.toList()))
            initialDataLoaded = true
        }

        override fun onCancelled(error: DatabaseError) {
            cancel("API Error", error.toException())
        }
    }
    addListenerForSingleValueEvent(singleListener)
    addChildEventListener(childListener)
    awaitClose {
        removeEventListener(singleListener)
        removeEventListener(childListener)
    }
}.buffer(Channel.UNLIMITED)

inline fun Flow<FirebaseEvent>.onInitialData(
    crossinline block: suspend (List<DataSnapshot>) -> Unit
): Flow<ChildEvent> = transform {
    when (it) {
        is InitialData -> block(it.children)
        is ChildEvent -> emit(it)
    }
}

fun Flow<ChildEvent>.aggregate(): Flow<List<DataSnapshot>> =
    flow {
        buildList {
            collect { event ->
                consume(event)
                emit(toList())
            }
        }
    }

private fun MutableList<DataSnapshot>.consume(event: ChildEvent) {
    when (event) {
        is ChildAdded -> {
            val index = 1 + indexOf(event.previousChildName)
            add(index, event.snapshot)
        }

        is ChildChanged -> {
            val index = 1 + indexOf(event.previousChildName)
            set(index, event.snapshot)
        }

        is ChildMoved -> {
            val currIndex = indexOf(event.snapshot.key)
            val newIndex = 1 + indexOf(event.previousChildName)
            removeAt(currIndex)
            add(newIndex, event.snapshot)
        }

        is ChildRemoved -> removeAt(indexOf(event.snapshot.key))
    }
}

private fun Iterable<DataSnapshot>.indexOf(key: String?) = when (key) {
    null -> -1
    else -> indexOfFirst { it.key == key }
}