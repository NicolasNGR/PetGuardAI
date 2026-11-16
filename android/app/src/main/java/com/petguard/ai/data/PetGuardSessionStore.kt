package com.petguard.ai.data

import android.content.Context

data class SavedPetGuardSession(val uid: String, val email: String)

object PetGuardSessionStore {
    private const val PREFERENCES = "petguard_session"
    private const val KEY_UID = "firebase_uid"
    private const val KEY_EMAIL = "email"

    @Volatile
    private var preferences: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        if (preferences == null) {
            synchronized(this) {
                if (preferences == null) {
                    preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun read(): SavedPetGuardSession? {
        val prefs = preferences ?: return null
        val uid = prefs.getString(KEY_UID, null)?.takeIf(String::isNotBlank) ?: return null
        val email = prefs.getString(KEY_EMAIL, null)?.orEmpty().orEmpty()
        return SavedPetGuardSession(uid, email)
    }

    fun save(uid: String, email: String) {
        if (uid.isBlank()) return
        preferences?.edit()?.putString(KEY_UID, uid)?.putString(KEY_EMAIL, email)?.apply()
    }

    fun clear() {
        preferences?.edit()?.remove(KEY_UID)?.remove(KEY_EMAIL)?.apply()
    }
}
