plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.readit.eink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readit.eink"
        minSdk = 19
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 共享 debug 签名：固定 keystore 放进工程（app/debug.keystore），
    // 各开发机用同一把钥匙签名 → 同一台测试机上用不同机器打的包可互相覆盖升级，
    // 不会 INSTALL_FAILED_UPDATE_INCOMPATIBLE。debug keystore 非机密，随工程同步即可。
    // 凭据沿用 AGP 默认 debug 习惯（alias=androiddebugkey / store&key 密码=android）。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",

                // BouncyCastle 的后量子密码（PQC）查表资源，规范 §3.4 体积红线治理项。
                // bcprov-jdk15to18:1.72 由 pdfbox-android 传递引入；其 jar 内的 Java 资源会被
                // AGP 原样打进 APK 根目录，其中 picnic/lowmc.properties 与 sike/p{434,503,610,751}
                // .properties 是 Picnic / SIKE 算法的 NTT 与 Karatsuba 常量表，
                // 压缩后合计约 3.95MB —— 本应用仅通过 pdfbox 使用 BC 的经典对称
                // （AES/DES）与摘要（MD5/SHA）实现来做 PDF 解密，PQC 代码路径永不进入，
                // 这些资源属于纯冗余，必须剔除。PQC 相关的 *class* 仍留在 dex 中
                // （未开 R8），但缺少资源文件不会被访问到。
                "/org/bouncycastle/pqc/**",
                "org/bouncycastle/pqc/**"
            )
        }
    }

    // PdfiumAndroid 的 AAR 内含全部 ABI（~19MB）。E-Ink 设备以侧载为主，
    // 按规范 §3.4 走 ABI 分包：只保留 ARM 两档，单包稳定压在红线内。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // --- AndroidX base ---
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.webkit:webkit:1.8.0")
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")

    // --- Lifecycle ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // --- API 19 multidex ---
    implementation("androidx.multidex:multidex:2.0.1")

    // --- Network (API 19 LTS line) ---
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // --- Encoding detection (pure java) ---
    implementation("com.googlecode.juniversalchardet:juniversalchardet:1.0.3")

    // --- JSON ---
    implementation("com.google.code.gson:gson:2.10.1")

    // --- PDF 渲染 + 书签目录（规范 §3.3 锁定 1.9.0 最终版；上游已停止维护，见风险 R22）---
    // 该 AAR 的 POM 声明了 com.android.support:support-v4:26.1.0（旧 support 库），
    // 与 AndroidX 混用会产生重复类，必须排除。
    //
    // !! 注意（P5 真机冒烟修正）!!
    // 之前的注释断言「Pdfium 源码并未使用它」——**这是错的**。
    // com.shockwave.pdfium.PdfDocument 的构造函数里确实 new 了
    // android.support.v4.util.ArrayMap（9 个类中仅此 1 处引用，0 处引用 androidx）。
    // 所以「排除 support-v4」必须搭配 gradle.properties 里的 android.enableJetifier=true，
    // 由 Jetifier 把这条引用改写到 androidx.collection.ArrayMap；否则真机开 PDF 必崩：
    //   java.lang.NoClassDefFoundError: Failed resolution of:
    //   Landroid/support/v4/util/ArrayMap;  at PdfDocument.<init>(PdfDocument.java:109)
    implementation("com.github.barteksc:pdfium-android:1.9.0") {
        exclude(group = "com.android.support")
    }

    // Jetifier 会把 PdfDocument 的 ArrayMap 引用指向 androidx.collection.ArrayMap，
    // 这里显式声明（版本取本地已缓存、且本就是 androidx.core 传递版本的 1.1.0），
    // 保证该实现一定在运行时 classpath 上（体积约几十 KB）。
    implementation("androidx.collection:collection:1.1.0")

    // --- PDF text extraction (Phase 0 baseline / Phase 3 fallback) ---
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // --- Test ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
