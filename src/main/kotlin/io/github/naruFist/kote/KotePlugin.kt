package io.github.naruFist.kote

import io.github.naruFist.kape2.Kape
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

class KotePlugin : JavaPlugin() {

    private val scriptsDir = File(dataFolder, "scripts")
    private val libsDir = File(dataFolder, "libs")
    private val defaultImportsFile = File(dataFolder, "default-import.yml")

    private val host = BasicJvmScriptingHost()

    override fun onEnable() {
        Kape.plugin = this

        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        if (!libsDir.exists()) libsDir.mkdirs()
        if (!defaultImportsFile.exists()) defaultImportsFile.writeText(
            """
            # 기본 import 예제
            - "org.bukkit.*"
            - "io.github.naruFist.kape2.*"
            - "io.github.naruFist.kote.Kote"
            """.trimIndent()
        )

        logger.info("kote 스크립트를 로드 중입니다...")
        loadAllScripts()
        logger.info("kote 활성화 완료 ✅")

        server.getPluginCommand("kote")?.apply {
            val command = KoteCommand(this@KotePlugin)
            setExecutor(command)
            tabCompleter = command
        }
    }

    override fun onDisable() {
        logger.info("kote 스크립트 리스너 및 태스크 정리 중...")
        unloadAllScripts()

        Kape.disable()

        logger.info("kote 비활성화 완료 ✅")
    }

    internal fun reload() {
        unloadAllScripts()
        loadAllScripts()
    }


    private fun unloadAllScripts() {
        HandlerList.unregisterAll(this)
        server.scheduler.cancelTasks(this)
    }


    private fun loadDefaultImports(): List<String> {
        return try {
            val yaml = Yaml()
            val data = yaml.load<List<String>>(defaultImportsFile.inputStream())
            data ?: emptyList()
        } catch (e: Exception) {
            logger.warning("⚠️ defaultImports.yml 읽기 실패: ${e.message}")
            emptyList()
        }
    }

    /**
     * @file:DependsOn("com.github.user:repo:version") 구문 파싱 후 JitPack에서 자동 다운로드
     */
    private fun loadDependencies(scriptFile: File): List<URL> {
        val urls = mutableListOf<URL>()
        val text = scriptFile.readText()
        val dependsOnRegex = Regex("""@file:DependsOn\("([^"]+)"\)""")

        dependsOnRegex.findAll(text).forEach { match ->
            val (dep) = match.destructured
            val parts = dep.split(":")
            if (parts.size == 3) {
                val (group, artifact, version) = parts
                val jarUrl = "https://jitpack.io/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar"
                val file = File(libsDir, "$artifact-$version.jar")

                if (!file.exists()) {
                    try {
                        logger.info("📦 JitPack에서 의존성 다운로드 중: $dep")
                        file.outputStream().use { out ->
                            URI(jarUrl).toURL().openStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                        logger.info("✅ 다운로드 완료: ${file.name}")
                    } catch (e: Exception) {
                        logger.warning("⚠️ $dep 다운로드 실패: ${e.message}")
                    }
                }

                if (file.exists()) urls += file.toURI().toURL()
            }
        }

        return urls
    }


    private fun getCoreClasspathUrls(): Set<URL> {
        val urls = mutableSetOf<URL>()
        try {
            // 1) 이 플러그인 JAR (Kape2, KoteProvide 등 포함)
            urls += KotePlugin::class.java.protectionDomain.codeSource.location

            // 2) Paper API JAR (Bukkit 클래스 기준)
            urls += Bukkit::class.java.protectionDomain.codeSource.location

            // 3) Adventure API JARs (Namespaced, ForwardingAudience 문제 해결)
            urls += net.kyori.adventure.audience.Audience::class.java.protectionDomain.codeSource.location
            urls += net.kyori.adventure.key.Key::class.java.protectionDomain.codeSource.location

            // 4) Kotlin Stdlib JAR (실행 안정성 확보)

            // 5) ⭐️⭐️⭐️ 현재 스레드/시스템 클래스로더의 모든 URL 추가 (핵심 수정) ⭐️⭐️⭐️
            // Kotlin 스크립팅 JAR 파일들이 여기에 포함될 가능성이 높습니다.
            val currentCl = Thread.currentThread().contextClassLoader
            if (currentCl is URLClassLoader) {
                urls.addAll(currentCl.urLs)
            }

            // 6) ⭐️⭐️⭐️ 플러그인 부모(서버) 클래스로더의 URL 추가 ⭐️⭐️⭐️
            val parentCl = server::class.java.classLoader
            if (parentCl is URLClassLoader) {
                urls.addAll(parentCl.urLs)
            }

        } catch (e: Exception) {
            logger.severe("❌ 코어 클래스패스 URL 수집 중 치명적인 오류 발생: ${e.message}")
        }
        return urls
    }


    private fun loadAllScripts() {
        val ktsFiles = scriptsDir.listFiles { f -> f.extension == "kts" } ?: return
        // ⚠️ defaultImports는 이전 답변에서처럼 핵심 클래스 목록으로 명시되어야 합니다.
        val defaultImports = loadDefaultImports()

        val loaded = mutableSetOf<String>()
        val coreUrls = getCoreClasspathUrls() // 필수 JAR URL 목록

        fun evalFile(file: File) {
            if (!file.exists() || file.name in loaded) return
            loaded.add(file.name)

            // 1. 스크립트별 의존성 다운로드 (libsDir에 저장)
            loadDependencies(file)

            // 2. /libs 폴더의 모든 JAR URL 수집
            val libUrls = libsDir.listFiles { f -> f.extension == "jar" }
                ?.map { it.toURI().toURL() }
                ?.toSet() ?: emptySet()

            // 3. 전체 URL 목록 결합
            val allUrls = (coreUrls + libUrls).toTypedArray()

            // 4. 컴파일러가 사용할 File 목록 (jrt:/... 같은 URI는 제외)
            val allFiles = allUrls.mapNotNull {
                try {
                    // URL을 File로 변환, 실패하면 null
                    if (it.protocol == "file") File(it.toURI()) else null
                } catch (e: Exception) {
                    null
                }
            }

            // 5. 런타임에 사용할 ClassLoader (서버 CL을 부모로)
            val scriptClassLoader = URLClassLoader(allUrls, KotePlugin::class.java.classLoader)

            // --- 컴파일 설정 ---
            val compilationConfig = ScriptCompilationConfiguration {
                jvm {
                    jvmTarget("21")
                    // ⭐️ 핵심 변경: 명시적 클래스패스 사용 (가장 안정적)
                    updateClasspath(allFiles)
                }
                defaultImports(*defaultImports.toTypedArray())

                ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
            }

            // --- 평가 설정 ---
            val evaluationConfig = ScriptEvaluationConfiguration {
                // ⭐️ 런타임에도 방금 만든 클래스로더를 사용
                jvm { baseClassLoader(scriptClassLoader) }
            }

            // 실행
            try {
                logger.info("▶ 실행 중: ${file.name}")
                // 간단한 println()이 안 되는 경우, 여기에 도달하지 못했거나 eval 내부 오류입니다.
                val result = host.eval(file.toScriptSource(), compilationConfig, evaluationConfig)

                when (result) {
                    is ResultWithDiagnostics.Success -> {
                        logger.info("✅ ${file.name} 실행 성공")
                        // 스크립트 실행이 성공하면, println("??")의 결과는 콘솔에 나타나야 합니다.
                    }
                    is ResultWithDiagnostics.Failure -> {
                        val errorMsg = result.reports.joinToString("\n") { it.message }
                        logger.warning("❌ ${file.name} 실행 실패:\n$errorMsg")
                    }
                }
            } catch (e: Exception) {
                // 이 블록은 eval 호출 자체가 실패한 경우입니다. (매우 심각한 초기화 오류)
                logger.severe("❌ ${file.name} 실행 중 치명적인 오류 발생: ${e.message}")
                e.printStackTrace()
            }
        }

        ktsFiles.forEach(::evalFile)
    }
}
