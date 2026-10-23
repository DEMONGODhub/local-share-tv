package com.localshare.tv

import android.content.Context
import java.util.UUID

data class Phone(val name: String, val url: String, val password: String)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("localshare_tv", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("dev", null) ?: UUID.randomUUID().toString().replace("-", "").take(24).also { sp.edit().putString("dev", it).apply() }

    fun phones(): List<Phone> = (sp.getString("phones", "") ?: "").lines().mapNotNull {
        val p = it.split('\t')
        if (p.size == 3) Phone(p[0], p[1], p[2]) else null
    }

    fun savePhone(p: Phone) {
        val list = listOf(p) + phones().filter { it.url != p.url }
        sp.edit().putString("phones", list.take(8).joinToString("\n") { "${it.name}\t${it.url}\t${it.password}" }).apply()
    }

    fun forget(url: String) {
        sp.edit().putString("phones", phones().filter { it.url != url }.joinToString("\n") { "${it.name}\t${it.url}\t${it.password}" }).apply()
    }

    fun position(id: String): Long = sp.getLong("pos:$id", 0L)

    fun setPosition(id: String, ms: Long) {
        sp.edit().apply { if (ms < 5000) remove("pos:$id") else putLong("pos:$id", ms) }.apply()
    }
}
