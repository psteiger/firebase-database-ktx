package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

val Query.snapshots: Flow<DataSnapshot>
    get() = callbackFlow {
        val listener = asValueEventListener()
        addValueEventListener(listener)
        awaitClose { removeEventListener(listener) }
    }

inline fun <reified T> Query.values(): Flow<T?> =
    snapshots.map { it.value<T>() }

private fun ProducerScope<DataSnapshot>.asValueEventListener() =
    object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            trySendBlocking(snapshot)
        }

        override fun onCancelled(error: DatabaseError) {
            cancel("API Error", error.toException())
        }
    }