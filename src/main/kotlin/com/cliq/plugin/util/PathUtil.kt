package com.cliq.plugin.util

import java.nio.file.Path

object PathUtil {
    fun toRelativePosix(basePath: String?, absolute: String): String? {
        if (basePath == null) return null
        return runCatching {
            val base = runCatching { Path.of(basePath).toRealPath() }.getOrElse { Path.of(basePath) }
            val abs = runCatching { Path.of(absolute).toRealPath() }.getOrElse { Path.of(absolute) }
            base.relativize(abs).toString().replace('\\', '/')
        }.getOrNull()?.takeIf { !it.startsWith("..") }
    }
}
