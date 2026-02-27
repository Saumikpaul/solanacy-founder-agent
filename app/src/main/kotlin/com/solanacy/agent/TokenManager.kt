package com.solanacy.agent

import android.content.Context

object TokenManager {
    private const val PREF = "solanacy_prefs"
    private const val KEY_GITHUB_TOKEN = "github_token"
    private const val KEY_GITHUB_USER = "github_user"

    fun saveGitHub(context: Context, token: String, user: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_GITHUB_TOKEN, token)
            .putString(KEY_GITHUB_USER, user)
            .apply()
    }

    fun getToken(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_GITHUB_TOKEN, "") ?: ""

    fun getUser(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_GITHUB_USER, "Saumikpaul") ?: "Saumikpaul"
}
