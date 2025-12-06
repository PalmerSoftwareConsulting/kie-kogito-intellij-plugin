# Kogito IntelliJ Plugin

An IntelliJ IDEA plugin that integrates Kogito's standalone BPMN and DMN editors, providing an alternative to the VS Code extension for JetBrains IDEs.

<!-- Plugin description -->
The Kogito IntelliJ Plugin brings powerful visual editing capabilities for Business Process Model and Notation (BPMN) and Decision Model and Notation (DMN) files directly into IntelliJ IDEA. Built on Kogito's standalone editors, this plugin provides a seamless integration that allows developers to design, edit, and visualize business processes and decision models without leaving their IDE.

**Key Features:**
- Visual BPMN 2.0 editor with full modeling capabilities
- DMN 1.2+ decision table and expression editor
- Real-time content synchronization
- File modification tracking
- Automatic server lifecycle management
- Support for large files (up to 10MB)
- Robust error handling and reconnection logic

**Supported File Types:**
- `.bpmn` and `.bpmn2` - BPMN process diagrams
- `.dmn` - DMN decision models
<!-- Plugin description end -->

## License

This plugin is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

### Third-Party Components

This plugin integrates the [Kogito Standalone Editors](https://github.com/apache/incubator-kie-tools) by Red Hat, Inc., licensed under Apache License 2.0.

- **BPMN Editor:** © Red Hat, Inc. and/or its affiliates
- **DMN Editor:** © Red Hat, Inc. and/or its affiliates

## Architecture

The plugin uses **direct JCEF integration** with bundled JavaScript editors:

```
┌─────────────────────────────────────────┐
│         IntelliJ IDEA Plugin            │
│  ┌───────────────────────────────────┐  │
│  │   KogitoEditor (FileEditor)       │  │
│  │   ┌───────────────────────────┐   │  │
│  │   │  JCEF Browser (Chromium)  │   │  │
│  │   │  - OSR Disabled           │   │  │
│  │   │  - Bundled HTML/JS        │   │  │
│  │   │  - @kie-tools editors     │   │  │
│  │   └───────────┬───────────────┘   │  │
│  └───────────────┼───────────────────┘  │
│                  │ JBCefJSQuery         │
│  ┌───────────────▼───────────────────┐  │
│  │  Kotlin Bridge Handler            │  │
│  │  - Content sync via JS bridge     │  │
│  │  - Direct method calls            │  │
│  │  - Save action integration        │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
           │
           └─ TypeScript/Vite Frontend
              (bundled during build)
```

**Key Components:**
- **KogitoEditor**: FileEditor implementation with embedded JCEF browser
- **Vite-bundled Frontend**: TypeScript/JavaScript compiled to single bundle (~30MB)
- **JBCefJSQuery**: Bidirectional bridge between Kotlin and JavaScript
- **Kogito Standalone Editors**: @kie-tools/dmn-editor-standalone and @kie-tools/kie-editors-standalone
- **KogitoSaveListener**: Integrates Cmd+S/Ctrl+S with IntelliJ's save action

## Requirements

- **IntelliJ IDEA**: 2024.3.6 or later (with JCEF support)
- **JDK**: 21 or later (for plugin development only)

### IntelliJ 2025.x Compatibility

IntelliJ 2025.1+ introduced out-of-process JCEF mode by default (IJPL-162747), which causes two issues for this plugin:
1. **Freezes when editing** (IJPL-186252) - Known bug in out-of-process JCEF
2. **Drag-and-drop breaks** (JBR-7399) - Out-of-process mode forces OSR (Off-Screen Rendering), which doesn't support HTML5 drag-and-drop

**Why this can't be fixed in code**: When out-of-process mode is enabled, `JBCefBrowserBuilder.setOffScreenRendering(false)` is **ignored** by IntelliJ's JCEF implementation:

```java
// From JBCefBrowserBuilder.java
public JBCefBrowserBuilder setOffScreenRendering(boolean isOffScreenRendering) {
    if (!isOffScreenRendering) {
        if (JBCefApp.isRemoteEnabled()) {
            LOG.warn("Trying to create windowed browser when remote-mode is enabled. " +
                     "Settings isOffScreenRendering=false will be ignored.");
            myIsOffScreenRendering = true;  // Forced back to true!
            return this;
        }
    }
    myIsOffScreenRendering = isOffScreenRendering;
    return this;
}
```

The `IS_REMOTE_ENABLED` flag in `JBCefApp` is declared `static final` and set during class initialization before any plugin code runs - it cannot be changed at runtime:

```java
// From JBCefApp.java
private static final boolean IS_REMOTE_ENABLED;

static {
    // ... registry/property checks ...
    IS_REMOTE_ENABLED = CefApp.isRemoteEnabled();
}

static boolean isRemoteEnabled() {
    return IS_REMOTE_ENABLED;
}
```

**Source links**:
- [JBCefBrowserBuilder.java](https://github.com/JetBrains/intellij-community/blob/idea/252.25557.131/platform/ui.jcef/jcef/JBCefBrowserBuilder.java)
- [JBCefApp.java](https://github.com/JetBrains/intellij-community/blob/idea/252.25557.131/platform/ui.jcef/jcef/JBCefApp.java)

The VM option must be set before the IDE starts.

**For development** (`./gradlew runIde`): The plugin automatically adds `-Dide.browser.jcef.out-of-process.enabled=false` to fix this.

**For installed plugins**: Add this VM option manually:
1. **Help** → **Edit Custom VM Options**
2. Add: `-Dide.browser.jcef.out-of-process.enabled=false`
3. Restart IntelliJ

This reverts JCEF to in-process mode, restoring drag-and-drop and fixing the freeze issues.

The plugin is fully self-contained:
- No external dependencies required for end users
- JavaScript editors are pre-bundled during build
- Works offline after installation
- JCEF (Chromium) is included with IntelliJ IDEA

## Quick Start

### Prerequisites Check

Before installing, ensure you have:

```bash
# 1. Check Java version (21+, for development only)
java -version

# 2. Check IntelliJ IDEA version (2024.3.6+)
# Help → About → JetBrains Runtime should show "JCEF"
```

### 5-Minute Setup

1. **Clone and build the plugin:**
   ```bash
   git clone https://github.com/PalmerSoftwareConsulting/kie-kogito-intellij-plugin.git
   cd kie-kogito-intellij-plugin
   ./gradlew buildPlugin
   ```

2. **Install in IntelliJ:**
   - **Settings** → **Plugins** → **⚙️ (gear icon)** → **Install Plugin from Disk...**
   - Select: `build/distributions/kie-kogito-intellij-plugin-*.zip`
   - Click **OK** and restart IntelliJ

3. **Test it out:**
   - Create or open a `.bpmn` or `.dmn` file
   - The Kogito editor should open automatically
   - Try editing and saving (**Cmd+S** on macOS, **Ctrl+S** on Windows/Linux)

### First Time Use

When you open your first BPMN/DMN file:
1. The plugin initializes the JCEF browser component
2. The bundled Kogito editor loads (typically < 1 second)
3. Your file content is displayed in the visual editor
4. Changes save when you press **Cmd+S** (macOS) or **Ctrl+S** (Windows/Linux)

**Note**: The editors are bundled within the plugin JAR - no external downloads or caching required.

## Installation

### From Source (Development)

1. Clone the repository:
   ```bash
   git clone https://github.com/PalmerSoftwareConsulting/kie-kogito-intellij-plugin.git
   cd kie-kogito-intellij-plugin
   ```

2. Build the plugin:
   ```bash
   ./gradlew buildPlugin
   ```

3. Install in IntelliJ IDEA:
   - Go to **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk...**
   - Select `build/distributions/kie-kogito-intellij-plugin-*.zip`

### From JetBrains Marketplace (Coming Soon)

Once published, you'll be able to install directly from the IDE:
- **Settings** → **Plugins** → **Marketplace** → Search for "Kogito"

## Usage

1. **Open a BPMN or DMN file** in IntelliJ IDEA
2. The Kogito editor will automatically open in a new tab
3. Edit your diagram using the visual editor
4. Changes are automatically synchronized
5. Save the file using **Cmd+S** (macOS) or **Ctrl+S** (Windows/Linux)

### Supported Operations

- **Visual Editing**: Full drag-and-drop diagram creation (palette items draggable onto canvas)
- **Properties**: Edit element properties through side panels
- **Validation**: Real-time validation feedback
- **Undo/Redo**: Full undo/redo support (Cmd+Z / Ctrl+Z)
- **Save**: Standard IntelliJ save shortcuts (Cmd+S / Ctrl+S)

## Development Status

### ✅ Completed Features

**Core Functionality:**
- ✅ BPMN and DMN file type registration
- ✅ Custom file editor provider
- ✅ JCEF browser integration with OSR disabled
- ✅ JBCefJSQuery bridge for Kotlin ↔ JavaScript communication
- ✅ File content synchronization
- ✅ Direct content loading via Base64 encoding
- ✅ Editor state tracking

**Robustness & Reliability:**
- ✅ Thread-safe editor initialization
- ✅ Resource leak prevention
- ✅ Atomic state management (isModified flag)
- ✅ Thread-safe property listeners (CopyOnWriteArrayList)
- ✅ Proper JCEF browser disposal
- ✅ Graceful error handling and cleanup

**Documentation:**
- ✅ Comprehensive KDoc comments for all Kotlin classes and methods
- ✅ JSDoc comments for TypeScript/JavaScript frontend code
- ✅ Architecture documentation with diagrams

**User Experience:**
- ✅ Keyboard shortcuts: Cmd+S/Ctrl+S for save (FileDocumentManagerListener integration)
- ✅ Drag-and-drop support in DMN/BPMN editor palettes (OSR mode disabled)
- ✅ Clean UI without browser toolbars or context menus
- ✅ Undo/Redo functionality (Cmd+Z/Ctrl+Z - built into Kogito editor)
- ✅ User-friendly error notifications
- ✅ Loading states with helpful messages

### 🚧 In Progress / Planned

**Testing:**
- Unit tests for critical components
- Integration tests for editor lifecycle
- E2E tests for file operations
- Performance benchmarking

**Advanced Features:**
- Custom icons for BPMN/DMN files
- Multiple file editing support
- Dark/light theme synchronization
- Work Item Definitions support (BPMN)
- DMN included models support

**Performance:**
- HTTP connection pooling
- Optimized file I/O operations
- Metrics and monitoring
- Bundle size optimization

## Recent Improvements

### Type Safety & Build Modernization (Dec 2025)

**TypeScript Improvements:**
- Added comprehensive type definitions in `global.d.ts`
- Discriminated union types for bridge messages (`JsToKotlinMessage`, `KotlinToJsMessage`)
- Type-safe `toIde()` function signature
- Request/response correlation with `requestId` pattern
- Fixed UTF-8 handling in Base64 decoding using `TextDecoder`
- Strict TypeScript configuration (`tsconfig.json`)

**Build Updates:**
- Updated to Vite 7.x and TypeScript 5.9.x
- Modern ES2020 target with bundler module resolution

**Critical Bug Fix:**
- Fixed EDT deadlock that caused infinite loading wheel when pressing Cmd+S/Ctrl+S to save
- Root cause: `CompletableFuture.thenAccept()` callback ran on JCEF thread, but `WriteCommandAction` needs EDT
- Solution: Wrapped write operation in `ApplicationManager.invokeLater{}` to schedule on EDT without blocking

### Code Cleanup (Dec 2025)

**Kotlin Improvements:**
- Centralized `EditorType` enum with file extension handling
- Added `ReadAction` wrapper for thread-safe VFS operations
- Exception-safe `dispose()` with try-finally pattern
- Gson-based type-safe JSON parsing with proper error handling
- `CompletableFuture` timeouts to prevent hanging operations
- Normalized logging levels (debug vs warn vs error)
- Removed emojis from production log messages

**TypeScript Cleanup (~440 lines removed):**
- Unused functions: `buildEditorHtmlWithDataUrl()`, `loadFileContent()`
- Debug inspection code blocks (~100 lines)
- CSS injection for drag-and-drop (no longer needed with OSR disabled)
- Unused API methods: SVG preview, explicit undo/redo handlers, `onMessage()`
- Redundant keyboard handlers (consolidated to `FileDocumentManagerListener`)
- Drag event debug listeners

**Impact:**
- Cleaner, more maintainable codebase
- Production-ready code quality
- Type-safe bridge communication
- Modern build tooling

### Documentation & Code Quality (Oct 2025)

1. **Comprehensive Documentation Added**
   - Complete KDoc comments for all Kotlin source files
   - JSDoc comments for TypeScript frontend code
   - Architecture diagrams and communication flow explanations
   - JCEF integration patterns and best practices
   - Performance rationale documentation

2. **Enhanced Code Comments**
   - Class-level architecture documentation
   - Step-by-step method implementation guides
   - Thread-safety and concurrency notes
   - Error handling and edge case documentation
   - Cross-references between related components
   - Human-readable time values for all constants

### Critical Fixes (Oct 2025)

1. **Race Condition in Server Initialization**
   - Added `@Volatile` disposal flag to prevent race conditions
   - Proper synchronization of server lifecycle methods
   - Safe coroutine cancellation on dispose

2. **Resource Leak Prevention**
   - Cleanup handler for failed editor initialization
   - Proper WebSocket disconnection on errors
   - Temporary variable tracking for resource cleanup

3. **Thread Safety**
   - `AtomicBoolean` for isModified flag
   - `CopyOnWriteArrayList` for property listeners
   - Synchronized access to shared state

4. **Improved Error Handling**
   - WebSocket reconnection with exponential backoff (1s, 2s, 4s, 8s, 16s)
   - Connection state tracking (DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED)
   - Graceful degradation on connection loss

5. **Input Validation**
   - Required field validation in server.js
   - Type checking for all parameters
   - UUID format validation
   - Command validation with whitelist approach

## Building from Source

### Prerequisites

- JDK 21 or later
- Gradle 9.0+ (wrapper included)
- Node.js 20.19+ or 22.12+ (only for rebuilding webui frontend, optional - required for Vite 7)

### Build Commands

```bash
# Clean build
./gradlew clean buildPlugin

# Run IDE with plugin for testing
./gradlew runIde

# Run tests
./gradlew test

# Verify plugin structure
./gradlew verifyPlugin

# Build distribution ZIP
./gradlew buildPlugin
# Output: build/distributions/kie-kogito-intellij-plugin-*.zip
```

### Development Tips

1. **Browser DevTools**: Uncomment `browser.openDevtools()` in KogitoEditor.kt:148 for debugging
2. **Frontend Build**: Run `cd src/main/resources/webui && npm run build` to rebuild JavaScript bundle
3. **Bundle Size**: Built JavaScript is ~30MB (includes full DMN/BPMN editors from @kie-tools)
4. **Hot Reload**: Use `./gradlew runIde` for iterative development
5. **Bridge Logging**: Check IntelliJ Event Log for JS ↔ Kotlin communication messages
6. **JCEF Support**: Verify with Help → About → JetBrains Runtime (should show "JCEF")

## Project Structure

```
kie-kogito-intellij-plugin/
├── src/main/
│   ├── kotlin/com/github/palmersoftwareconsulting/kogitointellijplugin/
│   │   ├── editor/
│   │   │   ├── KogitoEditor.kt              # Main editor with JCEF
│   │   │   ├── KogitoEditorProvider.kt      # FileEditor provider
│   │   │   └── EditorType.kt                # BPMN/DMN enum
│   │   ├── listener/
│   │   │   └── KogitoSaveListener.kt        # Cmd+S/Ctrl+S integration
│   │   ├── filetypes/
│   │   │   ├── BpmnFileType.kt              # BPMN file type
│   │   │   └── DmnFileType.kt               # DMN file type
│   └── resources/
│       ├── META-INF/plugin.xml              # Plugin configuration
│       └── webui/                            # Frontend source
│           ├── src/
│           │   ├── main.ts                  # Editor initialization
│           │   ├── global.d.ts              # TypeScript type definitions
│           │   └── index.html               # HTML template
│           ├── dist/assets/index.js         # Bundled output (~30MB)
│           ├── package.json                 # npm dependencies (Vite 7, TS 5.9)
│           ├── tsconfig.json                # Strict TypeScript config
│           └── vite.config.js               # Vite build config
├── build.gradle.kts                          # Gradle build script
└── gradle.properties                         # Plugin properties
```

### Code Organization

```
📁 Kotlin Source Files (5 files)
├── editor/          - Visual editor integration (JCEF + JS Bridge)
├── listener/        - IntelliJ save action integration
├── filetypes/       - File type definitions
└── No services      - Direct integration (no server)

📁 TypeScript Frontend (webui/)
├── main.ts          - Kogito editor initialization & bridge API
├── global.d.ts      - Type definitions for bridge messages
└── tsconfig.json    - Strict TypeScript configuration
```

### Best Practices Implemented

- **Thread-Safety**: `AtomicBoolean`, `AtomicReference`, `CopyOnWriteArrayList` for concurrent access
- **Resource Management**: Proper JCEF browser disposal, exception-safe cleanup in `dispose()`
- **Error Resilience**: Graceful error handling with user notifications, `CompletableFuture` timeouts
- **Type Safety**: Discriminated union types for bridge messages, strict TypeScript configuration
- **JCEF Integration**: OSR disabled for drag-and-drop, context menu disabled
- **EDT Threading**: `invokeLater` pattern for save operations to avoid JCEF/EDT deadlocks
- **Performance**: Lazy browser initialization, Base64 encoding with proper UTF-8 handling

## Acknowledgments

- [Kogito Tooling](https://github.com/apache/incubator-kie-tools) - Standalone BPMN/DMN editors
- [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) - Project foundation
- [Apache KIE](https://kie.apache.org/) - Business automation platform

## Troubleshooting

### Common Issues

#### Editor Freezes When Adding Elements (IntelliJ 2025.x)

**Cause:** IntelliJ 2025.1+ uses out-of-process JCEF mode by default, which has known bugs (IJPL-186252) causing freezes.

**Solution:**
1. **Help** → **Edit Custom VM Options**
2. Add: `-Dide.browser.jcef.out-of-process.enabled=false`
3. Restart IntelliJ

This disables out-of-process JCEF and reverts to in-process mode, which is stable and supports drag-and-drop.

#### Editor Shows "Loading..." Indefinitely

**Possible Causes:**
1. JCEF not supported on this platform/IDE version
2. Corrupted plugin installation
3. JavaScript bundle failed to load

**Solutions:**
```bash
# Check IDE logs for errors
# View → Tool Windows → Event Log
# Look for "Failed to load webui/dist/assets/index.js"

# Verify JCEF support
# Help → About → JetBrains Runtime should show "JCEF"

# Clean reinstall
./gradlew clean buildPlugin
# Settings → Plugins → Kogito → Uninstall → Reinstall
```

#### Drag-and-Drop Not Working

**Cause:** OSR (Off-Screen Rendering) might be forced on due to out-of-process JCEF mode.

**Solutions:**

1. **IntelliJ 2025.x users**: Add VM option to disable out-of-process JCEF:
   - **Help** → **Edit Custom VM Options**
   - Add: `-Dide.browser.jcef.out-of-process.enabled=false`
   - Restart IntelliJ

2. **Verify code** (for developers):
   - Check KogitoEditor.kt has `.setOffScreenRendering(false)`
   - Rebuild plugin: `./gradlew clean buildPlugin`

#### Files Not Saving with Cmd+S / Ctrl+S

**Possible Causes:**
1. `KogitoSaveListener` not registered
2. File marked as read-only

**Solutions:**
```bash
# Verify listener registration in plugin.xml:
# <fileDocumentManagerListener implementation="...KogitoSaveListener"/>

# Check file permissions
ls -l your-file.bpmn

# Check IDE logs for save-related errors
```

### Debug Mode

Enable detailed logging:

1. **Help** → **Diagnostic Tools** → **Debug Log Settings**
2. Add: `#com.github.palmersoftwareconsulting.kogitointellijplugin`
3. Reproduce the issue
4. Check logs: **Help** → **Show Log in Finder/Explorer**

### Browser DevTools

For JavaScript debugging, uncomment line 148 in `KogitoEditor.kt`:

```kotlin
// browser.openDevtools()
```

Then rebuild and run the plugin. The Chromium DevTools will open automatically.

### Clean Reinstall

If all else fails:

```bash
# 1. Uninstall the plugin
# Settings → Plugins → Kogito → Uninstall

# 2. Rebuild and reinstall
./gradlew clean buildPlugin
# Settings → Plugins → Install from Disk
```

## Support

- **Issues**: Report bugs at [GitHub Issues](https://github.com/PalmerSoftwareConsulting/kie-kogito-intellij-plugin/issues)
- **Frontend Source**: See `src/main/resources/webui/` for TypeScript/Vite frontend

---

**Note**: This plugin is not officially affiliated with Red Hat, Apache KIE, or Kogito. It is an independent implementation using the open-source Kogito standalone editors.
