package com.github.palmersoftwareconsulting.kogitointellijplugin.listener

import com.github.palmersoftwareconsulting.kogitointellijplugin.editor.EditorType
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
 * 4. Identifies Kogito files (.bpmn, .bpmn2, .dmn) using [EditorType.isSupported]
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
 * @see EditorType
 *
 * @since 0.0.3
 */
class KogitoSaveListener : FileDocumentManagerListener {

    companion object {
        private val logger = Logger.getInstance(KogitoSaveListener::class.java)
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
        logger.debug("Save All triggered - checking for Kogito editors")

        // Iterate through all open projects, filtering out disposed ones
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .forEach { project ->
                saveKogitoEditorsInProject(project)
            }
    }

    /**
     * Saves all modified Kogito editors in the specified project.
     *
     * This method:
     * 1. Gets the FileEditorManager for the project
     * 2. Iterates through all open files in the project
     * 3. Filters for Kogito files using [EditorType.isSupported]
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
     * @see EditorType.isSupported
     */
    private fun saveKogitoEditorsInProject(project: Project) {
        val fileEditorManager = FileEditorManager.getInstance(project)

        // Get all open files, filtering for valid Kogito files
        fileEditorManager.openFiles.asSequence()
            .filter { it.isValid && EditorType.isSupported(it) }
            .flatMap { fileEditorManager.getEditors(it).asSequence() }
            .filterIsInstance<KogitoEditor>()
            .filter { it.isModified }
            .forEach { editor ->
                val fileName = editor.file.name
                logger.info("Saving modified Kogito editor: $fileName")
                try {
                    editor.saveContent().whenComplete { success, throwable ->
                        if (throwable != null) {
                            logger.error("Failed to save $fileName", throwable)
                        } else if (success != true) {
                            logger.warn("Save operation reported failure for $fileName")
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to initiate save for $fileName", e)
                }
            }
    }
}
