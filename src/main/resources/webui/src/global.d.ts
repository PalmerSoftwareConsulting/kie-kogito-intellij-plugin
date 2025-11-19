/**
 * Global type declarations for the Kogito IntelliJ Plugin web application.
 *
 * This file defines TypeScript types for:
 * - The JCEF bridge communication between JavaScript and Kotlin
 * - The Kogito editor API exposed on the window object
 * - Global functions injected by the IntelliJ plugin
 *
 * ## Browser Environment
 * This code runs in a JCEF (Java Chromium Embedded Framework) browser
 * embedded within IntelliJ IDEA, not a standard browser environment.
 *
 * @module global
 * @since 0.0.1
 */

export {};

declare global {
    /**
     * API interface for interacting with Kogito editors (BPMN or DMN).
     *
     * This interface is implemented in main.ts and exposed on `window.app`.
     * Kotlin code calls these methods via JavaScript execution to:
     * - Retrieve editor content for saving
     * - Update editor content programmatically
     * - Manage editor lifecycle
     *
     * ## Implementation
     * The implementation varies by editor type:
     * - DMN editors provide `getDmnXml()` and `setDmnXml()`
     * - BPMN editors provide `getBpmnXml()` and `setBpmnXml()`
     * - Both provide `markAsSaved()` and `close()`
     *
     * ## Usage from Kotlin
     * ```kotlin
     * // Get content for saving
     * browser.executeJavaScript("window.app.getDmnXml()")
     *
     * // Mark as saved after successful write
     * browser.executeJavaScript("window.app.markAsSaved()")
     *
     * // Close on disposal
     * browser.executeJavaScript("window.app.close()")
     * ```
     *
     * @see main.ts
     */
    interface AppAPI {
        /**
         * Gets the current DMN XML content from the editor.
         *
         * Only available when editor type is 'dmn'.
         *
         * @returns Promise resolving to DMN XML string
         */
        getDmnXml?(): Promise<string>;

        /**
         * Sets the DMN XML content in the editor.
         *
         * Only available when editor type is 'dmn'.
         *
         * @param name The file name for the content
         * @param xml The DMN XML content to set
         * @returns Promise that resolves when content is set
         */
        setDmnXml?(name: string, xml: string): Promise<void>;

        /**
         * Gets the current BPMN XML content from the editor.
         *
         * Only available when editor type is 'bpmn'.
         *
         * @returns Promise resolving to BPMN XML string
         */
        getBpmnXml?(): Promise<string>;

        /**
         * Sets the BPMN XML content in the editor.
         *
         * Only available when editor type is 'bpmn'.
         *
         * @param name The file name for the content
         * @param xml The BPMN XML content to set
         * @returns Promise that resolves when content is set
         */
        setBpmnXml?(name: string, xml: string): Promise<void>;

        /**
         * Marks the editor as saved, resetting the dirty state.
         *
         * This should be called after successfully writing content to disk.
         * It triggers content change callbacks with dirty=false.
         *
         * @returns void
         */
        markAsSaved(): void;

        /**
         * Closes the editor and cleans up resources.
         *
         * Called during editor disposal to properly shut down the Kogito editor.
         *
         * @returns void
         */
        close(): void;
    }

    /**
     * Extended Window interface with plugin-specific properties and functions.
     *
     * These properties are set at runtime by either:
     * - Kotlin code injecting bridge functions
     * - JavaScript code exposing callbacks and APIs
     */
    interface Window {
        /**
         * Bridge function for sending messages from JavaScript to Kotlin.
         *
         * Injected by Kotlin code using JBCefJSQuery.inject().
         * The function is created in KogitoEditor.initializeEditor() and injected
         * into the page after it loads.
         *
         * ## Message Format
         * Expects JSON string payload with a 'type' field:
         * ```typescript
         * sendToIde(JSON.stringify({type: 'contentChanged', dirty: true}))
         * ```
         *
         * ## Timing
         * Not available immediately on page load. JavaScript should check for
         * existence or wait for `bridgeReady` flag before using.
         *
         * ## Error Handling
         * Should not throw exceptions. Kotlin side logs any issues.
         *
         * @param payload JSON string message to send to Kotlin
         * @returns unknown (return value not used)
         * @see bridgeReady
         */
        sendToIde?: (payload: string) => unknown;

        /**
         * Flag indicating the JCEF bridge is ready for use.
         *
         * Set to true by Kotlin after successfully injecting `sendToIde()`.
         * JavaScript can poll this flag before attempting to use the bridge.
         *
         * ## Usage Pattern
         * ```typescript
         * function waitForBridge() {
         *   if (window.sendToIde && window.bridgeReady) {
         *     // Bridge is ready, safe to use
         *   } else {
         *     setTimeout(waitForBridge, 100);
         *   }
         * }
         * ```
         *
         * @see sendToIde
         */
        bridgeReady?: boolean;

        /**
         * Callback for Kotlin to provide initial file content.
         *
         * Set by main.ts during initialization. Kotlin calls this function after:
         * 1. Reading file content from VirtualFile
         * 2. Base64-encoding the content
         * 3. Injecting JavaScript to decode and call this function
         *
         * This function resolves the `initialContent` promise in main.ts,
         * allowing the Kogito editor to complete initialization.
         *
         * ## Content Format
         * Expects raw XML string (UTF-8), not Base64-encoded.
         * Kotlin decodes before calling this function.
         *
         * ## Timing
         * Must be called before Kogito editor finishes initialization,
         * otherwise editor will start with empty content.
         *
         * @param content The XML content of the BPMN/DMN file
         * @returns void
         * @see main.ts initialContent promise
         */
        setInitialContent?: (content: string) => void;

        /**
         * API for interacting with the initialized Kogito editor.
         *
         * Set by main.ts after creating the DMN or BPMN editor.
         * Provides methods for content retrieval, lifecycle management, etc.
         *
         * @see AppAPI
         */
        app?: AppAPI;

        /**
         * Resources for the Kogito editor, provided by Kotlin.
         *
         * Injected by Kotlin code during HTML generation in KogitoEditor.buildEditorHtml().
         * Contains Base64-encoded file contents mapped by relative POSIX paths.
         *
         * ## Content by Editor Type
         * - **DMN Editor**: Contains all DMN files in the project for use as included models
         * - **BPMN Editor**: Contains all .wid (Work Item Definition) files for custom tasks
         *
         * ## Format
         * ```typescript
         * {
         *   "path/to/model.dmn": "BASE64_ENCODED_CONTENT",
         *   "path/to/tasks.wid": "BASE64_ENCODED_CONTENT"
         * }
         * ```
         *
         * ## Path Format
         * Paths are relative to the project base directory and normalized to POSIX format:
         * - No leading slash
         * - Forward slashes (/) as separators (even on Windows)
         * - For DMN: Excludes the current file being edited
         *
         * ## Usage
         * JavaScript code should decode the Base64 content using `atob()` and
         * convert to the format expected by Kogito editors via `buildResourcesMap()`.
         *
         * ## Important Note (DMN)
         * From Kogito documentation: "Resources located in a parent directory
         * (in relation to the current content path) won't be listed to be used
         * as an Included Model."
         *
         * The filtering is handled by the Kogito editor itself based on path relationships.
         *
         * @see buildResourcesMap
         * @see KogitoEditor.discoverResources
         */
        editorResources?: Record<string, string>;
    }
}