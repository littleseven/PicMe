package com.mamba.picme.agent.core.model.context

import java.util.concurrent.atomic.AtomicInteger

actual object AgentIdGenerator {
    private val counter = AtomicInteger(1)

    actual fun nextId(): Int = counter.getAndIncrement()
}
