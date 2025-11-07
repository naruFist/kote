package io.github.naruFist.kote

import io.github.naruFist.kape2.component.Color
import io.github.naruFist.kape2.component.text
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class KoteCommand(val plugin: KotePlugin): CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (command.name.equals("kote", ignoreCase = true)) {
            if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
                sender.sendMessage(text("[kote] 스크립트를 다시 불러옵니다...", Color.GREEN))
                plugin.loadAllScripts()
                sender.sendMessage(text("[kote] 다시 불러오기 완료!", Color.GREEN))
                return true
            }
        }
        return false
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String?>? {
        if (command.name.equals("kote", ignoreCase = true)) {
            if (args.size == 1) {
                return listOf("reload")
            }
        }

        return null
    }
}