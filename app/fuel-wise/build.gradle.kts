import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// API 키: local.properties(`fuelwise.apiKey`) -> 환경변수 FUELWISE_API_KEY -> 빈 문자열(= Fake 사용). 코드/Git 에 넣지 않는다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val apiKey: String = (localProps.getProperty("fuelwise.apiKey") ?: System.getenv("FUELWISE_API_KEY") ?: "").trim()

// release 서명: app/.signing/signing.properties(커밋 금지, .gitignore)가 있으면 그 키로 서명한다(팀 공용 키, SafeRadar·WakeMate 와 같은 인증서).
// 항목: storeFile(.signing/ 안의 파일 이름), storePassword, keyAlias, keyPassword. 없으면 서명되지 않은 release APK 가 나온다.
val signingProps = Properties().apply {
    rootProject.file(".signing/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val hasReleaseSigning = signingProps.getProperty("storeFile") != null
logger.lifecycle("[signing] fuel-wise release: " + (if (hasReleaseSigning) "app/.signing" else "unsigned"))

// 화면 렌더링 테스트는 -Pfuelwise.render=true 일 때만 돈다(평소 테스트에 Robolectric 준비 시간을 더하지 않는다).
// ./gradlew :fuel-wise:testDebugUnitTest -Pfuelwise.render=true --tests "*ScreenRenderTest"  ->  fuel-wise/build/renders/*.png
val renderScreens = (findProperty("fuelwise.render") as String?) == "true"

android {
    namespace = "com.hmgcarapp.fuelwise"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hmgcarapp.fuelwise"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = rootProject.file(".signing/" + signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    // release 빌드에는 API 키를 절대 주입하지 않는다(APK 에서 추출 가능하므로). debug 에서만 로컬 키를 주입.
    // release 에서 실데이터를 쓰려면 별도 서버 프록시/보안 저장소 방식이 필요하다(빈 문자열 = Fake 사용).
    buildTypes {
        debug {
            buildConfigField("String", "API_KEY", "\"${apiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "API_KEY", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // 화면 렌더링 테스트(Robolectric)가 앱 리소스(글꼴 등)를 읽을 수 있게 한다. 렌더링할 때만 켠다(평소 테스트 빌드에서 리소스 병합 생략)
        unitTests.isIncludeAndroidResources = renderScreens
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.pleos.navihelper)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    // 화면 렌더링(PNG): 에뮬레이터 없이 JVM 에서 Compose 화면을 그린다
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
}

tasks.withType<Test>().configureEach {
    systemProperty("fuelwise.render", renderScreens.toString())
    if (renderScreens) systemProperty("roborazzi.test.record", "true") else filter.excludeTestsMatching("*ScreenRenderTest")
}
