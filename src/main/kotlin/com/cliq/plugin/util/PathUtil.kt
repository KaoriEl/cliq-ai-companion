package com.cliq.plugin.util

import java.nio.file.Path

object PathUtil {
    fun toRelativePosix(basePath: String?, absolute: String): String? {
        if (basePath == null) return null
        return runCatching {
            Path.of(basePath).relativize(Path.of(absolute)).toString().replace('\\', '/')
        }.getOrNull()?.takeIf { !it.startsWith("..") }
    }
}
