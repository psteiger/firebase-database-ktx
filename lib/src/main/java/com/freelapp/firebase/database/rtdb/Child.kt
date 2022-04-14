package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

sealed class ChildEvent {
    data class ChildAdded(val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent()
    data class ChildChanged(val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent()
    data class ChildRemoved(val snapshot: DataSnapshot) : ChildEvent()
    data class ChildMoved(val snapshot: DataSnapshot, val previousChildName: String?) : ChildEvent()
}

fun Query.childEventFlow(): Flow<ChildEvent> = callbackFlow {
    val listener = object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            trySend(ChildEvent.ChildAdded(snapshot, previousChildName))
        }

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
            trySend(ChildEvent.ChildChanged(snapshot, previousChildName))
        }

        override fun onChildRemoved(snapshot: DataSnapshot) {
            trySend(ChildEvent.ChildRemoved(snapshot))
        }

        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
            trySend(ChildEvent.ChildMoved(snapshot, previousChildName))
        }

        override fun onCancelled(error: DatabaseError) {
            cancel("API Error", error.toException())
        }
    }
    addChildEventListener(listener)
    awaitClose { removeEventListener(listener) }
}.buffer(UNLIMITED)

fun Flow<ChildEvent>.toChildrenFlow(): Flow<List<DataSnapshot>> = flow {
    fun Iterable<DataSnapshot>.indexOf(key: String?) = when (key) {
        null -> -1
        else -> indexOfFirst { it.key == key }
    }
    fun MutableList<DataSnapshot>.consume(event: ChildEvent) {
        when (event) {
            is ChildEvent.ChildAdded -> {
                val index = 1 + indexOf(event.previousChildName)
                add(index, event.snapshot)
            }
            is ChildEvent.ChildChanged -> {
                val index = 1 + indexOf(event.previousChildName)
                set(index, event.snapshot)
            }
            is ChildEvent.ChildMoved -> {
                val currIndex = indexOf(event.snapshot.key)
                val newIndex = 1 + indexOf(event.previousChildName)
                removeAt(currIndex)
                add(newIndex, event.snapshot)
            }
            is ChildEvent.ChildRemoved -> {
                val index = indexOf(event.snapshot.key)
                removeAt(index)
            }
        }
    }
    val snapshots = mutableListOf<DataSnapshot>()
    collect { event ->
        snapshots.consume(event)
        emit(snapshots.toList())
    }
}.conflate()

fun Query.childrenFlow(): Flow<List<DataSnapshot>> = childEventFlow().toChildrenFlow()
