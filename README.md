# _kote_
_kotlin + note_ : write plugin with kotlin. like note

[![java](https://img.shields.io/badge/java-21-ED8B00.svg?logo=java)](https://www.azul.com/)
[![kotlin](https://img.shields.io/badge/kotlin-2.2.21-585DEF.svg?logo=kotlin)](http://kotlinlang.org)
[![gradle](https://img.shields.io/badge/gradle-8.8-02303A.svg?logo=gradle)](https://gradle.org)



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

## Usage of `@file:DependsOn`

- **Not tested yet**
- **To be uploaded later**