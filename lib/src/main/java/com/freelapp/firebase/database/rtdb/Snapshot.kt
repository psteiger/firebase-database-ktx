package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.DataSnapshot

inline fun <reified T> DataSnapshot.value(): T? = getValue(T::class.java)