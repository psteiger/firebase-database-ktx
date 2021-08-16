package com.freelapp.firebase.database.rtdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

@DslMarker
annotation class FirebaseRealtimeDatabaseDsl

internal fun <T> Flow<T>.applyOperators(): Flow<T> =
    conflate().flowOn(Dispatchers.IO)