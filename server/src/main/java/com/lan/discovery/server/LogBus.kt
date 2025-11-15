package com.lan.discovery.server

import java.util.concurrent.CopyOnWriteArrayList

object LogBus {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val logs = CopyOnWriteArrayList<String>()

    fun post(line: String) {
        logs.add(line)
        for (function in listeners) {
            try { function(line) } catch (_: Throwable) {}
        }
    }

    fun register(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun unregister(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun getAll(): List<String> = ArrayList(logs)
}

