plugins {
    id("java-library")
}

// Offline compile-only build: javac against prebuilt mojmap MC+NeoForge jar + SBW production jar.
// Same toolchain as sbw_vls addon.

version = project.property("mod_version") as String
group = "com.zzy.sbwradar"

base { archivesName.set(project.property("mod_id") as String) }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("libs/minecraft-neoforge-compiled-21.1.249.jar"))
    compileOnly(files("libs/superbwarfare.jar"))
    compileOnly(files("libs/geckolib-neoforge-1.21.1-4.9.3.jar"))
    compileOnly(files("libs/SimpleBedrockModel-2.5.1.1-neoforge-mc1.21.1.jar"))
    compileOnly(files("libs/loader-4.0.42.jar"))
    compileOnly(files("libs/bus-8.0.5.jar"))
    compileOnly(files("libs/modlauncher-11.0.5.jar"))
    compileOnly(files("libs/annotations-24.1.0.jar"))
    compileOnly(files("libs/mergetool-api.jar"))
    compileOnly(files("libs/slf4j-api-2.0.9.jar"))
    compileOnly(files("libs/kotlin-stdlib-2.3.20.jar"))
    compileOnly(files("libs/joml-1.10.5.jar"))
    compileOnly(files("libs/datafixerupper-8.0.16.jar"))
    compileOnly(files("libs/gson-2.10.1.jar"))
    compileOnly(files("libs/lwjgl-glfw-3.3.3.jar"))
    compileOnly(files("libs/netty-buffer-4.1.97.Final.jar"))
    compileOnly(files("libs/netty-common-4.1.97.Final.jar"))
    compileOnly(files("libs/brigadier-1.3.10.jar"))
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
