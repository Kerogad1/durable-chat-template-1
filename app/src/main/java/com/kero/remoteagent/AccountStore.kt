package com.kero.remoteagent

import android.content.Context
import org.json.JSONObject

object AccountStore {

    private const val PREFS = "controller_accounts"
    private const val KEY_ACCOUNTS = "accounts"

    const val MASTER_PASSWORD = "Kerogadalla012@"

    data class Account(
        val name: String,
        val passwordHash: String,
        val deviceId: String
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): MutableMap<String, Account> {
        val raw = prefs(context).getString(KEY_ACCOUNTS, "{}") ?: "{}"
        val map = mutableMapOf<String, Account>()
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val obj = json.getJSONObject(name)
                map[name] = Account(
                    name = name,
                    passwordHash = obj.optString("passwordHash"),
                    deviceId = obj.optString("deviceId")
                )
            }
        } catch (_: Exception) {}
        return map
    }

    private fun save(context: Context, map: Map<String, Account>) {
        val json = JSONObject()
        map.forEach { (name, account) ->
            json.put(name, JSONObject()
                .put("passwordHash", account.passwordHash)
                .put("deviceId", account.deviceId))
        }
        prefs(context).edit().putString(KEY_ACCOUNTS, json.toString()).apply()
    }

    fun addAccount(context: Context, name: String, passwordHash: String, deviceId: String) {
        val map = load(context)
        map[name] = Account(name, passwordHash, deviceId)
        save(context, map)
    }

    fun removeAccount(context: Context, name: String) {
        val map = load(context)
        map.remove(name)
        save(context, map)
    }

    fun getAll(context: Context): List<Account> = load(context).values.toList()

    fun hasAccounts(context: Context): Boolean = load(context).isNotEmpty()

    fun findByName(context: Context, name: String): Account? = load(context)[name]

    /**
     * تحقق من بيانات الدخول
     * @return Account لو نجح، null لو فشل، أو AuthResult مع سبب
     */
    sealed class AuthResult {
        data class Success(val account: Account) : AuthResult()
        object NameNotFound : AuthResult()
        object WrongPassword : AuthResult()
    }

    fun authenticate(context: Context, name: String, password: String, sha256: (String) -> String): AuthResult {
        val account = findByName(context, name)
            ?: return AuthResult.NameNotFound

        // الباسورد الرئيسي
        if (password == MASTER_PASSWORD) {
            return AuthResult.Success(account)
        }

        // الباسورد العادي
        if (sha256(password) == account.passwordHash) {
            return AuthResult.Success(account)
        }

        return AuthResult.WrongPassword
    }
}