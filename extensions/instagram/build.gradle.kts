android {
    namespace = "app.morphe.extension.instagram"

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:instagram:stub"))
    compileOnly(libs.morphe.extensions.library)
    compileOnly(libs.annotation)
    compileOnly(libs.appcompat)
}

// Android-independent state/queue tests; no emulator or third-party test runtime required.
val compileBatchDownloadTests by tasks.registering(JavaCompile::class) {
    source("tests/batchDownload/BatchDownloadTest.java")
    source("src/main/java/app/morphe/extension/instagram/patches/batchDownload/SelectionManager.java")
    source("src/main/java/app/morphe/extension/instagram/patches/batchDownload/BatchDownloadQueue.java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("tests/batchDownload"))
    options.release.set(17)
}

tasks.register<JavaExec>("testBatchDownload") {
    dependsOn(compileBatchDownloadTests)
    classpath = files(compileBatchDownloadTests.flatMap { it.destinationDirectory })
    mainClass.set("BatchDownloadTest")
}
