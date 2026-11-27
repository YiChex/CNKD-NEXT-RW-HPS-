package net.rwhps.server.plugin.v2

import java.net.URL
import java.net.URLClassLoader

/** Child-first for plugin-private code; platform and CNKD API packages stay parent-first. */
internal class V2PluginClassLoader(url: URL, parent: ClassLoader): URLClassLoader(arrayOf(url), parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            if (isServerInternal(name)) {
                throw ClassNotFoundException("V2 plugins cannot access CNKD server internals: $name")
            }
            if (isParentFirst(name)) {
                return super.loadClass(name, resolve)
            }
            val loaded = try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                super.loadClass(name, false)
            }
            if (resolve) {
                resolveClass(loaded)
            }
            return loaded
        }
    }

    private fun isParentFirst(name: String): Boolean {
        return PARENT_FIRST_PREFIXES.any(name::startsWith)
    }

    private fun isServerInternal(name: String): Boolean {
        return name.startsWith("net.rwhps.server.") &&
            !name.startsWith("net.rwhps.server.plugin.api.")
    }

    private companion object {
        val PARENT_FIRST_PREFIXES = arrayOf(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "kotlin.",
            "kotlinx.",
            "org.jetbrains.annotations.",
            "net.rwhps.server.plugin.api."
        )
    }
}
