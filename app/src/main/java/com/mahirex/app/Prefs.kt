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
}
