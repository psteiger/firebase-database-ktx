package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

sealed class ChildEvent(open val snapshot: DataSnapshot)
data class ChildAdded(override val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent(snapshot)
data class ChildChanged(override val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent(snapshot)
data class ChildRemoved(override val snapshot: DataSnapshot) : ChildEvent(snapshot)
data class ChildMoved(override val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent(snapshot)

fun Query.children(
    onInitialDataLoaded: (initialData: Iterable<DataSnapshot>) -> Unit = {}
): Flow<ChildEvent> = callbackFlow {
    val listener = asChildEventListener()
    val singleListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            onInitialDataLoaded(snapshot.children)
        }
        override fun onCancelled(error: DatabaseError) {}
    }
    addChildEventListener(listener)
    addListenerForSingleValueEvent(singleListener)
    awaitClose {
        removeEventListener(listener)
        removeEventListener(singleListener)
    }
}

fun Flow<ChildEvent>.aggregate(): Flow<List<DataSnapshot>> =
    flow {
        buildList<DataSnapshot> {
            collect { event ->
                consume(event)
                emit(toList())
            }
        }
    }

private fun ProducerScope<ChildEvent>.asChildEventListener() = object : ChildEventListener {
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

private fun Iterable<DataSnapshot>.indexOf(key: String?) = when (key) {
    null -> -1
    else -> indexOfFirst { it.key == key }
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
        is ChildRemoved -> {
            val index = indexOf(event.snapshot.key)
            removeAt(index)
        }
    }
}