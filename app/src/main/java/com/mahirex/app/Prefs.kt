package com.mahirex.app

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("mahirex", Context.MODE_PRIVATE)
    }

    fun nightMode(): Int =
        sp?.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    fun setNightMode(mode: Int) {
        sp?.edit()?.putInt("night", mode)?.apply()
    }

    /** "" means: follow the device language */
    fun lang(): String = sp?.getString("lang", "") ?: ""

    fun setLang(tag: String) {
        sp?.edit()?.putString("lang", tag)?.apply()
    }

    // generic helpers used by the speed test and the widget layout (all stored locally only)
    fun getStr(key: String, def: String): String = sp?.getString(key, def) ?: def

    fun putStr(key: String, v: String) {
        sp?.edit()?.putString(key, v)?.apply()
    }

    fun getInt(key: String, def: Int): Int = sp?.getInt(key, def) ?: def

    fun putInt(key: String, v: Int) {
        sp?.edit()?.putInt(key, v)?.apply()
    }
}
