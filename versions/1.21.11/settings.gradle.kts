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
// Paperweight 需要一個 git 工作區才能取得 upstream remote 與版本分支，
// 因此這裡除了本目錄，也接受上層目錄有 .git 的情況。
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
