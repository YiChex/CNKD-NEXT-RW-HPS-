package net.rwhps.server.game.headless.core.link

/** Optional internal result contract; preserves existing plugin-facing Unit methods. */
interface CheckedGameControlLink {
    fun tryPauseGame(pause: Boolean): Boolean
    fun trySaveGame(): Boolean
}
