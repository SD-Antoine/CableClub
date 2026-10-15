import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
}

// ---------------------------------------------------------------- the same Cable Club as on the PC

val repo: File = rootProject.projectDir.parentFile
val appVersion: String = Regex("VERSION = \"([^\"]+)\"").find(File(repo, "src/slt/Version.java").readText())!!.groupValues[1]
val versionNumber: Int = appVersion.split('.', '-').take(3).map { it.takeWhile(Char::isDigit).ifEmpty { "0" }.toInt() }
    .let { it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 } }

// the sources of the PC version that Android runs as they are; the others have an Android version in src/main/java
// (same class names: the rest of the program does not see the difference)
val pcOnly = listOf(
    "slt/Desktop.java", "slt/Icon.java", "slt/Images.java", "slt/Main.java", "slt/Net.java",
    "slt/server/**",
    "slt/client/ClientApp.java", "slt/client/Firewall.java", "slt/client/GameProcess.java", "slt/client/Hosting.java",
    "slt/client/Updates.java", "slt/client/Upnp.java",
    "slt/player/AudioOut.java", "slt/player/Controls.java", "slt/player/CoreHost.java", "slt/player/Cores.java",
    "slt/player/PlayerMain.java", "slt/player/Retro.java", "slt/player/ScriptRunner.java",
)
val shared = layout.buildDirectory.dir("shared")

val sharedJava by tasks.registering(Sync::class) {
    from(File(repo, "src")) { exclude(pcOnly) }
    into(shared.map { it.dir("java") })
}

// the interface and the patch notes, read as resources as on the PC
val sharedResources by tasks.registering(Sync::class) {
    from(File(repo, "web")) { into("web") }
    from(File(repo, "CHANGELOG.md")) { rename { "changelog.md" } }
    into(shared.map { it.dir("resources") })
}

// the randomizer engine (Universal Pokemon Randomizer ZX), built by ../build.ps1, without its Swing interface
val uprJar = File(repo, "build/lib/upr-zx.jar")
val uprAndroid by tasks.registering(Jar::class) {
    doFirst {
        if (!uprJar.isFile) {
            throw GradleException("Moteur de randomisation absent : lancez d'abord .\\build.ps1 -NoPackage Ã  la racine du dÃ©pÃ´t.")
        }
    }
    archiveFileName.set("upr-zx-android.jar")
    destinationDirectory.set(shared.map { it.dir("libs") })
    from({ if (uprJar.isFile) zipTree(uprJar) else files() }) {
        exclude("com/dabomstew/pkrandom/newgui/*.class", "com/dabomstew/pkrandom/cli/**", "launcher/**", "META-INF/**")
    }
}

// ---------------------------------------------------------------- emulator cores (libretro builds for Android)

abstract class DownloadCores : DefaultTask() {
    @get:Input
    abstract val cores: ListProperty<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        for (abi in abis.get()) {
            for (core in cores.get()) {
                val name = "${core}_libretro_android.so"
                val target = outputDir.get().dir(abi).file("lib$name").asFile
                if (!target.isFile) {
                    logger.lifecycle("TÃ©lÃ©chargement du cÅ“ur d'Ã©mulation $core ($abi)")
                    target.parentFile.mkdirs()
                    val url = URI("https://buildbot.libretro.com/nightly/android/latest/$abi/$name.zip").toURL()
                    val tmp = File(target.path + ".tmp")
                    ZipInputStream(url.openStream()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: throw GradleException("$name absent de l'archive de $abi")
                            if (e.name.endsWith(name)) {
                                tmp.outputStream().use { zip.copyTo(it) }
                                break
                            }
                        }
                    }
                    tmp.renameTo(target)
                }
                val sha = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
                logger.lifecycle("    $abi/${target.name} sha256 $sha")
            }
        }
    }
}

val abiList = listOf("arm64-v8a", "x86_64")
// kept outside build/ (a clean does not download them again)
val coresDir = File(repo, "build/cores-android")
val downloadCores by tasks.registering(DownloadCores::class) {
    cores.set(listOf("mgba", "melondsds"))
    abis.set(abiList)
    outputDir.set(coresDir)
}

// ---------------------------------------------------------------- signing (the release key never enters the repository)

val signingFile = File(System.getenv("CABLECLUB_SIGNING") ?: (System.getProperty("user.home") + "/.cableclub/android-signing.properties"))
val signingProps = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }

android {
    namespace = "app.cableclub"
    compileSdk = 36
    compileSdkMinor = 1
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.cableclub"
        // Android 11: the Java of older phones lacks too much (java.nio.file, see coreLibraryDesugaring)
        minSdk = 30
        targetSdk = 36
        versionCode = versionNumber
        versionName = appVersion
        ndk {
            abiFilters += abiList
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
        // the Java of the PC version on the Android versions that lack it (Path.of, Files.readString…)
        isCoreLibraryDesugaringEnabled = true
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    sourceSets.getByName("main") {
        java.srcDir(shared.get().dir("java"))
        resources.srcDir(shared.get().dir("resources"))
        jniLibs.srcDir(coresDir)
    }

    packaging {
        jniLibs {
            // the cores are opened by their path (dlopen): they must be files of their own on the phone
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF")
        }
    }

    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // where the app comes from: the APK of the GitHub releases updates itself (src/github), the Play Store version
    // (bundle .aab) leaves its updates to Google Play, whose rules forbid any other way (src/play)
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
        }
        create("play") {
            dimension = "store"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(files(uprAndroid))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}

tasks.named("preBuild") {
    dependsOn(sharedJava, sharedResources, downloadCores, uprAndroid)
}

base {
    archivesName.set("CableClub-$appVersion")
}
