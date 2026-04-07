package com.roocode.jetbrains.protocol

import com.intellij.openapi.application.JBProtocolCommand
import com.intellij.openapi.application.JBProtocolCommandResult
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.roocode.jetbrains.core.ExtensionHostManager
import com.roocode.jetbrains.core.PluginContext
import com.roocode.jetbrains.core.ServiceProxyRegistry
import java.net.URI

/**
 * Custom protocol handler for RooCode.
 * Handles URIs with the IDE's specific scheme (e.g., idea://, webstorm://) via JBProtocolCommand.
 *
 * Expected URI format: idea://WeCode-AI.RunVSAgent.roo-cline/auth/clerk/callback?code=...
 * The command name is "WeCode-AI.RunVSAgent.roo-cline", which matches the extensionId.
 * The target parameter will contain the sub-path (e.g., "auth/clerk/callback").
 *
 * We override BOTH [execute] and [executeAndGetResult] to ensure compatibility across
 * different IntelliJ Platform versions:
 * - Older versions (2023.3) call [execute] directly.
 * - Newer versions (2024.1+) call [executeAndGetResult], which by default delegates to [execute].
 * Since we override [executeAndGetResult] without calling super, [execute] is only invoked
 * on older IDEs that call it directly.
 */
class RooCodeProtocolHandler : JBProtocolCommand("WeCode-AI.RunVSAgent.roo-cline") {
    private val logger = Logger.getInstance(RooCodeProtocolHandler::class.java)

    /**
     * Primary entry point for IntelliJ 2024.1+ (with [JBProtocolCommandResult] support).
     * The IDE calls this method when it receives a matching protocol URI.
     * We do NOT call super here, so [execute] is not invoked again by the base class.
     */
    override suspend fun executeAndGetResult(
        target: String?,
        parameters: Map<String, String>,
        fragment: String?
    ): JBProtocolCommandResult? {
        logger.info("executeAndGetResult called: target=$target, fragment=$fragment")
        val errorMessage = handleProtocolRequest(target, parameters, fragment)
        return JBProtocolCommandResult(errorMessage)
    }

    /**
     * Fallback entry point for older IntelliJ versions (2023.3) that call [execute] directly.
     * In newer versions, [executeAndGetResult] is called instead and this method is NOT invoked
     * because our [executeAndGetResult] override does not call super.
     */
    override suspend fun execute(
        target: String?,
        parameters: Map<String, String>,
        fragment: String?
    ): String? {
        logger.info("execute called: target=$target, fragment=$fragment")
        return handleProtocolRequest(target, parameters, fragment)
    }

    /**
     * Common protocol handling logic called from both [execute] and [executeAndGetResult].
     * @return null on success, or an error message string on failure.
     */
    private fun handleProtocolRequest(
        target: String?,
        parameters: Map<String, String>,
        fragment: String?
    ): String? {
        logger.info("RooCode Protocol Handler invoked: target=$target, parameters=$parameters, fragment=$fragment")

        try {
            // Use the actual extension ID.
            val extensionId = "WeCode-AI.RunVSAgent.roo-cline"
            val subPath = target ?: ""

            // Reconstruct URI components for the extension host.
            // We use the dynamically determined scheme to match what Extension Host expects.
            val scheme = ExtensionHostManager.getIdeProtocolScheme()
            val queryString = parameters.entries.joinToString("&") { "${it.key}=${it.value}" }
            val uriString = "$scheme://$extensionId/$subPath" +
                    (if (queryString.isNotEmpty()) "?$queryString" else "") +
                    (if (!fragment.isNullOrEmpty()) "#$fragment" else "")

            val parsedUri = URI.create(uriString)
            logger.info("Reconstructed URI for Extension Host: $uriString")

            // Dispatch to the correct project's extension host handler via RPC
            dispatchUri(extensionId, parsedUri)
        } catch (e: Exception) {
            logger.error("Failed to handle protocol command: target=$target", e)
            return "Error: ${e.message}"
        }

        // null means success (no error dialog)
        return null
    }

    /**
     * Dispatches a URI to the registered extension handler via RPC.
     * Iterates over all open projects to find the one with an active RPC protocol.
     */
    private fun dispatchUri(extensionId: String, uri: URI) {
        val projects = ProjectManager.getInstance().openProjects
        logger.info("Attempting to dispatch URI to ${projects.size} open projects")

        if (projects.isEmpty()) {
            logger.warn("No open projects available to dispatch URI")
            return
        }

        for (project in projects) {
            logger.info("Trying to dispatch to project: ${project.name}")
            val rpcProtocol = project.getService(PluginContext::class.java)?.getRPCProtocol() ?: continue
            val proxy = rpcProtocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostUrls)

            // The proxy is dynamically generated; try to dispatch.
            // We use handle=0 as a fallback. In VS Code's ExtHostUrls implementation,
            // if the specific handle is not found, it will broadcast to all registered UriHandlers.
            // Since MainThreadUrls is not a project service, we cannot easily retrieve the exact handle here.
            try {
                proxy.handleExternalUri(0, uriToComponents(uri))
                logger.info("Successfully dispatched URI to project '${project.name}' for extension $extensionId")
                return
            } catch (e: Exception) {
                logger.info("Failed to dispatch URI via project '${project.name}': ${e.message}")
            }
        }

        logger.warn("No project could handle URI for extension: $extensionId")
    }

    private fun uriToComponents(uri: URI): Map<String, Any?> {
        return mapOf(
            "scheme" to uri.scheme,
            "authority" to uri.authority,
            "path" to uri.path,
            "query" to uri.query,
            "fragment" to uri.fragment
        )
    }
}
