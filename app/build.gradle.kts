plugins {
    id("com.android.application")
}

android {
    namespace = "df.root"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "df.root"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // The exploit is position dependent and expects a plain
                // build: no exceptions, no RTTI, and optimisation on because
                // the splicing loop is tight.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=none",
                )
                cFlags += listOf("-O2", "-fno-exceptions", "-fno-rtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The native binaries are loaded straight from the APK's lib
            // directory; compressing them would make them unexecutable.
            packaging {
                jniLibs.useLegacyPackaging = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // AGP renames lib*.so on install if this is left on, and the
            // exploit locates its payloads by their exact names.
            useLegacyPackaging = false
        }
    }
}

dependencies {
}