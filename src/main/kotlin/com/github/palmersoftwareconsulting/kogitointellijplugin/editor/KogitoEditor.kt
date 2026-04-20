package com.github.palmersoftwareconsulting.kogitointellijplugin.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.jcef.JBCefBrowserBuilder
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefContextMenuHandler
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDisplayHandler
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.CefSettings
import org.cef.misc.BoolRef
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Custom file editor for BPMN and DMN files using Kogito's standalone editors.
 *
 * This editor embeds Kogito's JavaScript-based editors within IntelliJ using JCEF
 * (Java Chromium Embedded Framework), providing a visual editing experience for
 * business process and decision models.
 *
 * ## Architecture
 * - **JCEF Browser**: Hosts the Kogito editor in an embedded Chromium browser
 * - **JavaScript Bridge**: Enables bidirectional communication between Kotlin and JavaScript
 * - **OSR Disabled**: Off-Screen Rendering is disabled to enable HTML5 drag-and-drop support
 * - **Inline Bundle**: JavaScript bundle is embedded directly in HTML to avoid resource loading issues
 *
 * ## Thread Safety
 * - [browserRef]: AtomicReference ensures thread-safe browser access
 * - [isModified]: AtomicBoolean provides lock-free dirty state tracking
 * - [listeners]: CopyOnWriteArrayList allows safe concurrent listener management
 * - [pendingSave]: Volatile-like access via CompletableFuture for save coordination
 *
 * ## Lifecycle
 * 1. **Initialization**: Creates UI components, shows loading indicator
 * 2. **Browser Creation**: Initializes JCEF browser with custom handlers (load, keyboard, context menu)
 * 3. **Bridge Setup**: Establishes JavaScript-to-JVM communication channel via JBCefJSQuery
 * 4. **Content Loading**: Injects file content via Base64-encoded JavaScript execution
 * 5. **Editor Ready**: Kogito editor initializes and begins tracking changes
 * 6. **Save Operations**: Extracts XML from editor, writes to VirtualFile via WriteCommandAction
 * 7. **Disposal**: Closes editor, cleans up JCEF resources
 *
 * ## Known Limitations
 * - JCEF must be available in the IDE distribution (may not work in all environments)
 * - Keyboard shortcuts like Cmd+S are intercepted at IDE level before reaching JCEF
 * - Large files may experience performance degradation during initial load
 * - Browser DevTools are disabled by default for cleaner UX
 *
 * ## JCEF-Specific Considerations
 * - **OSR Disabled**: Required for drag-and-drop functionality (JBR-7399)
 * - **Context Menu Cleared**: Right-click menu is suppressed for cleaner integration
 * - **Base64 Encoding**: Used for content injection to avoid escaping issues with special characters
 * - **Bridge Timing**: JavaScript bridge availability is checked asynchronously with retry logic
 *
 * @property project The IntelliJ project containing this editor
 * @property file The virtual file being edited (BPMN or DMN)
 * @property editorType The type of Kogito editor (BPMN or DMN)
 *
 * @see KogitoEditorProvider
 * @see EditorType
 * @see FileEditor
 *
 * @since 0.0.1
 */
class KogitoEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val editorType: EditorType
) : UserDataHolderBase(), FileEditor {

    companion object {
        private val logger = Logger.getInstance(KogitoEditor::class.java)

        /** Timeout for save operations in seconds. */
        private const val SAVE_TIMEOUT_SECONDS = 10L

        /** Build directory names to exclude from resource discovery. */
        private val BUILD_DIR_NAMES = setOf("target", "build", "out", "classes", "bin", ".gradle", "node_modules")

        /** Extensions to include for BPMN editor resources. */
        private val BPMN_RESOURCE_EXTENSIONS = setOf("wid", "dmn")

        /** Extensions to include for DMN editor resources (included models). */
        private val DMN_RESOURCE_EXTENSIONS = setOf("dmn", "pmml")

        /** Maximum file size in bytes for resource files (10MB). */
        private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L

        /** Flag to ensure OSR warning is only shown once per IDE session. */
        private val osrWarningShown = AtomicBoolean(false)

        /** Flag to ensure we only attempt to disable out-of-process JCEF once per IDE session. */
        private val outOfProcessDisableAttempted = AtomicBoolean(false)
    }

    /**
     * Main UI panel containing the editor component.
     * Uses BorderLayout with browser component occupying the CENTER region.
     */
    private val editorPanel = JPanel(BorderLayout())

    /**
     * Thread-safe reference to the JCEF browser instance.
     * Uses AtomicReference to ensure safe access from multiple threads (UI thread, EDT, background tasks).
     * Initialized to null and set during [createBrowserAndLoad].
     */
    private val browserRef = AtomicReference<JBCefBrowser?>()

    /**
     * Thread-safe flag indicating whether the editor content has been modified.
     * Uses AtomicBoolean for lock-free compare-and-swap operations.
     * Updated by [setModified] when JavaScript editor reports content changes.
     */
    private val isModified = AtomicBoolean(false)

    /**
     * Thread-safe list of property change listeners.
     * Uses CopyOnWriteArrayList to allow safe iteration during listener notification
     * while supporting concurrent add/remove operations from multiple threads.
     */
    private val listeners = CopyOnWriteArrayList<PropertyChangeListener>()

    /**
     * JavaScript-to-JVM communication bridge provided by JCEF.
     *
     * This bridge allows JavaScript code in the browser to invoke Kotlin code.
     * Messages are sent from JS using the injected `window.sendToIde()` function,
     * which invokes handlers registered with this query object.
     *
     * Initialized during [createBrowserAndLoad] and disposed in [dispose].
     *
     * @see JBCefJSQuery
     * @see handleJsMessage
     */
    private var jsQueryFromJs: JBCefJSQuery? = null

    /**
     * Pending save operation future that resolves when JavaScript returns content.
     *
     * When [saveContent] is called, we cannot directly retrieve the return value from
     * JavaScript execution (CEF limitation). Instead, we:
     * 1. Create a CompletableFuture and store it here
     * 2. Execute JavaScript to extract editor content
     * 3. JavaScript sends content back via `window.sendToIde({type:'content', xml})`
     * 4. [handleJsMessage] receives the message and completes this future
     * 5. Save operation continues with the received content
     *
     * This enables async request-response communication pattern over the one-way bridge.
     *
     * Uses AtomicReference for thread-safe access from both the save thread and the
     * JCEF message handler thread.
     *
     * @see saveContent
     * @see handleJsMessage
     */
    private val pendingSave = AtomicReference<CompletableFuture<String>?>(null)

    /**
     * Guard to prevent concurrent save operations.
     *
     * Save operations can be triggered from multiple sources:
     * - [KogitoSaveListener] when user presses Cmd+S/Ctrl+S
     * - CEF keyboard handler as backup
     * - JavaScript editor via "saveRequested" message
     *
     * Without this guard, rapid successive save requests could overwrite [pendingSave]
     * before the first save completes, causing the first save's future to hang until timeout.
     *
     * @see saveContent
     */
    private val saveInProgress = AtomicBoolean(false)

    // ============================================================
    // CEF Handler References (stored for cleanup in dispose())
    // ============================================================
    // These handlers are added to JBCefClient and must be removed
    // to prevent memory leaks when the editor is closed.

    /** Context menu handler that disables right-click menu. */
    private var contextMenuHandler: CefContextMenuHandler? = null

    /** Display handler that captures JavaScript console messages. */
    private var displayHandler: CefDisplayHandler? = null

    /** Load handler that initializes the editor when page loads. */
    private var loadHandler: CefLoadHandler? = null

    /** Keyboard handler for Cmd+S / Ctrl+S save shortcut. */
    private var keyboardHandler: CefKeyboardHandler? = null

    /**
     * Flag indicating the editor has been disposed.
     *
     * Used to:
     * - Prevent operations (save, JS execution) after disposal starts
     * - Guard against double-dispose calls
     * - Break race conditions between save operations and disposal
     */
    private val disposed = AtomicBoolean(false)

    /**
     * Initializes the editor component and begins browser creation.
     *
     * This initialization happens on the Event Dispatch Thread (EDT) and:
     * 1. Logs initialization details for debugging
     * 2. Shows a user notification that the editor is loading
     * 3. Creates a loading label as a placeholder
     * 4. Schedules browser creation on EDT via [ApplicationManager.invokeLater]
     *
     * The actual browser creation is deferred to avoid blocking the UI thread
     * during JCEF initialization.
     *
     * @see createBrowserAndLoad
     */
    init {
        logger.info("KogitoEditor INIT started for ${editorType.displayName}, file: ${file.path}")

        val loading = JLabel("<html><center>Loading ${editorType.displayName}...</center></html>")
        loading.horizontalAlignment = SwingConstants.CENTER
        editorPanel.add(loading, BorderLayout.CENTER)
        editorPanel.isFocusable = true

        ApplicationManager.getApplication().invokeLater {
            logger.debug("About to call createBrowserAndLoad()")
            createBrowserAndLoad()
        }
    }

    /**
     * Creates and initializes the JCEF browser with the Kogito editor.
     *
     * This method handles the complete browser setup process:
     *
     * ## JCEF Initialization
     * 1. Checks if JCEF is supported on the current platform
     * 2. Creates browser instance with OSR (Off-Screen Rendering) disabled for drag-and-drop support
     * 3. Stores browser reference in thread-safe [browserRef]
     *
     * ## Bridge Setup
     * - Creates JBCefJSQuery for JavaScript-to-Kotlin communication
     * - Registers message handler that routes to [handleJsMessage]
     *
     * ## Browser Event Handlers
     * - **Context Menu Handler**: Disables right-click menu for cleaner UX
     * - **Load Handler**: Triggers [initializeEditor] when page loads, logs errors
     * - **Keyboard Handler**: Intercepts Cmd+S/Ctrl+S to trigger [saveContent]
     *
     * ## Content Loading
     * - Builds HTML page with inline JavaScript bundle via [buildEditorHtml]
     * - Loads HTML into browser using `loadHTML()`
     * - Requests focus on browser component
     *
     * ## Error Handling
     * If JCEF is unsupported or initialization fails:
     * - Logs error details
     * - Shows error notification to user
     * - Leaves loading indicator visible
     *
     * ## Threading
     * Must be called on EDT. Uses [ApplicationManager.invokeLater] in init block.
     *
     * ## JCEF Quirks
     * - OSR disabled: Required for HTML5 drag-and-drop (JBR-7399 limitation)
     * - Keyboard shortcuts: Cmd+S intercepted at CEF level but IDE level takes precedence
     * - DevTools: Can be enabled for debugging by calling browser.openDevtools() in onLoadEnd
     *
     * @throws Exception if browser creation fails (caught and shown to user)
     * @see JBCefBrowserBuilder
     * @see initializeEditor
     * @see buildEditorHtml
     */
    private fun createBrowserAndLoad() {
        try {
            logger.debug("Creating browser for ${editorType.displayName} editor, file: ${file.path}")

            // Check if JCEF is supported
            if (!JBCefApp.isSupported()) {
                logger.error("JCEF is not supported on this platform")
                notifyError("JCEF Not Supported", "Your IDE version doesn't support embedded browsers")
                return
            }

            // Try to disable out-of-process JCEF mode before creating browser
            // This may not work if JCEF is already initialized, but worth trying
            tryDisableOutOfProcessJcef()

            // Check if OSR mode is forced (IntelliJ 2025.x out-of-process JCEF)
            // When OSR is forced, drag-and-drop won't work and freezes may occur
            if (JBCefApp.isOffScreenRenderingModeEnabled()) {
                logger.warn("JCEF is running in OSR mode - drag-and-drop may not work")
                showOsrWarningOnce()
            }

            logger.debug("JCEF is supported, creating browser instance with OSR disabled for drag-and-drop")
            // Disable OSR (Off-Screen Rendering) to enable drag-and-drop support
            // See: https://intellij-support.jetbrains.com/hc/en-us/community/posts/20066759270162
            val browser = JBCefBrowserBuilder()
                .setOffScreenRendering(false)
                .build()
            browserRef.set(browser)
            logger.debug("Browser instance created successfully (OSR disabled)")

            // Register browser with Disposer hierarchy: Editor → Browser
            // This ensures browser is automatically disposed if editor is disposed via Disposer
            Disposer.register(this, browser)
            logger.debug("Browser registered with Disposer hierarchy")

            // Setup JS -> JVM bridge
            logger.debug("Setting up JS->JVM bridge")
            jsQueryFromJs = JBCefJSQuery.create(browser as JBCefBrowserBase).apply {
                addHandler { payload ->
                    logger.info("Received message from JS: ${payload.take(100)}...")
                    handleJsMessage(payload)
                    null
                }
            }

            // Register JS query with Disposer hierarchy: Browser → JSQuery
            // This creates disposal chain: Editor → Browser → JSQuery
            Disposer.register(browser, jsQueryFromJs!!)

            // Add context menu handler to disable right-click menu
            val client = browser.jbCefClient
            logger.debug("Adding context menu handler to disable right-click menu")
            contextMenuHandler = object : CefContextMenuHandlerAdapter() {
                override fun onBeforeContextMenu(
                    browser: CefBrowser,
                    frame: CefFrame,
                    params: org.cef.callback.CefContextMenuParams,
                    model: org.cef.callback.CefMenuModel
                ) {
                    // Clear the context menu model to disable the menu
                    model.clear()
                }
            }
            client.addContextMenuHandler(contextMenuHandler!!, browser.cefBrowser)

            // Add display handler to capture console.log messages from JavaScript
            logger.debug("Adding display handler to capture JavaScript console messages")
            displayHandler = object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    cefBrowser: CefBrowser,
                    level: CefSettings.LogSeverity,
                    message: String,
                    source: String,
                    line: Int
                ): Boolean {
                    // Log JavaScript console messages to the IDE log
                    val levelStr = when (level) {
                        CefSettings.LogSeverity.LOGSEVERITY_VERBOSE -> "VERBOSE"
                        CefSettings.LogSeverity.LOGSEVERITY_INFO -> "INFO"
                        CefSettings.LogSeverity.LOGSEVERITY_WARNING -> "WARN"
                        CefSettings.LogSeverity.LOGSEVERITY_ERROR -> "ERROR"
                        CefSettings.LogSeverity.LOGSEVERITY_DISABLE -> "DISABLED"
                        else -> "DEFAULT"
                    }

                    // Log with appropriate level and include source information
                    val logMessage = "[JS Console][$levelStr] $message (${source}:${line})"

                    // Downgrade Kogito internal workaround messages from error to info
                    val isKogitoWorkaround = message.contains("WORKAROUND APPLIED")
                    when {
                        isKogitoWorkaround -> logger.info(logMessage)
                        level == CefSettings.LogSeverity.LOGSEVERITY_ERROR -> logger.error(logMessage)
                        level == CefSettings.LogSeverity.LOGSEVERITY_WARNING -> logger.warn(logMessage)
                        else -> logger.info(logMessage)
                    }

                    // Return false to allow default handling (still show in DevTools if open)
                    return false
                }
            }
            client.addDisplayHandler(displayHandler!!, browser.cefBrowser)

            // Add load handler to inject bridge and initialize editor
            // NOTE: We use browserRef.get() instead of capturing 'browser' from outer scope
            // to avoid memory leaks from lambda closures holding strong references
            logger.debug("Adding load handler")
            loadHandler = object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    transitionType: org.cef.network.CefRequest.TransitionType
                ) {
                    logger.debug("Load started for frame: ${frame.url}")
                }

                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    logger.info("Load ended for frame: ${frame.url}, status: $httpStatusCode, isMain: ${frame.isMain}")
                    if (frame.isMain) {
                        // Use browserRef to avoid capturing 'browser' from outer scope
                        val jbBrowser = browserRef.get()
                        if (jbBrowser != null && !disposed.get()) {
                            logger.info("Initializing editor...")
                            initializeEditor(jbBrowser)
                        } else {
                            logger.warn("Browser unavailable or editor disposed, skipping initialization")
                        }
                    }
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
                    errorText: String,
                    failedUrl: String
                ) {
                    logger.error("Load error: $errorText (code: $errorCode) for URL: $failedUrl")
                }
            }
            client.addLoadHandler(loadHandler!!, browser.cefBrowser)

            // Replace loading label with browser
            logger.debug("Replacing loading label with browser component")
            editorPanel.removeAll()
            editorPanel.add(browser.component, BorderLayout.CENTER)
            editorPanel.revalidate()
            editorPanel.repaint()

            // Add keyboard handler for Cmd+S / Ctrl+S at the CEF level
            logger.debug("Adding CefKeyboardHandler for Cmd+S / Ctrl+S")
            keyboardHandler = object : CefKeyboardHandlerAdapter() {
                override fun onPreKeyEvent(
                    browser: CefBrowser,
                    event: CefKeyboardHandler.CefKeyEvent,
                    isKeyboardShortcut: BoolRef
                ): Boolean {
                    // Check for Ctrl+S (Windows/Linux) or Cmd+S (macOS)
                    // EVENTFLAG_CONTROL_DOWN = 1 << 2 = 4
                    // EVENTFLAG_COMMAND_DOWN = 1 << 3 = 8
                    val isCtrlOrCmd = (event.modifiers and 4) != 0 || (event.modifiers and 8) != 0
                    val isSKey = event.windows_key_code == 83 // 'S' key

                    if (isCtrlOrCmd && isSKey && event.type == CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) {
                        logger.info("Save shortcut detected (Cmd+S / Ctrl+S) at CEF level")
                        // Trigger save on the Swing thread
                        ApplicationManager.getApplication().invokeLater {
                            saveContent()
                        }
                        return true // Consume the event
                    }

                    return false // Let other events pass through
                }
            }
            client.addKeyboardHandler(keyboardHandler!!, browser.cefBrowser)
            logger.debug("CefKeyboardHandler added successfully")

            // Load the bundled HTML with inline JavaScript
            logger.debug("Building editor HTML...")

            // Load with inline JavaScript
            val html = buildEditorHtml()
            logger.debug("HTML built successfully, size: ${html.length} bytes")

            // Use http://jbcef/ as the base URL (JCEF-specific protocol)
            logger.debug("Loading HTML into browser...")
            browser.loadHTML(html)

            // Focus
            IdeFocusManager.getInstance(project).requestFocus(browser.component, true)

        } catch (e: Exception) {
            logger.error("Failed to create browser", e)
            notifyError("Failed to Load Editor", e.message ?: "Unknown error")
        }
    }

    /**
     * Builds the complete HTML page containing the Kogito editor and bridge infrastructure.
     *
     * This method constructs a self-contained HTML document that:
     *
     * ## Bundle Loading
     * - Reads the bundled JavaScript from `webui/dist/assets/index.js`
     * - Inlines the entire bundle directly into the HTML (avoids resource loading issues)
     * - Logs bundle size for debugging performance issues
     *
     * ## HTML Structure
     * - Minimal DOCTYPE and meta tags for proper rendering
     * - CSS for full-viewport editor (100vh/100vw with no overflow)
     * - Separate containers for DMN and BPMN editors (only one visible at a time)
     * - Global error handlers for debugging JavaScript issues
     *
     * ## Query Parameters
     * - Sets URL query params (type, file, readonly) that JavaScript reads during initialization
     * - Uses `window.history.replaceState()` to avoid triggering navigation
     *
     * ## Bridge Preparation
     * - JavaScript expects `window.sendToIde()` to be injected by Kotlin (done in [initializeEditor])
     * - JavaScript provides `window.setInitialContent()` for Kotlin to supply file content
     *
     * ## Error Handling
     * - Wraps module loading in try-catch with inline error display
     * - Throws IllegalStateException if JavaScript bundle cannot be loaded
     *
     * @return Complete HTML document as a string
     * @throws IllegalStateException if JavaScript bundle is missing from resources
     * @see initializeEditor
     * @see loadInitialFileContent
     */
    private fun buildEditorHtml(): String {
        logger.debug("Building HTML for ${editorType.typeName} editor")

        // Calculate file path relative to project root for Kogito
        val projectBasePath = project.basePath ?: ""
        val filePathFromRoot = if (projectBasePath.isNotEmpty() && file.path.startsWith(projectBasePath)) {
            file.path
                .removePrefix(projectBasePath)
                .removePrefix("/")
                .replace("\\", "/")  // Normalize to POSIX path separators
        } else {
            file.name  // Fallback to just filename if project path is not available
        }
        logger.debug("File path from root: $filePathFromRoot")

        // Discover resources (DMN included models or BPMN Work Item Definitions)
        val resourcesJson = discoverResources()

        // Load the bundled JavaScript
        val jsContent = try {
            logger.debug("Loading JavaScript bundle from resources...")
            val stream = javaClass.classLoader.getResourceAsStream("webui/dist/assets/index.js")
                ?: throw IllegalStateException("Resource stream was null - file not found")

            val content = stream.bufferedReader().use { it.readText() }
            logger.debug("JavaScript loaded successfully, size: ${content.length} bytes (${content.length / 1024 / 1024}MB)")
            content
        } catch (e: Exception) {
            logger.error("Failed to load webui/dist/assets/index.js", e)
            throw IllegalStateException("Could not load webui/dist/assets/index.js: ${e.message}", e)
        }

        // Build HTML with inline JS
        val html = """
            <!doctype html>
            <html>
            <head>
                <meta charset="utf-8" />
                <title>Kogito Editor - ${editorType.displayName}</title>
                <style>
                    * {
                        box-sizing: border-box;
                    }
                    html, body {
                        height: 100vh;
                        width: 100vw;
                        margin: 0;
                        padding: 0;
                        overflow: hidden;
                    }
                    #app {
                        height: 100%;
                        width: 100%;
                        margin: 0;
                        padding: 0;
                        overflow: hidden;
                        position: relative;
                    }
                    #dmn-editor-container, #bpmn-editor-container {
                        height: 100%;
                        width: 100%;
                        position: absolute;
                        top: 0;
                        left: 0;
                        display: none;
                    }
                    #dmn-editor-container.visible, #bpmn-editor-container.visible {
                        display: block !important;
                    }
                    /* Ensure Kogito editor iframes are sized correctly */
                    #dmn-editor-container > iframe, #bpmn-editor-container > iframe {
                        height: 100% !important;
                        width: 100% !important;
                        border: none;
                    }
                </style>
            </head>
            <body>
                <div id="app">
                    <div id="dmn-editor-container"></div>
                    <div id="bpmn-editor-container"></div>
                </div>
                <script>
                    console.log('========================================');
                    console.log('[Kogito] HTML PAGE LOADED');
                    console.log('[Kogito] Editor type: ${editorType.typeName}');
                    console.log('[Kogito] File: ${file.name}');
                    console.log('[Kogito] File path from root: $filePathFromRoot');
                    console.log('[Kogito] ReadOnly: ${!file.isWritable}');
                    console.log('========================================');

                    // Inject editor resources (DMN included models or BPMN WID files)
                    window.editorResources = $resourcesJson;
                    console.log('[Kogito] Editor resources injected:', Object.keys(window.editorResources).length, 'files');

                    // Set query parameters for editor initialization
                    const params = new URLSearchParams();
                    params.set('type', '${editorType.typeName}');
                    params.set('file', '$filePathFromRoot');
                    params.set('readonly', '${!file.isWritable}');
                    window.history.replaceState({}, '', '?' + params.toString());
                    console.log('[Kogito] Query params set:', params.toString());


                    // Global error handler
                    window.addEventListener('error', (e) => {
                        console.error('[Kogito] ❌ Global error:', e.error, e.message, e.filename, e.lineno);
                    });
                    window.addEventListener('unhandledrejection', (e) => {
                        console.error('[Kogito] ❌ Unhandled promise rejection:', e.reason);
                    });
                </script>
                <script type="module">
                    console.log('[Kogito] 📦 Loading editor module...');
                    try {
                        $jsContent
                        console.log('[Kogito] ✅ Editor module loaded successfully');
                    } catch (e) {
                        console.error('[Kogito] ❌ Error loading editor module:', e);
                        document.body.innerHTML = '<div style="padding: 20px; color: red; font-family: monospace;"><h2>Error loading editor</h2><pre>' + e.message + '\n\n' + e.stack + '</pre></div>';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        logger.debug("HTML built successfully, total size: ${html.length} bytes")
        return html
    }

    /**
     * Discovers resource files for the Kogito editor based on editor type.
     *
     * This method finds and encodes resources needed by the Kogito editors:
     * - **DMN Editor**: Discovers all DMN files in the project for included models
     * - **BPMN Editor**: Discovers all .wid (Work Item Definition) files for custom tasks
     *
     * ## DMN Included Models
     * DMN files can reference other DMN files as included models. The editor needs access
     * to these files to provide autocomplete and validation for included decision services.
     *
     * Important: "Resources located in a parent directory (in relation to the current content path)
     * won't be listed to be used as an Included Model." (Kogito documentation)
     *
     * ## BPMN Work Item Definitions
     * WID files define custom service tasks that can be used in BPMN diagrams. They specify
     * task properties, icons, and default names using MVEL language.
     *
     * ## Path Calculation
     * Paths are calculated relative to the project base directory and normalized to POSIX format:
     * - Backslashes converted to forward slashes (Windows compatibility)
     * - Relative to project base path
     * - For DMN: Excludes the current file being edited
     *
     * ## Resource Format
     * Returns a JavaScript object literal that can be embedded in the HTML:
     * ```javascript
     * {
     *   "path/to/model1.dmn": "BASE64_ENCODED_CONTENT",
     *   "path/to/custom-tasks.wid": "BASE64_ENCODED_CONTENT"
     * }
     * ```
     *
     * ## Performance Considerations
     * - Uses VFS traversal for resource file discovery
     * - Reads all file contents into memory (may impact performance with many large files)
     * - Base64 encoding increases size by ~33%
     *
     * @return JavaScript object literal string with resources, or "{}" if none found
     */
    private fun discoverResources(): String {
        try {
            val projectBasePath = project.basePath ?: return "{}"
            val projectBaseDir = VirtualFileManager.getInstance().findFileByUrl("file://$projectBasePath") ?: return "{}"

            // Select extensions based on editor type
            val extensions = when (editorType) {
                EditorType.DMN -> DMN_RESOURCE_EXTENSIONS
                EditorType.BPMN -> BPMN_RESOURCE_EXTENSIONS
            }

            logger.info("Discovering ${editorType.displayName} resources (${extensions.joinToString(", ").uppercase()} files)...")

            // Find all resource files in the project using VFS
            val resourceFiles = findResourceFiles(projectBaseDir, extensions)

            // Count files by extension for logging
            val extCounts = extensions.associateWith { ext ->
                resourceFiles.count { it.extension?.lowercase() == ext }
            }
            val countsStr = extCounts.entries.joinToString(", ") { "${it.value} ${it.key.uppercase()}" }
            logger.info("Found ${resourceFiles.size} resource files ($countsStr)")

            // Build resources map
            val resourcesMap = mutableMapOf<String, String>()

            for (virtualFile in resourceFiles) {
                try {
                    // Skip files that are too large
                    if (virtualFile.length > MAX_FILE_SIZE_BYTES) {
                        logger.warn("Skipping large file ${virtualFile.name} (${virtualFile.length} bytes)")
                        continue
                    }

                    // Validate file is still valid and accessible
                    if (!virtualFile.isValid || !virtualFile.isInLocalFileSystem) {
                        logger.debug("Skipping invalid or non-local file: ${virtualFile.path}")
                        continue
                    }

                    // Calculate relative POSIX path based on editor type
                    // - DMN: Uses project-root-relative paths for included models
                    // - BPMN: Uses file-relative paths for Work Item Definitions
                    val relativePath = when (editorType) {
                        EditorType.DMN -> toPosixPathFromProjectRoot(virtualFile.path, projectBasePath)
                        EditorType.BPMN -> toPosixPathFromCurrentFile(virtualFile.path, file.path)
                    }

                    // Read content with proper read action for thread safety
                    val contentBytes = ReadAction.nonBlocking<ByteArray> {
                        virtualFile.contentsToByteArray()
                    }.executeSynchronously()

                    // Validate UTF-8 content
                    val contentString = String(contentBytes, Charsets.UTF_8)
                    if (contentString.contains('\uFFFD')) {
                        logger.warn("Skipping file with invalid UTF-8: ${virtualFile.name}")
                        continue
                    }

                    // Base64 encode directly from bytes (efficient)
                    val base64Content = Base64.getEncoder().encodeToString(contentBytes)

                    resourcesMap[relativePath] = base64Content
                    logger.debug("Added ${virtualFile.extension?.uppercase()} resource: $relativePath (${contentBytes.size} bytes)")
                } catch (e: Exception) {
                    logger.warn("Failed to read resource file ${virtualFile.path}", e)
                }
            }

            if (resourcesMap.isEmpty()) {
                logger.info("No ${editorType.displayName} resources found")
            } else {
                logger.info("Found ${resourcesMap.size} ${editorType.displayName} resources ($countsStr)")
            }

            // Generate JavaScript object literal
            if (resourcesMap.isEmpty()) {
                return "{}"
            }

            // Sort entries alphabetically by path for consistent ordering in UI
            val jsObject = resourcesMap.entries
                .sortedBy { it.key }
                .joinToString(",\n") { (path, content) ->
                    // Escape the path for JavaScript string literal
                    val escapedPath = path.replace("\\", "\\\\").replace("\"", "\\\"")
                    "\"$escapedPath\": \"$content\""
                }

            return "{\n$jsObject\n}"

        } catch (e: Exception) {
            logger.error("Failed to discover resources", e)
            return "{}"
        }
    }

    /**
     * Finds all resource files with specified extensions in the project directory.
     *
     * Uses an iterative approach to avoid stack overflow on deep directory trees.
     * Excludes build directories and the current file being edited.
     * Wrapped in ReadAction for thread safety with IntelliJ's VFS.
     *
     * @param rootDir The root directory to start searching from
     * @param extensions Set of file extensions to include (lowercase)
     * @return List of VirtualFiles matching the resource criteria
     */
    private fun findResourceFiles(rootDir: VirtualFile, extensions: Set<String>): List<VirtualFile> {
        return ReadAction.nonBlocking<List<VirtualFile>> {
            val result = mutableListOf<VirtualFile>()
            val stack = ArrayDeque<VirtualFile>()
            stack.add(rootDir)

            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()

                // Skip build directories
                if (dir.name.lowercase() in BUILD_DIR_NAMES) {
                    continue
                }

                for (child in dir.children) {
                    when {
                        child.isDirectory -> stack.add(child)
                        child.extension?.lowercase() in extensions &&
                                child.path != file.path -> result.add(child)
                    }
                }
            }

            result
        }.executeSynchronously()
    }

    /**
     * Converts an absolute file path to a POSIX-style relative path from the project root.
     *
     * @param absolutePath The absolute file path
     * @param projectBasePath The project's base directory path
     * @return Relative POSIX path (forward slashes, no leading slash)
     */
    private fun toPosixPathFromProjectRoot(absolutePath: String, projectBasePath: String): String {
        return absolutePath
            .removePrefix(projectBasePath)
            .removePrefix("/")
            .removePrefix("\\")
            .replace("\\", "/")
    }

    /**
     * Converts an absolute file path to a POSIX-style relative path from the current file's directory.
     *
     * Used by BPMN editor where resources need to be relative to the file being edited,
     * not the project root.
     *
     * @param resourcePath The absolute path to the resource file
     * @param currentFilePath The absolute path to the file being edited
     * @return Relative POSIX path (e.g., "../tasks/custom.wid", "./sibling.wid")
     */
    private fun toPosixPathFromCurrentFile(resourcePath: String, currentFilePath: String): String {
        val resourceParts = resourcePath.replace("\\", "/").split("/").filter { it.isNotEmpty() }
        val currentDir = currentFilePath.replace("\\", "/").substringBeforeLast("/")
        val currentParts = currentDir.split("/").filter { it.isNotEmpty() }

        // Find common prefix length
        var commonLength = 0
        while (commonLength < resourceParts.size && commonLength < currentParts.size &&
            resourceParts[commonLength] == currentParts[commonLength]) {
            commonLength++
        }

        // Build relative path: go up from current directory, then down to resource
        val upCount = currentParts.size - commonLength
        val downParts = resourceParts.drop(commonLength)

        val relativePath = buildString {
            repeat(upCount) { append("../") }
            append(downParts.joinToString("/"))
        }

        return relativePath.ifEmpty { "./" + resourceParts.last() }
    }

    /**
     * Handles messages received from JavaScript via the JCEF bridge.
     *
     * This is the primary message handler for all JavaScript-to-Kotlin communication.
     * Messages are JSON-formatted strings with a `type` field indicating the message purpose.
     *
     * ## Supported Message Types
     *
     * ### contentChanged
     * ```json
     * {"type": "contentChanged", "dirty": true/false}
     * ```
     * Sent by JavaScript when editor content changes or is saved.
     * Updates [isModified] flag and notifies property change listeners.
     *
     * ### saveRequested
     * ```json
     * {"type": "saveRequested"}
     * ```
     * Sent when user triggers save from within the editor (if supported).
     * Invokes [saveContent] to persist changes.
     *
     * ### content
     * ```json
     * {"type": "content", "xml": "..."}
     * ```
     * Response to a save request containing the editor's XML content.
     * Completes the [pendingSave] future to continue the save operation.
     * XML is unescaped (\\n → \n, \\" → ", \\\\ → \\).
     *
     * ### editorReady
     * ```json
     * {"type": "editorReady"}
     * ```
     * Sent when Kogito editor finishes initialization and is ready for interaction.
     * Currently logged but no action taken (content already loaded via initialContent promise).
     *
     * ## Error Handling
     * - Catches parse errors and logs full payload for debugging
     * - Unknown message types are logged as warnings
     * - Malformed JSON is caught and logged with stack trace
     *
     * ## Threading
     * Called from JCEF's message dispatch thread. Must be thread-safe.
     * Uses atomic operations for [isModified] and thread-safe [listeners] collection.
     *
     * @param payload JSON-formatted message string from JavaScript
     * @see jsQueryFromJs
     * @see saveContent
     * @see setModified
     */
    private fun handleJsMessage(payload: String) {
        try {
            logger.info("Received JS message: ${payload.take(100)}...")

            // Parse JSON using Gson for proper handling of special characters
            val json: JsonObject = try {
                JsonParser.parseString(payload).asJsonObject
            } catch (e: Exception) {
                logger.warn("Failed to parse JSON payload: ${payload.take(200)}", e)
                return
            }

            val messageType = json.get("type")?.asString
            if (messageType == null) {
                logger.warn("Message has no 'type' field: ${payload.take(200)}")
                return
            }

            when (messageType) {
                "contentChanged" -> {
                    val dirty = json.get("dirty")?.asBoolean ?: false
                    logger.info("Content changed, dirty: $dirty")
                    setModified(dirty)
                }
                "saveRequested" -> {
                    logger.info("Save requested from editor")
                    saveContent()
                }
                "content" -> {
                    val xml = json.get("xml")?.asString ?: ""
                    logger.info("Received content from editor, size: ${xml.length}")
                    // Atomically get and clear the pending save to avoid race conditions
                    pendingSave.getAndSet(null)?.complete(xml)
                }
                "editorReady" -> {
                    logger.info("Editor is ready")
                    // Content was already loaded via initialContent promise
                }
                else -> {
                    logger.warn("Unknown message type '$messageType': ${payload.take(200)}")
                }
            }
        } catch (e: Exception) {
            logger.warn("Bridge payload handling error: ${payload.take(200)}", e)
        }
    }

    /**
     * Initializes the JavaScript-to-Kotlin bridge and loads initial file content.
     *
     * This method is called after the HTML page loads successfully (from CefLoadHandler.onLoadEnd).
     * It performs two critical operations:
     *
     * ## Bridge Injection
     * Creates the `window.sendToIde()` function that JavaScript uses to send messages to Kotlin.
     * The function is created by injecting a script that wraps the JBCefJSQuery injection code:
     *
     * ```javascript
     * window.sendToIde = function(payload) {
     *   // JBCefJSQuery injection code here
     * };
     * ```
     *
     * This enables the JavaScript code to call:
     * ```javascript
     * window.sendToIde(JSON.stringify({type: 'contentChanged', dirty: true}));
     * ```
     *
     * ## Bridge Verification
     * - Sets `window.bridgeReady = true` flag to signal bridge availability
     * - JavaScript can poll this flag before attempting to use sendToIde()
     * - Logs bridge creation success/failure for debugging
     *
     * ## Content Loading
     * After bridge is established, immediately calls [loadInitialFileContent] to provide
     * the file's XML content to the editor before it finishes initialization.
     *
     * ## Timing Considerations
     * This method must run after page load but before JavaScript creates the Kogito editor.
     * The JavaScript bundle polls for bridge availability before proceeding with editor creation.
     *
     * @param browser The JCEF browser instance (must be fully loaded)
     * @see jsQueryFromJs
     * @see loadInitialFileContent
     * @see handleJsMessage
     */
    private fun initializeEditor(browser: JBCefBrowser) {
        logger.debug("Creating sendToIde bridge function...")

        // Create the bridge function manually using the JCEF query
        val bridgeScript = """
            (function() {
                console.log('[Kotlin] Creating sendToIde function...');

                // The actual bridge function
                window.sendToIde = function(payload) {
                    console.log('[Kotlin->JS] Sending to IDE:', payload.substring(0, 100));
                    ${jsQueryFromJs!!.inject("payload")}
                };

                console.log('[Kotlin] window.sendToIde type:', typeof window.sendToIde);

                if (typeof window.sendToIde === 'function') {
                    window.bridgeReady = true;
                    console.log('[Kotlin] ✅ Bridge ready!');
                } else {
                    console.error('[Kotlin] ❌ Bridge creation failed!');
                }
            })();
        """.trimIndent()

        logger.debug("Executing bridge creation script...")
        browser.cefBrowser.executeJavaScript(bridgeScript, browser.cefBrowser.url, 0)

        logger.debug("Bridge created, now setting initial content...")

        // Immediately provide the initial content before editor is created
        loadInitialFileContent(browser)
    }

    /**
     * Loads the file's content and injects it into the JavaScript editor.
     *
     * This method reads the BPMN/DMN XML file from disk and provides it to the JavaScript
     * editor by calling the `window.setInitialContent()` function that was exposed by the
     * JavaScript bundle during initialization.
     *
     * ## Content Reading
     * - Reads file bytes via [VirtualFile.contentsToByteArray]
     * - Converts to UTF-8 string
     * - Logs content size for debugging large file performance issues
     *
     * ## Injection Strategy
     * Uses Base64 encoding to avoid escaping issues with special characters in XML:
     * 1. Encode content as Base64 string
     * 2. Execute JavaScript to decode: `atob(base64String)`
     * 3. Call `window.setInitialContent(decodedContent)`
     *
     * This approach is more reliable than string escaping because XML may contain:
     * - Single and double quotes
     * - Newlines and special characters
     * - Unicode characters
     * - Template literals that could break JavaScript string literals
     *
     * ## Retry Logic
     * If `window.setInitialContent` is not yet available:
     * - Waits 100ms and retries once
     * - Handles race condition between page load and JavaScript module initialization
     * - Logs warning if function is still unavailable after retry
     *
     * ## Error Handling
     * - Catches file read errors and logs with full stack trace
     * - Shows error notification to user
     * - Catches JavaScript execution errors (logged in browser console)
     *
     * @param browser The JCEF browser instance with loaded page
     * @throws IOException if file cannot be read (caught and shown to user)
     * @see buildEditorHtml
     * @see initializeEditor
     */
    private fun loadInitialFileContent(browser: JBCefBrowser) {
        try {
            logger.debug("Loading initial file content: ${file.name} (${file.length} bytes)")
            val content = ReadAction.nonBlocking<String> {
                String(file.contentsToByteArray(), Charsets.UTF_8)
            }.executeSynchronously()
            logger.debug("File content read: ${content.length} characters")

            // Use Base64 encoding to avoid any escaping issues with special characters
            val base64Content = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))

            val js = """
                console.log('[Kotlin] Setting initial content via Base64...');
                console.log('[Kotlin] Base64 length: ${base64Content.length}');
                try {
                    var decodedContent = atob('$base64Content');
                    console.log('[Kotlin] Decoded content length: ' + decodedContent.length);
                    if (typeof window.setInitialContent === 'function') {
                        window.setInitialContent(decodedContent);
                        console.log('[Kotlin] ✅ Initial content set successfully');
                    } else {
                        console.error('[Kotlin] ❌ window.setInitialContent is not available yet!');
                        // Retry after a delay
                        setTimeout(function() {
                            if (typeof window.setInitialContent === 'function') {
                                window.setInitialContent(decodedContent);
                                console.log('[Kotlin] ✅ Initial content set on retry');
                            } else {
                                console.error('[Kotlin] ❌ Still no setInitialContent function!');
                            }
                        }, 100);
                    }
                } catch (e) {
                    console.error('[Kotlin] ❌ Failed to decode Base64 content:', e);
                }
            """.trimIndent()

            browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
            logger.debug("Initial content injection requested")

        } catch (e: Exception) {
            logger.error("Failed to load initial file content", e)
            notifyError("Failed to Load Content", e.message ?: "Unknown error")
        }
    }

    /**
     * Shows an informational notification to the user.
     *
     * Uses IntelliJ's notification system to display a balloon popup.
     * Notifications appear in the bottom-right corner and auto-dismiss after a timeout.
     *
     * @param title Notification title (bold text)
     * @param message Notification body message
     * @see NotificationGroupManager
     */
    private fun notifyInfo(title: String, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kogito Editor")
            .createNotification(title, message, NotificationType.INFORMATION)
            .notify(project)
    }

    /**
     * Shows an error notification to the user.
     *
     * Uses IntelliJ's notification system to display a balloon popup with error styling.
     * Error notifications persist until dismissed by the user.
     *
     * @param title Error title (bold text, shown in red)
     * @param message Error details message
     * @see NotificationGroupManager
     */
    private fun notifyError(title: String, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kogito Editor")
            .createNotification(title, message, NotificationType.ERROR)
            .notify(project)
    }

    /**
     * Shows a warning notification about OSR mode limitations, only once per IDE session.
     *
     * IntelliJ 2025.x enables out-of-process JCEF by default, which forces OSR mode.
     * This breaks drag-and-drop functionality and may cause freezes (IJPL-186252).
     * Users need to add a VM option to disable out-of-process mode.
     */
    private fun showOsrWarningOnce() {
        // Only show the warning once per IDE session using compare-and-set
        if (osrWarningShown.compareAndSet(false, true)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Kogito Editor")
                .createNotification(
                    "Kogito Editor: Drag-and-Drop May Not Work",
                    """
                    IntelliJ 2025.x uses out-of-process JCEF mode which disables drag-and-drop.
                    <br><br>
                    To fix this, add the following VM option:
                    <br><b>-Dide.browser.jcef.out-of-process.enabled=false</b>
                    <br><br>
                    Go to <b>Help → Edit Custom VM Options</b>, add the line, and restart IntelliJ.
                    """.trimIndent(),
                    NotificationType.WARNING
                )
                .notify(project)
        }
    }

    /**
     * Attempts to disable out-of-process JCEF mode via the registry.
     *
     * IntelliJ 2025.x enables out-of-process JCEF by default, which forces OSR mode
     * and breaks drag-and-drop. This method tries to disable it before the browser
     * is created, but may not work if JCEF is already initialized.
     *
     * Only attempts once per IDE session to avoid repeated registry access.
     */
    private fun tryDisableOutOfProcessJcef() {
        if (outOfProcessDisableAttempted.compareAndSet(false, true)) {
            try {
                val key = "ide.browser.jcef.out-of-process.enabled"
                if (Registry.`is`(key)) {
                    logger.info("Attempting to disable out-of-process JCEF mode via registry")
                    Registry.get(key).setValue(false)
                    logger.info("Registry key '$key' set to false")
                }
            } catch (e: Exception) {
                logger.warn("Failed to disable out-of-process JCEF via registry: ${e.message}")
            }
        }
    }

    /**
     * Updates the modified state and notifies property change listeners.
     *
     * This method uses compare-and-set semantics to update [isModified]:
     * - Only fires property change event if the value actually changed
     * - Thread-safe due to AtomicBoolean usage
     * - Notifies all registered listeners of the "modified" property change
     *
     * Property change listeners are used by IntelliJ to:
     * - Show/hide asterisk (*) in editor tab
     * - Enable/disable Save action
     * - Prompt user to save on close
     *
     * ## Threading
     * Thread-safe. Can be called from any thread (typically JCEF message handler thread).
     * Listeners are notified on the calling thread.
     *
     * @param dirty true if editor has unsaved changes, false if content is saved
     * @see isModified
     * @see listeners
     */
    private fun setModified(dirty: Boolean) {
        val old = isModified.getAndSet(dirty)
        if (old != dirty) {
            val evt = java.beans.PropertyChangeEvent(this, "modified", old, dirty)
            listeners.forEach { it.propertyChange(evt) }
        }
    }

    // === FileEditor API ===

    /**
     * Returns the root UI component for this editor.
     *
     * @return The JPanel containing the JCEF browser component
     * @see editorPanel
     */
    override fun getComponent(): JComponent = editorPanel

    /**
     * Returns the component that should receive focus when this editor is selected.
     *
     * @return The JCEF browser component, or null if not yet initialized
     * @see browserRef
     */
    override fun getPreferredFocusedComponent(): JComponent? = browserRef.get()?.component

    /**
     * Returns the display name for this editor tab.
     *
     * @return Human-readable editor type name ("BPMN Editor" or "DMN Editor")
     * @see EditorType.displayName
     */
    override fun getName(): String = editorType.displayName

    /**
     * Returns the file being edited.
     *
     * @return The VirtualFile representing the BPMN/DMN file
     */
    override fun getFile(): VirtualFile = file

    /**
     * Restores editor state from serialized data.
     *
     * Currently not implemented - editor state is not persisted between IDE sessions.
     * This means scroll position, zoom level, and other editor state is lost on close.
     *
     * @param state The state to restore (ignored)
     */
    override fun setState(state: FileEditorState) {}

    /**
     * Returns whether the editor has unsaved changes.
     *
     * This flag controls:
     * - Asterisk (*) display in editor tab
     * - "Save" action enablement
     * - Unsaved changes prompt on close
     *
     * @return true if content has been modified, false if saved
     * @see isModified
     * @see setModified
     */
    override fun isModified(): Boolean = isModified.get()

    /**
     * Returns whether this editor is still valid.
     *
     * An editor becomes invalid when the underlying file is deleted or moved.
     * Invalid editors are automatically closed by IntelliJ.
     *
     * @return true if the file still exists, false if deleted/invalid
     */
    override fun isValid(): Boolean = file.isValid

    /**
     * Registers a listener to be notified of property changes.
     *
     * IntelliJ uses this to listen for "modified" property changes to update the UI.
     *
     * @param listener The listener to register
     * @see listeners
     * @see setModified
     */
    override fun addPropertyChangeListener(listener: PropertyChangeListener) { listeners.add(listener) }

    /**
     * Unregisters a property change listener.
     *
     * @param listener The listener to remove
     * @see listeners
     */
    override fun removePropertyChangeListener(listener: PropertyChangeListener) { listeners.remove(listener) }

    /**
     * Returns the current caret location within the editor.
     *
     * Not applicable for visual editors - always returns null.
     * This is used by text editors for "Go to Line" functionality.
     *
     * @return null (not applicable for visual editors)
     */
    override fun getCurrentLocation() = null

    /**
     * Disposes of editor resources and cleans up.
     *
     * This method is called when:
     * - The editor tab is closed
     * - The file is deleted
     * - The project is closed
     *
     * ## Cleanup Steps
     * 1. Closes the Kogito editor via `window.app.close()` (if available)
     * 2. Disposes of the JavaScript bridge [jsQueryFromJs]
     * 3. Disposes of the JCEF browser instance
     *
     * ## Threading
     * May be called on any thread. Cleanup operations are thread-safe.
     *
     * @see jsQueryFromJs
     * @see browserRef
     */
    override fun dispose() {
        // Guard against double-dispose and mark as disposed atomically
        // This prevents race conditions with concurrent operations like saveContent()
        if (!disposed.compareAndSet(false, true)) {
            logger.debug("dispose() already called for ${file.name}, skipping")
            return
        }

        logger.info("Disposing KogitoEditor for ${file.name}")

        // 1. Cancel pending operations FIRST to prevent race conditions
        pendingSave.getAndSet(null)?.cancel(true)
        saveInProgress.set(false)

        // 2. Clear listeners early to prevent notifications during cleanup
        listeners.clear()

        // 3. Clear UI references BEFORE disposing browser
        // This removes the browser component from the Swing hierarchy first
        try {
            editorPanel.removeAll()
        } catch (e: Exception) {
            logger.debug("Failed to clear editorPanel", e)
        }

        // 4. Get browser reference (only once, atomically clear it)
        val browser = browserRef.getAndSet(null)

        // 5. Close the Kogito editor via JS
        try {
            browser?.cefBrowser?.executeJavaScript(
                "window.app && window.app.close && window.app.close()",
                browser.cefBrowser.url, 0
            )
        } catch (e: Exception) {
            logger.debug("Failed to close Kogito editor during dispose", e)
        }

        // 6. Navigate to about:blank to help release HTML content from
        // LOADHTML_REQUEST_MAP (WeakHashMap in JBCefFileSchemeHandlerFactory)
        // This clears the loaded content before disposing the browser
        try {
            browser?.loadURL("about:blank")
        } catch (e: Exception) {
            logger.debug("Failed to navigate to about:blank before dispose", e)
        }

        // 7. Remove CEF handlers from client BEFORE disposing browser
        // This is critical to prevent memory leaks - handlers hold references
        // to KogitoEditor via lambdas and must be explicitly removed
        // Remove each handler individually with its own try-catch
        // This ensures partial failures don't prevent other handlers from being removed
        val client = browser?.jbCefClient
        val cefBrowser = browser?.cefBrowser

        if (client != null && cefBrowser != null) {
            contextMenuHandler?.let { handler ->
                try {
                    client.removeContextMenuHandler(handler, cefBrowser)
                    logger.debug("Removed context menu handler")
                } catch (e: Exception) {
                    logger.warn("Failed to remove context menu handler", e)
                }
            }

            displayHandler?.let { handler ->
                try {
                    client.removeDisplayHandler(handler, cefBrowser)
                    logger.debug("Removed display handler")
                } catch (e: Exception) {
                    logger.warn("Failed to remove display handler", e)
                }
            }

            loadHandler?.let { handler ->
                try {
                    client.removeLoadHandler(handler, cefBrowser)
                    logger.debug("Removed load handler")
                } catch (e: Exception) {
                    logger.warn("Failed to remove load handler", e)
                }
            }

            keyboardHandler?.let { handler ->
                try {
                    client.removeKeyboardHandler(handler, cefBrowser)
                    logger.debug("Removed keyboard handler")
                } catch (e: Exception) {
                    logger.warn("Failed to remove keyboard handler", e)
                }
            }
        }

        // Clear handler references to break reference cycles
        contextMenuHandler = null
        displayHandler = null
        loadHandler = null
        keyboardHandler = null

        // 8. Clear JS query reference
        // Note: Actual disposal is handled automatically by Disposer hierarchy
        // (Editor → Browser → JSQuery) registered in createBrowserAndLoad()
        jsQueryFromJs = null

        // 9. Browser disposal is handled automatically by Disposer hierarchy
        // registered via Disposer.register(this, browser) in createBrowserAndLoad()
        // No manual dispose() call needed - Disposer ensures proper cleanup order

        logger.info("KogitoEditor disposed for ${file.name}")
    }

    // === Save Operations ===

    /**
     * Saves the editor content to disk asynchronously.
     *
     * This method implements the complete save workflow:
     *
     * ## Save Process
     * 1. Validates that browser is initialized
     * 2. Creates a CompletableFuture to track content retrieval
     * 3. Executes JavaScript to extract XML from Kogito editor:
     *    - DMN: `await window.app.getDmnXml()`
     *    - BPMN: `await window.app.getBpmnXml()`
     * 4. JavaScript sends XML back via `window.sendToIde({type:'content', xml})`
     * 5. [handleJsMessage] receives the message and completes [pendingSave] future
     * 6. Writes content to file using [WriteCommandAction] (IntelliJ's write-safe API)
     * 7. Updates [isModified] flag to false
     * 8. Notifies JavaScript editor via `window.app.markAsSaved()` to reset dirty state
     * 9. Shows success notification
     *
     * ## Async Communication Pattern
     * CEF's `executeJavaScript()` cannot return values directly. We work around this by:
     * - Storing a CompletableFuture in [pendingSave]
     * - JavaScript sends result back through the bridge
     * - Bridge handler completes the future
     * - Save operation continues with the result
     *
     * This enables request-response semantics over a one-way bridge.
     *
     * ## Write Safety
     * Uses [WriteCommandAction] to ensure:
     * - Write operation is undoable
     * - File system notifications are fired
     * - VCS integration is triggered
     * - Write lock is held during operation
     *
     * ## Error Handling
     * - Returns `false` if browser is not initialized
     * - Catches write errors and shows notification
     * - Handles JavaScript execution failures via promise rejection
     * - Logs all errors for debugging
     *
     * ## Threading
     * - Can be called from any thread (typically EDT or JCEF thread)
     * - File write occurs on EDT via WriteCommandAction
     * - CompletableFuture completes on JCEF thread
     *
     * ## Invocation Points
     * This method is called by:
     * - [KogitoSaveListener] when user presses Cmd+S/Ctrl+S
     * - Keyboard handler in browser (backup, but usually intercepted by listener)
     * - JavaScript editor if it sends "saveRequested" message
     *
     * @return CompletableFuture that completes with true on success, false on failure
     * @see pendingSave
     * @see handleJsMessage
     * @see KogitoSaveListener
     * @see WriteCommandAction
     */
    fun saveContent(): CompletableFuture<Boolean> {
        // Check if editor is disposed - prevents race condition with dispose()
        if (disposed.get()) {
            logger.debug("Editor is disposed, skipping save")
            return CompletableFuture.completedFuture(false)
        }

        // Check if project is disposed
        if (project.isDisposed) {
            logger.debug("Project is disposed, skipping save")
            return CompletableFuture.completedFuture(false)
        }

        // Prevent concurrent saves - if save is already in progress, return success
        // (the in-progress save will handle the content)
        if (!saveInProgress.compareAndSet(false, true)) {
            logger.info("Save already in progress, skipping duplicate request")
            return CompletableFuture.completedFuture(true)
        }

        // Validate browser
        val browser = browserRef.get()
        if (browser == null) {
            saveInProgress.set(false)
            return CompletableFuture.completedFuture(false)
        }

        // Request XML from the page. Since executeJavaScript cannot return,
        // JS will call window.sendToIde({type:"content", xml})
        val future = CompletableFuture<Boolean>()
        val contentFuture = CompletableFuture<String>()

        // Atomically set the pending save future
        pendingSave.set(contentFuture)

        // Ask JS to produce content based on editor type
        val js = when (editorType) {
            EditorType.DMN  -> "(async () => { const xml = await (window.app?.getDmnXml?.()); window.sendToIde(JSON.stringify({type:'content', xml: xml || ''})); })();"
            EditorType.BPMN -> "(async () => { const xml = await (window.app?.getBpmnXml?.()); window.sendToIde(JSON.stringify({type:'content', xml: xml || ''})); })();"
        }
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)

        // Add timeout to prevent hanging indefinitely if JS never responds
        contentFuture
            .orTimeout(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .thenAccept { xml ->
                // Schedule write on EDT to avoid deadlock - thenAccept runs on JCEF thread,
                // but WriteCommandAction needs EDT. Using invokeLater avoids blocking.
                ApplicationManager.getApplication().invokeLater {
                    try {
                        // Check if editor is disposed - browser may have been disposed
                        // while we were waiting for JS response
                        if (disposed.get()) {
                            logger.debug("Editor disposed during save, skipping post-save operations")
                            future.complete(false)
                            saveInProgress.set(false)
                            return@invokeLater
                        }

                        // Check if project is disposed before writing
                        if (project.isDisposed) {
                            logger.debug("Project disposed before save completed, skipping write")
                            future.complete(false)
                            saveInProgress.set(false)
                            return@invokeLater
                        }

                        WriteCommandAction.runWriteCommandAction(project) {
                            file.setBinaryContent(xml.toByteArray(Charsets.UTF_8))
                        }
                        setModified(false)
                        future.complete(true)

                        // Call markAsSaved() - use browserRef to get current browser reference
                        // in case the original was disposed and re-created (unlikely but safe)
                        val currentBrowser = browserRef.get()
                        currentBrowser?.cefBrowser?.executeJavaScript(
                            "window.app && window.app.markAsSaved && window.app.markAsSaved()",
                            currentBrowser.cefBrowser.url, 0
                        )
                    } catch (e: Exception) {
                        logger.error("Save error", e)
                        notifyError("Save Failed", e.message ?: "Unknown error")
                        future.complete(false)
                    } finally {
                        // Always clear the save-in-progress flag
                        saveInProgress.set(false)
                    }
                }
            }.exceptionally { ex ->
                // Clear pending save and save-in-progress flag on any error
                pendingSave.set(null)
                saveInProgress.set(false)

                val message = when (ex.cause) {
                    is TimeoutException -> "Save operation timed out after ${SAVE_TIMEOUT_SECONDS}s"
                    else -> ex.cause?.message ?: ex.message ?: "Could not retrieve content"
                }
                logger.error("Could not retrieve content from JS: $message", ex)
                notifyError("Save Failed", message)
                future.complete(false)
                null
            }

        return future
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
}