package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
fun Query.childrenFlow(): Flow<List<DataSnapshot>> =
    callbackFlow {
        // Actor is used to run the list operations in a worker thread.
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

inline fun Query.addChildrenListener(
    crossinline block: ChildListenerScope.() -> Unit
): ChildEventListener =
    addChildEventListener(childrenListener(block))

inline fun childrenListener(
    crossinline block: ChildListenerScope.() -> Unit
): ChildEventListener =
    ChildListenerScopeImpl().apply(block).build()

@FirebaseRealtimeDatabaseDsl
interface ChildListenerScope {
    fun onChildAdded(block: (DataSnapshot, String?) -> Unit)
    fun onChildChanged(block: (DataSnapshot, String?) -> Unit)
    fun onChildRemoved(block: (DataSnapshot) -> Unit)
    fun onChildMoved(block: (DataSnapshot, String?) -> Unit)
    fun onCancelled(block: (DatabaseError) -> Unit)
}

@PublishedApi
internal class ChildListenerScopeImpl : ChildListenerScope {
    internal var onChildAdded: (DataSnapshot, String?) -> Unit = { _, _ -> }
    internal var onChildChanged: (DataSnapshot, String?) -> Unit = { _, _ -> }
    internal var onChildRemoved: (DataSnapshot) -> Unit = {}
    internal var onChildMoved: (DataSnapshot, String?) -> Unit = { _, _ -> }
    internal var onCancelled: (DatabaseError) -> Unit = {}

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

@PublishedApi
internal fun ChildListenerScopeImpl.build(): ChildEventListener =
    object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            this@build.onChildAdded(snapshot, previousChildName)
        }

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
            this@build.onChildChanged(snapshot, previousChildName)
        }

        override fun onChildRemoved(snapshot: DataSnapshot) {
            this@build.onChildRemoved(snapshot)
        }

        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
            this@build.onChildMoved(snapshot, previousChildName)
        }

        override fun onCancelled(error: DatabaseError) {
            this@build.onCancelled(error)
        }
    }
