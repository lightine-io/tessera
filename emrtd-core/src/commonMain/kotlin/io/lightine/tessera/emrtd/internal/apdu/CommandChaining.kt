package io.lightine.tessera.emrtd.internal.apdu

/**
 * Command chaining (ISO/IEC 7816-4, section 5.4.2): every command of a chain except the last has
 * the chaining bit (`0x10`) set in its class byte.
 *
 * Doc 9303 Part 11, section 4.4.4.3 and 9.3.2 (and Part 10, 3.6.5): the reader chains the
 * GENERAL AUTHENTICATE steps of PACE, and uses chaining for nothing else unless the chip says it
 * supports it. This helper does not apply that policy; its callers do.
 */
internal object CommandChaining {
    /** The command marked as a non-final member of a chain. */
    fun markNotLast(command: CommandApdu): CommandApdu = command.withCla(command.cla or CommandApdu.CLA_CHAINING_BIT)

    /** True when the command is marked as a non-final member of a chain. */
    fun isNotLast(command: CommandApdu): Boolean = (command.cla and CommandApdu.CLA_CHAINING_BIT) != 0

    /**
     * Splits a command whose data is too long for one exchange into a chain of commands of at most
     * [maxDataPerCommand] data bytes each. Only the last command keeps the expected length; the
     * others ask for none. A command that fits is returned alone, unchanged.
     */
    fun split(
        command: CommandApdu,
        maxDataPerCommand: Int,
    ): List<CommandApdu> {
        require(maxDataPerCommand >= 1) { "maxDataPerCommand must be at least 1" }
        if (command.data.size <= maxDataPerCommand) return listOf(command)
        val chunks = command.data.toList().chunked(maxDataPerCommand) { it.toByteArray() }
        return chunks.mapIndexed { i, chunk ->
            val last = i == chunks.lastIndex
            CommandApdu(
                cla = if (last) command.cla else command.cla or CommandApdu.CLA_CHAINING_BIT,
                ins = command.ins,
                p1 = command.p1,
                p2 = command.p2,
                data = chunk,
                ne = if (last) command.ne else null,
            )
        }
    }
}
