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
    testImplementation("com.google.code.gson:gson:2.14.0")
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
