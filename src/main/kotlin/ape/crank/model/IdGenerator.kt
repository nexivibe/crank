package ape.crank.model

import java.util.UUID

object IdGenerator {
    /** Generates a short ID (first segment of a UUID, 8 hex chars). */
    fun generate(): String = UUID.randomUUID().toString().substringBefore('-')

    /** Generates a short ID guaranteed unique against the given existing IDs. */
    fun generateUnique(existingIds: Set<String>): String {
        var id = generate()
        while (id in existingIds) {
            id = generate()
        }
        return id
    }
}
