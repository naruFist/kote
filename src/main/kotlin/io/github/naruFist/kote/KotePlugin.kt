package io.github.naruFist.kote

import io.github.naruFist.kape2.Kape
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.defaultImports
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
    private val classpathFile = File(dataFolder, "classpath.yml")

    private val host = BasicJvmScriptingHost()

    override fun onEnable() {
        Kape.plugin = this

        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        if (!libsDir.exists()) libsDir.mkdirs()
        if (!defaultImportsFile.exists()) defaultImportsFile.writeText(
            """
            # 기본 import 예제
            - "org.bukkit.Bukkit"
            - "io.github.naruFist.kape2.Kape"
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

    private fun loadClasspath(): Pair<List<File>, List<File>> {
        if (!classpathFile.exists()) {
            classpathFile.writeText(
                """
                core:
                  - "libs/paper-api.jar"
                  - "libs/adventure-api-4.24.0.jar"
                  - "libs/kape2-0.0.5.jar"
                libs: []
                """.trimIndent()
            )
        }

        val yaml = Yaml()
        val map = yaml.load<Map<String, List<String>>>(classpathFile.inputStream()) ?: emptyMap()

        val coreFiles = map["core"]?.map { File(dataFolder, it).canonicalFile }?.filter { it.exists() } ?: emptyList()
        val libFiles = map["libs"]?.map { File(dataFolder, it).canonicalFile }?.filter { it.exists() } ?: emptyList()

        logger.info(coreFiles.map { it.toURI().toURL().toString() }.toString())

        return coreFiles to libFiles
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


    private fun loadAllScripts() {
        val ktsFiles = scriptsDir.listFiles { f -> f.extension == "kts" } ?: return
        // ⚠️ defaultImports는 이전 답변에서처럼 핵심 클래스 목록으로 명시되어야 합니다.
        val defaultImport = loadDefaultImports()

        val loaded = mutableSetOf<String>()
        val (coreFiles, libFiles) = loadClasspath()

        val kotlinStdlibUrl = Unit::class.java.protectionDomain.codeSource.location

        val allUrls = (coreFiles + libFiles).map { it.toURI().toURL() }.toTypedArray() + kotlinStdlibUrl

        // 핵심 변화: URL을 File로 변환 실패해도 URLClassLoader가 처리할 수 있게 구성
        val allFiles = allUrls.mapNotNull { url ->
            try {
                if (url.protocol == "file") File(url.toURI()) else null
            } catch (_: Exception) { null }
        }


        val scriptClassLoader = URLClassLoader(allUrls, javaClass.classLoader)

        // 모든 URL을 포함한 classloader 생성 (jrt 포함 가능)

        val compilationConfig = ScriptCompilationConfiguration {
            jvm {
                jvmTarget("21")
                // 핵심 변화: updateClasspath는 File만 넣지만
                // scriptClassLoader가 URL 기반 classpath를 보완해줌
                updateClasspath(allFiles)
            }

            defaultImports(*defaultImport.toTypedArray())
        }

        // --- 평가 설정 ---
        val evaluationConfig = ScriptEvaluationConfiguration {
            // ⭐️ 런타임에도 방금 만든 클래스로더를 사용
            jvm { baseClassLoader(scriptClassLoader) }
        }


        fun evalFile(file: File) {
            if (!file.exists() || file.name in loaded) return
            loaded.add(file.name)

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
