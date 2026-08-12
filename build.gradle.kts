plugins {
    id("net.neoforged.moddev") version "2.0.141"
    id("com.gradleup.shadow") version "9.0.0-beta4"
    java
}

version = project.property("mod_version") as String
group = project.property("mod_group") as String

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = project.property("neoforge_version") as String

    runs {
        create("server") {
            server()
        }
    }

    mods {
        create("crossvoicechat") {
            sourceSet(sourceSets.main.get())
        }
    }
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    // Full Simple Voice Chat mod jar (the addon uses a few internal SVC classes
    // beyond the published API, so the api-only artifact is not enough)
    maven("https://api.modrinth.com/maven")
}

val shade: Configuration by configurations.creating {
    isTransitive = true
    exclude(group = "org.slf4j")
}

dependencies {
    // Simple Voice Chat (compile-only; provided at runtime by the server's mods folder)
    compileOnly("maven.modrinth:simple-voice-chat:${project.property("voicechat_version")}")

    // Jedis for Redis (shaded into the mod jar; gson is provided by Minecraft)
    shade("redis.clients:jedis:5.2.0") {
        exclude(group = "com.google.code.gson")
    }
    implementation("redis.clients:jedis:5.2.0") {
        exclude(group = "com.google.code.gson")
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(shade)
    relocate("redis.clients", "com.uberswe.crossvoicechat.shaded.jedis")
    relocate("org.apache.commons.pool2", "com.uberswe.crossvoicechat.shaded.pool2")
    relocate("org.json", "com.uberswe.crossvoicechat.shaded.json")
    mergeServiceFiles()
    exclude("module-info.class")
    exclude("META-INF/versions/**")
    exclude("org/slf4j/**")
}

tasks.named("build") {
    dependsOn("shadowJar")
}
