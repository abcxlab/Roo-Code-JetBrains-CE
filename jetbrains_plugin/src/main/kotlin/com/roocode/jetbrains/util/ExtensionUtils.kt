// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.util

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Common utility methods for Extension
 */
object ExtensionUtils {
    private val logger = Logger.getInstance(ExtensionUtils::class.java)

    /**
     * Check whether the Socket server port (Int) or UDS path (String) is valid
     * @param portOrPath Port (Int) or UDS path (String)
     * @return Returns true if valid, otherwise false
     */
    @JvmStatic
    fun isValidPortOrPath(portOrPath: Any?): Boolean {
        return when (portOrPath) {
            is Int -> portOrPath > 0
            is String -> portOrPath.isNotEmpty()
            else -> false
        }
    }

    /**
     * Find Node.js executable path
     * @return Node.js executable path, or null if not found
     */
    @JvmStatic
    fun findNodeExecutable(): String? {
        // First check system PATH
        val nodePath = findExecutableInPath("node")
        if (nodePath != null) {
            logger.info("Found Node.js in system PATH: $nodePath")
            return nodePath
        }

        // Then check built-in Node.js
        val resourcesPath = PluginResourceUtil.getResourcePath(PluginConstants.PLUGIN_ID, PluginConstants.NODE_MODULES_PATH)
        if (resourcesPath != null) {
            val resourceDir = File(resourcesPath)
            if (resourceDir.exists() && resourceDir.isDirectory) {
                val nodeBin = if (SystemInfo.isWindows) {
                    File(resourceDir, "node.exe")
                } else {
                    File(resourceDir, ".bin/node")
                }

                if (nodeBin.exists() && nodeBin.canExecute()) {
                    logger.info("Found built-in Node.js: ${nodeBin.absolutePath}")
                    return nodeBin.absolutePath
                }
            }
        }

        logger.warn("Node.js executable not found")
        return null
    }

    /**
     * Find executable in system PATH
     */
    private fun findExecutableInPath(name: String): String? {
        return PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(name)?.absolutePath
    }
}