plugins {
    java
}

group = "com.yeemo"
version = "1.2.1"

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
    // WorldEdit / FAWE 整合：FAWE-Core 內含 WorldEdit API，一份依賴就能同時編譯兩種整合（執行時都是選用）
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.3") { isTransitive = false }
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
        archiveFileName.set("YeePlot-${project.version}.jar")
    }
}
