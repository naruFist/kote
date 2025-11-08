package io.github.naruFist.kote

import io.github.naruFist.kape2.Kape
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
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
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
                        logger.warning("⚠️  $dep 다운로드 실패: ${e.message}")
                    }
                }

                if (file.exists()) urls += file.toURI().toURL()
            }
        }

        return urls
    }

    fun loadAllScripts() {
        val ktsFiles = scriptsDir.listFiles { f -> f.extension == "kts" } ?: return
        val defaultImports = loadDefaultImports()

        // 순환 참조 방지용 캐시
        val loaded = mutableSetOf<String>()

        // helper: 현재 플러그인 + kotlin stdlib + libs 폴더의 JAR들을 URL로 수집
        fun buildClassLoaderForScript(): URLClassLoader {
            val urls = mutableListOf<URL>()

            // 1) 플러그인 JAR (this plugin)
            runCatching {
                val pluginJar = javaClass.protectionDomain.codeSource.location.toURI().toURL()
                urls += pluginJar
            }

            // 2) kotlin stdlib 위치 (kotlin.Unit 클래스로 찾음)
            runCatching {
                val kotlinStdlibUrl = Unit::class.java.protectionDomain.codeSource.location.toURI().toURL()
                urls += kotlinStdlibUrl
            }

            // 3) script-runtime / scripting jars (있다면 부모 classloader에서 가져오기)
            val ctx = Thread.currentThread().contextClassLoader
            if (ctx is URLClassLoader) {
                urls += ctx.urLs // 추가로, 부모 CL에 있는 라이브러리들도 포함 (중복은 무시됨)
            }

            // 4) 또한 plugins/<this>/libs 에 다운로드된 jars 추가
            if (libsDir.exists()) {
                libsDir.listFiles { f -> f.extension == "jar" }?.forEach { f ->
                    urls += f.toURI().toURL()
                }
            }

            // URLClassLoader 생성 (부모는 plugin classloader)
            return URLClassLoader(urls.toTypedArray(), javaClass.classLoader)
        }

        fun evalFile(file: File) {
            if (!file.exists() || file.name in loaded) return
            loaded.add(file.name)

            // 먼저 imports 처리 (unchanged

            // JitPack 의존성 다운로드 후 그 파일들 URL도 포함시키려면 loadDependencies(file) 호출해서 libsDir에 파일을 넣어놔야 함
            val depUrls = loadDependencies(file) // 기존 함수 사용, libsDir에 jar들을 만든다

            // 여기서 custom classloader 생성
            val combinedClassLoader = buildClassLoaderForScript()

            // 만약 depUrls가 있으면 새로운 classloader에 추가 (URLClassLoader chaining)
            val allUrls = combinedClassLoader.urLs + depUrls.toTypedArray()
            val scriptClassLoader = URLClassLoader(allUrls, javaClass.classLoader)

            // --- 컴파일/평가 설정 ---
            val compilationConfig = ScriptCompilationConfiguration {
                jvm {
                    // 플러그인(및 그 의존성) 기반으로 컴파일 classpath 확보
                    dependenciesFromClassContext(KotePlugin::class, wholeClasspath = true)
                    dependenciesFromCurrentContext(wholeClasspath = true)
                }
                defaultImports(*defaultImports.toTypedArray())
                implicitReceivers(ScriptShared::class) // 변수, 함수 공유
                ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
            }

            val evaluationConfig = ScriptEvaluationConfiguration {
                jvm { baseClassLoader(scriptClassLoader) }
            }

            // 실행
            try {
                logger.info("▶ 실행 중: ${file.name}")
                val result = host.eval(file.toScriptSource(), compilationConfig, evaluationConfig)

                when (result) {
                    is ResultWithDiagnostics.Success -> {
                        logger.info("✅ ${file.name} 실행 성공")
                    }
                    is ResultWithDiagnostics.Failure -> {
                        val errorMsg = result.reports.joinToString("\n") { it.message }
                        logger.warning("❌ ${file.name} 실행 실패:\n$errorMsg")
                    }
                }
            } catch (e: Exception) {
                logger.severe("❌ ${file.name} 실행 중 오류 발생: ${e.message}")
                e.printStackTrace()
            }
        }

        ktsFiles.forEach(::evalFile)
    }
}

object ScriptShared