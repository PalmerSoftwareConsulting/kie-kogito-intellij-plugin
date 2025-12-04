package com.github.palmersoftwareconsulting.kogitointellijplugin.service

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Unit tests for IncludedModelsService.
 *
 * Tests the core functionality of DMN/PMML model discovery, loading, and path resolution.
 */
class IncludedModelsServiceTest : BasePlatformTestCase() {

    private lateinit var service: IncludedModelsService

    override fun setUp() {
        super.setUp()
        service = IncludedModelsService(project)
    }

    // ========== Model Discovery Tests ==========

    fun `test discoverAvailableModels finds DMN and PMML files`() {
        // Create test files in the project
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val model1 = myFixture.addFileToProject("models/model1.dmn", createDmnContent("Model1"))
        val model2 = myFixture.addFileToProject("shared/model2.dmn", createDmnContent("Model2"))
        val pmml1 = myFixture.addFileToProject("models/prediction.pmml", createPmmlContent())

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // Should find exactly 3 models (not "at least 3")
        assertEquals("Should find exactly 3 models", 3, availableModels.size)
        assertTrue("Should contain model1.dmn", availableModels.any { it.contains("model1.dmn") })
        assertTrue("Should contain model2.dmn", availableModels.any { it.contains("model2.dmn") })
        assertTrue("Should contain prediction.pmml", availableModels.any { it.contains("prediction.pmml") })
        assertFalse("Should not contain current file", availableModels.any { it.contains("current.dmn") && !it.contains("..") })

        // Verify each discovered model can be loaded with correct type
        val model1Result = service.loadModelByPath(currentFile.virtualFile, "model1.dmn")
        assertEquals("model1.dmn should have DMN type", IncludedModelsService.ModelType.DMN, model1Result?.modelType)

        val pmml1Result = service.loadModelByPath(currentFile.virtualFile, "prediction.pmml")
        assertEquals("prediction.pmml should have PMML type", IncludedModelsService.ModelType.PMML, pmml1Result?.modelType)
    }

    fun `test discoverAvailableModels excludes current file`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        myFixture.addFileToProject("models/other.dmn", createDmnContent("Other"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // Should not include the current file itself (unless it's via a different path like ../)
        val directMatch = availableModels.any { it == "current.dmn" }
        assertFalse("Should not include current file as direct match", directMatch)
    }

    fun `test discoverAvailableModels excludes build directories`() {
        val currentFile = myFixture.addFileToProject("src/current.dmn", createDmnContent("Current"))
        myFixture.addFileToProject("src/model.dmn", createDmnContent("Source"))

        // These should be excluded (in build directories)
        myFixture.addFileToProject("target/classes/compiled.dmn", createDmnContent("Compiled"))
        myFixture.addFileToProject("build/output/generated.dmn", createDmnContent("Generated"))
        myFixture.addFileToProject("out/production/built.dmn", createDmnContent("Built"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // Should find source model but not build artifacts
        assertTrue("Should find source model", availableModels.any { it.contains("model.dmn") })
        assertFalse("Should exclude target/ files", availableModels.any { it.contains("compiled.dmn") })
        assertFalse("Should exclude build/ files", availableModels.any { it.contains("generated.dmn") })
        assertFalse("Should exclude out/ files", availableModels.any { it.contains("built.dmn") })
    }

    fun `test discoverAvailableModels returns relative paths`() {
        val currentFile = myFixture.addFileToProject("src/main/models/current.dmn", createDmnContent("Current"))
        myFixture.addFileToProject("src/main/models/sibling.dmn", createDmnContent("Sibling"))
        myFixture.addFileToProject("src/main/shared/parent.dmn", createDmnContent("Parent"))
        myFixture.addFileToProject("src/test/fixtures/test.dmn", createDmnContent("Test"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // All paths should be relative (not absolute)
        availableModels.forEach { path ->
            assertFalse("Path should be relative: $path", path.startsWith("/"))
            assertFalse("Path should not contain absolute Windows path", path.contains(":\\"))
        }

        // Should use POSIX separators (forward slashes)
        availableModels.forEach { path ->
            assertFalse("Path should use forward slashes: $path", path.contains("\\"))
        }
    }

    fun `test discoverAvailableModels returns sorted results`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        myFixture.addFileToProject("models/zebra.dmn", createDmnContent("Zebra"))
        myFixture.addFileToProject("models/apple.dmn", createDmnContent("Apple"))
        myFixture.addFileToProject("models/middle.dmn", createDmnContent("Middle"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // Results should be sorted
        assertEquals("Results should be sorted", availableModels, availableModels.sorted())
    }

    // ========== Model Loading Tests ==========

    fun `test loadModelByPath loads valid DMN file`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val targetFile = myFixture.addFileToProject("models/target.dmn", createDmnContent("Target"))

        val result = service.loadModelByPath(currentFile.virtualFile, "target.dmn")

        assertNotNull("Should load model successfully", result)
        assertEquals("Should have correct path", "target.dmn", result?.relativePath)
        assertEquals("Should be DMN type", IncludedModelsService.ModelType.DMN, result?.modelType)
        assertNotNull("Should have base64 content", result?.base64Content)
        assertTrue("Base64 content should not be empty", result?.base64Content?.isNotEmpty() == true)

        // Verify DMN structure is present
        val decoded = String(java.util.Base64.getDecoder().decode(result?.base64Content))
        assertTrue("Should contain DMN namespace", decoded.contains("https://www.omg.org/spec/DMN"))
        assertTrue("Should contain definitions element", decoded.contains("<definitions"))
    }

    fun `test loadModelByPath loads valid PMML file`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val pmmlFile = myFixture.addFileToProject("models/prediction.pmml", createPmmlContent())

        val result = service.loadModelByPath(currentFile.virtualFile, "prediction.pmml")

        assertNotNull("Should load PMML successfully", result)
        assertEquals("Should have correct path", "prediction.pmml", result?.relativePath)
        assertEquals("Should be PMML type", IncludedModelsService.ModelType.PMML, result?.modelType)
        assertNotNull("Should have base64 content", result?.base64Content)
        assertTrue("Base64 content should not be empty", result?.base64Content?.isNotEmpty() == true)

        // Verify PMML structure is present
        val decoded = String(java.util.Base64.getDecoder().decode(result?.base64Content))
        assertTrue("Should contain PMML namespace", decoded.contains("http://www.dmg.org/PMML"))
        assertTrue("Should contain DataDictionary", decoded.contains("DataDictionary"))
    }

    fun `test loadModelByPath preserves PMML content correctly`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val expectedPmmlContent = createPmmlContent()
        val pmmlFile = myFixture.addFileToProject("models/prediction.pmml", expectedPmmlContent)

        val result = service.loadModelByPath(currentFile.virtualFile, "prediction.pmml")

        assertNotNull("Should load PMML model", result)
        assertEquals("Should be PMML type", IncludedModelsService.ModelType.PMML, result?.modelType)

        // Decode Base64 and verify content matches exactly
        val decodedContent = String(java.util.Base64.getDecoder().decode(result?.base64Content))
        assertEquals("PMML content should be preserved exactly", expectedPmmlContent, decodedContent)
    }

    fun `test discoverAvailableModels distinguishes DMN from PMML by extension`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // Create files with different extensions
        val dmnFile = myFixture.addFileToProject("models/decision.dmn", createDmnContent("Decision"))
        val pmmlFile = myFixture.addFileToProject("models/prediction.pmml", createPmmlContent())

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // Both should be discovered
        assertTrue("Should discover DMN file", availableModels.any { it.contains("decision.dmn") })
        assertTrue("Should discover PMML file", availableModels.any { it.contains("prediction.pmml") })

        // Load each and verify type is correct
        val dmnResult = service.loadModelByPath(currentFile.virtualFile, "decision.dmn")
        val pmmlResult = service.loadModelByPath(currentFile.virtualFile, "prediction.pmml")

        assertEquals("DMN file should have DMN type", IncludedModelsService.ModelType.DMN, dmnResult?.modelType)
        assertEquals("PMML file should have PMML type", IncludedModelsService.ModelType.PMML, pmmlResult?.modelType)
    }

    fun `test loadModelByPath rejects unsupported model types`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // Create files with unsupported extensions
        val xmlFile = myFixture.addFileToProject("models/data.xml", "<data/>")
        val jsonFile = myFixture.addFileToProject("models/config.json", "{}")

        // These should return null because extensions are not supported
        val xmlResult = service.loadModelByPath(currentFile.virtualFile, "data.xml")
        val jsonResult = service.loadModelByPath(currentFile.virtualFile, "config.json")

        assertNull("Should reject .xml files", xmlResult)
        assertNull("Should reject .json files", jsonResult)
    }

    fun `test mixed DMN and PMML project discovery`() {
        val currentFile = myFixture.addFileToProject("project/decisions/current.dmn", createDmnContent("Current"))

        // Create a realistic project structure with mixed DMN and PMML files
        myFixture.addFileToProject("project/decisions/loan-approval.dmn", createDmnContent("LoanApproval"))
        myFixture.addFileToProject("project/decisions/credit-score.dmn", createDmnContent("CreditScore"))
        myFixture.addFileToProject("project/models/risk-model.pmml", createPmmlContent())
        myFixture.addFileToProject("project/models/fraud-detection.pmml", createPmmlContent())
        myFixture.addFileToProject("project/shared/common-rules.dmn", createDmnContent("CommonRules"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // Should find all files except current
        val dmnFiles = availableModels.filter { it.endsWith(".dmn") }
        val pmmlFiles = availableModels.filter { it.endsWith(".pmml") }

        assertEquals("Should find 3 DMN files (excluding current)", 3, dmnFiles.size)
        assertEquals("Should find 2 PMML files", 2, pmmlFiles.size)

        // Verify specific files are found
        assertTrue("Should find loan-approval.dmn", availableModels.any { it.contains("loan-approval.dmn") })
        assertTrue("Should find risk-model.pmml", availableModels.any { it.contains("risk-model.pmml") })
    }

    fun `test loadModelByPath handles relative paths with parent directories`() {
        val currentFile = myFixture.addFileToProject("src/main/models/current.dmn", createDmnContent("Current"))
        val sharedFile = myFixture.addFileToProject("src/shared/common.dmn", createDmnContent("Common"))

        val result = service.loadModelByPath(currentFile.virtualFile, "../../shared/common.dmn")

        assertNotNull("Should resolve parent directory paths", result)
        assertEquals("Should preserve original relative path", "../../shared/common.dmn", result?.relativePath)
    }

    fun `test loadModelByPath returns null for nonexistent file`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        val result = service.loadModelByPath(currentFile.virtualFile, "nonexistent.dmn")

        assertNull("Should return null for nonexistent file", result)
    }

    fun `test loadModelByPath rejects files outside project`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // Try to access file outside project using path traversal
        val result = service.loadModelByPath(currentFile.virtualFile, "../../../../../etc/passwd")

        assertNull("Should reject files outside project", result)
    }

    fun `test loadModelByPath rejects files in build directories`() {
        val currentFile = myFixture.addFileToProject("src/current.dmn", createDmnContent("Current"))
        myFixture.addFileToProject("target/compiled.dmn", createDmnContent("Compiled"))

        val result = service.loadModelByPath(currentFile.virtualFile, "../target/compiled.dmn")

        assertNull("Should reject files in build directories", result)
    }

    fun `test loadModelByPath validates file size limit`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // Create a very large file (11MB - exceeds 10MB limit)
        val largeContent = "x".repeat(11 * 1024 * 1024)
        val largeFile = myFixture.addFileToProject("models/large.dmn", largeContent)

        val result = service.loadModelByPath(currentFile.virtualFile, "large.dmn")

        assertNull("Should reject files larger than 10MB", result)
    }

    fun `test loadModelByPath rejects unsupported file types`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val txtFile = myFixture.addFileToProject("models/readme.txt", "This is a text file")

        val result = service.loadModelByPath(currentFile.virtualFile, "readme.txt")

        assertNull("Should reject unsupported file types", result)
    }

    fun `test loadModelByPath decodes base64 content correctly`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val expectedContent = createDmnContent("Target")
        val targetFile = myFixture.addFileToProject("models/target.dmn", expectedContent)

        val result = service.loadModelByPath(currentFile.virtualFile, "target.dmn")

        assertNotNull("Should load model", result)

        // Decode Base64 and verify content
        val decodedContent = String(java.util.Base64.getDecoder().decode(result?.base64Content))
        assertEquals("Decoded content should match original", expectedContent, decodedContent)
    }

    // ========== Edge Cases and Error Handling ==========

    fun `test discoverAvailableModels with empty project`() {
        val currentFile = myFixture.addFileToProject("lonely.dmn", createDmnContent("Lonely"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // With only one file (the current file), result should be exactly empty
        assertTrue("Should return empty list when only current file exists", availableModels.isEmpty())
    }

    fun `test loadModelByPath with empty relative path`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        val result = service.loadModelByPath(currentFile.virtualFile, "")

        assertNull("Should handle empty path gracefully", result)
    }

    fun `test loadModelByPath with malformed path`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        val result = service.loadModelByPath(currentFile.virtualFile, "../..////..//invalid//path")

        assertNull("Should return null for malformed paths", result)
    }

    fun `test service handles special characters in filenames`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val specialFile = myFixture.addFileToProject("models/my model (v2).dmn", createDmnContent("Special"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // Should find file with special characters
        assertTrue("Should handle special characters in filenames",
            availableModels.any { it.contains("my model (v2).dmn") })
    }

    fun `test service handles deeply nested directory structures`() {
        val currentFile = myFixture.addFileToProject("a/b/c/d/e/current.dmn", createDmnContent("Current"))
        val deepFile = myFixture.addFileToProject("a/b/c/d/e/f/g/deep.dmn", createDmnContent("Deep"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        assertTrue("Should handle deeply nested structures", availableModels.any { it.contains("deep.dmn") })
    }

    // ========== Security and Boundary Tests ==========

    fun `test loadModelByPath rejects invalid UTF-8 content`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // Create file with invalid UTF-8 bytes
        val invalidUtf8 = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()  // Invalid UTF-8 sequence
        )

        // First create the file with valid content
        val binaryFile = myFixture.addFileToProject("models/binary.dmn", "temporary")

        // Then replace with invalid binary content
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            binaryFile.virtualFile.setBinaryContent(invalidUtf8)
        }

        val result = service.loadModelByPath(currentFile.virtualFile, "binary.dmn")

        assertNull("Should reject invalid UTF-8 content", result)
    }

    fun `test isInBuildDirectory handles filename vs directory name`() {
        val currentFile = myFixture.addFileToProject("src/current.dmn", createDmnContent("Current"))

        // File NAMED "target.dmn" but NOT in target/ directory - should be INCLUDED
        val targetNamedFile = myFixture.addFileToProject("src/target.dmn", createDmnContent("TargetNamed"))

        // File in directory named "mytarget/" - should be INCLUDED
        val myTargetDir = myFixture.addFileToProject("src/mytarget/model.dmn", createDmnContent("MyTarget"))

        // File in actual "target/" directory - should be EXCLUDED
        val targetDirFile = myFixture.addFileToProject("target/model.dmn", createDmnContent("InTarget"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        assertTrue("Should include file named 'target.dmn'",
            availableModels.any { it.endsWith("target.dmn") })
        assertTrue("Should include file in 'mytarget/' directory",
            availableModels.any { it.contains("mytarget") && it.endsWith("model.dmn") })
        assertFalse("Should exclude file in 'target/' directory",
            availableModels.any { it.contains("InTarget") })
    }

    fun `test loadModelByPath enforces exact file size boundary`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // Exactly 10MB - should succeed
        val exactlyMaxSize = "x".repeat(10 * 1024 * 1024)
        val maxFile = myFixture.addFileToProject("models/max.dmn", exactlyMaxSize)
        val maxResult = service.loadModelByPath(currentFile.virtualFile, "max.dmn")
        assertNotNull("Should load file exactly at 10MB limit", maxResult)

        // 10MB + 1 byte - should fail
        val overMaxSize = "x".repeat(10 * 1024 * 1024 + 1)
        val overFile = myFixture.addFileToProject("models/over.dmn", overMaxSize)
        val overResult = service.loadModelByPath(currentFile.virtualFile, "over.dmn")
        assertNull("Should reject file over 10MB limit", overResult)
    }

    fun `test loadModelByPath handles special characters in content`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // Content with special characters, Unicode, XML entities
        val specialContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions name="Special ©️ 中文 Émojis 🚀">
                <text>Special chars: &lt; &gt; &amp; " '</text>
            </definitions>
        """.trimIndent()

        val specialFile = myFixture.addFileToProject("models/special.dmn", specialContent)

        val result = service.loadModelByPath(currentFile.virtualFile, "special.dmn")
        assertNotNull("Should load file with special characters", result)

        // Decode and verify exact match
        val decoded = String(java.util.Base64.getDecoder().decode(result?.base64Content))
        assertEquals("Should preserve all special characters", specialContent, decoded)
    }

    fun `test discoverAvailableModels calculates correct relative paths`() {
        val currentFile = myFixture.addFileToProject("src/main/models/current.dmn", createDmnContent("Current"))

        // Same directory - should be just filename
        val sibling = myFixture.addFileToProject("src/main/models/sibling.dmn", createDmnContent("Sibling"))

        // Parent's sibling directory
        val shared = myFixture.addFileToProject("src/main/shared/common.dmn", createDmnContent("Common"))

        // Different branch of tree
        val testModel = myFixture.addFileToProject("src/test/fixtures/test.dmn", createDmnContent("Test"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // Verify exact paths
        assertTrue("Same directory should use filename only",
            availableModels.contains("sibling.dmn"))
        assertTrue("Parent sibling should use ../shared/common.dmn",
            availableModels.contains("../shared/common.dmn"))
        assertTrue("Different tree branch should navigate correctly",
            availableModels.any { it.contains("test.dmn") && it.contains("..") })
    }

    fun `test discoverAvailableModels returns only POSIX paths`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val model = myFixture.addFileToProject("models/subdir/test.dmn", createDmnContent("Test"))

        val availableModels = service.discoverAvailableModels(currentFile.virtualFile)

        // All paths should use forward slashes, never backslashes
        availableModels.forEach { path ->
            assertFalse("Should never contain backslashes: $path", path.contains("\\"))
            if (path.contains("/")) {
                assertTrue("Should use forward slashes for directories: $path",
                    path.contains("/"))
            }
        }
    }

    // ========== Type Detection Edge Cases ==========

    fun `test type detection handles case-insensitive extensions`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // Test uppercase extensions
        val upperDmn = myFixture.addFileToProject("models/MODEL.DMN", createDmnContent("UpperDMN"))
        val upperPmml = myFixture.addFileToProject("models/PRED.PMML", createPmmlContent())

        // Test mixed case extensions
        val mixedDmn = myFixture.addFileToProject("models/Mixed.DmN", createDmnContent("MixedDMN"))
        val mixedPmml = myFixture.addFileToProject("models/mixed.PmMl", createPmmlContent())

        val upperDmnResult = service.loadModelByPath(currentFile.virtualFile, "MODEL.DMN")
        val upperPmmlResult = service.loadModelByPath(currentFile.virtualFile, "PRED.PMML")
        val mixedDmnResult = service.loadModelByPath(currentFile.virtualFile, "Mixed.DmN")
        val mixedPmmlResult = service.loadModelByPath(currentFile.virtualFile, "mixed.PmMl")

        assertEquals("Uppercase .DMN should be recognized", IncludedModelsService.ModelType.DMN, upperDmnResult?.modelType)
        assertEquals("Uppercase .PMML should be recognized", IncludedModelsService.ModelType.PMML, upperPmmlResult?.modelType)
        assertEquals("Mixed case .DmN should be recognized", IncludedModelsService.ModelType.DMN, mixedDmnResult?.modelType)
        assertEquals("Mixed case .PmMl should be recognized", IncludedModelsService.ModelType.PMML, mixedPmmlResult?.modelType)
    }

    fun `test loadModelByPath handles files with no extension`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))
        val noExtFile = myFixture.addFileToProject("models/noext", createDmnContent("NoExt"))

        val result = service.loadModelByPath(currentFile.virtualFile, "noext")

        assertNull("Should reject file with no extension", result)
    }

    fun `test loadModelByPath handles files with multiple extensions`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        // File with .dmn.bak should check last extension (.bak)
        val dmnBackup = myFixture.addFileToProject("models/file.dmn.bak", createDmnContent("Backup"))
        val pmmlBackup = myFixture.addFileToProject("models/model.pmml.old", createPmmlContent())

        val dmnResult = service.loadModelByPath(currentFile.virtualFile, "file.dmn.bak")
        val pmmlResult = service.loadModelByPath(currentFile.virtualFile, "model.pmml.old")

        // Should check LAST extension only (.bak and .old are not supported)
        assertNull("Should reject .dmn.bak (checks .bak extension)", dmnResult)
        assertNull("Should reject .pmml.old (checks .old extension)", pmmlResult)
    }

    fun `test DMN and PMML content do not cross-contaminate`() {
        val currentFile = myFixture.addFileToProject("models/current.dmn", createDmnContent("Current"))

        val dmnContent = createDmnContent("TestDMN")
        val pmmlContent = createPmmlContent()

        val dmnFile = myFixture.addFileToProject("models/test.dmn", dmnContent)
        val pmmlFile = myFixture.addFileToProject("models/test.pmml", pmmlContent)

        val dmnResult = service.loadModelByPath(currentFile.virtualFile, "test.dmn")
        val pmmlResult = service.loadModelByPath(currentFile.virtualFile, "test.pmml")

        val decodedDmn = String(java.util.Base64.getDecoder().decode(dmnResult?.base64Content))
        val decodedPmml = String(java.util.Base64.getDecoder().decode(pmmlResult?.base64Content))

        // Verify no cross-contamination
        assertEquals("DMN content should be preserved exactly", dmnContent, decodedDmn)
        assertEquals("PMML content should be preserved exactly", pmmlContent, decodedPmml)
        assertFalse("DMN and PMML should be different", decodedDmn == decodedPmml)

        // Verify content contains expected namespaces
        assertTrue("DMN should contain DMN namespace", decodedDmn.contains("https://www.omg.org/spec/DMN"))
        assertTrue("PMML should contain PMML namespace", decodedPmml.contains("http://www.dmg.org/PMML"))
        assertFalse("DMN should NOT contain PMML namespace", decodedDmn.contains("http://www.dmg.org/PMML"))
        assertFalse("PMML should NOT contain DMN namespace", decodedPmml.contains("https://www.omg.org/spec/DMN"))
    }

    // ========== Helper Methods ==========

    /**
     * Creates minimal valid DMN XML content for testing.
     */
    private fun createDmnContent(name: String): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                         xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/"
                         xmlns:dc="http://www.omg.org/spec/DMN/20180521/DC/"
                         id="definitions_$name"
                         name="$name"
                         namespace="https://kie.org/dmn/_test">
                <decision id="decision_$name" name="Decision $name">
                    <decisionTable id="decisionTable_$name">
                        <input id="input_1">
                            <inputExpression typeRef="string">
                                <text>Input</text>
                            </inputExpression>
                        </input>
                        <output id="output_1" typeRef="string"/>
                    </decisionTable>
                </decision>
            </definitions>
        """.trimIndent()
    }

    /**
     * Creates minimal valid PMML XML content for testing.
     */
    private fun createPmmlContent(): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <PMML xmlns="http://www.dmg.org/PMML-4_4" version="4.4">
                <Header copyright="Test">
                    <Application name="Test" version="1.0"/>
                </Header>
                <DataDictionary numberOfFields="1">
                    <DataField name="input" optype="continuous" dataType="double"/>
                </DataDictionary>
                <RegressionModel functionName="regression">
                    <MiningSchema>
                        <MiningField name="input"/>
                    </MiningSchema>
                </RegressionModel>
            </PMML>
        """.trimIndent()
    }
}
