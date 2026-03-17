// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.Charset

/**
 * Terminal charset detector utility
 * Provides multi-strategy encoding detection for terminal output
 */
object TerminalCharsetDetector {

    /**
     * Detect the best charset for terminal output
     * 
     * Strategy:
     * 1. On Windows, we force UTF-8 by injecting 'chcp 65001' during terminal initialization.
     *    Therefore, we always use UTF-8 to ensure consistency between OS output and plugin decoding.
     * 2. On macOS/Linux, UTF-8 is the standard and default.
     * 
     * @param project IntelliJ project instance
     * @return Detected Charset (Always UTF-8 in the enhanced solution)
     */
    fun detect(project: Project?): Charset {
        // In the enhanced solution, we unify the entire pipeline to UTF-8.
        // This avoids encoding conflicts between system output (GBK) and shell integration markers (UTF-8).
        return Charsets.UTF_8
    }
}
