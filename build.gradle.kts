
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
    dependsOn(tasks.named("verifyLocatorChests"))
    dependsOn(tasks.named("verifyCosmeticArmor"), tasks.named("verifyCosmeticArmorOptional"))
    dependsOn(tasks.named("verifyTerminalIo"))
    dependsOn(tasks.named("verifyProspectorChineseNames"))
    dependsOn(tasks.named("verifySharedStorageReadApi"))
    dependsOn(tasks.named("verifyDisassembler"))
    dependsOn(tasks.named("verifyDisassemblerUi"))
    dependsOn(tasks.named("verifyTerminalClicks"))
    dependsOn(tasks.named("verifyBookmarkWithdrawal"))
    dependsOn(tasks.named("verifyTerminalSearch"))
    dependsOn(tasks.named("verifyTerminalNeiSearch"))
    dependsOn(tasks.named("verifyStockControls"))
}

tasks.register<JavaExec>("verifyLocatorChests") {
    group = "verification"
    description = "Checks placed chest orientations, inventory searches and loaded-chunk-only locator scans"
    dependsOn(compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.locator.LocatorChestRegression")
    classpath = files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

val compileCosmeticChecks = tasks.register<JavaCompile>("compileCosmeticChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/cosmetic-check") { include("**/*.java") })
    classpath = sourceSets["main"].output + sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("cosmetic-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

for (optionalAbsent in listOf(false, true)) {
    tasks.register<JavaExec>(if (optionalAbsent) "verifyCosmeticArmorOptional" else "verifyCosmeticArmor") {
        group = "verification"
        description = "Checks optional CAR UUID compatibility, real client Mixin and connection isolation"
        dependsOn(compileCosmeticChecks, "compileProspectorChecks", "extractTerminalClickLwjgl")
        mainClass.set("com.futa_gtnh.common.ProspectorCheckLauncher")
        args("--tweakClass", "com.futa_gtnh.cosmeticcheck.CosmeticCheckTweaker", "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
        systemProperty("futa.cosmetic.absent", optionalAbsent.toString())
        classpath = files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
            files(compileCosmeticChecks.flatMap { it.destinationDirectory }) +
            files(layout.buildDirectory.dir("prospector-check/classes")) + files("tools/cosmetic-check") +
            (sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath).filter {
                !optionalAbsent || !it.name.startsWith("CosmeticArmorReworked-")
            }
    }
}

// LaunchWrapper + real Mixin transformation, without starting a world or a graphical client.
val compileProspectorChecks = tasks.register<JavaCompile>("compileProspectorChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/prospector-check") { include("**/*.java") })
    classpath = sourceSets["main"].output +
        sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("prospector-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

tasks.register<JavaExec>("verifyProspectorChineseNames") {
    group = "verification"
    description = "Checks transformed GT scanner Chinese names, dictionary overrides and packet round trips"
    dependsOn(compileProspectorChecks)
    mainClass.set("com.futa_gtnh.common.ProspectorCheckLauncher")
    args("--tweakClass", "com.futa_gtnh.prospectorcheck.ProspectorCheckTweaker", "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
    classpath = files(compileProspectorChecks.flatMap { it.destinationDirectory }) +
        files("tools/prospector-check") + sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

val compilePumpFluidChecks = tasks.register<JavaCompile>("compilePumpFluidChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/pump-fluid-check") { include("**/*.java") })
    classpath = sourceSets["main"].output + sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("pump-fluid-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

tasks.register<JavaExec>("verifyPumpFluidUpdates") {
    group = "verification"
    description = "Checks the real GT pump Mixin, vanilla lava decay and bounded local fluid repair"
    dependsOn(compilePumpFluidChecks, compileProspectorChecks)
    mainClass.set("com.futa_gtnh.common.ProspectorCheckLauncher")
    args("--tweakClass", "com.futa_gtnh.pumpcheck.PumpCheckTweaker", "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
    classpath = files(compilePumpFluidChecks.flatMap { it.destinationDirectory }) +
        files(compileProspectorChecks.flatMap { it.destinationDirectory }) + files("tools/pump-fluid-check") +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}
tasks.named("check") { dependsOn("verifyPumpFluidUpdates") }

val compileSupplyChecks = tasks.register<JavaCompile>("compileSupplyChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/supply-check") { include("**/*.java") })
    source(files("tools/terminal-io-check/appeng/core/Api.java"))
    classpath = sourceSets["main"].output + sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("supply-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

tasks.register<JavaExec>("verifySupplyTracking") {
    group = "verification"
    description = "Checks AE subnet attribution, shared IO accounting, bounded history, watches and protocol"
    dependsOn(compileSupplyChecks, compileProspectorChecks, "extractTerminalClickLwjgl")
    mainClass.set("com.futa_gtnh.common.ProspectorCheckLauncher")
    args("--tweakClass", "com.futa_gtnh.supplycheck.SupplyCheckTweaker", "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
    classpath = files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) + files(compileSupplyChecks.flatMap { it.destinationDirectory }) +
        files(compileProspectorChecks.flatMap { it.destinationDirectory }) + files("tools/supply-check") +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}
tasks.register<JavaExec>("benchmarkProductionQueries") {
    group = "verification"
    description = "Compares frozen v1.34.0 and optimized queries at 16384 pairs / 60 minutes; opt-in, not a TPS benchmark"
    dependsOn(compileSupplyChecks, compileProspectorChecks, "extractTerminalClickLwjgl")
    mainClass.set("com.futa_gtnh.common.ProspectorCheckLauncher")
    args("--tweakClass", "com.futa_gtnh.supplycheck.SupplyCheckTweaker", "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
    systemProperty("futa.supplyQueryBenchmark", "true")
    maxHeapSize = "2g"
    classpath = files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) + files(compileSupplyChecks.flatMap { it.destinationDirectory }) +
        files(compileProspectorChecks.flatMap { it.destinationDirectory }) + files("tools/supply-check") +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

tasks.named("check") { dependsOn("verifySupplyTracking") }

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

tasks.register<JavaExec>("verifySupplyGui") {
    group = "verification"
    description = "Checks supply tracker GUI layout, zero-stock history selection and modal editors"
    dependsOn(compileTerminalClickChecks, extractTerminalClickLwjgl, compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.client.SupplyGuiRegression")
    classpath = files(compileTerminalClickChecks.flatMap { it.destinationDirectory }) +
        files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}
tasks.named("check") { dependsOn("verifySupplyGui") }

tasks.register<JavaExec>("verifyBookmarkWithdrawal") {
    group = "verification"
    description = "Checks real NEI bookmark quantities, full warehouse access, batching and inventory conservation"
    dependsOn(compileTerminalClickChecks, extractTerminalClickLwjgl, compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.client.BookmarkWithdrawalRegression")
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

tasks.register<JavaExec>("verifyStockControls") {
    group = "verification"
    description = "Checks excess stock persistence, manual/automation limits and paged IO whitelist removal"
    dependsOn(compileTerminalClickChecks, extractTerminalClickLwjgl, compileReadApiChecks)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.client.StockControlsRegression")
    classpath = files(compileTerminalClickChecks.flatMap { it.destinationDirectory }) +
        files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

// Retain the modified LGPL library sources with every executable distribution.
tasks.named<Jar>("jar") {
    from("src/main/java") {
        include("com/futa_gtnh/disassembler/**", "com/futa_gtnh/mixins/MixinShimmerGT*Recipe.java")
        into("META-INF/licenses/gtnl/source")
    }
}

tasks.register<JavaExec>("verifyAeIntegration") {
    group = "verification"
    description = "Checks ME dock conservation, simulation, fluid NBT, detached handles and network text limits"
    dependsOn(compileReadApiChecks, compileTerminalClickChecks, extractTerminalClickLwjgl)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.medock.AeIntegrationRegression")
    classpath = files(compileTerminalClickChecks.flatMap { it.destinationDirectory }) +
        files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}
tasks.named("check") { dependsOn("verifyAeIntegration") }

// Real GL/integrated-server screenshots, deliberately excluded from normal build/CI and release jars.
if (providers.gradleProperty("uiSmoke").isPresent) {
    tasks.withType<JavaExec>().matching { it.name == "runClient17" }.configureEach {
        dependsOn(compileReadApiChecks)
        classpath += files(compileReadApiChecks.flatMap { it.destinationDirectory })
        systemProperty("futa.uiSmoke", "true")
        if (providers.gradleProperty("gtswnCompatJar").isPresent) systemProperty("futa.gtswnSmoke", "true")
    }
}

// Exercises the production crafting transfer with real shared storage, MC/NBT and AE contracts.
tasks.register<JavaExec>("verifyAeCraftingDelivery") {
    group = "verification"
    description = "Checks crafting stock conservation, final-output returns, refills and durable failure escrow"
    dependsOn(compileReadApiChecks, extractTerminalClickLwjgl)
    mainClass.set("com.futa_gtnh.shared.ReadApiCheckLauncher")
    args("com.futa_gtnh.ae2.CraftingDeliveryRegression")
    classpath = files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileReadApiChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}
tasks.named("check") { dependsOn("verifyAeCraftingDelivery") }

val compileTerminalIoChecks = tasks.register<JavaCompile>("compileTerminalIoChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/terminal-io-check") { include("**/*.java") })
    classpath = sourceSets["main"].output +
        sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("terminal-io-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

tasks.register<JavaExec>("verifyTerminalIo") {
    group = "verification"
    description = "Checks terminal IO, AE2 ore card parity, legacy filter migration and quantity conservation"
    dependsOn(compileTerminalIoChecks)
    mainClass.set("com.futa_gtnh.block.TerminalIoCheckLauncher")
    classpath = files(compileTerminalIoChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}

val compileStorageBusChecks = tasks.register<JavaCompile>("compileStorageBusChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/storage-bus-check") { include("**/*.java") })
    source("tools/terminal-io-check/appeng/core/Api.java")
    classpath = sourceSets["main"].output +
        sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("storage-bus-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

tasks.register<JavaExec>("verifyStorageBus") {
    group = "verification"
    description = "Checks storage bus aggregation, priority routing, filters, access modes, fluids and unregistering"
    dependsOn(compileStorageBusChecks)
    mainClass.set("com.futa_gtnh.storagebus.StorageBusCheckLauncher")
    classpath = files(compileStorageBusChecks.flatMap { it.destinationDirectory }) +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}
tasks.named("check") { dependsOn("verifyStorageBus") }

tasks.register<JavaExec>("verifyStorageBusWithoutDrawers") {
    group = "verification"
    description = "Checks storage bus loading and transfers without the optional Storage Drawers API"
    dependsOn(compileStorageBusChecks)
    mainClass.set("com.futa_gtnh.storagebus.StorageBusCheckLauncher")
    classpath = files(compileStorageBusChecks.flatMap { it.destinationDirectory }) +
        (sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath).filter {
            !it.name.startsWith("StorageDrawers-")
        }
}
tasks.named("check") { dependsOn("verifyStorageBusWithoutDrawers") }

// Botania is optional at runtime; only these checks need its concrete classes.
val botaniaAuraCheckDependencies = configurations.create("botaniaAuraCheckDependencies") {
    isTransitive = false
}
dependencies.add(botaniaAuraCheckDependencies.name, "com.github.GTNewHorizons:Botania:1.13.36-GTNH:dev")
dependencies.add(botaniaAuraCheckDependencies.name, "com.github.GTNewHorizons:CropsNH:2.0.133:dev")

val compileBotaniaAuraChecks = tasks.register<JavaCompile>("compileBotaniaAuraChecks") {
    dependsOn(tasks.named("classes"))
    source(fileTree("tools/botania-aura-check") { include("**/*.java") })
    classpath = sourceSets["main"].output + botaniaAuraCheckDependencies +
        sourceSets["main"].compileClasspath.filter { !it.name.startsWith("jabel-") }
    destinationDirectory.set(layout.buildDirectory.dir("botania-aura-check/classes"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

tasks.register<JavaExec>("verifyBotaniaAura") {
    group = "verification"
    description = "Checks Swift Step Botania ticks, pure daisy conversion, mana conservation and aura isolation"
    // Standalone Forge enum registration needs Java 8 without lwjgl3ify's game bootstrap.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    dependsOn(compileBotaniaAuraChecks, compileReadApiChecks, compileProspectorChecks, extractTerminalClickLwjgl)
    mainClass.set("com.futa_gtnh.common.ProspectorCheckLauncher")
    args("--tweakClass", "com.futa_gtnh.growthcheck.GrowthCheckTweaker", "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
    classpath = files(layout.buildDirectory.dir("terminal-click-check/lwjgl")) +
        files(compileBotaniaAuraChecks.flatMap { it.destinationDirectory }) +
        files(compileProspectorChecks.flatMap { it.destinationDirectory }) + files("tools/botania-aura-check") + botaniaAuraCheckDependencies +
        sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
}
tasks.named("check") { dependsOn("verifyBotaniaAura") }

// Keep the exact Eclipse 4.19 formatter while avoiding the retired update-site index.
configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
        clearSteps()
        toggleOffOn()
        importOrderFile(com.diffplug.blowdryer.Blowdryer.file("spotless.importorder"))
        removeUnusedImports()
        eclipse("4.19").withP2Mirrors(mapOf(
            "https://download.eclipse.org/eclipse/updates/4.19" to
                "https://download.eclipse.org/releases/2021-03/202103171000"
        )).configFile(com.diffplug.blowdryer.Blowdryer.file("spotless.eclipseformat.xml"))
    }
}

tasks.register<JavaExec>("verifyPlantAuraOptional") {
    group = "verification"
    description = "Checks server-side plant aura loading without Botania or CropsNH"
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    dependsOn(compileBotaniaAuraChecks, compileProspectorChecks)
    mainClass.set("com.futa_gtnh.common.ProspectorCheckLauncher")
    args("--tweakClass", "com.futa_gtnh.growthcheck.GrowthCheckTweaker", "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
    systemProperty("futa.growth.absent", "true")
    classpath = files(compileBotaniaAuraChecks.flatMap { it.destinationDirectory }) +
        files(compileProspectorChecks.flatMap { it.destinationDirectory }) + files("tools/botania-aura-check") +
        (sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath).filter {
            !it.name.startsWith("Botania-") && !it.name.startsWith("CropsNH-")
        }
}
tasks.named("check") { dependsOn("verifyPlantAuraOptional") }
