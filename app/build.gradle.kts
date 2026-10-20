import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

abstract class GenerateGitHashTask : DefaultTask() {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headFile: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val refsDir: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packedRefsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val hash = resolveHash().take(7) // Get the short commit hash

        val outFile = outputDir.file("git-hash.txt").get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(hash)
    }

    /**
     * The commit the build is made from.
     *
     * The head file points to the current ref, the ref itself is either a loose
     * file in the refs directory or only listed in the packed refs. A git
     * worktree has its own head file, the refs are in the common directory.
     */
    private fun resolveHash(): String {
        val head = headFile.getOrNull()?.asFile ?: return ""
        if (!head.exists()) return ""

        val headContent = head.readText().trim()
        // If it is a detached HEAD (commit hash directly) there is nothing to resolve
        if (!headContent.startsWith("ref:")) return headContent
        val refPath = headContent.substring(5).trim() // e.g., refs/heads/main

        val looseRef = refsDir.getOrNull()?.asFile?.let {
            File(it, refPath.removePrefix("refs/"))
        }
        if (looseRef != null && looseRef.exists()) return looseRef.readText().trim()

        val packedRefs = packedRefsFile.getOrNull()?.asFile
        if (packedRefs != null && packedRefs.exists()) {
            for (line in packedRefs.readLines()) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("^")) continue
                val parts = line.split(" ", limit = 2)
                if (parts.size == 2 && parts[1].trim() == refPath) return parts[0].trim()
            }
        }
        return ""
    }
}

/**
 * The .git directory of the checkout. In a git worktree it is a file that points
 * to the git directory of the worktree, the refs live in the common directory.
 */
val dotGit = layout.projectDirectory.dir("../.git").asFile
val (gitDir, gitCommonDir) = run {
    if (dotGit.isFile) {
        val worktreeGitDir = File(dotGit.readText().trim().removePrefix("gitdir:").trim())
        val common = try {
            File(worktreeGitDir, "commondir").readText().trim()
        } catch (_: Throwable) {
            "."
        }
        worktreeGitDir to File(worktreeGitDir, common).canonicalFile
    } else {
        dotGit to dotGit
    }
}

val generateGitHash = tasks.register<GenerateGitHashTask>("generateGitHash") {
    // Only the files that exist are set, a checkout without packed refs or
    // without a refs directory is fine.
    val head = gitDir.resolve("HEAD")
    if (head.exists()) headFile.set(head)

    val refs = gitCommonDir.resolve("refs")
    if (refs.exists()) refsDir.set(refs)

    val packedRefs = gitCommonDir.resolve("packed-refs")
    if (packedRefs.exists()) packedRefsFile.set(packedRefs)

    outputDir.set(layout.buildDirectory.dir("generated/git"))
}

android {
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Looks like google likes to add metadata only they can read https://gitlab.com/IzzyOnDroid/repo/-/work_items/491
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                generateGitHash,
                GenerateGitHashTask::outputDir
            )
        }
    }

    signingConfigs {
        // We just use SIGNING_KEY_ALIAS here since it won't change
        // so won't kill the configuration cache.
        if (System.getenv("SIGNING_KEY_ALIAS") != null) {
            val tmpFilePath = System.getProperty("user.home") + "/work/_temp/keystore/"
            val prereleaseStoreFile: File? = File(tmpFilePath).listFiles()?.first()

            create("prerelease") {
                storeFile = prereleaseStoreFile?.let { file(it) }
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }

            // The stable flavour is signed with the same key, so that the app can be
            // updated in place and the in app updater can install the new version.
            create("stable") {
                storeFile = prereleaseStoreFile?.let { file(it) }
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lagradost.cloudstream3"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        manifestPlaceholders["target_sdk_version"] = libs.versions.targetSdk.get()

        // Reads local.properties
        val localProperties = gradleLocalProperties(rootDir, project.providers)

        buildConfigField(
            "long",
            "BUILD_DATE",
            "${System.currentTimeMillis()}"
        )
        buildConfigField(
            "String",
            "SIMKL_CLIENT_ID",
            "\"" + (System.getenv("SIMKL_CLIENT_ID") ?: localProperties["simkl.id"]) + "\""
        )
        buildConfigField(
            "String",
            "SIMKL_CLIENT_SECRET",
            "\"" + (System.getenv("SIMKL_CLIENT_SECRET") ?: localProperties["simkl.secret"]) + "\""
        )
        buildConfigField(
            "String",
            "MAL_KEY",
            "\"" + (System.getenv("MAL_KEY") ?: localProperties["mal.key"]) + "\""
        )
        buildConfigField(
            "String",
            "ANILIST_KEY",
            "\"" + (System.getenv("ANILIST_KEY") ?: localProperties["anilist.key"]) + "\""
        )
        /**
         * The repository the in app updater checks for new versions.
         * Override it for other builds with -PupdateRepoOwner=... -PupdateRepoName=...
         */
        val updateRepoOwner =
            (project.findProperty("updateRepoOwner") as String?) ?: "itswill00"
        val updateRepoName =
            (project.findProperty("updateRepoName") as String?) ?: "cloudstream"
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"$updateRepoOwner\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"$updateRepoName\"")

        /**
         * Limit the architectures of the build, e.g. -PtargetAbi=armeabi-v7a.
         * A single architecture makes the APK a lot smaller, which is needed to
         * send it to a test device.
         *
         * The build is for arm64, that is the architecture of the devices that
         * are used. Use -PtargetAbi=all for every architecture.
         */
        val targetAbi = (project.findProperty("targetAbi") as String?) ?: "arm64-v8a"
        if (targetAbi.isNotBlank() && targetAbi != "all") {
            ndk {
                abiFilters += targetAbi
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("state")
    productFlavors {
        create("stable") {
            dimension = "state"
            if (signingConfigs.names.contains("stable")) {
                signingConfig = signingConfigs.getByName("stable")
            } else {
                logger.warn("No stable signing config!")
            }
        }
        create("prerelease") {
            dimension = "state"
            applicationIdSuffix = ".prerelease"
            if (signingConfigs.names.contains("prerelease")) {
                signingConfig = signingConfigs.getByName("prerelease")
            } else {
                logger.warn("No prerelease signing config!")
            }
            versionNameSuffix = "-PRE"
            versionCode = (System.currentTimeMillis() / 60000).toInt()
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(javaTarget.target)
        targetCompatibility = JavaVersion.toVersion(javaTarget.target)
    }

    java {
        // Use Java 17 toolchain even if a higher JDK runs the build.
        // We still use Java 8 for now which higher JDKs have deprecated.
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get()))
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Enables legacy JNI packaging to reduce APK size (similar to builds before minSdk 23).
            // Note: This may increase app startup time slightly.
            useLegacyPackaging = true
        }
    }

    namespace = "com.lagradost.cloudstream3"
}

dependencies {
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.instancio.core)
    androidTestImplementation(libs.junit.ktx)
    androidTestImplementation(libs.kotlin.test)

    // Android Core & Lifecycle
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json) // JSON Parser

    // Design & UI
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Coil Image Loading
    implementation(libs.bundles.coil)

    // Media 3 (ExoPlayer)
    implementation(libs.bundles.media3)
    implementation(libs.video)

    // FFmpeg Decoding
    implementation(libs.bundles.nextlib)

    // Anime-db for filler
    implementation(libs.anime.db)

    // PlayBack
    implementation(libs.colorpicker) // Subtitle Color Picker
    implementation(libs.newpipeextractor) // For Trailers
    implementation(libs.juniversalchardet) // Subtitle Decoding

    // UI Stuff
    implementation(libs.shimmer) // Shimmering Effect (Loading Skeleton)
    implementation(libs.palette.ktx) // Palette for Images -> Colors
    implementation(libs.tvprovider)
    implementation(libs.overlappingpanels) // Gestures
    implementation(libs.biometric) // Fingerprint Authentication
    implementation(libs.previewseekbar.media3) // SeekBar Preview
    implementation(libs.qrcode.kotlin) // QR Code for PIN Auth on TV

    // Extensions & Other Libs
    implementation(libs.jsoup) // HTML Parser
    implementation(libs.ksoup) // HTML Parser
    implementation(libs.rhino) // Run JavaScript
    implementation(libs.safefile) // To Prevent the URI File Fu*kery
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio) // NIO Flavor Needed for NewPipeExtractor
    implementation(libs.conscrypt.android) // To Fix SSL Fu*kery on Android 9
    implementation(libs.jackson.module.kotlin) // JSON Parser
    implementation(libs.zipline)

    // Temp/deprecated; will be removed once extensions have time to migrate from using it
    implementation("com.google.code.gson:gson:2.11.0")
    // Deprecated; will be removed once extensions have time to migrate from using it
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Torrent Support
    implementation(libs.torrentserver)

    // Downloading & Networking
    implementation(libs.work.runtime.ktx)
    implementation(libs.nicehttp) // HTTP Lib

    implementation(libs.bundles.compose)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.io.core) // Logcat parser

    implementation(project(":library"))
    implementation(project(":shared"))
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.directories) // Full Sources
}

tasks.register<Copy>("copyJar") {
    dependsOn("build", ":library:jvmJar")
    from(
        "build/intermediates/compile_app_classes_jar/prereleaseDebug/bundlePrereleaseDebugClassesToCompileJar",
        "../library/build/libs"
    )
    into("build/app-classes")
    include("classes.jar", "library-jvm*.jar")
    // Remove the version
    rename("library-jvm.*.jar", "library-jvm.jar")
}

// Merge the app classes and the library classes into classes.jar
tasks.register<Jar>("makeJar") {
    // Duplicates cause hard to catch errors, better to fail at compile time.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(tasks.getByName("copyJar"))
    from(
        zipTree("build/app-classes/classes.jar"),
        zipTree("build/app-classes/library-jvm.jar")
    )
    destinationDirectory.set(layout.buildDirectory)
    archiveBaseName = "classes"
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(javaTarget)
        jvmDefault.set(JvmDefaultMode.ENABLE)
        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
        )
    }
}

dokka {
    moduleName = "App"
    dokkaSourceSets {
        configureEach {
            suppress = name != "prereleaseDebug"
            analysisPlatform = KotlinPlatform.JVM
            displayName = "JVM"
            documentedVisibilities(
                VisibilityModifier.Public,
                VisibilityModifier.Protected
            )

            sourceLink {
                localDirectory = file("..")
                remoteUrl("https://github.com/recloudstream/cloudstream/tree/master")
                remoteLineSuffix = "#L"
            }
        }
    }
}
