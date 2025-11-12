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

/**
 * Origin for postMessage communication with Kogito editor envelope.
 * Set to wildcard "*" to allow communication in JCEF's custom protocol environment.
 * Kogito editors use iframes and require cross-origin message passing.
 */
const ORIGIN = "*";

console.log("[Kogito] Window location:", window.location.href);
console.log("[Kogito] Window origin:", window.location.origin);
console.log("[Kogito] Using origin:", ORIGIN);

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
 */
function toIde(payload: unknown) {
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
 * Name of the file being edited.
 * Used for display in the editor and for file path normalization.
 */
const fileName = params.get("file") || "Untitled";

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
let resolveInitialContent: (content: string) => void;
const initialContent = new Promise<string>((resolve) => {
    resolveInitialContent = resolve;
    console.log("[Kogito] Initial content promise created, waiting for content...");
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
    console.log("[Kogito] ✅ setInitialContent called with", content.length, "characters");
    console.log("[Kogito] Content preview:", content.substring(0, 200));
    resolveInitialContent(content);
    console.log("[Kogito] Initial content promise resolved!");
};

console.log("[Kogito] Waiting for initial content before creating editor...");

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

console.log("[Kogito] ========== CREATING EDITOR ==========");
console.log("[Kogito] Editor type:", editorType);
console.log("[Kogito] File name:", fileName);
console.log("[Kogito] Read only:", readOnly);
console.log("[Kogito] Origin:", ORIGIN);

/**
 * DMN Editor Initialization
 *
 * Creates and initializes the DMN (Decision Model and Notation) editor
 * using Kogito's standalone DMN editor package.
 */
if (editorType === "dmn") {
    console.log("[Kogito] Opening DMN editor...");
    console.log("[Kogito] initialContent is a Promise:", initialContent instanceof Promise);

    // Log when the promise resolves
    initialContent.then((content) => {
        console.log("[Kogito] 🎉 initialContent promise resolved! Content length:", content.length);
        console.log("[Kogito] Content starts with:", content.substring(0, 100));
    });

    /**
     * DMN editor instance created by DmnEditor.open().
     *
     * Configuration:
     * - container: DOM element to render the editor in
     * - initialContent: Promise<string> that resolves with file content
     * - initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: File name for display
     * - readOnly: Whether editing is disabled
     * - origin: Origin for postMessage communication
     * - onError: Error handler for editor initialization errors
     * - resources: Map of additional resources (currently empty)
     *
     * The editor is an iframe-based component that communicates via postMessage.
     */
    const dmn = DmnEditor.open({
        container: document.getElementById("dmn-editor-container")!,
        initialContent,
        initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: fileName,
        readOnly,
        origin: ORIGIN,
        onError: (e) => {
            console.error("[Kogito] ❌ DMN ERROR:", e);
            alert("DMN Editor Error: " + JSON.stringify(e));
        },
        resources: new Map(),
    });
    console.log("[Kogito] DMN editor created:", dmn);

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
    const dmnContainer = document.getElementById("dmn-editor-container")!;
    const bpmnContainer = document.getElementById("bpmn-editor-container")!;
    dmnContainer.classList.add("visible");
    bpmnContainer.classList.remove("visible");
    console.log("[Kogito] DMN container display:", window.getComputedStyle(dmnContainer).display);
    console.log("[Kogito] DMN container dimensions:", dmnContainer.offsetWidth, "x", dmnContainer.offsetHeight);

/**
 * BPMN Editor Initialization
 *
 * Creates and initializes the BPMN (Business Process Model and Notation) editor
 * using Kogito's standalone BPMN editor package.
 */
} else {
    console.log("[Kogito] Opening BPMN editor...");

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
        console.log("[Kogito] Resource requested:", path);
        // Return empty promise for missing resources to suppress errors
        return Promise.resolve({ content: "", path });
    };

    /**
     * BPMN editor instance created by BpmnEditor.open().
     *
     * Configuration similar to DMN editor but with BPMN-specific features.
     * The resourceContentHandler allows loading additional BPMN resources.
     */
    const bpmn = BpmnEditor.open({
        container: document.getElementById("bpmn-editor-container")!,
        initialContent,
        readOnly,
        origin: ORIGIN,
        onError: (e) => {
            console.error("[Kogito] ❌ BPMN ERROR:", e);
            alert("BPMN Editor Error: " + JSON.stringify(e));
        },
        resources: new Map(),
        resourceContentHandler,
    });
    console.log("[Kogito] BPMN editor created:", bpmn);

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
    const dmnContainer = document.getElementById("dmn-editor-container")!;
    const bpmnContainer = document.getElementById("bpmn-editor-container")!;
    dmnContainer.classList.remove("visible");
    bpmnContainer.classList.add("visible");
    console.log("[Kogito] BPMN container display:", window.getComputedStyle(bpmnContainer).display);
    console.log("[Kogito] BPMN container dimensions:", bpmnContainer.offsetWidth, "x", bpmnContainer.offsetHeight);
}

// ============================================================================
// Bridge Readiness Notification
// ============================================================================

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
 * ## Why Polling is Needed
 * JavaScript module execution and Kotlin's bridge injection happen asynchronously.
 * We cannot guarantee which completes first, so we poll until bridge is ready.
 *
 * @see window.sendToIde
 * @see window.bridgeReady
 */
function notifyEditorReady() {
    if (typeof window.sendToIde === "function" && window.bridgeReady) {
        toIde({ type: "editorReady" });
        console.log("[Kogito] ✅ Editor ready event sent to IDE");
    } else {
        console.log("[Kogito] ⏳ Waiting for sendToIde bridge... (sendToIde exists:", typeof window.sendToIde === "function", ", bridgeReady:", window.bridgeReady, ")");
        setTimeout(notifyEditorReady, 100);
    }
}

// Start polling for bridge availability
console.log("[Kogito] 🔄 Starting bridge polling...");
notifyEditorReady();