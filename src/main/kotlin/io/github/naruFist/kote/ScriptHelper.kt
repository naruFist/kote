package io.github.naruFist.kote

import io.github.naruFist.kape2.Kape
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitTask

class ScriptHelper {
    companion object {
        // --- 메모리 누수 방지용 추적 필드 추가 ---
        internal val activeListeners = mutableSetOf<Listener>()
        internal val activeTasks = mutableSetOf<BukkitTask>()

        /**
         * 스크립트에서 호출하여 리스너를 등록하고 KotePlugin의 추적 리스트에 추가합니다.
         */
        fun addListener(listener: Listener) {
            activeListeners.add(listener)
            Kape.plugin.logger.info("⭐ 스크립트 헬퍼: Listener 객체가 안전하게 등록되었습니다: ${listener::class.simpleName}")
        }

        /**
         * 스크립트에서 호출하여 태스크를 등록하고 KotePlugin의 추적 리스트에 추가합니다.
         */
        fun addTask(task: BukkitTask) {
            activeTasks.add(task)
            Kape.plugin.logger.info("⭐ 스크립트 헬퍼: BukkitTask 객체가 안전하게 등록되었습니다.")
        }
    }
}
