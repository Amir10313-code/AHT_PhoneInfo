package com.ahtteam.phoneinfo

class InfoItem(val key: String, val value: String, val header: Boolean = false)

class InfoSection(val title: String) {
    val items = ArrayList<InfoItem>()

    fun add(key: String, value: Any?) {
        val v = value?.toString()?.trim()
        items.add(InfoItem(key, if (v.isNullOrEmpty()) "—" else v))
    }

    fun sub(title: String) {
        items.add(InfoItem(title, "", true))
    }
}
