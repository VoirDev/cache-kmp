package dev.voir.cache.core.policy

/** Keeps a value fresh until it is explicitly invalidated or removed. */
internal data object NeverExpire : FreshnessPolicy

