plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val djiApiKey = providers.gradleProperty("DJI_API_KEY").orNull?.trim()

android {
    namespace = "com.skycommand.relay.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.skycommand.relay"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["DJI_API_KEY"] = djiApiKey ?: "UNCONFIGURED_DJI_API_KEY"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libconstants.so",
                "**/libdji_innertools.so",
                "**/libdjibase.so",
                "**/libDJICSDKCommon.so",
                "**/libDJIFlySafeCore-CSDK.so",
                "**/libdjifs_jni-CSDK.so",
                "**/libDJIRegister.so",
                "**/libdjisdk_jni.so",
                "**/libDJIUpgradeCore.so",
                "**/libDJIUpgradeJNI.so",
                "**/libDJIWaypointV2Core-CSDK.so",
                "**/libdjiwpv2-CSDK.so",
                "**/libFlightRecordEngine.so",
                "**/libvideo-framing.so",
                "**/libwaes.so",
                "**/libagora-rtsa-sdk.so",
                "**/libc++.so",
                "**/libc++_shared.so",
                "**/libmrtc_28181.so",
                "**/libmrtc_agora.so",
                "**/libmrtc_core.so",
                "**/libmrtc_core_jni.so",
                "**/libmrtc_data.so",
                "**/libmrtc_log.so",
                "**/libmrtc_onvif.so",
                "**/libmrtc_rtmp.so",
                "**/libmrtc_rtsp.so",
                "**/libSdkyclx_clx.so",
                "**/libdataclx.so",
            )
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
            )
        }
    }
    androidResources {
        noCompress += listOf("so", "zip")
    }
}
kotlin { jvmToolchain(17) }

val androidNamespace = "http://schemas.android.com/apk/res/android"
val requiredRuntimePermissions = setOf(
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.READ_PHONE_STATE",
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.READ_EXTERNAL_STORAGE",
)

tasks.register("verifyAndroidManifestContract") {
    group = "verification"
    description = "Verifies the Android permissions and USB capability declarations required by the relay contract."
    dependsOn("processDebugMainManifest")
    doLast {
        val manifest = layout.buildDirectory
            .file("intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
            .get()
            .asFile
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val permissions = (0 until document.getElementsByTagName("uses-permission").length)
            .map { document.getElementsByTagName("uses-permission").item(it) }
            .map { it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue }
            .filterNotNull()
            .toSet()
        check(permissions.containsAll(requiredRuntimePermissions)) {
            "AndroidManifest.xml must declare every permission requested by AndroidRuntimePermissionPolicy"
        }
        val features = (0 until document.getElementsByTagName("uses-feature").length)
            .map { document.getElementsByTagName("uses-feature").item(it) }
            .associate {
                val name = it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue
                val required = it.attributes.getNamedItemNS(androidNamespace, "required")?.nodeValue
                name to required
            }
        check(features["android.hardware.usb.host"] == "false") {
            "AndroidManifest.xml must declare USB host support as optional"
        }
        check(features["android.hardware.usb.accessory"] == "true") {
            "AndroidManifest.xml must declare USB accessory support as required"
        }
        val actions = (0 until document.getElementsByTagName("action").length)
            .map { document.getElementsByTagName("action").item(it) }
            .mapNotNull { it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue }
            .toSet()
        check(actions.contains("android.hardware.usb.action.USB_ACCESSORY_ATTACHED")) {
            "AndroidManifest.xml must register USB_ACCESSORY_ATTACHED so a plugged DJI RC is delivered to the app"
        }
        val metaData = (0 until document.getElementsByTagName("meta-data").length)
            .map { document.getElementsByTagName("meta-data").item(it) }
            .mapNotNull { it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue }
            .toSet()
        check(metaData.contains("android.hardware.usb.action.USB_ACCESSORY_ATTACHED")) {
            "AndroidManifest.xml must bind USB_ACCESSORY_ATTACHED to accessory_filter"
        }
        val application = document.getElementsByTagName("application").item(0)
            ?: error("AndroidManifest.xml must contain an application element")
        check(
            application.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                "com.skycommand.relay.device.sdk.android.DjiSdkApplication",
        ) {
            "AndroidManifest.xml must use DjiSdkApplication to install the DJI runtime before SDK access"
        }
        check(application.attributes.getNamedItemNS(androidNamespace, "usesCleartextTraffic")?.nodeValue == "true") {
            "AndroidManifest.xml must allow cleartext traffic because the relay contract supports ws:// endpoints"
        }
        check(application.attributes.getNamedItemNS(androidNamespace, "extractNativeLibs")?.nodeValue == "true") {
            "AndroidManifest.xml must extract native libraries so DJI Helper.install can bind JNI"
        }
    }
}

tasks.named("check") { dependsOn("verifyAndroidManifestContract") }

tasks.configureEach {
    if (name == "packageDebug" || name == "packageRelease") {
        doFirst {
            check(!djiApiKey.isNullOrBlank()) {
                "DJI_API_KEY must be supplied with -PDJI_API_KEY=<registered-key> before packaging an APK"
            }
        }
    }
}

dependencies {
    implementation(project(":relay-gateway")); implementation(project(":relay-gateway:transport-adapter")); implementation(project(":relay-gateway:protocol-core"))
    implementation(project(":device-connection")); implementation(project(":device-connection:android-dji-sdk-adapter")); implementation(project(":device-connection:android-remote-controller-adapter")); implementation(project(":device-connection:android-aircraft-adapter")); implementation(project(":device-connection:android-pairing-command-adapter")); implementation(project(":device-connection:android-pairing-status-adapter"))
    implementation(project(":telemetry")); implementation(project(":telemetry:android-flight-telemetry-adapter"))
    implementation(project(":live-stream")); implementation(project(":live-stream:android-dji-stream-adapter"))
    implementation(project(":flight-control")); implementation(project(":flight-control:android-dji-flight-adapter"))
    implementation(project(":device-settings")); implementation(project(":device-settings:android-dji-settings-adapter"))
    implementation(project(":wayline-mission")); implementation(project(":wayline-mission:android-dji-wayline-adapter")); implementation(project(":wayline-mission:android-mission-staging-adapter"))
    implementation(project(":relay-settings")); implementation(project(":relay-settings:android-settings-adapter"))
    implementation(project(":app-runtime")); implementation(project(":app-runtime:permission-coordinator")); implementation(project(":app-runtime:foreground-service")); implementation(project(":app-runtime:app-bootstrap")); implementation(project(":app-runtime:android-permission-adapter")); implementation(project(":app-runtime:android-foreground-service-adapter"))
    implementation(project(":runtime-diagnostics:diagnostic-core")); implementation(project(":runtime-diagnostics:android-diagnostic-adapter")); implementation(project(":runtime-diagnostics:gateway-diagnostic-publisher"))
    implementation("androidx.activity:activity-ktx:1.9.3"); implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test")); testImplementation("junit:junit:4.13.2")
}
