package com.ayham.navidrome

// SQLiteOpenHelper only implements AutoCloseable on newer Android releases.
// Close explicitly so these tests also run against the API 28 Robolectric runtime.
internal inline fun <T> LibraryDb.useDatabase(block: (LibraryDb) -> T): T =
    try { block(this) } finally { close() }
