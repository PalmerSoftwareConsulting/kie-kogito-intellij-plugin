# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.0.5] - 2025-11-22

### Added
- **Dynamic DMN Model Loading**: DMN files can now load included models on-demand instead of all at initialization
  - Implements request/response protocol between TypeScript and Kotlin for model discovery and loading
  - JavaScript requests available models via `requestAvailableModels()`
  - JavaScript loads specific models via `requestModelByPath()`
  - Kotlin `IncludedModelsService` handles file discovery, security validation, and content encoding
  - Supports both DMN and PMML model types
  - 10MB file size limit for performance and security
  - Comprehensive security: project bounds validation, build directory exclusion, path traversal protection
  - File-relative path calculation ensures cross-platform compatibility
- **PMML Model Support**: PMML (Predictive Model Markup Language) files can now be loaded as external models
  - PMML files discovered and loaded alongside DMN files
  - Raw XML content passed to DMN editor for model referencing
  - Enables predictive analytics integration in DMN decisions

### Technical Improvements
- Added `IncludedModelsService.kt` for centralized model discovery and loading
  - Uses `ProjectFileIndex` for efficient file iteration
  - Implements proper security checks via `ProjectFileIndex.isInContent()`
  - Base64 encoding for safe JCEF bridge transmission
  - Proper `ReadAction` wrapping for IntelliJ Platform thread safety
- Enhanced TypeScript bridge in `main.ts` with async request/response handling
  - Promise-based API with configurable timeouts (10s for models, 15s for discovery)
  - Request correlation via unique request IDs
  - Granular error handling (decode, parse, network errors)
  - `pendingModelRequests` Map for tracking in-flight requests
- Updated `KogitoEditor.kt` message handlers for `requestAvailableModels` and `requestModel` messages
  - Background thread execution for non-blocking file operations
  - JSON response formatting with proper escaping
- Comprehensive unit test suite (19 tests) covering:
  - Model discovery with various file structures
  - Model loading with security validation
  - Build directory exclusion
  - Special characters and deeply nested paths
  - File size limits and error handling
- **Type Safety Enhancements**
  - Added comprehensive TypeScript type definitions in `global.d.ts`
  - Defined discriminated union types for bridge messages (`JsToKotlinMessage`, `KotlinToJsMessage`)
  - Type-safe `toIde()` function signature with proper import
- **Code Quality**
  - Centralized EditorType enum with file extension handling
  - Exception-safe dispose() with try-finally pattern
  - Gson-based type-safe JSON parsing with proper error handling
  - CompletableFuture timeouts to prevent hanging operations
  - Normalized logging levels (debug vs warn vs error)
  - Removed emojis from production log messages

### Fixed
- **EDT Deadlock on Save**: Fixed infinite loading wheel when pressing Cmd+S/Ctrl+S to save DMN/BPMN files
  - Root cause: `CompletableFuture.thenAccept()` callback ran on JCEF thread, but `WriteCommandAction` requires EDT
  - Solution: Wrapped write operation in `ApplicationManager.invokeLater{}` to schedule on EDT without blocking
  - Prevents deadlock between JCEF callback thread and Event Dispatch Thread
- **DMN Included Models Path Display**: Fixed conflict between old static resource system (v0.0.4) and new dynamic loading system (v0.0.5)
  - Removed legacy static DMN resource discovery that used project-relative paths
  - DMN editor now exclusively uses dynamic model loading with file-relative paths
  - Fixes incompatibility with VS Code Kogito extension
  - Paths now display as `applicant-demographics.dmn` instead of `/kogito/poc-medicaid-rules/src/main/resources/applicant-demographics.dmn`
- **DMN Included Models Path Calculation**: Fixed path calculation for DMN included models to be file-relative instead of project-relative
  - Uses `Path.relativize()` to calculate proper relative paths between files
  - Same directory: `other-model.dmn`
  - Subdirectory: `subdir/other-model.dmn`
  - Parent directory: `../other-model.dmn`
  - Sibling directory: `../sibling/other-model.dmn`
  - Ensures compatibility with the VS Code Kogito extension and proper model resolution
  - Fixes "External model not found" errors when opening DMN files created with this plugin in VS Code
- **UTF-8 Handling**: Fixed Base64 decoding to properly handle multi-byte UTF-8 characters using `TextDecoder`

## [0.0.4] - 2025-11-18

### Added
- **DMN Included Models Support**: DMN files can now reference other DMN files as included models
  - Automatically discovers all DMN files in the project
  - Provides them as resources to the DMN editor
  - Files appear in "Include models" dialog when editing DMN files
  - Respects path hierarchy rules (parent directory resources excluded per Kogito spec)
  - Resources sorted alphabetically for easy navigation
- **BPMN Work Item Definitions Support**: BPMN editor now supports custom task definitions
  - Automatically discovers all `.wid` (Work Item Definition) files in the project
  - Provides them as resources to the BPMN editor
  - Enables custom service tasks with icons and properties defined in WID files
  - Resources sorted alphabetically for easy navigation

### Technical Improvements
- Added `discoverResources()` method in `KogitoEditor.kt` for intelligent resource discovery
  - Uses `FileTypeIndex` for efficient DMN file discovery (indexed search)
  - Uses VFS traversal for WID file discovery
  - Calculates relative POSIX paths for cross-platform compatibility (Windows/Mac/Linux)
  - Base64-encodes file contents for secure transmission to JavaScript
  - Excludes current file from DMN resources to avoid circular references
- Added `buildResourcesMap()` function in `main.ts` to decode and format resources
  - Converts Base64-encoded resources to Kogito editor format
  - Supports both DMN and BPMN resource types
  - Proper error handling for malformed resources
- Enhanced type definitions in `global.d.ts` for `window.editorResources`
- Improved documentation with Kogito API usage patterns and path relationship rules

### Changed
- DMN editor now initializes with project-wide DMN resources instead of empty Map
- BPMN editor now initializes with project-wide WID resources instead of empty Map
- Resource injection happens at HTML build time for better performance

### Removed
- Removed "Initializing {editor type} for {file name}" notification on file open
- Removed "File Saved" notification after successful save
- Error notifications are still shown for troubleshooting purposes

### Performance Considerations
- DMN file discovery is indexed and very fast
- All file contents are read into memory at editor initialization
- Base64 encoding adds ~33% overhead but ensures safe transmission
- Large projects with many DMN/WID files may see slight initialization delay

## [0.0.3] - 2025-11-05

### Added
- **Keyboard Shortcut Support**: Cmd+S (macOS) and Ctrl+S (Windows/Linux) now properly trigger save
  - Implemented via `FileDocumentManagerListener` to hook into IntelliJ's save action system
  - Works seamlessly with IntelliJ's "Save All" action
  - Automatically saves all modified Kogito editors (BPMN/DMN files)
- **Context Menu Handler**: Disabled right-click context menu for cleaner editing experience
  - Removes browser's default context menu (Reload, Inspect, etc.)

### Fixed
- **Drag-and-Drop Support**: Fixed palette drag-and-drop functionality in DMN editor
  - Disabled JCEF OSR (Off-Screen Rendering) mode to enable drag-and-drop
  - Top toolbar items now properly draggable onto canvas
  - Cursor correctly changes to grab/grabbing states
  - Resolves issue JBR-7399 (JCEF drag-and-drop not supported in OSR mode)
- **Enhanced CSS Injection**: Improved palette styling with comprehensive drag-and-drop support
  - Added `pointer-events: auto` for all draggable elements
  - Implemented retry logic for iframe content access
  - Better z-index management for palette elements

### Changed
- **Removed In-Browser Toolbar**: Eliminated save/undo/redo buttons from bottom of editor
  - Cleaner UI with full editor space utilization
  - Users now use standard IntelliJ shortcuts (Cmd+S, Cmd+Z)
  - Undo/redo still available via Kogito editor's built-in functionality
- **DevTools Auto-Open Disabled**: No longer automatically opens browser DevTools window
  - Cleaner development experience
  - Can still be enabled by uncommenting one line in code
  - Remote debugging still available at http://localhost:9222

### Technical Improvements
- Switched from OSR to native rendering mode for better browser compatibility
- Added `KogitoSaveListener` class to intercept save actions at IDE level
- Implemented proper keyboard event handling via `CefKeyboardHandler`
- Comprehensive documentation updates for JCEF integration patterns

### Known Limitations
- File size limit remains at 10MB for performance reasons
- Server uses ports 9876-9976 (may conflict with other applications)

## [0.0.2]

### Added
- Toolbar with Save, Undo, and Redo buttons in the editor for easy access to common operations
- Toolbar positioned at bottom-center of the editor
- Removed navigation hint overlay that appeared when editor first loaded

### Known Issues
- Keyboard shortcuts (Cmd+S for save) were not interceptable at IntelliJ level due to JCEF browser consuming events
- Drag-and-drop not working in DMN editor palette (top toolbars)

### Technical Notes
- JCEF's `onPreKeyEvent` was never invoked due to OSR mode limitations
- Added in-browser toolbar as temporary workaround for keyboard shortcut limitations

## [0.0.1]

### Added
- Initial plugin implementation
- BPMN and DMN file type support
- Kogito editor integration via Node.js server
- JCEF browser-based editor display
- WebSocket communication between IntelliJ and Kogito editors
- Auto-download Node.js runtime
- File save functionality
- Basic undo/redo support
