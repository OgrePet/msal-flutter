package uk.co.moodio.msal_flutter

import java.util.concurrent.Executor

interface Scheduler {
    fun background(action: () -> Unit)
    fun foreground(action: () -> Unit)

    companion object {
        fun fromExecutors(background: Executor, foreground: Executor): Scheduler =
                ExecutorsScheduler(background, foreground)
    }
}

private class ExecutorsScheduler(private val background: Executor, private val foreground: Executor) : Scheduler {
    override fun background(action: () -> Unit) = background.execute(action)
    override fun foreground(action: () -> Unit) = foreground.execute(action)
}
