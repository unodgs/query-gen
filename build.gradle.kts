plugins {
    kotlin("jvm") version "1.4.10"
    antlr
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    antlr("org.antlr:antlr4:4.8")
    implementation("org.jooq:jooq:3.14.1")
}

tasks.generateGrammarSource {
    maxHeapSize = "64m"
    arguments = arguments + listOf("-package", "com.adverity.antlr", "-visitor", "-long-messages")
    outputDirectory = File("gen-src/main/com/adverity/antlr")
}

tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

sourceSets {
    main {
        java.srcDirs(listOf("gen-src/main", "src/main/java"))
    }
}
