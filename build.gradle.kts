
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
