package com.freelapp.firebase.database.rtdb

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.GenericTypeIndicator

inline fun <reified T> DataSnapshot.value(): T? = getValue(object : GenericTypeIndicator<T>() {})