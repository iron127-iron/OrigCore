import io.papermc.fill.model.BuildChannel
import io.papermc.paperweight.attribute.DevBundleOutput
import io.papermc.paperweight.util.*
import java.time.Instant

plugins {
    `java-library`
    `maven-publish`
    idea
    id("io.papermc.paperweight.core")
    id("io.papermc.fill.gradle") version "1.0.10"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

// OrigCore - OrigCore 核心自身的版本號（來源：gradle.properties 的 origCoreVersion）。
// 刻意與 project.version 分開：project.version 是 Minecraft／Bukkit API 版本，
// 會影響 Bukkit#getBukkitVersion() 與插件相容性，不能改成 OrigCore 的版本號。
val origCoreVersion: String = providers.gradleProperty("origCoreVersion")
    .orElse("0.1.0")
    .get()

dependencies {
    mache("io.papermc:mache:1.21.11+build.1")
    paperclip("io.papermc:paperclip:3.0.3")
}

paperweight {
    minecraftVersion = providers.gradleProperty("mcVersion")
    gitFilePatches = false

    // OrigCore - 產出的 JAR 檔名使用 OrigCore 品牌
    // Paperweight 的預設值是硬編碼的 "paper"，與 Gradle 的 rootProject.name 無關，
    // 因此必須在此明確設定。設定後輸出檔名為：
    //   <bundlerJarName>-<kind>-<version>-<classifier>.jar
    //   例如 origcore-bundler-0.1.0-mojmap.jar、origcore-paperclip-0.1.0-mojmap.jar
    bundlerJarName.set("origcore")

    spigot {
        enabled = true
        buildDataRef = "17f77cee7117ab9d6175f088ae8962bfd04e61a9"
        packageVersion = "v1_21_R7" // also needs to be updated in MappingEnvironment
    }

    reobfPackagesToFix.addAll(
        "co.aikar.timings",
        "com.destroystokyo.paper",
        "com.mojang",
        "io.origcore", // OrigCore - OrigCore 自己的類別會參考 net.minecraft，重新混淆時必須一併修正
        "io.papermc.paper",
        "ca.spottedleaf",
        "net.kyori.adventure.bossbar",
        "net.minecraft",
        "org.bukkit.craftbukkit",
        "org.spigotmc",
    )

    updatingMinecraft {
        // oldPaperCommit = "c82b438b5b4ea0b230439b8e690e34708cd11ab3"
    }
}

tasks.generateDevelopmentBundle {
    libraryRepositories.addAll(
        "https://repo.maven.apache.org/maven2/",
        paperMavenPublicUrl,
    )
}

abstract class Services {
    @get:Inject
    abstract val archiveOperations: ArchiveOperations
}
val services = objects.newInstance<Services>()

if (project.providers.gradleProperty("publishDevBundle").isPresent) {
    val devBundleComponent = publishing.softwareComponentFactory.adhoc("devBundle")
    components.add(devBundleComponent)

    val devBundle = configurations.consumable("devBundle") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.ZIP))
        outgoing.artifact(tasks.generateDevelopmentBundle.flatMap { it.devBundleFile })
    }
    devBundleComponent.addVariantsFromConfiguration(devBundle) {}

    val runtime = configurations.consumable("serverRuntimeClasspath") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.SERVER_DEPENDENCIES))
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        extendsFrom(configurations.runtimeClasspath.get())
    }
    devBundleComponent.addVariantsFromConfiguration(runtime) {
        mapToMavenScope("runtime")
    }

    val compile = configurations.consumable("serverCompileClasspath") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.SERVER_DEPENDENCIES))
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        extendsFrom(configurations.compileClasspath.get())
    }
    devBundleComponent.addVariantsFromConfiguration(compile) {
        mapToMavenScope("compile")
    }

    tasks.withType(GenerateMavenPom::class).configureEach {
        doLast {
            val text = destination.readText()
            // Remove dependencies from pom, dev bundle is designed for gradle module metadata consumers
            destination.writeText(
                text.substringBefore("<dependencies>") + text.substringAfter("</dependencies>")
            )
        }
    }

    publishing {
        publications.create<MavenPublication>("devBundle") {
            artifactId = "dev-bundle"
            from(devBundleComponent)
        }
    }
}

val log4jPlugins = sourceSets.create("log4jPlugins")
configurations.named(log4jPlugins.compileClasspathConfigurationName) {
    extendsFrom(configurations.compileClasspath.get())
}
val alsoShade: Configuration by configurations.creating

val runtimeConfiguration by configurations.consumable("runtimeConfiguration") {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    extendsFrom(configurations.getByName(sourceSets.main.get().runtimeElementsConfigurationName))
}

// Configure mockito agent that is needed in newer java versions
val mockitoAgent = configurations.register("mockitoAgent")
abstract class MockitoAgentProvider : CommandLineArgumentProvider {
    @get:CompileClasspath
    abstract val fileCollection: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> {
        return listOf("-javaagent:" + fileCollection.files.single().absolutePath)
    }
}

dependencies {
    implementation(project(":paper-api"))
    implementation("ca.spottedleaf:concurrentutil:0.0.8")
    implementation("org.jline:jline-terminal-ffm:3.27.1") // use ffm on java 22+
    implementation("org.jline:jline-terminal-jni:3.27.1") // fall back to jni on java 21
    implementation("net.minecrell:terminalconsoleappender:1.3.0")
    implementation("net.kyori:adventure-text-serializer-ansi")

    /*
      Required to add the missing Log4j2Plugins.dat file from log4j-core
      which has been removed by Mojang. Without it, log4j has to classload
      all its classes to check if they are plugins.
      Scanning takes about 1-2 seconds so adding this speeds up the server start.
     */
    implementation("org.apache.logging.log4j:log4j-core:2.24.1")
    log4jPlugins.annotationProcessorConfigurationName("org.apache.logging.log4j:log4j-core:2.24.1") // Needed to generate meta for our Log4j plugins
    runtimeOnly(log4jPlugins.output)
    alsoShade(log4jPlugins.output)

    implementation("com.velocitypowered:velocity-native:3.4.0-SNAPSHOT") {
        isTransitive = false
    }
    implementation("io.netty:netty-codec-haproxy:4.2.7.Final") // Add support for proxy protocol
    implementation("org.apache.logging.log4j:log4j-iostreams:2.24.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    implementation("org.spongepowered:configurate-yaml:4.2.0")

    // Deps that were previously in the API but have now been moved here for backwards compat, eventually to be removed
    runtimeOnly("commons-lang:commons-lang:2.6")
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    runtimeOnly("com.mysql:mysql-connector-j:9.2.0")
    runtimeOnly("com.lmax:disruptor:3.4.4")
    implementation("com.googlecode.json-simple:json-simple:1.1.1") { // change to runtimeOnly once Timings is removed
        isTransitive = false // includes junit
    }

    testImplementation("io.github.classgraph:classgraph:4.8.179") // For mob goal test
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.junit.platform:junit-platform-suite-engine:1.12.2")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.mockito:mockito-core:5.14.1")
    mockitoAgent("org.mockito:mockito-core:5.14.1") { isTransitive = false } // Configure mockito agent that is needed in newer java versions
    testImplementation("org.ow2.asm:asm-tree:9.9.1")
    testImplementation("org.junit-pioneer:junit-pioneer:2.2.0") // CartesianTest

    implementation("net.neoforged:srgutils:1.0.9") // Mappings handling
    implementation("net.neoforged:AutoRenamingTool:2.0.3") // Remap plugins

    // Remap reflection
    val reflectionRewriterVersion = "0.0.3"
    implementation("io.papermc:reflection-rewriter:$reflectionRewriterVersion")
    implementation("io.papermc:reflection-rewriter-runtime:$reflectionRewriterVersion")
    implementation("io.papermc:reflection-rewriter-proxy-generator:$reflectionRewriterVersion")

    // Spark
    implementation("me.lucko:spark-api:0.1-20240720.200737-2")
    implementation("me.lucko:spark-paper:1.10.152")
}

tasks.jar {
    manifest {
        val git = Git(rootProject.layout.projectDirectory.path)
        val mcVersion = rootProject.providers.gradleProperty("mcVersion").get()
        val build = System.getenv("BUILD_NUMBER") ?: null
        val buildTime = providers.environmentVariable("BUILD_STARTED_AT").map(Instant::parse).orElse(Instant.EPOCH).get()
        val gitHash = git.exec(providers, "rev-parse", "--short=7", "HEAD").get().trim()
        val implementationVersion = "$mcVersion-${build ?: "DEV"}-$gitHash"
        val date = git.exec(providers, "show", "-s", "--format=%ci", gitHash).get().trim()
        val gitBranch = git.exec(providers, "rev-parse", "--abbrev-ref", "HEAD").get().trim()
        attributes(
            "Main-Class" to "org.bukkit.craftbukkit.Main",
            // OrigCore start - 品牌與版本識別
            // 這些 manifest 屬性會決定啟動日誌中的核心名稱（ServerBuildInfo -> Bukkit#getName），
            // 以及 Bukkit#getVersion() 顯示的內容。
            "Implementation-Title" to "OrigCore",
            "Implementation-Version" to implementationVersion,
            "Implementation-Vendor" to date,
            "Specification-Title" to "OrigCore",
            "Specification-Version" to project.version,
            "Specification-Vendor" to "OrigCore Project",
            "OrigCore-Version" to origCoreVersion,
            "OrigCore-Upstream" to "PaperMC/Paper",
            "OrigCore-Upstream-Branch" to "ver/1.21.11",
            "OrigCore-Upstream-Commit" to gitHash,
            // Brand-Id 改為 OrigCore，但 ServerBuildInfoImpl#isBrandCompatible
            // 仍然接受 Paper 的品牌識別，以維持插件相容性。
            "Brand-Id" to "origcore:origcore",
            "Brand-Name" to "OrigCore",
            // OrigCore end
            "Build-Number" to (build ?: ""),
            "Build-Time" to buildTime.toString(),
            "Git-Branch" to gitBranch,
            "Git-Commit" to gitHash,
        )
        for (tld in setOf("net", "com", "org")) {
            attributes("$tld/bukkit", "Sealed" to true)
        }
    }
}

// Compile tests with -parameters for better junit parameterized test names
tasks.compileTestJava {
    options.compilerArgs.add("-parameters")
}

// Bump compile tasks to 1GB memory to avoid OOMs
tasks.withType<JavaCompile>().configureEach {
    options.forkOptions.memoryMaximumSize = "1G"
}

val scanJarForBadCalls by tasks.registering(io.papermc.paperweight.tasks.ScanJarForBadCalls::class) {
    badAnnotations.add("Lio/papermc/paper/annotation/DoNotUse;")
    jarToScan.set(tasks.jar.flatMap { it.archiveFile })
    classpath.from(configurations.compileClasspath)
}
tasks.check {
    dependsOn(scanJarForBadCalls)
}

// Use TCA for console improvements
tasks.jar {
    val archiveOperations = services.archiveOperations
    from(alsoShade.elements.map {
        it.map { f ->
            if (f.asFile.isFile) {
                archiveOperations.zipTree(f.asFile)
            } else {
                f.asFile
            }
        }
    })
}

tasks.test {
    include("**/**TestSuite.class")
    workingDir = temporaryDir
    useJUnitPlatform {
        forkEvery = 1
        excludeTags("Slow")
    }

    // Configure mockito agent that is needed in newer java versions
    val provider = objects.newInstance<MockitoAgentProvider>()
    provider.fileCollection.from(mockitoAgent)
    jvmArgumentProviders.add(provider)
}

// ---------------------------------------------------------------------------
// OrigCore start - OrigCore 專屬單元測試
//
// 只驗證 OrigCore 自己的純邏輯（設定驗證、工作池語意、取消語意、百分位數、
// CPU 推導）。這些測試不需要啟動 Minecraft 伺服器，因此與 Paper 既有的
// test 任務分開：Paper 的 test 任務會啟動伺服器且只執行 *TestSuite。
//
// 執行方式：gradlew :paper-server:origcoreTest
// ---------------------------------------------------------------------------
val origCoreTest: SourceSet = sourceSets.create("origcoreTest") {
    java.srcDir("src/origcoreTest/java")
}
// 沿用 Paper 既有的測試相依（JUnit 5 等）與編譯類別路徑。
configurations.named(origCoreTest.compileClasspathConfigurationName) {
    extendsFrom(configurations.testCompileClasspath.get())
}
configurations.named(origCoreTest.runtimeClasspathConfigurationName) {
    extendsFrom(configurations.testRuntimeClasspath.get())
}
// 受測類別位於主原始碼集。
origCoreTest.compileClasspath += sourceSets.main.get().output
origCoreTest.runtimeClasspath += sourceSets.main.get().output

val origCoreTestTask = tasks.register<Test>("origcoreTest") {
    description = "執行 OrigCore 專屬單元測試（不需要 Minecraft 伺服器）。"
    group = "verification"
    testClassesDirs = origCoreTest.output.classesDirs
    classpath = origCoreTest.runtimeClasspath
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
tasks.named("check") {
    dependsOn(origCoreTestTask)
}

// ---------------------------------------------------------------------------
// OrigCore start - 建置後自動複製 bundler JAR 到指定目錄
//
// 用途：每次產出 mojmap bundler JAR 之後，自動複製一份到伺服器實際執行的目錄
//       （例如 MCSManager 的實例目錄），省去手動複製。
//
// 目標目錄由 Gradle 屬性 mcsmanagerInstanceDir 決定，★不寫死在版控檔案裡★：
//   - 專案 gradle.properties
//   - 使用者家目錄的 ~/.gradle/gradle.properties（建議放這裡，只有本機生效）
//   - 或命令列 -PmcsmanagerInstanceDir=...
//
// 未設定時：略過複製，只印一行說明（CI 與其他開發者不受影響）。
// 已設定但目錄不存在時：印出警告並略過，不讓建置失敗（JAR 本身仍然可用）。
//
// 刻意使用獨立的 Task 類別而不是 doLast 閉包：腳本層級的閉包會參考 Gradle
// 腳本物件，無法被 Gradle 的設定快取序列化（實際踩過這個錯）。
// ---------------------------------------------------------------------------

/** 把產出的 mojmap bundler JAR 複製到 [instanceDir] 指定的目錄。 */
abstract class DeployBundlerJarTask : DefaultTask() {

    /** 目標目錄；未設定（或空白）時整個任務直接略過並印出說明。 */
    @get:Internal
    abstract val instanceDir: Property<String>

    /** bundler 任務的產出；由 dependsOn 的任務提供。 */
    @get:Internal
    abstract val jarFiles: ConfigurableFileCollection

    @TaskAction
    fun deploy() {
        val configured = instanceDir.orNull
        if (configured.isNullOrBlank()) {
            logger.lifecycle(
                "[OrigCore] mcsmanagerInstanceDir is not set; the bundler JAR was NOT copied. " +
                    "Set it (for example in ~/.gradle/gradle.properties) to copy it automatically."
            )
            return
        }

        val jars = jarFiles.files.filter { it.name.endsWith(".jar") }
        if (jars.size != 1) {
            logger.warn(
                "[OrigCore] Expected exactly one bundler JAR but found {} ({}); nothing was copied.",
                jars.size, jars.map { it.name }
            )
            return
        }

        val jar = jars.single()
        val destinationDir = File(configured)
        if (!destinationDir.isDirectory) {
            logger.warn(
                "[OrigCore] mcsmanagerInstanceDir does not exist, so {} was NOT copied: {}",
                jar.name, destinationDir.absolutePath
            )
            return
        }

        val destination = File(destinationDir, jar.name)
        jar.copyTo(destination, overwrite = true)
        logger.lifecycle(
            "[OrigCore] Copied {} to {} ({} bytes).",
            jar.name, destination.absolutePath, destination.length()
        )
    }
}

val deployBundlerJarTask = tasks.register<DeployBundlerJarTask>("deployBundlerJar") {
    group = "origcore"
    description = "把 mojmap bundler JAR 複製到 mcsmanagerInstanceDir 指定的目錄（未設定則略過）。"
    instanceDir.set(providers.gradleProperty("mcsmanagerInstanceDir"))
    val bundlerJarTask = tasks.named("createMojmapBundlerJar")
    dependsOn(bundlerJarTask)
    jarFiles.from(bundlerJarTask)
}

// 每次產出 bundler JAR 之後自動複製；目標未設定時會自行略過。
tasks.named("createMojmapBundlerJar") {
    finalizedBy(deployBundlerJarTask)
}
// OrigCore end

val generatedDir: java.nio.file.Path = layout.projectDirectory.dir("src/generated/java").asFile.toPath()
idea {
    module {
        generatedSourceDirs.add(generatedDir.toFile())
    }
}
sourceSets {
    main {
        java {
            srcDir(generatedDir)
        }
    }
}

fun TaskContainer.registerRunTask(
    name: String,
    block: JavaExec.() -> Unit
): TaskProvider<JavaExec> = register<JavaExec>(name) {
    group = "runs"
    mainClass.set("org.bukkit.craftbukkit.Main")
    standardInput = System.`in`
    workingDir = rootProject.layout.projectDirectory
        .dir(providers.gradleProperty("paper.runWorkDir").getOrElse("run"))
        .asFile
    javaLauncher.set(project.javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    jvmArgs("-XX:+AllowEnhancedClassRedefinition")

    if (rootProject.childProjects["test-plugin"] != null) {
        val testPluginJar = rootProject.project(":test-plugin").tasks.jar.flatMap { it.archiveFile }
        inputs.file(testPluginJar)
        args("-add-plugin=${testPluginJar.get().asFile.absolutePath}")
    }

    args("--nogui")
    systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", true)
    if (providers.gradleProperty("paper.runDisableWatchdog").getOrElse("false") == "true") {
        systemProperty("disable.watchdog", true)
    }
    systemProperty("io.papermc.paper.suppress.sout.nags", true)

    val memoryGb = providers.gradleProperty("paper.runMemoryGb").getOrElse("2")
    minHeapSize = "${memoryGb}G"
    maxHeapSize = "${memoryGb}G"

    doFirst {
        workingDir.mkdirs()
    }

    block(this)
}

tasks.registerRunTask("runServer") {
    description = "Spin up a test server from the Mojang mapped server jar"
    classpath(tasks.includeMappings.flatMap { it.outputJar })
    classpath(configurations.runtimeClasspath)
}

tasks.registerRunTask("runReobfServer") {
    description = "Spin up a test server from the reobfJar output jar"
    classpath(tasks.reobfJar.flatMap { it.outputJar })
    classpath(configurations.runtimeClasspath)
}

tasks.registerRunTask("runDevServer") {
    description = "Spin up a test server without assembling a jar"
    classpath(sourceSets.main.map { it.runtimeClasspath })
}

tasks.registerRunTask("runBundler") {
    description = "Spin up a test server from the Mojang mapped bundler jar"
    classpath(tasks.createMojmapBundlerJar.flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runReobfBundler") {
    description = "Spin up a test server from the reobf bundler jar"
    classpath(tasks.createReobfBundlerJar.flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runPaperclip") {
    description = "Spin up a test server from the Mojang mapped Paperclip jar"
    classpath(tasks.createMojmapPaperclipJar.flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runReobfPaperclip") {
    description = "Spin up a test server from the reobf Paperclip jar"
    classpath(tasks.createReobfPaperclipJar.flatMap { it.outputZip })
    mainClass.set(null as String?)
}

fill {
    project("paper")
    versionFamily(paperweight.minecraftVersion.map { it.split(".", "-").takeWhile { part -> part.toIntOrNull() != null }.take(2).joinToString(".") })
    version(paperweight.minecraftVersion)

    build {
        channel = BuildChannel.STABLE

        downloads {
            register("server:default") {
                file = tasks.createMojmapPaperclipJar.flatMap { it.outputZip }
                nameResolver.set { project, _, version, build -> "$project-$version-$build.jar" }
            }
        }
    }
}
