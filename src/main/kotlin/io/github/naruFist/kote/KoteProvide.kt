package io.github.naruFist.kote

import com.destroystokyo.paper.event.player.PlayerJumpEvent

private val globals = mutableMapOf<String, Any>()

object Kote {
    fun <T> set(key: String, value: T): T {
        globals[key] = value as Any
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = globals[key] as? T

    fun remove(key: String) { globals.remove(key) }
}

fun a(event: PlayerJumpEvent) {
    event.player.currentInput
}
