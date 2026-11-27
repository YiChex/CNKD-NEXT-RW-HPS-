package net.rwhps.server.plugin.v2

/** Runs plugin code with the owning class loader as the thread context loader. */
internal object V2PluginExecution {
    fun <T> withContextClassLoader(classLoader: ClassLoader, action: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        if (previous === classLoader) {
            return action()
        }
        thread.contextClassLoader = classLoader
        return try {
            action()
        } finally {
            thread.contextClassLoader = previous
        }
    }
}
