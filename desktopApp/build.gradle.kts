import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// ---------------------------------------------------------------- Flimmer für Windows

val flimmerVersion = rootProject.file("desktop-version.txt").takeIf { it.exists() }?.readText()?.trim() ?: "0.1"
val runNumber = System.getenv("GITHUB_RUN_NUMBER") ?: "lokal"

/** Schlüssel kommen beim Build aus den GitHub-Secrets (gleich wie bei der Android-App). */
val generateFlimmerConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/flimmerConfig/kotlin")
    val tmdb = System.getenv("TMDB_API_KEY") ?: ""
    val trakt = System.getenv("FLIMMER_TRAKT_ID") ?: ""
    inputs.property("tmdb", tmdb)
    inputs.property("trakt", trakt)
    inputs.property("version", flimmerVersion)
    inputs.property("run", runNumber)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("com/lagradost/cloudstream3")
        dir.mkdirs()
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        dir.resolve("BuildConfig.kt").writeText(
            """
            |package com.lagradost.cloudstream3
            |
            |object BuildConfig {
            |    const val TMDB_API_KEY = "${esc(tmdb)}"
            |    const val FLIMMER_TRAKT_ID = "${esc(trakt)}"
            |    const val FLIMMER_VERSION = "${esc(flimmerVersion)}"
            |    const val FLIMMER_BUILD = "${esc(runNumber)}"
            |}
            |""".trimMargin()
        )
    }
}

/** Der Flimmer Katalog wird unverändert aus der Android-App übernommen (eine gemeinsame Quelle). */
val copySharedKatalog by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java/com/lagradost/cloudstream3/flimmer")) {
        include("FlimmerKatalog.kt")
    }
    into(layout.buildDirectory.dir("generated/flimmerShared/kotlin/com/lagradost/cloudstream3/flimmer"))
}

kotlin {
    jvm {}
    sourceSets {
        jvmMain {
            kotlin.srcDir(generateFlimmerConfig)
            kotlin.srcDir(layout.buildDirectory.dir("generated/flimmerShared/kotlin"))
        }
        jvmMain.dependencies {
            implementation(libs.bundles.compose)
            implementation(libs.coil.network.okhttp)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.jackson.module.kotlin)
            implementation(libs.jsoup)
            implementation(libs.nicehttp)
            implementation("de.femtopedia.dex2jar:dex-translator:2.4.35")
            implementation(compose.desktop.currentOs) {
                // compose.desktop.currentOs imports the wrong material 2, so we exclude it
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            implementation(project(":library"))
        }
    }
}

tasks.matching { it.name.startsWith("compileKotlinJvm") || it.name == "jvmSourcesJar" }.configureEach {
    dependsOn(copySharedKatalog, generateFlimmerConfig)
}

// java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader in an unnamed module
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

/** Selbsttest ohne Fenster: Erweiterungen laden und einen Titel suchen (läuft im GitHub-Build). */
val flimmerSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn("jvmMainClasses")
    mainClass.set("ch.hotihysen.flimmer.desktop.selftest.SelfTestKt")
    classpath = files(
        layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        layout.buildDirectory.dir("processedResources/jvm/main"),
    ) + configurations.getByName("jvmRuntimeClasspath")
    environment("FLIMMER_DATA", layout.buildDirectory.dir("selftest").get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "ch.hotihysen.flimmer.desktop.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Flimmer"
            packageVersion = "1.0.0"
            description = "Flimmer"
            vendor = "Flimmer"
            // Module für Netzwerk (TLS), Zip und Skripte der Erweiterungen
            modules("java.net.http", "java.sql", "jdk.crypto.ec", "jdk.zipfs", "java.naming", "jdk.unsupported", "java.scripting", "jdk.localedata")

            val iconsRoot = project.file("src/desktop-icons")
            windows {
                iconFile.set(iconsRoot.resolve("icon-windows.ico"))
                console = false
            }
            linux {
                iconFile.set(iconsRoot.resolve("icon-linux.png"))
            }
        }
    }
}
