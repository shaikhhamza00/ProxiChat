package com.example.p2pchat.common

import android.content.Context
import android.content.SharedPreferences

object PrefsManager {
    private const val PREFS_NAME = "LocalChatPrefs"
    private const val KEY_PHONE_NUMBER = "phone_number"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun savePhoneNumber(context: Context, phoneNumber: String) {
        getPrefs(context).edit().putString(KEY_PHONE_NUMBER, phoneNumber).apply()
    }

    fun getPhoneNumber(context: Context): String? {
        return getPrefs(context).getString(KEY_PHONE_NUMBER, null)
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}