import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.music.musica"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.music.musica"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

/**
 * Strips org.schabi.newpipe.extractor.utils.Utils.class (and nested siblings) from the upstream
 * NewPipeExtractor jar so Musica's API-1-safe vendored Utils.java is the single definition
 * reaching D8/R8 dex merging in release builds. Configuration-cache safe and incremental.
 */
abstract class StripNewPipeUtilsJarTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun strip() {
        val out = outputJar.get().asFile
        out.parentFile?.mkdirs()
        val seenEntries = HashSet<String>()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            inputJars.files.sortedBy { it.name }.forEach { jarFile ->
                ZipInputStream(jarFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val isExcluded = name == "org/schabi/newpipe/extractor/utils/Utils.class" ||
                            (name.startsWith("org/schabi/newpipe/extractor/utils/Utils$") && name.endsWith(".class"))
                        if (!isExcluded && seenEntries.add(name)) {
                            val copy = ZipEntry(name)
                            if (entry.time != -1L) copy.time = entry.time
                            zos.putNextEntry(copy)
                            if (!entry.isDirectory) {
                                zis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
    }
}

val newPipeExtractorRaw = configurations.create("newPipeExtractorRaw") {
    isTransitive = false
    isCanBeConsumed = false
}

val stripNewPipeExtractorUtils = tasks.register<StripNewPipeUtilsJarTask>("stripNewPipeExtractorUtils") {
    inputJars.from(newPipeExtractorRaw)
    outputJar.set(layout.buildDirectory.file("stripped-libs/NewPipeExtractor-v0.26.3-noutils.jar"))
}

dependencies {
    // Compose & AndroidX
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Media3 / ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.database)
    implementation(libs.kotlinx.coroutines.guava)

    // Networking & Serialization
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    // YouTube Extraction
    newPipeExtractorRaw(libs.newpipe.extractor)
    implementation(libs.innertubex.android)
    implementation(files(stripNewPipeExtractorUtils.flatMap { it.outputJar }))
    implementation(libs.newpipe.nanojson)
    implementation(libs.jsr305)
    implementation(libs.protobuf.javalite)
    implementation(libs.rhino)
    implementation(libs.rhino.engine)
    implementation(libs.quickjs.android)

    // Images & Frosted Glass
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
