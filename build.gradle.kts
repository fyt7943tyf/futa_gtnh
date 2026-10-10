
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// ---------------------------------------------------------------------------
//  版本号
//
//  GTNH 默认用 Git tag 作为版本号；本项目当前不在 Git 仓库里，所以关掉了 Git
//  版本推断（见 gradle.properties 里的 gtnh.modules.gitVersion = false）。
//  关掉之后必须自己提供版本号，有两个要求，缺一个构建就会报错：
//    1. project.ext.modVersion —— RFG/GTNHGradle 内部任务要读它
//    2. project.version       —— 决定产物 jar 的文件名
//  这里统一从 version.txt 读取，改版本号只需要改 version.txt 一个地方。
//
//  以后如果把项目放进 Git 仓库并打了 tag（例如 git tag 1.0.0），
//  可以删掉 gradle.properties 里的 gtnh.modules.gitVersion = false 恢复自动版本号。
// ---------------------------------------------------------------------------
val modVersionString = file("version.txt").readText().trim()

version = modVersionString
ext.set("modVersion", modVersionString)

// Keep the standalone runner outside the JUnit test source set.
val compileReadApiChecks = tasks.register<JavaCompile>("compileSharedStorageReadApiChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/read-api-check") { include("**/*.java") })
    classpath = sourceSets["main"].output +
        sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("read-api-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

// Focused warehouse/API checks use the real MC/Forge classes without launching a game or adding JUnit.
tasks.register<JavaExec>("verifySharedStorageReadApi") {
    group = "verification"
    description = "Checks read API isolation, exact amounts, lifecycle and server-thread access"
    dependsOn(compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    classpath = files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

tasks.named("check") {
    dependsOn(tasks.named("verifySharedStorageReadApi"))
    dependsOn(tasks.named("verifyDisassembler"))
    dependsOn(tasks.named("verifyDisassemblerUi"))
    dependsOn(tasks.named("verifyTerminalClicks"))
    dependsOn(tasks.named("verifyTerminalSearch"))
    dependsOn(tasks.named("verifyTerminalNeiSearch"))
}

// Test-only keyboard/clock fixtures exercise real GUI press/release code without a display or native input.
val compileTerminalClickChecks = tasks.register<JavaCompile>("compileTerminalClickChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/terminal-click-check") { include("**/*.java") })
    classpath = sourceSets["main"].output +
        sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("terminal-click-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

// LWJGL 2 seals its packages; unpack its classes so test-only input fixtures can share those packages.
val extractTerminalClickLwjgl = tasks.register<Sync>("extractTerminalClickLwjgl") {
    from(sourceSets["main"].compileClasspath.filter {
        it.name.startsWith("lwjgl-2") || it.name.startsWith("lwjgl_util-2")
    }.map { zipTree(it) })
    exclude("META-INF/**")
    into(layout.buildDirectory.dir("terminal-click-check/lwjgl"))
}

tasks.register<JavaExec>("verifyTerminalClicks") {
    group = "verification"
    description = "Checks terminal Shift click/release dispatch, cursor collection and backpack double-click deposits"
    dependsOn(compileTerminalClickChecks, extractTerminalClickLwjgl, compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.client.TerminalClickRegression")
    classpath = files(compileTerminalClickChecks.flatMap { it.destinationDirectory }) +
        files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

tasks.register<JavaExec>("verifyDisassemblerUi") {
    group = "verification"
    description = "Checks machine page request permissions, client/server page selection and real NEI foreground texture"
    dependsOn(compileTerminalClickChecks, extractTerminalClickLwjgl, compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.disassembler.DisassemblerUiRegression")
    classpath = files(compileTerminalClickChecks.flatMap { it.destinationDirectory }) +
        files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

tasks.register<JavaExec>("verifyTerminalSearch") {
    group = "verification"
    description = "Checks focused search input before NEI shortcuts, consumed clicks and real NEI item drag completion"
    dependsOn(compileTerminalClickChecks, extractTerminalClickLwjgl, compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.client.TerminalSearchRegression")
    classpath = files(compileTerminalClickChecks.flatMap { it.destinationDirectory }) +
        files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

tasks.register<JavaExec>("verifyTerminalNeiSearch") {
    group = "verification"
    description = "Checks shared storage against actual NEI search providers, modes, English names and GT fluid displays"
    dependsOn(compileTerminalClickChecks, extractTerminalClickLwjgl, compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.client.TerminalNeiSearchRegression")
    classpath = files(compileTerminalClickChecks.flatMap { it.destinationDirectory }) +
        files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

tasks.register<JavaExec>("verifyDisassembler") {
    group = "verification"
    description = "Checks Shimmer route parity, reverse batches, real output capacity and persisted atomic processing"
    dependsOn(compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.disassembler.DisassemblerRegression")
    classpath = files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

// Retain the modified LGPL library sources with every executable distribution.
tasks.named<Jar>("jar") {
    from("src/main/java") {
        include("com/futa_gtnh/disassembler/**", "com/futa_gtnh/mixins/MixinShimmerGT*Recipe.java")
        into("META-INF/licenses/gtnl/source")
    }
}
