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

// The cores are described by ../cores.lock.json (point 7: the source of each binary shipped must be known). A core
// taken from an official release is checked against its SHA-256; one from the libretro buildbot gives the commit
// it was built from (read in the binary: the build stops when it cannot be read). An ABI a core is not listed for
// gets no copy of it (melonDS DS: arm64-v8a only, the ABI of its official release). Writes coeurs.txt / coeurs.json.
abstract class DownloadCores : DefaultTask() {
    @get:InputFile
    abstract val lockFile: RegularFileProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The bytes of the zip entry whose name ends with {@code name}. */
    private fun unzip(bytes: ByteArray, name: String): ByteArray {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: throw GradleException("$name absent de l'archive")
                if (e.name.endsWith(name)) return zip.readBytes()
            }
        }
    }

    @TaskAction
    fun download() {
        @Suppress("UNCHECKED_CAST")
        val lock = groovy.json.JsonSlurper().parse(lockFile.get().asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val cores = (lock["cores"] as List<Map<String, Any?>>).filter { it["android"] != null }
        val shipped = mutableListOf<Map<String, String>>()
        for (abi in abis.get()) {
            for (c in cores) {
                @Suppress("UNCHECKED_CAST")
                val android = c["android"] as Map<String, Any?>
                val name = android["file"] as String
                val target = outputDir.get().dir(abi).file("lib$name").asFile
                if (abi !in (android["abis"] as List<*>)) {
                    // not shipped for this ABI (a copy left from an earlier build would be packaged: remove it)
                    target.delete()
                    continue
                }
                val release = c["from"] == "release"
                if (release && target.isFile && sha256(target.readBytes()) != c["entrySha256"]) target.delete()
                if (!target.isFile) {
                    logger.lifecycle("Telechargement du coeur d'emulation ${c["id"]} ($abi)")
                    target.parentFile.mkdirs()
                    val url = if (release) c["url"] as String else "https://buildbot.libretro.com/nightly/android/latest/$abi/$name.zip"
                    val zip = URI(url).toURL().openStream().use { it.readBytes() }
                    if (release && sha256(zip) != c["sha256"]) throw GradleException("${c["id"]} : empreinte de $url differente de cores.lock.json")
                    val so = unzip(zip, if (release) c["entry"] as String else name)
                    if (release && sha256(so) != c["entrySha256"]) throw GradleException("${c["id"]} : empreinte du coeur differente de cores.lock.json")
                    val tmp = File(target.path + ".tmp")
                    tmp.writeBytes(so)
                    tmp.renameTo(target)
                }
                val bytes = target.readBytes()
                val ref = if (release) c["tag"] as String else {
                    val m = Regex(c["commit"] as String).find(String(bytes, Charsets.ISO_8859_1))
                        ?: throw GradleException("Commit introuvable dans $abi/${target.name} : la source de ce binaire ne pourrait pas etre fournie (cores.lock.json)")
                    m.groupValues.last()
                }
                val sha = sha256(bytes)
                logger.lifecycle("    $abi/${target.name} sha256 $sha ${if (release) "version" else "commit"} $ref")
                shipped += mapOf("file" to "$abi/${target.name}", "core" to c["name"] as String, "license" to c["license"] as String,
                    "repo" to c["repo"] as String, "ref" to ref, "sha256" to sha, "source" to "https://github.com/${c["repo"]}/tree/$ref")
            }
        }
        val dir = outputDir.get().asFile
        File(dir, "coeurs.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(shipped)) + "\n")
        File(dir, "coeurs.txt").writeText((listOf("Coeurs d'emulation livres avec Cable Club (Android) : fichier, coeur, licence, version ou commit, empreinte, source.", "") +
            shipped.map { "${it["file"]} : ${it["core"]}, ${it["license"]}, ${it["ref"]}, sha256 ${it["sha256"]}\n    source : ${it["source"]}" }).joinToString("\n") + "\n")
    }
}

val abiList = listOf("arm64-v8a", "x86_64")
// kept outside build/ (a clean does not download them again)
val coresDir = File(repo, "build/cores-android")
val downloadCores by tasks.registering(DownloadCores::class) {
    lockFile.set(File(repo, "cores.lock.json"))
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
