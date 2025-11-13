# Kogito IntelliJ Plugin

An IntelliJ IDEA plugin that integrates Kogito's standalone BPMN and DMN editors, providing an alternative to the VS Code extension for JetBrains IDEs.

<!-- Plugin description -->
The Kogito IntelliJ Plugin brings powerful visual editing capabilities for Business Process Model and Notation (BPMN) and Decision Model and Notation (DMN) files directly into IntelliJ IDEA. Built on Kogito's standalone editors, this plugin provides a seamless integration that allows developers to design, edit, and visualize business processes and decision models without leaving their IDE.

**Key Features:**
- Visual BPMN 2.0 editor with full modeling capabilities
- DMN 1.2+ decision table and expression editor
- Real-time content synchronization via JCEF bridge
- File modification tracking
- Direct browser integration (no external server required)
- Support for large files (up to 10MB)
- Robust error handling and graceful degradation

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
- **Vite-bundled Frontend**: TypeScript/JavaScript compiled to single bundle (~25MB)
- **JBCefJSQuery**: Bidirectional bridge between Kotlin and JavaScript
- **Kogito Standalone Editors**: @kie-tools/dmn-editor-standalone and @kie-tools/kie-editors-standalone
- **KogitoSaveListener**: Integrates Cmd+S/Ctrl+S with IntelliJ's save action

## Requirements

- **IntelliJ IDEA**: 2024.3.6 or later (with JCEF support)
- **JDK**: 21 or later (for plugin development only)

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

### Code Cleanup (Nov 2025)

**Removed ~440 lines of unused/debug code:**
- Unused functions: `buildEditorHtmlWithDataUrl()`, `loadFileContent()`
- Debug inspection code blocks (~100 lines)
- CSS injection for drag-and-drop (no longer needed with OSR disabled)
- Unused API methods: SVG preview, explicit undo/redo handlers, `onMessage()`
- Redundant keyboard handlers (consolidated to `FileDocumentManagerListener`)
- Drag event debug listeners

**Impact:**
- Cleaner, more maintainable codebase
- Reduced main.ts from ~383 to ~160 lines (58% reduction)
- Removed dead code paths
- Production-ready code quality

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

1. **Resource Leak Prevention**
   - Cleanup handler for failed editor initialization
   - Proper JCEF browser disposal on errors
   - Proper JavaScript bridge disposal
   - Thread-safe browser reference management

2. **Thread Safety**
   - `AtomicBoolean` for isModified flag
   - `CopyOnWriteArrayList` for property listeners
   - `AtomicReference` for thread-safe browser access
   - Synchronized access to shared state

3. **Async Communication Pattern**
   - CompletableFuture-based request-response over one-way JCEF bridge
   - Proper handling of async save operations
   - Bridge message routing with type-based dispatch

4. **Input Validation**
   - Content validation for BPMN/DMN XML
   - Base64 encoding to handle special characters safely
   - Message type validation in bridge handler

## Building from Source

### Prerequisites

- JDK 21 or later
- Gradle 9.0+ (wrapper included)
- Node.js 14.x or later (only for rebuilding webui frontend, optional)

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
3. **Bundle Size**: Built JavaScript is ~25MB (includes full DMN/BPMN editors from @kie-tools)
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
│           │   ├── global.d.ts              # TypeScript declarations
│           │   └── index.html               # HTML template
│           ├── dist/assets/index.js         # Bundled output (~25MB)
│           ├── package.json                 # npm dependencies
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
└── main.ts          - Kogito editor initialization & bridge API
```

### Best Practices Implemented

- **Thread-Safety**: `AtomicBoolean`, `CopyOnWriteArrayList` for concurrent access
- **Resource Management**: Proper JCEF browser disposal, cleanup on errors
- **Error Resilience**: Graceful error handling with user notifications
- **JCEF Integration**: OSR disabled for drag-and-drop, context menu disabled
- **Performance**: Lazy browser initialization, Base64 encoding for content transfer

## Acknowledgments

- [Kogito Tooling](https://github.com/apache/incubator-kie-tools) - Standalone BPMN/DMN editors
- [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) - Project foundation
- [Apache KIE](https://kie.apache.org/) - Business automation platform

## Troubleshooting

### Common Issues

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

**Cause:** OSR (Off-Screen Rendering) might have been re-enabled.

**Solution:**
- Verify KogitoEditor.kt line 100-102 has `.setOffScreenRendering(false)`
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
