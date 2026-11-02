import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keyProps = Properties().apply {
    rootProject.file("key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Per-ABI runtime bundles produced by runtime/build-runtime.sh.
val runtimeOut = rootProject.file("../../runtime/out")

// versionCode is the build minute, same encoding as tooling/build_number.py:
// (year - 2026) * 10^8 + MMDDhhmm at UTC+8. tooling/build-apk.sh passes it as
// --build-number; this covers builds that skip the script (flutter run, the
// IDE), where pubspec's `+1` placeholder would be a downgrade and make
// `flutter run` uninstall the installed app, data included.
fun buildMinuteVersionCode(): Int {
    val t = OffsetDateTime.now(ZoneOffset.ofHours(8))
    return (t.year - 2026) * 100_000_000 + t.monthValue * 1_000_000 + t.dayOfMonth * 10_000 + t.hour * 100 + t.minute
}

android {
    namespace = "io.github.ccch1mneyyy.dsh_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "io.github.ccch1mneyyy.dsh_app"
        // java.nio.file (symlinks while unpacking the rootfs) needs API 26.
        minSdk = 26
        // Target 28 like Termux, so the API 29+ exec restriction on app-private
        // files does not apply to binaries inside the rootfs.
        targetSdk = 28
        versionCode = flutter.versionCode.takeIf { it > 1 } ?: buildMinuteVersionCode()
        versionName = flutter.versionName
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            // 必须 clear()：abiFilters 默认由 Flutter 注入 arm64-v8a / armeabi-v7a /
            // x86_64，`+=` 只是追加，会在包里留下别的 ABI 目录。
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
        }
        create("x64") {
            dimension = "abi"
            ndk {
                abiFilters.clear()
                abiFilters.add("x86_64")
            }
        }
    }

    sourceSets {
        getByName("arm64") {
            assets.srcDir(File(runtimeOut, "arm64-v8a/assets"))
            jniLibs.srcDir(File(runtimeOut, "arm64-v8a/jniLibs"))
        }
        getByName("x64") {
            assets.srcDir(File(runtimeOut, "x86_64/assets"))
            jniLibs.srcDir(File(runtimeOut, "x86_64/jniLibs"))
        }
    }

    packaging {
        // proot and friends ship as lib*.so; they must be extracted to
        // nativeLibraryDir so they can be executed.
        jniLibs {
            useLegacyPackaging = true
            // Keep the Termux binaries byte-identical to runtime/out (their hashes are in the manifest).
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so", "**/libtalloc.so", "**/libandroid-shmem.so")
        }
    }

    lint {
        // targetSdk 28 is deliberate (see docs/architecture.md); not a Play build.
        disable += "ExpiredTargetSdkVersion"
    }

    androidResources {
        noCompress += listOf("gz", "xz", "zst", "tar")
    }

    signingConfigs {
        if (!keyProps.isEmpty) {
            create("release") {
                storeFile = keyProps.getProperty("storeFile")?.let { file(it) }
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
                // All three schemes: AGP drops v1 for minSdk 24+, but some installers and
                // security scanners on Chinese ROMs still read the JAR signature.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Use the release key when configured; local builds without key.properties fall back to debug.
            signingConfig = signingConfigs.getByName(if (keyProps.isEmpty) "debug" else "release")
            // Ours on top of Flutter's: phone control's privileged process and
            // its TLS libraries are reached by class name (see the file).
            proguardFiles("proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // .tar.xz support for the rootfs bundle.
    implementation("org.tukaani:xz:1.10")

    // 不打扰模式 (phone/background): a shell-uid privileged display via Shizuku or self-ADB.
    implementation("dev.rikka.shizuku:api:13.1.5")       // MIT
    implementation("dev.rikka.shizuku:provider:13.1.5")  // MIT
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") // used under its Apache-2.0 option; pulls in spake2-android (LGPL-3.0)
    implementation("org.conscrypt:conscrypt-android:2.5.3")         // Apache-2.0: libadb TLS/pairing
    // MIT: self-signed adb client cert. The same jdk15to18 family libadb-android
    // pulls in (bcprov-jdk15to18:1.81); adding jdk18on next to it duplicates every class.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("androidx.webkit:webkit:1.15.0") // Apache-2.0: web islands' force dark (webview_flutter's own version)
}

// ─── 只保留本变体需要的 ABI 目录 ───────────────────────────────────────────────
//
// abiFilters 只影响我们自己 NDK 编出来的东西。AAR 里预编译的 .so
// （libconscrypt_jni / libdartjni / libspake2 / libdatastore_shared_counter）
// 走的是另一条打包路径，会连别的 ABI 目录一起带进包。
//
// 后果很具体：x64 包里出现 lib/arm64-v8a，但那里只有上面那 4 个库，没有
// libflutter.so 和 libproot.so。多 ABI 设备或容器一旦把 arm64-v8a 选成主 ABI，
// 启动就 UnsatisfiedLinkError 闪退。
//
// 这里按变体在打包前剔除不属于它的 ABI 目录。注意 packaging.jniLibs.excludes
// 在 flavor 层不生效（会连自己的 ABI 一起剔掉，包出一个没有任何原生库的 APK），
// 所以必须走 variant API。
androidComponents {
    onVariants { variant ->
        val keep = if (variant.name.contains("arm64", ignoreCase = true)) "arm64-v8a" else "x86_64"
        val drop = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").filter { it != keep }
        variant.packaging.jniLibs.excludes.addAll(drop.map { "lib/$it/**" })
    }
}