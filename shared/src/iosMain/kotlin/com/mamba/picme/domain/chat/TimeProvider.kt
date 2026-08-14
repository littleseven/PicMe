package com.mamba.picme.domain.chat

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun nowEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
