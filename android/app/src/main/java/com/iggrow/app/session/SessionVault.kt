package com.iggrow.app.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson

/**
 * Single-account encrypted session vault backed by EncryptedSharedPreferences.
 */
class SessionVault(context: Context) {
    private val gson = Gson()
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "ig_grow_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveSession(session: IgSession) {
        prefs.edit().putString(KEY_SESSION, gson.toJson(session)).apply()
    }

    fun getSession(): IgSession? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching { gson.fromJson(raw, IgSession::class.java) }.getOrNull()
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun saveActivePlanId(planId: String) {
        prefs.edit().putString(KEY_ACTIVE_PLAN_ID, planId).apply()
    }

    fun getActivePlanId(): String? = prefs.getString(KEY_ACTIVE_PLAN_ID, null)

    companion object {
        private const val KEY_SESSION = "ig_session_json"
        private const val KEY_ACTIVE_PLAN_ID = "active_plan_id"
    }
}
