import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The app's logic without Android: ELM327, OBD, the car and trouble code databases, CarLink.
// jvm — the Android app and the unit tests; js — a browser page (Web Serial) built on the same code.
plugins {
    kotlin("multiplatform")
}

// Optional report sources (private/report, git-ignored here): compiled into the core when the checkout has them. Without them everything builds as before. -PnoPrivate builds without them anyway.
val privateReport = rootProject.file("private/report").takeIf { it.isDirectory && !project.hasProperty("noPrivate") }

kotlin {
    // expect/actual classes (JSONObject, IOException) are still "Beta" in Kotlin 2.0.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    js(IR) {
        // The page imports it as "obdscanner-core": ES modules with TypeScript declarations (core/build/dist/js/productionLibrary).
        moduleName = "obdscanner-core"
        browser()
        // Node runs the core's tests in the browser build (the session replays against the JVM's goldens: ~5 min).
        nodejs {
            testTask { useMocha { timeout = "20m" } }
        }
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        jvmMain.dependencies {
            // Android has org.json built in; the JVM build only compiles against it.
            compileOnly("org.json:json:20240303")
        }
        jsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.json:json:20240303")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        if (privateReport != null) {
            commonMain { kotlin.srcDir(privateReport.resolve("src/commonMain/kotlin")) }
            jsMain { kotlin.srcDir(privateReport.resolve("src/jsMain/kotlin")) }
            jvmTest { kotlin.srcDir(privateReport.resolve("src/jvmTest/kotlin")) }
            jsTest { kotlin.srcDir(privateReport.resolve("src/jsTest/kotlin")) }
        }
    }
}

// Node.js and Yarn (the browser build) come from settings.gradle.kts: the project allows no repositories of its own.
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().downloadBaseUrl = null
}
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    rootProject.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().downloadBaseUrl = null
}

// The tests read the sessions archive and the databases by paths relative to the module, like before.
tasks.withType<Test>().configureEach {
    workingDir = projectDir
}
