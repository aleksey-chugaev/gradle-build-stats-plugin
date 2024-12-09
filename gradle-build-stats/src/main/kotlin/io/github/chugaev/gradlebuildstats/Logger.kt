package io.github.chugaev.gradlebuildstats

import org.slf4j.LoggerFactory

internal fun getLogger(className: String) = LoggerFactory.getLogger("io.github.chugaev.gradlebuildstats.$className")
