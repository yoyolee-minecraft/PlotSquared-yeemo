plugins {
    java
}

group = "com.intellectualsites.plotsquared"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // 只依賴 Paper API；SQLite 與 MySQL 驅動由伺服器本身提供，不需要打包
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    jar {
        archiveFileName.set("PlotSquaredLite-${project.version}.jar")
    }
}
