package com.ahtteam.phoneinfo

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val mode = getSharedPreferences("aht", MODE_PRIVATE)
            .getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
