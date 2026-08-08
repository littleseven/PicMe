package com.mamba.picme.agent.core.model.context

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
actual object AgentIdGenerator {
    private val counter = AtomicInt(1)

    actual fun nextId(): Int = counter.fetchAndAdd(1)
}
