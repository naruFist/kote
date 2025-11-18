# _kote_
_kotlin + note_ : write plugin with kotlin. like note

[![java](https://img.shields.io/badge/java-21-ED8B00.svg?logo=java)](https://www.azul.com/)
[![kotlin](https://img.shields.io/badge/kotlin-2.2.21-585DEF.svg?logo=kotlin)](http://kotlinlang.org)
[![gradle](https://img.shields.io/badge/gradle-8.8-02303A.svg?logo=gradle)](https://gradle.org)

`version 2.0`

## how to use?

plugins/kote/scripts ~> (fileName).kts

kote does provide [_**kape2**_](https://github.com/naruFist/kape2)

\# example.kts
```kts
println("Hello, World!")

Kape.plugin.logger.info("[kote] providing kape2")

Kape.listener<PlayerJoinEvent> { event ->
    event.player.sendMessage(text("Hello, Kote!", Color.AQUA))
}
```

\# result

```console
[kote] Loading Scripts...
Hello, World!
kote providing kape2
(..Listener Activated..)
[kote] Loaded Scripts!
```

## settings
### 1. `classpath.yml`
`plugins/kote/libs/`: add [paper-api.jar](https://artifactory.papermc.io/artifactory/universe/io/papermc/paper/paper-api/1.21.8-R0.1-SNAPSHOT/paper-api-1.21.8-R0.1-20250906.215025-55.jar),
[adventure-api.jar](https://artifactory.papermc.io/artifactory/universe/net/kyori/adventure-api/4.24.0/adventure-api-4.24.0.jar),
[kape2.jar](https://github.com/naruFist/kape2/releases/download/0.0.5/kape2-0.0.5.jar)

and direct with `plugins/kote/classpath.yml`

### 2. `default-import.yml`
this add `import` to every file.

this doesn't work when writing this
```yaml
- "org.bukkit.*"
- "io.github.naruFist.*"
```

instead write like this
```yaml
- "org.bukkit.Bukkit"
- "org.bukkit.entity.Player"
- "io.github.naruFist.kape2.Kape"
- "io.github.naruFist.kote.Kote"
```

( it not works when `~.~.~.*`)