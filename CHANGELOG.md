# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
