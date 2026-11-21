package io.lightine.tessera.emrtd.internal.files

import io.lightine.tessera.emrtd.internal.tlv.TlvFailure

/** A typed failure while selecting or reading one chip file. */
internal sealed class FileReadFailure(
    message: String,
) : Exception(message)

/** SELECT returned 6A82. [fid] is null when the missing item was an application. */
internal class FileNotFound(
    val fid: Int?,
) : FileReadFailure("The selected file was not found")

/** The chip returned 6982 while selecting or reading a file. */
internal class FileSecurityStatusNotSatisfied(
    val fid: Int?,
) : FileReadFailure("The chip's security status does not permit this file operation")

/** A chip returned a status word not handled by file reading. */
internal class FileStatusFailure(
    val statusWord: Int,
) : FileReadFailure("The chip returned status word ${statusWord.toString(16).uppercase().padStart(4, '0')}")

/** A BER-TLV header or the bytes that followed it did not form a complete file. */
internal class MalformedFile(
    val failure: TlvFailure?,
) : FileReadFailure("The chip returned a malformed file")

/** The response or command could not be used to complete a file read. */
internal class FileProtocolFailure : FileReadFailure("The chip returned an unusable file response")
