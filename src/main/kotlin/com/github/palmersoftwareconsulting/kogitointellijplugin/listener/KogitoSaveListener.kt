package com.github.palmersoftwareconsulting.kogitointellijplugin.listener

import com.github.palmersoftwareconsulting.kogitointellijplugin.editor.KogitoEditor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Intercepts IntelliJ's save action to trigger save operations for Kogito editors.
 *
 * ## Purpose
 * This listener bridges the gap between IntelliJ's save system and custom JCEF-based editors.
 * By default, IntelliJ's Cmd+S/Ctrl+S only saves Document-based editors (text files).
 * Kogito editors are not Document-based, so this listener ensures they are saved when
 * the user triggers a save action.
 *
 * ## How It Works
 * 1. IntelliJ triggers "Save All" action (Cmd+S, Ctrl+S, File > Save All)
 * 2. [beforeAllDocumentsSaving] is called before any documents are saved
 * 3. Iterates through all open projects and their open files
 * 4. Identifies Kogito files (.bpmn, .bpmn2, .dmn)
 * 5. Calls [KogitoEditor.saveContent] for modified editors
 *
 * ## Why CefKeyboardHandler Doesn't Work
 * IntelliJ intercepts keyboard shortcuts at the IDE level *before* they reach JCEF.
 * This means:
 * - `CefKeyboardHandler.onPreKeyEvent()` never receives Cmd+S/Ctrl+S
 * - The IDE's action system handles the shortcut first
 * - FileDocumentManagerListener is the correct integration point for custom editors
 *
 * ## Registration
 * This listener is registered in plugin.xml:
 * ```xml
 * <fileDocumentManagerListener implementation="...KogitoSaveListener"/>
 * ```
 *
 * ## Threading
 * - [beforeAllDocumentsSaving] is called on EDT
 * - [KogitoEditor.saveContent] may execute async operations but is EDT-safe
 *
 * ## Integration Points
 * - Works with IntelliJ's "Save All" action (Cmd+S, Ctrl+S)
 * - Integrates with File > Save All menu item
 * - Triggered before closing editors with unsaved changes
 * - Works with VCS commit save hooks
 *
 * @see KogitoEditor.saveContent
 * @see FileDocumentManagerListener
 *
 * @since 0.0.3
 */
class KogitoSaveListener : FileDocumentManagerListener {

    companion object {
        private val logger = Logger.getInstance(KogitoSaveListener::class.java)

        /** File extensions that Kogito editors handle. */
        private val KOGITO_EXTENSIONS = setOf("bpmn", "bpmn2", "dmn")
    }

    /**
     * Called before IntelliJ saves all open documents.
     *
     * This is the entry point for the save interception. It:
     * 1. Iterates through all open projects
     * 2. Delegates to [saveKogitoEditorsInProject] for each project
     *
     * ## Timing
     * This method is called:
     * - Before any Document.save() operations
     * - Before VCS operations during commit
     * - Before project close if there are unsaved changes
     *
     * ## Threading
     * Always called on EDT (Event Dispatch Thread).
     *
     * @see saveKogitoEditorsInProject
     */
    override fun beforeAllDocumentsSaving() {
        logger.info("Save All triggered - checking for Kogito editors")

        // Iterate through all open projects
        ProjectManager.getInstance().openProjects.forEach { project ->
            saveKogitoEditorsInProject(project)
        }
    }

    /**
     * Saves all modified Kogito editors in the specified project.
     *
     * This method:
     * 1. Gets the FileEditorManager for the project
     * 2. Iterates through all open files in the project
     * 3. Filters for Kogito files using [isKogitoFile]
     * 4. Gets all editor instances for each file
     * 5. Checks if editor is a [KogitoEditor] and is modified
     * 6. Calls [KogitoEditor.saveContent] for modified editors
     *
     * ## Why Check All Editors
     * IntelliJ can have multiple editors open for the same file (split view).
     * We need to check all editor instances, though typically only one will be active.
     *
     * ## Modified Check
     * Only saves editors that report [KogitoEditor.isModified] = true.
     * This avoids unnecessary save operations and file system notifications.
     *
     * @param project The project to scan for Kogito editors
     * @see FileEditorManager
     * @see isKogitoFile
     */
    private fun saveKogitoEditorsInProject(project: Project) {
        val fileEditorManager = FileEditorManager.getInstance(project)

        // Get all open files
        fileEditorManager.openFiles.forEach { virtualFile ->
            // Check if this is a Kogito file
            if (isKogitoFile(virtualFile)) {
                logger.info("Found Kogito file: ${virtualFile.name}")

                // Get all editors for this file
                fileEditorManager.getEditors(virtualFile).forEach { fileEditor ->
                    if (fileEditor is KogitoEditor && fileEditor.isModified) {
                        logger.info("Saving modified Kogito editor: ${virtualFile.name}")
                        fileEditor.saveContent()
                    }
                }
            }
        }
    }

    /**
     * Checks if a virtual file is a Kogito file (BPMN or DMN).
     *
     * A file is considered a Kogito file if its extension (case-insensitive) is:
     * - `bpmn` - BPMN 2.0 files
     * - `bpmn2` - Alternative BPMN 2.0 extension
     * - `dmn` - DMN (Decision Model and Notation) files
     *
     * ## Null Safety
     * Returns false if the file has no extension (null-safe check).
     *
     * ## Case Insensitivity
     * Extension comparison is case-insensitive (.BPMN, .bpmn, .Bpmn all match).
     *
     * @param file The virtual file to check
     * @return true if the file is a BPMN or DMN file, false otherwise
     * @see KOGITO_EXTENSIONS
     */
    private fun isKogitoFile(file: VirtualFile): Boolean {
        val extension = file.extension?.lowercase()
        return extension in KOGITO_EXTENSIONS
    }
}
