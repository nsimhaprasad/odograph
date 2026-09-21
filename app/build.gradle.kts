import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "in.odograph.tracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "in.odograph.tracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 32
        versionName = "0.20.0"
    }

    // buildConfig so the running app can say which build it is: without it neither the box
    // nor anyone querying it could tell 0.7.0 from 0.9.0, which made "did the install take?"
    // unanswerable except by looking for a feature that had not existed before.
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isIncludeAndroidResources = true }

    signingConfigs {
        create("release") {
            if (!keystoreProps.isEmpty) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
            // v1 (JAR) signing is only meaningful below API 24 and AGP skips it above that.
            // minSdk is 26 and the target box is API 34, so v2 is both correct and sufficient.
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/{AL2.0,LGPL2.1}"
        )
    }
}

// Exported schemas let the 1 -> 2 migration be tested against the real DDL rather than trusting
// that hand-written ALTER statements match what Room expects.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android { sourceSets["test"].assets.srcDir("$projectDir/schemas") }

// Screenshot rendering is a build tool, not a unit test: it asserts nothing about correctness and
// it needs Robolectric's native graphics runtime, which loads once per JVM and only if no
// legacy-graphics test got there first. Keeping it out of the default suite stops a tooling
// constraint from producing false failures.
//
//   ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) {
        filter { excludeTestsMatching("*ScreenshotTest*") }
    } else {
        setForkEvery(1)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // MG iSMART India vehicle/charging status, brought in via the composite build.
    implementation("io.windsor.telematics:telematics:0.1.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("io.ktor:ktor-server-core:2.3.11")
    implementation("io.ktor:ktor-server-cio:2.3.11")

    implementation("org.osmdroid:osmdroid-android:6.1.18")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.room:room-testing:2.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
