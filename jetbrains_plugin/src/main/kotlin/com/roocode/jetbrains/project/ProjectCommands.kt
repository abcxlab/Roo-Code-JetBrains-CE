// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.project

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.roocode.jetbrains.commands.CommandRegistry
import com.roocode.jetbrains.commands.ICommand
import com.roocode.jetbrains.editor.createURI
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSameFileAs

/**
 * Registers project-related API commands.
 *
 * @param project The current IntelliJ project
 * @param registry The command registry to register commands with
 */
fun registerProjectAPICommands(project: Project, registry: CommandRegistry) {
    registry.registerCommand(
        object : ICommand {
            override fun getId(): String = "vscode.openFolder"
            override fun getMethod(): String = "openFolder"
            override fun handler(): Any = ProjectCommands(project)
            override fun returns(): String = "void"
        }
    )
}

/**
 * Handles project-related commands such as opening or switching folders/projects.
 */
class ProjectCommands(val project: Project) {
    private val logger = Logger.getInstance(ProjectCommands::class.java)

    /**
     * Opens a folder or project.
     * Corresponds to vscode.openFolder.
     *
     * @param uri Map containing URI components
     * @param options Optional map containing options like forceNewWindow
     */
    fun openFolder(uri: Map<String, Any?>, options: Map<String, Any?>?) {
        try {
            val ktUri = createURI(uri)
            if (ktUri.scheme != "file") {
                logger.warn("openFolder: Only 'file' scheme is supported, got '${ktUri.scheme}'")
                return
            }

            val fsPath = ktUri.fsPath
            val targetPath = Paths.get(fsPath).toAbsolutePath().normalize()
            if (!targetPath.exists() || !targetPath.isDirectory()) {
                logger.warn("openFolder: Target path does not exist or is not a directory: $targetPath")
                return
            }

            val forceNewWindow = options?.get("forceNewWindow") as? Boolean ?: false
            val reuseWindow = !forceNewWindow

            logger.info("openFolder: path=$targetPath, forceNewWindow=$forceNewWindow, reuseWindow=$reuseWindow")

            // Use a pooled thread to introduce a delay before project closing.
            // This ensures the RPC response is sent back to Node.js before the extension host is killed.
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // 200ms is usually enough for the RPC response to be flushed to the socket.
                    Thread.sleep(200)

                    ApplicationManager.getApplication().invokeLater {
                        if (reuseWindow) {
                            // Pre-save all documents to reduce potential blocking dialogs during switch
                            FileDocumentManager.getInstance().saveAllDocuments()
                        }

                        // Check if the project is already open in any frame
                        val openProjects = ProjectManager.getInstance().openProjects
                        val existingProject = openProjects.find { p ->
                            p.basePath?.let {
                                try {
                                    Paths.get(it).toAbsolutePath().normalize() == targetPath
                                } catch (e: Exception) {
                                    false
                                }
                            } ?: false
                        }

                        if (existingProject != null) {
                            logger.info("openFolder: Project already open, focusing existing window: ${existingProject.name}")

                            // Ensure the frame is brought to front and requested focus
                            val frame = WindowManager.getInstance().getFrame(existingProject)
                            frame?.let {
                                it.toFront()
                                it.requestFocus()
                            }

                            // If we intended to switch (reuseWindow), close the *current* project
                            if (reuseWindow && existingProject != project) {
                                logger.info("openFolder: Switching to already open project, explicitly closing current project: ${project.name}")
                                // We use invokeLater to ensure the new window is already active before closing the old one
                                ApplicationManager.getApplication().invokeLater {
                                    ProjectManager.getInstance().closeAndDispose(project)
                                }
                            }
                        } else {
                            // When reuseWindow is true, we want to replace the current project with the new one.
                            // However, ProjectUtil.openOrImport's behavior can be inconsistent across IDE versions.
                            // To ensure reliable switching:
                            // 1. Open the new project first (in a new window if necessary)
                            // 2. If it was a switch (reuseWindow), close the old project
                            logger.info("openFolder: Opening project: $targetPath, reuseWindow=$reuseWindow")

                            if (reuseWindow) {
                                // Open the new project
                                val newProject = ProjectUtil.openOrImport(targetPath, null, false)
                                if (newProject != null) {
                                    logger.info("openFolder: New project opened, now closing old project: ${project.name}")
                                    // Close the old project after the new one is initialized
                                    ApplicationManager.getApplication().invokeLater {
                                        ProjectManager.getInstance().closeAndDispose(project)
                                    }
                                }
                            } else {
                                // Just open in a new window
                                ProjectUtil.openOrImport(targetPath, null, false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to open project: $targetPath", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to execute openFolder", e)
        }
    }
}
