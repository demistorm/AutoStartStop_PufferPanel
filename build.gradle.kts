import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.taskTriggers

plugins {
    kotlin("jvm") version "2.0.20-Beta1"
    kotlin("kapt") version "2.0.20-Beta1"
    id("com.gradleup.shadow") version "9.0.0-beta16"
    id("eclipse")
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.8"
    id("xyz.jpenilla.run-velocity") version "2.3.1"
}

group = "win.demistorm"
version = "1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.spongepowered:configurate-yaml:4.2.0")
    implementation("org.spongepowered:configurate-core:4.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

tasks {
    runVelocity {
        velocityVersion("3.4.0-SNAPSHOT")
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("okhttp3", "win.demistorm.pufferPanelAutoStartStop.okhttp3")
        relocate("org.spongepowered.configurate", "win.demistorm.pufferPanelAutoStartStop.configurate")
        relocate("kotlinx.coroutines", "win.demistorm.pufferPanelAutoStartStop.coroutines")
        // Ensure proper dependency order
        dependsOn(generateBuildConstants)
    }

    compileKotlin {
        dependsOn(generateBuildConstants)
    }

    compileJava {
        dependsOn(generateBuildConstants)
    }
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

// Generate BuildConstants programmatically to avoid template issues
val generateBuildConstants = tasks.register("generateBuildConstants") {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildConstants")
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().asFile.resolve("win/demistorm/pufferPanelAutoStartStop/BuildConstants.java")
        file.parentFile.mkdirs()
        file.writeText("""
            package win.demistorm.pufferPanelAutoStartStop;
            
            public class BuildConstants {
                public static final String VERSION = "${project.version}";
            }
        """.trimIndent())
    }
}

sourceSets.main.configure {
    java.srcDir(generateBuildConstants.map { it.outputs })
}

project.idea.project.settings.taskTriggers.afterSync(generateBuildConstants)
project.eclipse.synchronizationTasks(generateBuildConstants)