/**
 * Main entry point for the Kogito editor web application.
 *
 * This module initializes either a BPMN or DMN editor based on URL parameters,
 * establishes bidirectional communication with the IntelliJ plugin via JCEF bridge,
 * and manages editor lifecycle and content synchronization.
 *
 * ## Architecture
 * - Runs in a JCEF browser embedded in IntelliJ
 * - Communicates with Kotlin code via `window.sendToIde()` bridge
 * - Receives initial content via `window.setInitialContent()` callback
 * - Uses Kogito's standalone editors from @kie-tools packages
 *
 * ## Initialization Flow
 * 1. Parse URL parameters (type, file, readonly)
 * 2. Wait for Kotlin to inject `window.sendToIde()` bridge function
 * 3. Create promise for initial content
 * 4. Initialize appropriate editor (DMN or BPMN)
 * 5. Wait for Kotlin to call `window.setInitialContent()` with file content
 * 6. Subscribe to content changes and expose API via `window.app`
 * 7. Notify IDE that editor is ready
 *
 * @module main
 * @since 0.0.1
 */

import * as DmnEditor from "@kie-tools/dmn-editor-standalone/dist";
import * as BpmnEditor from "@kie-tools/kie-editors-standalone/dist/bpmn";
import type { JsToKotlinMessage } from "./global";

// ============================================================================
// Logging & Initialization
// ============================================================================

console.log("[Kogito] Editor script loaded");

/**
 * Origin for postMessage communication with Kogito editor envelope.
 * Set to wildcard "*" to allow communication in JCEF's custom protocol environment.
 * Kogito editors use iframes and require cross-origin message passing.
 */
const ORIGIN = "*";

/**
 * Sends a message to the IntelliJ plugin via the JCEF bridge.
 *
 * This function provides a safe wrapper around `window.sendToIde()` which is
 * injected by the Kotlin side via JBCefJSQuery. Messages are JSON-stringified
 * before being sent.
 *
 * ## Message Format
 * All payloads should be objects with a `type` field:
 * ```typescript
 * {type: 'contentChanged', dirty: boolean}
 * {type: 'saveRequested'}
 * {type: 'content', xml: string}
 * {type: 'editorReady'}
 * ```
 *
 * ## Error Handling
 * - Logs warning if `window.sendToIde()` is not yet available
 * - Catches serialization errors and logs them
 * - Does not throw exceptions (fire-and-forget pattern)
 *
 * ## Threading
 * Executes synchronously on JavaScript main thread.
 * Kotlin receives message on JCEF callback thread.
 *
 * @param payload The message payload to send (will be JSON-stringified)
 * @see window.sendToIde
 * @see JsToKotlinMessage
 */
function toIde(payload: JsToKotlinMessage) {
    if (typeof window.sendToIde === "function") {
        try {
            window.sendToIde(JSON.stringify(payload));
        } catch (e) {
            // eslint-disable-next-line no-console
            console.warn("Failed to send payload to IDE:", e, payload);
        }
    } else {
        // eslint-disable-next-line no-console
        console.warn("sendToIde is not yet available on window");
    }
}

/**
 * URL parameters parsed from window.location.search.
 * Set by Kotlin when building the HTML page.
 */
const params = new URLSearchParams(window.location.search);

/**
 * Editor type to initialize ('bpmn' or 'dmn').
 * Determines which Kogito editor to load.
 */
const editorType = params.get("type") || "bpmn";

/**
 * Path of the file relative to workspace root.
 * Used by Kogito to determine which resources are in parent directories.
 * Example: "kogito/poc-medicaid-rules/src/main/resources/aca-subsidy-rules.dmn"
 */
const filePathFromRoot = params.get("file") || "Untitled";

/**
 * Whether the editor should be read-only.
 * When true, editing is disabled (view-only mode).
 */
const readOnly = params.get("readonly") === "true";

/**
 * Promise that resolves with the initial file content.
 *
 * This promise is created immediately but resolves later when Kotlin
 * calls `window.setInitialContent()` with the file's XML content.
 *
 * The Kogito editors accept this promise as the `initialContent` parameter,
 * allowing them to initialize asynchronously while waiting for content.
 *
 * ## Why Use a Promise?
 * - Avoids race conditions between page load and content availability
 * - Kogito editors natively support Promise<string> for initial content
 * - Provides clean async coordination between Kotlin and JavaScript
 *
 * @see window.setInitialContent
 */
let resolveInitialContent: ((content: string) => void) | null = null;
const initialContent = new Promise<string>((resolve) => {
    resolveInitialContent = resolve;
});

/**
 * Callback function exposed to Kotlin for providing initial file content.
 *
 * Kotlin calls this function after:
 * 1. Page has loaded (CefLoadHandler.onLoadEnd)
 * 2. Bridge has been injected (window.sendToIde created)
 * 3. File content has been read from VirtualFile
 * 4. Content has been Base64-decoded in JavaScript
 *
 * This function resolves the [initialContent] promise, allowing the
 * Kogito editor to complete initialization with the file's content.
 *
 * ## Timing
 * This must be called BEFORE the Kogito editor finishes initializing,
 * otherwise the editor will initialize with empty content.
 *
 * ## Content Format
 * Content should be raw XML string (UTF-8), not Base64-encoded.
 * Kotlin handles decoding before calling this function.
 *
 * @param content The XML content of the BPMN/DMN file
 * @see initialContent
 */
window.setInitialContent = (content: string) => {
    if (resolveInitialContent) {
        resolveInitialContent(content);
        resolveInitialContent = null; // Clear after use to prevent double-resolution
    } else {
        console.warn("[Kogito] setInitialContent called but resolver is null (already resolved?)");
    }
};

/**
 * Builds the resources Map from window.editorResources.
 *
 * The Kotlin side injects `window.editorResources` as a JavaScript object
 * containing Base64-encoded resource contents:
 * ```javascript
 * {
 *   "path/to/model.dmn": "BASE64_CONTENT",
 *   "path/to/tasks.wid": "BASE64_CONTENT"
 * }
 * ```
 *
 * This function converts it to the format expected by Kogito editors:
 * ```javascript
 * Map([
 *   ["path/to/model.dmn", {contentType: "text", content: Promise.resolve("DECODED_CONTENT")}],
 *   ["path/to/tasks.wid", {contentType: "text", content: Promise.resolve("DECODED_CONTENT")}]
 * ])
 * ```
 *
 * ## Resource Types
 * - DMN Editor: Uses DMN files as included models
 * - BPMN Editor: Uses .wid files as Work Item Definitions
 *
 * @returns Map of resources in the format expected by Kogito editors
 */
function buildResourcesMap(): Map<string, { contentType: "text" | "binary"; content: Promise<string> }> {
    const resourcesMap = new Map<string, { contentType: "text" | "binary"; content: Promise<string> }>();

    // Check if window.editorResources exists
    if (!window.editorResources || typeof window.editorResources !== "object") {
        return resourcesMap;
    }

    // Convert each Base64-encoded resource to the proper format
    for (const [path, base64Content] of Object.entries(window.editorResources)) {
        try {
            // Decode Base64 content with proper UTF-8 handling
            // atob() alone doesn't handle multi-byte UTF-8 characters correctly
            const binaryString = atob(base64Content);
            const bytes = Uint8Array.from(binaryString, char => char.charCodeAt(0));
            const decodedContent = new TextDecoder("utf-8").decode(bytes);

            // Add to map with proper format
            resourcesMap.set(path, {
                contentType: "text",
                content: Promise.resolve(decodedContent)
            });
        } catch (e) {
            console.error("[Kogito] Failed to decode resource:", path, e);
        }
    }

    return resourcesMap;
}

/**
 * Notifies the IDE when editor content changes (dirty state).
 *
 * This callback is registered with both DMN and BPMN editors via
 * `subscribeToContentChanges()`. It's called whenever:
 * - User makes an edit in the editor
 * - Content is programmatically changed
 * - Editor is saved (dirty = false)
 *
 * The IDE uses this to:
 * - Show/hide asterisk (*) in editor tab
 * - Enable/disable Save action
 * - Prompt user on close if there are unsaved changes
 *
 * @param dirty true if content has unsaved changes, false if saved
 * @see toIde
 */
const notifyDirty = (dirty: boolean) =>
    toIde({ type: "contentChanged", dirty });

// ============================================================================
// Editor Initialization
// ============================================================================

console.log("[Kogito] Initializing", editorType.toUpperCase(), "editor for:", filePathFromRoot);

/**
 * DMN Editor Initialization
 *
 * Creates and initializes the DMN (Decision Model and Notation) editor
 * using Kogito's standalone DMN editor package.
 */
if (editorType === "dmn") {
    // Get DOM elements with null checks
    const dmnContainer = document.getElementById("dmn-editor-container");
    const bpmnContainer = document.getElementById("bpmn-editor-container");

    if (!dmnContainer) {
        console.error("[Kogito] FATAL: dmn-editor-container not found in DOM");
        throw new Error("dmn-editor-container element not found");
    }

    /**
     * DMN editor instance created by DmnEditor.open().
     *
     * Configuration:
     * - container: DOM element to render the editor in
     * - initialContent: Promise<string> that resolves with file content
     * - initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: Path from workspace root
     * - readOnly: Whether editing is disabled
     * - origin: Origin for postMessage communication
     * - onError: Error handler for editor initialization errors
     * - resources: Map of DMN/PMML files that can be used as included models
     *
     * NOTE: The @kie-tools/dmn-editor-standalone v10.1.0 does NOT support
     * onRequestExternalModelsAvailableToInclude or onRequestExternalModelByPath callbacks.
     * All resources must be provided upfront via the resources Map.
     *
     * The editor is an iframe-based component that communicates via postMessage.
     */
    const dmn = DmnEditor.open({
        container: dmnContainer,
        initialContent,
        initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: filePathFromRoot,
        readOnly,
        origin: ORIGIN,
        onError: (e) => {
            console.error("[Kogito] DMN ERROR:", e);
            alert("DMN Editor Error: " + JSON.stringify(e));
        },
        resources: buildResourcesMap(),
    });

    // Subscribe to content changes to track dirty state
    dmn.subscribeToContentChanges(notifyDirty);

    /**
     * DMN editor API exposed on window.app for Kotlin to interact with.
     *
     * This object provides methods for:
     * - Getting current DMN XML content (for saving)
     * - Setting DMN XML content (for external updates)
     * - Marking editor as saved (resets dirty state)
     * - Closing the editor (cleanup)
     *
     * Kotlin calls these methods via JavaScript execution:
     * ```kotlin
     * browser.executeJavaScript("window.app.getDmnXml()")
     * browser.executeJavaScript("window.app.markAsSaved()")
     * ```
     *
     * @see window.AppAPI
     */
    window.app = {
        async getDmnXml() {
            return await dmn.getContent();
        },
        async setDmnXml(name: string, xml: string) {
            await dmn.setContent(name, xml);
        },
        markAsSaved() { dmn.markAsSaved(); },
        close() { dmn.close(); },
    };

    // Show only DMN container
    dmnContainer.classList.add("visible");
    bpmnContainer?.classList.remove("visible");

/**
 * BPMN Editor Initialization
 *
 * Creates and initializes the BPMN (Business Process Model and Notation) editor
 * using Kogito's standalone BPMN editor package.
 */
} else {
    // Get DOM elements with null checks
    const dmnContainer = document.getElementById("dmn-editor-container");
    const bpmnContainer = document.getElementById("bpmn-editor-container");

    if (!bpmnContainer) {
        console.error("[Kogito] FATAL: bpmn-editor-container not found in DOM");
        throw new Error("bpmn-editor-container element not found");
    }

    /**
     * Resource content handler for BPMN editor.
     *
     * BPMN editors may request additional resources (Work Item Definitions, etc.).
     * This handler returns empty content for missing resources to suppress errors
     * while still allowing the editor to function normally.
     *
     * For a full implementation, this could:
     * - Fetch resources from project directory
     * - Cache resources for performance
     * - Report missing critical resources to user
     *
     * @param path The resource path being requested
     * @returns Promise resolving to resource content (empty for now)
     */
    const resourceContentHandler = (path: string) => {
        // Return empty promise for missing resources to suppress errors
        return Promise.resolve({ content: "", path });
    };

    /**
     * BPMN editor instance created by BpmnEditor.open().
     *
     * Configuration similar to DMN editor but with BPMN-specific features:
     * - resources: Map of .wid (Work Item Definition) files for custom tasks
     * - resourceContentHandler: Fallback handler for dynamically requested resources
     */
    const bpmn = BpmnEditor.open({
        container: bpmnContainer,
        initialContent,
        readOnly,
        origin: ORIGIN,
        onError: (e) => {
            console.error("[Kogito] BPMN ERROR:", e);
            alert("BPMN Editor Error: " + JSON.stringify(e));
        },
        resources: buildResourcesMap(),
        resourceContentHandler,
    });

    // Subscribe to content changes to track dirty state
    bpmn.subscribeToContentChanges(notifyDirty);

    /**
     * BPMN editor API exposed on window.app for Kotlin to interact with.
     *
     * Similar to DMN API but provides BPMN-specific methods.
     * Kotlin uses these methods for save operations and lifecycle management.
     *
     * @see window.AppAPI
     */
    window.app = {
        async getBpmnXml() {
            return await bpmn.getContent();
        },
        async setBpmnXml(name: string, xml: string) {
            await bpmn.setContent(name, xml);
        },
        markAsSaved() { bpmn.markAsSaved(); },
        close() { bpmn.close(); },
    };

    // Show only BPMN container
    dmnContainer?.classList.remove("visible");
    bpmnContainer.classList.add("visible");
}

// ============================================================================
// Bridge Readiness Notification
// ============================================================================

/**
 * Maximum time to wait for bridge availability (30 seconds).
 * After this time, we give up and log an error.
 */
const BRIDGE_TIMEOUT_MS = 30000;

/**
 * Polling interval for bridge availability check (100ms).
 */
const BRIDGE_POLL_INTERVAL_MS = 100;

/**
 * Notifies the IDE that the editor is fully initialized and ready.
 *
 * This function polls until the bridge is available before sending the notification.
 * The bridge may not be immediately available due to timing between:
 * - Page load (JavaScript module execution)
 * - Bridge injection (Kotlin executeJavaScript call)
 *
 * ## Polling Logic
 * Checks every 100ms for:
 * - `window.sendToIde` function exists
 * - `window.bridgeReady` flag is true
 *
 * Once both conditions are met, sends "editorReady" message to IDE.
 *
 * ## Timeout
 * If bridge is not available after 30 seconds, stops polling and logs an error.
 * This prevents infinite polling in case of initialization failure.
 *
 * ## Why Polling is Needed
 * JavaScript module execution and Kotlin's bridge injection happen asynchronously.
 * We cannot guarantee which completes first, so we poll until bridge is ready.
 *
 * @param startTime The timestamp when polling started (for timeout calculation)
 * @see window.sendToIde
 * @see window.bridgeReady
 */
function notifyEditorReady(startTime: number = Date.now()) {
    if (typeof window.sendToIde === "function" && window.bridgeReady) {
        toIde({ type: "editorReady" });
        console.log("[Kogito] Editor ready");
    } else {
        const elapsed = Date.now() - startTime;
        if (elapsed >= BRIDGE_TIMEOUT_MS) {
            console.error(
                "[Kogito] Bridge not available after",
                BRIDGE_TIMEOUT_MS / 1000,
                "seconds. Editor may not function correctly."
            );
            return; // Give up after timeout
        }
        setTimeout(() => notifyEditorReady(startTime), BRIDGE_POLL_INTERVAL_MS);
    }
}

// Start polling for bridge availability
notifyEditorReady();