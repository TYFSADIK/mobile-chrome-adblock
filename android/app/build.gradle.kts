import java.net.URI

plugins {
    id("com.android.application")
}

android {
    namespace = "com.tyfsadik.adblock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tyfsadik.adblock"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release builds are signed with the keystore named in these environment
    // variables (set locally or as GitHub Actions secrets). Without them the
    // release build falls back to the debug key so it still installs.
    val keystore = System.getenv("ADBLOCK_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ADBLOCK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ADBLOCK_KEY_ALIAS")
                keyPassword = System.getenv("ADBLOCK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Bundle the blocklists into the APK so blocking works right after install,
// even offline. The app can download fresh copies later.
val blocklists = mapOf(
    "stevenblack.txt" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
    "adguard.txt" to "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    "hagezi-pro-plus.txt" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.plus-onlydomains.txt",
    "hagezi-doh.txt" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/doh-onlydomains.txt",
)
val assetsDir = layout.projectDirectory.dir("src/main/assets")
val downloadBlocklists = tasks.register("downloadBlocklists") {
    outputs.dir(assetsDir)
    doLast {
        blocklists.forEach { (name, url) ->
            val f = assetsDir.file(name).asFile
            if (!f.exists()) {
                f.parentFile.mkdirs()
                URI(url).toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadBlocklists) }
