package com.kowalski.humdinger.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Checks whether a name is free, and finds random fun names nobody else has. */
object NameCheck {
    private val db get() = FirebaseFirestore.getInstance()

    /** True if nobody else has this name (names are unique, ignoring capitals). */
    suspend fun isNameFree(rawName: String, myUid: String): Boolean {
        val key = FunNames.key(UserRepository.cleanName(rawName))
        if (key.length < 3) return false
        val doc = db.collection("usernames").document(key).get().await()
        return !doc.exists() || doc.getString("uid") == myUid
    }

    /** A random fun name that nobody else has right now (for the 🎲 button). */
    suspend fun randomFreeName(myUid: String): String {
        repeat(15) { attempt ->
            val name = FunNames.random(attempt)
            val free = runCatching { isNameFree(name, myUid) }.getOrDefault(true)
            if (NameFilter.isAllowed(name) && free) return name
        }
        return FunNames.random(99)
    }
}
