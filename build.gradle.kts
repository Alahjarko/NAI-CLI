import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "net.naicli"
version = "0.3.0-SNAPSHOT"

// Versioned headless snapshot; standalone builds never read a sibling checkout.
val sharedSourceRoot = file("src/shared/kotlin")
val sharedResourceRoot = file("src/shared/resources")

kotlin {
    // 与 MacNAI 同一条工具链：JDK 25 编译，字节码对齐 25（Console.isTerminal 需要 JDK 22+）。
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        // 不带 MacNAI 那边的 `-Xjvm-default=all`：它只影响接口默认方法编译成
        // JVM default 方法还是 DefaultImpls，而本项目的调用方全是 Kotlin，两者等价。
        // （Kotlin 2.4 已不接受 `all` 这个取值，硬跟反而会编译失败。）
    }
}

// Keep Java and Kotlin compilation at the same actual minimum runtime.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

sourceSets {
    main {
        kotlin {
            srcDir(sharedSourceRoot)
            // 白名单而不是黑名单：include 一旦声明，未列出的路径就不参与编译。
            // 这样 MacNAI 新增一个 ui/ 子包不会悄悄把 Compose 依赖拖进来。
            //
            // ui/state contains pure Kotlin holders used by AppContainer, not Compose UI.
            include(
                "net/naicli/**",
                "net/pocketnai/BuildConfig.kt",
                "net/pocketnai/core/**",
                "net/pocketnai/domain/**",
                "net/pocketnai/data/**",
                "net/pocketnai/di/**",
                "net/pocketnai/ui/state/**",
            )
        }
        resources {
            srcDir(sharedResourceRoot)
            // 私有节点含可还原的凭据：即使本机有资源，也不得进入公开发行包。
            exclude("**/public_proxy_nodes.enc")
        }
    }
}

dependencies {
    // 刻意不含任何 Compose / lifecycle / navigation 依赖 —— 这是本模块能无头编译的证明。
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jna)

    testImplementation(libs.kotlin.test)
}

application {
    applicationName = "nai"
    mainClass.set("net.naicli.MainKt")

    // JNA 调 Security.framework 读 Keychain，JDK 25 要求显式放行原生访问；
    // headless 是因为 data/image/ 用 ImageIO，而 CLI 永远不需要窗口。
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.awt.headless=true",
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
}

/**
 * 确认"排除 ui/ 之后仍然编得过"这条前提还成立。
 *
 * MacNAI 将来若在 core/domain/data/di 里引入 Compose（AGENTS.md 明令禁止，但人会破例），
 * 或者 AppContainer 开始引用 ui/state 之外的界面类，本项目的编译会失败，
 * 而这个任务能把失败原因直接指出来，省掉一轮排查。
 */
tasks.register("checkSourceContract") {
    group = "verification"
    description = "校验 MacNAI 的无头子集仍然不含 Compose 依赖"
    doLast {
        val headless = listOf("core", "domain", "data", "di")
        val offenders = headless.flatMap { pkg ->
            fileTree("$sharedSourceRoot/net/pocketnai/$pkg") {
                include("**/*.kt")
            }.files.filter { file ->
                file.readText().let {
                    it.contains("androidx.compose") || it.contains("net.pocketnai.resources")
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "MacNAI 的无头层出现了 Compose 依赖，NAI-CLI 的源码共享前提被破坏：\n" +
                    offenders.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("源码契约校验通过：core/domain/data/di 无 Compose 依赖。")
    }
}

tasks.named("check") { dependsOn("checkSourceContract") }

// Default tests are fully isolated, with no access to the user's native credential store.
tasks.named<Test>("test") { exclude("**/NativeKeychainTest*") }
tasks.register<Test>("nativeStoreTest") {
    group = "verification"
    description = "Opt-in native credential-store roundtrip using isolated dummy entries"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("net.naicli.platform.NativeKeychainTest") }
    environment("NAI_CLI_TEST_NATIVE_STORE", "1")
    outputs.upToDateWhen { false }
}


// Ship installation and platform requirements alongside both launchers.
distributions {
    main {
        contents {
            from("README.md")
            from(listOf("docs/CROSS_PLATFORM.md", "docs/PROMPTS.md", "docs/pocketnai-prompt-snapshot.json")) { into("docs") }
        }
    }
}
