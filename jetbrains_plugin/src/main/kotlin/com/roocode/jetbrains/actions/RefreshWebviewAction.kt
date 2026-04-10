package com.roocode.jetbrains.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.roocode.jetbrains.webview.WebViewManager

/**
 * Action to refresh the Roo Code WebView.
 * Provides a "self-rescue" mechanism when the WebView freezes or fails to respond.
 */
class RefreshWebviewAction : AnAction(), DumbAware {
    private val logger = Logger.getInstance(RefreshWebviewAction::class.java)
    private var lastExecutionTime = 0L
    private val THROTTLE_MS = 500L

    override fun actionPerformed(e: AnActionEvent) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastExecutionTime < THROTTLE_MS) {
            logger.debug("Refresh action throttled")
            return
        }
        lastExecutionTime = currentTime

        val project = e.project ?: return
        logger.debug("Refresh Webview action triggered")
        
        val webViewManager = project.getService(WebViewManager::class.java)
        val latestWebView = webViewManager.getLatestWebView()
        
        if (latestWebView != null) {
            latestWebView.reload()
        } else {
            logger.warn("No active WebView instance found to refresh")
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        
        val webViewManager = project.getService(WebViewManager::class.java)
        e.presentation.isEnabledAndVisible = webViewManager.getLatestWebView() != null
    }
}
