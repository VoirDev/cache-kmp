package dev.voir.cache.core.policy

/** Keeps a value fresh until it is explicitly invalidated or removed. */
public data object NeverExpire : FreshnessPolicy

