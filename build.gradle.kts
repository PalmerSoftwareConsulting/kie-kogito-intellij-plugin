import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

// Helper function to find npm executable (configuration-cache compatible)
fun findNpmExecutable(): String {
    val homeDir = System.getProperty("user.home")
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val npmCommand = if (isWindows) "npm.cmd" else "npm"

    // Search in PATH environment variable without executing processes
    val pathEnv = System.getenv("PATH") ?: ""
    val pathSeparator = if (isWindows) ";" else ":"
    pathEnv.split(pathSeparator).forEach { pathDir ->
        val npmPath = File(pathDir, npmCommand)
        if (npmPath.exists() && npmPath.canExecute()) {
            return npmPath.absolutePath
        }
    }

    // Check NVM installations
    val nvmDir = System.getenv("NVM_DIR") ?: if (isWindows) {
        "$homeDir\\AppData\\Roaming\\nvm"
    } else {
        "$homeDir/.nvm"
    }

    if (!isWindows) {
        // Unix/Linux/macOS NVM
        val versionsDir = File(nvmDir, "versions/node")
        if (versionsDir.exists()) {
            val versions = versionsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }

            versions?.forEach { versionDir ->
                val npmPath = File(versionDir, "bin/npm")
                if (npmPath.exists() && npmPath.canExecute()) {
                    return npmPath.absolutePath
                }
            }
        }
    } else {
        // Windows NVM
        val currentNode = File("$nvmDir\\current\\npm.cmd")
        if (currentNode.exists()) {
            return currentNode.absolutePath
        }
    }

    // Check common locations
    val commonPaths = if (isWindows) {
        listOf(
            "C:\\Program Files\\nodejs\\npm.cmd",
            "C:\\Program Files (x86)\\nodejs\\npm.cmd"
        )
    } else {
        listOf(
            "/usr/local/bin/npm",
            "/usr/bin/npm",
            "/opt/homebrew/bin/npm"
        )
    }

    commonPaths.forEach { path ->
        val npmFile = File(path)
        if (npmFile.exists() && npmFile.canExecute()) {
            return path
        }
    }

    throw GradleException("npm not found. Please install Node.js from https://nodejs.org/")
}

// Task to install webui npm dependencies
val installWebUIDependencies by tasks.registering(Exec::class) {
    description = "Install npm dependencies for webui"
    val webuiDir = layout.projectDirectory.dir("src/main/resources/webui")
    workingDir = webuiDir.asFile

    val isWindows = providers.systemProperty("os.name")
        .map { it.lowercase().contains("windows") }
        .orElse(false)

    // Detect npm executable at configuration time for cache compatibility
    val npmExec = findNpmExecutable()

    // Set command line with detected npm
    commandLine = if (isWindows.get()) {
        listOf("cmd", "/c", npmExec, "install")
    } else {
        listOf(npmExec, "install")
    }

    inputs.file(webuiDir.file("package.json"))
    inputs.file(webuiDir.file("package-lock.json"))
    outputs.dir(webuiDir.dir("node_modules"))

    doFirst {
        val packageJson = webuiDir.file("package.json").asFile
        if (!packageJson.exists()) {
            throw GradleException("package.json not found in webui directory")
        }
        logger.lifecycle("Using npm: $npmExec")
        logger.lifecycle("Installing webui npm dependencies...")
    }

    doLast {
        logger.lifecycle("webui npm dependencies installed successfully")
    }
}

// Task to build webui with Vite
val buildWebUI by tasks.registering(Exec::class) {
    description = "Build webui static assets with Vite"
    val webuiDir = layout.projectDirectory.dir("src/main/resources/webui")
    workingDir = webuiDir.asFile

    dependsOn(installWebUIDependencies)

    val isWindows = providers.systemProperty("os.name")
        .map { it.lowercase().contains("windows") }
        .orElse(false)

    // Detect npm executable at configuration time for cache compatibility
    val npmExec = findNpmExecutable()

    // Set command line with detected npm
    commandLine = if (isWindows.get()) {
        listOf("cmd", "/c", npmExec, "run", "build")
    } else {
        listOf(npmExec, "run", "build")
    }

    inputs.file(webuiDir.file("package.json"))
    inputs.file(webuiDir.file("vite.config.js"))
    inputs.dir(webuiDir.dir("src"))
    outputs.dir(webuiDir.dir("dist"))

    doFirst {
        logger.lifecycle("Building webui with Vite...")
    }

    doLast {
        val distDir = webuiDir.dir("dist").asFile
        if (distDir.exists()) {
            val distSize = distDir
                .walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum() / 1024 // Convert to KB
            logger.lifecycle("webui built successfully (${distSize}KB)")
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    // Ensure webui is built before resources are processed
    processResources {
        dependsOn(buildWebUI)
    }

    // Make buildPlugin depend on webui build
    buildPlugin {
        dependsOn(buildWebUI)

        doFirst {
            logger.lifecycle("""
                ┌─────────────────────────────────────────┐
                │         Plugin Build Summary            │
                ├─────────────────────────────────────────┤
                │ • WebUI: Vite build (static assets)     │
                │ • Total plugin size: ~30MB              │
                └─────────────────────────────────────────┘
            """.trimIndent())
        }
    }

}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}

// Configure the default runIde task with JCEF remote debugging
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Dcef.remote.debugging.port=9222",
        )
    }
}