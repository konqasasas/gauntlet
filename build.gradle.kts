plugins { java }
group = "konqasasas"
version = "0.1.0"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.166-beta")
    compileOnly("com.google.code.gson:gson:2.14.0")
    // Emotes from the menu; BetterModel is an optional runtime plugin (softdepend).
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:3.5.1") { isTransitive=false }
    compileOnly("io.github.toxicity188:bettermodel-api:3.5.1") { isTransitive=false }
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation("net.kyori:adventure-text-minimessage:5.2.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:5.2.0")
    testImplementation("net.kyori:adventure-text-serializer-gson:5.2.0")
    testImplementation("org.xerial:sqlite-jdbc:3.51.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding="UTF-8"; options.release.set(25) }
tasks.processResources {
    inputs.property("version",project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.withType<Jar>().configureEach { isPreserveFileTimestamps=false; isReproducibleFileOrder=true }
dependencyLocking { lockAllConfigurations() }
tasks.test { useJUnitPlatform() }
