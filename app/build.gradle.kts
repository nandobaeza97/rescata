import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Clave de subida de Google Play (la guarda el propietario; nunca va al repositorio).
// Se lee de variables de entorno (CI, con los secretos de GitHub) o de keystore.properties
// (PC del propietario; está en .gitignore). Si no hay clave, la versión release se firma con la
// clave de depuración, como siempre (APK para instalar a mano y para las pruebas).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val uploadStorePath: String? = signingValue("RESCATA_UPLOAD_STORE_FILE", "storeFile")
val hasUploadKey = uploadStorePath != null && rootProject.file(uploadStorePath).isFile

// Cada subida a Google Play necesita un versionCode mayor que el anterior: en el CI es el número
// de ejecución; en el PC se puede fijar con RESCATA_VERSION_CODE. Sin nada, 1.
val autoVersionCode = (System.getenv("RESCATA_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER"))
    ?.toIntOrNull() ?: 1

android {
    namespace = "dev.rescata.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.rescata.app"
        minSdk = 26
        targetSdk = 36
        versionCode = autoVersionCode
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = rootProject.file(uploadStorePath!!)
                storePassword = signingValue("RESCATA_UPLOAD_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("RESCATA_UPLOAD_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("RESCATA_UPLOAD_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Con la clave de subida: AAB para Google Play. Sin ella: clave de depuración (APK de pruebas).
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Prueba de extremo a extremo en emulador
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
