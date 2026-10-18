pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// OrigCore - 多版本資料夾結構的調整（與 Paper 上游不同之處）
// 本專案位於 OrigCore 母 repo 的 versions/<Minecraft 版本>/ 底下，
// git 工作區（.git）在母 repo 根目錄，而不是本目錄。
// Paper 原本要求每個專案目錄本身必須是一個 git clone（Paperweight 會用 git
// 取得 commit 資訊、並以 git 存放修補後的原始碼），在資料夾式多版本結構裡
// 母 repo 已經提供這個工作區，因此改為也接受上層目錄的 .git。
// 若你要把這個資料夾單獨搬出去當成獨立 repo，本目錄有 .git 時原本的檢查就會通過。
if (!file(".git").exists() && !file("../.git").exists() && !file("../../.git").exists()) {
    val errorText = """

        =====================[ ERROR ]=====================
         The Paper project directory is not a properly cloned Git repository.

         In order to build Paper from source you must clone
         the Paper repository using Git, not download a code
         zip from GitHub.

         Built Paper jars are available for download at
         https://papermc.io/downloads/paper

         See https://github.com/PaperMC/Paper/blob/main/CONTRIBUTING.md
         for further information on building and modifying Paper.

         OrigCore 補充：本專案預期位於 OrigCore 母 repo 的
         versions/<Minecraft 版本>/ 底下（.git 在母 repo 根目錄）。
         如果你是把這個資料夾單獨搬出來，請在這一層目錄執行 git init 並
         設定 upstream remote 指向 PaperMC/Paper。
        ===================================================
    """.trimIndent()
    error(errorText)
}

rootProject.name = "origcore" // OrigCore - 核心識別名稱

for (name in listOf("paper-api", "paper-server")) {
    include(name)
    file(name).mkdirs()
}

include("paper-checkstyle")

optionalInclude("test-plugin")
optionalInclude("paper-generator")

fun optionalInclude(name: String, op: (ProjectDescriptor.() -> Unit)? = null) {
    val settingsFile = file("$name.settings.gradle.kts")
    if (settingsFile.exists()) {
        apply(from = settingsFile)
        findProject(":$name")?.let { op?.invoke(it) }
    } else {
        settingsFile.writeText(
            """
            // Uncomment to enable the '$name' project
            // include(":$name")

            """.trimIndent()
        )
    }
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val paperVersionChannel = providers.gradleProperty("channel").get().trim()
    val paperBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (paperBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$paperBuildNumber-${paperVersionChannel.lowercase()}"
    }
    version = versionString
}

if (providers.gradleProperty("paperBuildCacheEnabled").orNull.toBoolean()) {
    val buildCacheUsername = providers.gradleProperty("paperBuildCacheUsername").orElse("").get()
    val buildCachePassword = providers.gradleProperty("paperBuildCachePassword").orElse("").get()
    if (buildCacheUsername.isBlank() || buildCachePassword.isBlank()) {
        println("The Paper remote build cache is enabled, but no credentials were provided. Remote build cache will not be used.")
    } else {
        val buildCacheUrl = providers.gradleProperty("paperBuildCacheUrl")
            .orElse("https://gradle-build-cache.papermc.io/")
            .get()
        val buildCachePush = providers.gradleProperty("paperBuildCachePush").orNull?.toBoolean()
            ?: System.getProperty("CI").toBoolean()
        buildCache {
            remote<HttpBuildCache> {
                url = uri(buildCacheUrl)
                isPush = buildCachePush
                credentials {
                    username = buildCacheUsername
                    password = buildCachePassword
                }
            }
        }
    }
}
